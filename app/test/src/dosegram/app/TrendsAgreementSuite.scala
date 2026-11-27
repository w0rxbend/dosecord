package dosegram.app

import dosegram.api.InsightBlock
import dosegram.api.Insights
import dosegram.api.TrackingUseCases
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.insights.TrendsView
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.TrendCopy
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.TrendPeriod
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

/** ROADMAP G9.5, the cross-representation test: the same records on PostgreSQL give the same figure in the chat's
  * trends text, on the trends card (IMG-23, laid out by the renderer the application wires) and in the Mini App's
  * Insights blocks (`GET /insights/overview`), for 30 and 90 days, in English and in Ukrainian. The records are written
  * by the services the chat and the API call, not by hand.
  */
final class TrendsAgreementSuite extends PgSuite:

  private val start = Instant.parse("2026-11-20T10:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)
  private val cards = RenderedStatusCards()
  private val look = CardLook(ImageTheme.Light, isDiscreet = false)

  private final class Rig:
    val clock = MutableClock(start)
    val uow = PgUnitOfWork(dataSource, clock, DataKeys.development)
    val tracking = TrackingServices.over(uow, clock)
    val insights = Insights(uow, TrackingUseCases(tracking.stats, tracking.hydration, tracking.mood, tracking.sleep, tracking.nutrition))
    val account: UUID = fixtures.account("Europe/Kyiv")
    fixtures.grant(account, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.Nutrition, ConsentPurpose.ImagesInChat)

    /** Twelve days of water, mood and lunch, and older water in the previous 30 days, written on their own days. */
    def record(): Unit =
      (0 until 12).foreach: back =>
        on(back): tx =>
          val amount = WaterAmount.ofMillilitres(1500 + 20 * back).toOption.get
          assert(tracking.hydration.log(tx, LogWater(account, amount, EntryDay.Today, EntrySource.Bot, s"w$back")).isRight)
          val checkIn = CheckIn(account, Valence.of(3 + back % 3).get, None, Nil, EntryDay.Today, EntrySource.Bot, s"m$back")
          assert(tracking.mood.checkIn(tx, checkIn).isRight)
          val meal = LogMeal(account, MealInput(MealType.Lunch, None, None), EntrySource.Bot, s"f$back")
          assert(tracking.nutrition.log(tx, meal).isRight)
      (40 until 44).foreach: back =>
        on(back): tx =>
          val amount = WaterAmount.ofMillilitres(1000).toOption.get
          assert(tracking.hydration.log(tx, LogWater(account, amount, EntryDay.Today, EntrySource.Bot, s"p$back")).isRight)

    private def on(daysAgo: Int)(write: dosegram.core.ports.Tx => Unit): Unit =
      clock.at = start.minus(Duration.ofDays(daysAgo.toLong))
      uow.transactionAs(account)(write)
      clock.at = start

    def trends(period: TrendPeriod): TrendsView =
      uow.transactionAs(account)(tx => tracking.stats.trends(tx, account, period)).toOption.get

    def overview(period: TrendPeriod): List[InsightBlock] =
      insights.overview(account, period.days).toOption.get.blocks

  /** Every string the card draws, XML entities read back. */
  private def drawn(page: CardPage): List[String] =
    "<text[^>]*>([^<]*)</text>".r
      .findAllMatchIn(page.svg)
      .map(_.group(1).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'"))
      .toList

  private val domains = List(TrendDomain.Hydration, TrendDomain.Mood, TrendDomain.Nutrition)

  private def agree(rig: Rig, period: TrendPeriod, lang: Lang): Unit =
    Lang.within(lang):
      val view = rig.trends(period)
      val chat = view.lines
      val blocks = rig.overview(period)
      val page = cards.trends(view.card.get, look).get
      val onCard = drawn(page)
      assertEquals(blocks.map(_.title), domains.map(TrendCopy.title))
      domains.zip(blocks).foreach: (domain, block) =>
        val label = s"$period $lang $domain"
        assert(block.lines.nonEmpty, label)
        assert(chat.contains(TrendCopy.domainLine(domain, block.lines)), s"$label: chat $chat, app ${block.lines}")
        assert(chat.contains(block.comparison.get), s"$label: chat $chat, app ${block.comparison}")
        assert(onCard.contains(block.lines.head), s"$label: card $onCard, app ${block.lines.head}")
        assert(onCard.contains(TrendCopy.title(domain)), s"$label: card $onCard")
        assert(page.altText.contains(TrendCopy.cardSentence(domain, block.lines.head, block.comparison.get)), label)
      view.weekCounts.foreach(line => assertEquals(chat.last, line))

  test("water, mood and food read the same in the chat, on the card and in the Mini App, over 30 and 90 days"):
    val rig = Rig()
    rig.record()
    List(TrendPeriod.Month, TrendPeriod.Quarter).foreach(agree(rig, _, Lang.En))
    // The figures themselves, as a person reads them on every surface.
    Lang.within(Lang.En):
      val water = rig.overview(TrendPeriod.Month).head
      assertEquals(water.lines.head, "average 1.6 l a day")
      assertEquals(water.comparison, Some("Previous 30 days: water logged on 4 days"))
      assert(drawn(cards.trends(rig.trends(TrendPeriod.Month).card.get, look).get).contains("average 1.6 l a day"))

  test("the Ukrainian surfaces agree as well (ROADMAP G2.22)"):
    val rig = Rig()
    rig.record()
    List(TrendPeriod.Month, TrendPeriod.Quarter).foreach(agree(rig, _, Lang.Uk))
