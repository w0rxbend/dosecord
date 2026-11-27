package dosegram.app

import dosegram.api.PreviewLimits
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.DoseMark
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.ShareCardView
import dosegram.core.ports.TrendCardRow
import dosegram.core.ports.TrendsCardView
import dosegram.core.ports.WeekCardView
import dosegram.core.ports.WeekDayFood
import dosegram.core.ports.WeekDayMood
import dosegram.core.ports.WeekDayNight
import dosegram.core.ports.WeekDayWater
import dosegram.infra.RenderSettings
import dosegram.infra.perf.Timings

import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** docs/PERFORMANCE.md, the share builder's preview (supervisor decision, 2026-11-27): `GET /report-cards/{kind}/preview`
  * lays a card out and draws it inside the request, as the Mini App edge wires it (`MiniAppEdge`: the production drawing
  * of `StatusImages.from`, the PNG read back from the store and encoded as base64). Every builder card is personal and
  * keyed by its bytes, so it is drawn on every preview; then a burst of previews at once shows what the drawing limit
  * of `PreviewLimits` lets through. Run:
  * `./mill app.test.runMain dosegram.app.sharePreviewProbe <resvg> <fonts dir> <samples> [worker]`.
  */
@main def sharePreviewProbe(resvg: String, fonts: String, samples: Int, worker: String*): Unit =
  val today = LocalDate.parse("2026-11-27")
  val look = CardLook(ImageTheme.Light, isDiscreet = false)
  val week = WeekCardView(
    today.minusDays(6),
    Some(List.fill(7)(List(DoseMark.TakenOnTime, DoseMark.TakenLate))),
    Some(List.tabulate(7)(i => Some(WeekDayWater(1200 + 150 * i, Some(2000))))),
    Some(List.tabulate(7)(i => Some(WeekDayMood(2.5 + i * 0.3, Some(3.0))))),
    Some(List.tabulate(7)(i => Some(WeekDayNight(400 + 10 * i, Some(450))))),
    Some(List.fill(7)(Some(WeekDayFood(3, 1))))
  )
  def trends(days: Int, byWeek: Boolean): TrendsCardView =
    val buckets = if byWeek then (days + 6) / 7 else days
    val rows = TrendDomain.values.toList.map: domain =>
      TrendCardRow(domain, "probe figure", List.tabulate(buckets)(i => Option.when(i % 5 != 0)(2.0 + i % 3)))
    TrendsCardView(DateRange.endingOn(today, days), byWeek, rows, "Probe text equivalent.")
  val settings = Some(RenderSettings(resvg, fonts, worker.headOption))
  val images = StatusImages.from(settings, StatusImages.Stores.inMemory, line => println(line))
  // What the edge's `pictures` does with a laid-out card, then the base64 of the answer.
  def preview(images: StatusImages, card: StatusImages => Option[CardPage]): Int =
    val page = card(images).getOrElse(sys.error("no page"))
    val png = images.drawing.draw(page, None).toOption.flatMap(ref => images.store.load(ref.assetKey))
    png.map(Base64.getEncoder.encodeToString).fold(sys.error("not drawn"))(_.length)
  val cards: List[(String, StatusImages => Option[CardPage])] = List(
    "share-week-mood (IMG-17)" -> (_.cards.share(ShareCardView.WeekMood(3.6, Some(3.0)), look)),
    "week-glance-7 (IMG-19)" -> (_.cards.weekGlance(week, look)),
    "trends-30 (IMG-23)" -> (_.cards.trends(trends(30, byWeek = false), look)),
    "trends-90 (IMG-23)" -> (_.cards.trends(trends(90, byWeek = true), look))
  )
  val probes = cards.map((label, card) => Timings.of(s"preview $label", 3, samples)(preview(images, card)))
  println(Timings.Header)
  probes.foreach(probe => println(probe.row))
  // A burst: eight accounts ask for a 90-day preview at the same moment.
  val limits = PreviewLimits.default()
  val pool = Executors.newVirtualThreadPerTaskExecutor()
  val start = System.nanoTime()
  val outcomes = (1 to 8).toList
    .map(_ =>
      pool.submit(() =>
        limits
          .admit(UUID.randomUUID(), java.time.Instant.now())
          .flatMap(_ => limits.drawing(preview(images, cards.last._2)))
          .isRight
      )
    )
    .map(_.get(30, TimeUnit.SECONDS))
  pool.shutdown()
  val wall = (System.nanoTime() - start) / 1e6
  println(f"burst of 8 previews at once: ${outcomes.count(identity)} drawn, ${outcomes.count(!_)} answered 429, $wall%.1f ms")
