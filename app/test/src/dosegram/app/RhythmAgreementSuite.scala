package dosegram.app

import dosegram.api.InsightRhythms
import dosegram.api.TrackingUseCases
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.insights.RhythmCards
import dosegram.core.application.insights.RhythmView
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.RhythmCopy
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ImageTheme
import dosegram.infra.crypto.DataKeys
import dosegram.infra.db.Fixtures
import dosegram.infra.db.MutableClock
import dosegram.infra.db.PgSuite
import dosegram.infra.db.PgUnitOfWork

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The rhythm of the day (supervisor decision, 2026-11-28), the cross-representation test: the same records on
  * PostgreSQL give the same hours and words in the chat's `/stats rhythm`, on its card (IMG-28, laid out by the renderer
  * the application wires) and in `GET /insights/rhythm`, in English and in Ukrainian. The records are written by the
  * services the chat and the API call, not by hand.
  */
final class RhythmAgreementSuite extends PgSuite:

  // 10:00 UTC is 12:00 in Kyiv.
  private val start = Instant.parse("2026-11-20T10:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)
  private val cards = RenderedStatusCards()
  private val look = CardLook(ImageTheme.Light, isDiscreet = false)

  private final class Rig:
    val clock = MutableClock(start)
    val uow = PgUnitOfWork(dataSource, clock, DataKeys.development)
    val tracking = TrackingServices.over(uow, clock)
    private val useCases =
      TrackingUseCases(tracking.stats, tracking.hydration, tracking.mood, tracking.sleep, tracking.nutrition)
    val api = InsightRhythms(uow, useCases)
    val account: UUID = fixtures.account("Europe/Kyiv")
    fixtures.grant(account, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.Nutrition)

    /** Water at 12:00 on 16 days, a lunch at 12:00 on the same days and a mood check-in at 21:00 on 9 of them, below
      * the gate; older water outside the window.
      */
    def record(): Unit =
      (0 until 16).foreach: back =>
        on(back, Duration.ZERO): tx =>
          val amount = WaterAmount.ofMillilitres(250).toOption.get
          assert(tracking.hydration.log(tx, LogWater(account, amount, EntryDay.Today, EntrySource.Bot, s"w$back")).isRight)
          val meal = LogMeal(account, MealInput(MealType.Lunch, None, None), EntrySource.Bot, s"f$back")
          assert(tracking.nutrition.log(tx, meal).isRight)
      (0 until 9).foreach: back =>
        on(back, Duration.ofHours(9)): tx =>
          val checkIn = CheckIn(account, Valence.of(4).get, None, Nil, EntryDay.Today, EntrySource.Bot, s"m$back")
          assert(tracking.mood.checkIn(tx, checkIn).isRight)
      (31 until 34).foreach: back =>
        on(back, Duration.ZERO): tx =>
          val amount = WaterAmount.ofMillilitres(250).toOption.get
          assert(tracking.hydration.log(tx, LogWater(account, amount, EntryDay.Today, EntrySource.Bot, s"o$back")).isRight)

    private def on(daysAgo: Int, later: Duration)(write: dosegram.core.ports.Tx => Unit): Unit =
      clock.at = start.minus(Duration.ofDays(daysAgo.toLong)).plus(later)
      uow.transactionAs(account)(write)
      clock.at = start

    def rhythm: RhythmView = uow.transactionAs(account)(tx => tracking.stats.rhythm(tx, account)).toOption.get

  /** Every string the card draws, XML entities read back. */
  private def drawn(page: CardPage): List[String] =
    "<text[^>]*>([^<]*)</text>".r
      .findAllMatchIn(page.svg)
      .map(_.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'"))
      .toList

  private def agree(rig: Rig, lang: Lang): Unit =
    Lang.within(lang):
      val view = rig.rhythm
      val chat = RhythmCopy.lines(view.figures, view.zone, keepGates = true)
      val document = rig.api.rhythm(rig.account).toOption.get
      val page = cards.rhythm(RhythmCards.of(view).get, look).get
      assertEquals(page.templateId, "card.rhythm.v1")
      assertEquals(document.lines, chat, lang)
      assertEquals(page.altText, chat.mkString("\n"), lang)
      val onCard = drawn(page)
      assertEquals(document.areas.map(_.id.wire), List("hydration", "mood", "nutrition"))
      view.figures.rows.zip(document.areas).foreach: (row, area) =>
        val label = s"$lang ${row.domain}"
        assertEquals(area.hours, row.pattern.shown.map(_.band.counts.toList), label)
        assert(chat.contains(area.text), s"$label: chat $chat, app ${area.text}")
        assert(onCard.contains(area.days), s"$label: card $onCard, app ${area.days}")
        val summary = row.pattern.shown.fold(RhythmCopy.belowGate(row.domain, row.recordedDays))(RhythmCopy.cardSummary)
        assert(onCard.contains(summary), s"$label: card $onCard, summary $summary")

  test("water, mood and food read the same in the chat, on the card and in the Mini App"):
    val rig = Rig()
    rig.record()
    agree(rig, Lang.En)
    Lang.within(Lang.En):
      val rows = rig.rhythm.figures.rows
      assertEquals(rows.map(row => row.domain -> row.recordedDays), List(TrendDomain.Hydration -> 16, TrendDomain.Mood -> 9, TrendDomain.Nutrition -> 16))
      val water = rig.api.rhythm(rig.account).toOption.get.areas.head
      assertEquals(water.hours.map(_(12)), Some(16), "the water 31 days ago and before is outside the window")
      assertEquals(water.text, "Water (16 days): 16 entries, most often 12:00 to 13:00 · morning 0, afternoon 16, evening 0, night 0")

  test("the Ukrainian surfaces agree as well (ROADMAP G2.22)"):
    val rig = Rig()
    rig.record()
    agree(rig, Lang.Uk)
end RhythmAgreementSuite
