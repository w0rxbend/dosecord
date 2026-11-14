package dosegram.app

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.Comparisons
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.InsightRule
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.MonthStats
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.PairedDay
import dosegram.core.domain.insights.SleepNight
import dosegram.core.ports.CardLook
import dosegram.core.ports.DoseMark
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MonthCardView
import dosegram.core.ports.WeekCardView
import dosegram.core.ports.WeekDayFood
import dosegram.core.ports.WeekDayMood
import dosegram.core.ports.WeekDayNight
import dosegram.core.ports.WeekDayWater
import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser
import dosegram.render.blob.MoodState
import dosegram.render.card.DayAdherence
import dosegram.render.card.MealDay

import java.nio.charset.StandardCharsets.UTF_8
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** The report cards on the renderer (ADR-022): the month's pages, the pattern card and the weekly card are drawn from
  * the core's figures, a domain below its gate has no row or panel, and a figure the card would have to invent (a
  * goal the user never set) is left to the text.
  */
class ReportCardsSuite extends munit.FunSuite:

  private val october = YearMonth.of(2026, 10)
  private val look = CardLook(ImageTheme.Dark, isDiscreet = false)
  private val medication = UUID.fromString("00000000-0000-7000-8000-000000000001")

  private object Counting extends Rasteriser:
    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      Right(PngImage(IArray.unsafeFromArray(s"png:${svg.hashCode}".getBytes(UTF_8))))

  private def cards = StatusImages.over(Counting, StatusImages.Stores.inMemory, _ => ()).cards

  private def days[A](range: Range)(value: Int => A): Map[LocalDate, A] =
    range.map(day => october.atDay(day) -> value(day)).toMap

  private def figures(
      medicationDays: Option[Map[LocalDate, MedicationDay]] = None,
      water: Option[Map[LocalDate, HydrationDay]] = None,
      mood: Option[Map[LocalDate, MoodDay]] = None,
      sleep: Option[Map[LocalDate, SleepNight]] = None
  ): MonthFigures =
    MonthStats.of(october, DayOfWeek.MONDAY, medicationDays.map(_ -> Nil), water, mood, sleep, None)

  private def view(month: MonthFigures): MonthCardView = MonthCardView(month, None, october.atDay(31))

  test("a month with every domain at its gate draws three pages; one without page 2 domains draws pages 1 and 3"):
    val full = figures(
      medicationDays = Some(days(1 to 20)(_ => MedicationDay(Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(2, 0, 0, 0)))))),
      water = Some(days(1 to 20)(_ => HydrationDay(1800, 3, Some(2000)))),
      mood = Some(days(1 to 20)(day => MoodDay(1, 1 + day % 5, 1, 5, 0, 0)))
    )
    assertEquals(cards.month(view(full), look).size, 3)
    val waterOnly = figures(water = Some(days(1 to 20)(_ => HydrationDay(1800, 3, Some(2000)))))
    val pages = cards.month(view(waterOnly), look)
    assertEquals(pages.size, 2)
    assert(pages.last.altText.startsWith("October 2026, nights and water. Water: Goal reached on 0 of 20"), pages.last.altText)

  test("page 1 has a row only for a domain at its gate, and the previous month only behind its own gate"):
    val month = figures(
      water = Some(days(1 to 20)(_ => HydrationDay(1800, 3, Some(2000)))),
      mood = Some(days(1 to 5)(_ => MoodDay(1, 4, 4, 4, 0, 0)))
    )
    val page = ReportCards.monthPage(month)
    assertEquals(page.moodCheckInDays, None)
    assertEquals(page.water.map(goal => (goal.reached, goal.recorded)), Some((0, 20)))
    assertEquals(page.previousAdherence, None)
    assertEquals(page.weeks.size, 5)

  test("a calendar day takes its class from its resolved doses, and a day with only open doses is open"):
    val mixed = Map(
      october.atDay(1) -> MedicationDay(Map(medication -> DoseBuckets(1, 0, 0, 1, 0, 0, 0))),
      october.atDay(2) -> MedicationDay(Map(medication -> DoseBuckets.zero.copy(open = 2))),
      october.atDay(3) -> MedicationDay(Map(medication -> DoseBuckets.zero.copy(unknown = 1)))
    ) ++ days(4 to 20)(_ => MedicationDay(Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(1, 0, 0, 0)))))
    val calendar = ReportCards.monthDays(figures(medicationDays = Some(mixed)), view(figures())).medication.get
    assertEquals(
      calendar.days.take(4),
      Vector(
        DayAdherence.Resolved(1, 2, hasMissed = true),
        DayAdherence.Open,
        DayAdherence.NotKnown,
        DayAdherence.Resolved(1, 1, hasMissed = false)
      )
    )
    assertEquals(calendar.days.last, DayAdherence.NothingScheduled)

  test("the pattern card draws a comparison of mood and no other outcome"):
    val window = DateRange.endingOn(LocalDate.of(2026, 11, 9), 28)
    def shown(rule: InsightRule, first: Double, second: Double): Comparison.Shown =
      val paired = List.tabulate(20)(i => PairedDay(window.start.plusDays(i.toLong), i % 2 == 0, if i % 2 == 0 then first else second))
      Comparisons.compare(rule, paired, window).asInstanceOf[Comparison.Shown]
    assert(cards.insight(shown(InsightRule.WaterGoalAndMood, 4.0, 3.0), look).isDefined)
    assert(cards.insight(shown(InsightRule.WorkDaysAndMood, 4.0, 3.0), look).isDefined)
    assertEquals(cards.insight(shown(InsightRule.WorkDaysAndWater, 2400, 1600), look), None)
    assertEquals(cards.insight(shown(InsightRule.WorkDaysAndSleep, 480, 400), look), None)

  test("the weekly card leaves water and sleep to the text when a recorded day had no goal"):
    val from = LocalDate.of(2026, 11, 16)
    val week = WeekCardView(
      from,
      doses = Some(List.fill(7)(List(DoseMark.TakenOnTime))),
      water = Some(List.fill(6)(None) :+ Some(WeekDayWater(900, None))),
      mood = Some(List.fill(6)(None) :+ Some(WeekDayMood(4.5, Some(5.0)))),
      sleep = Some(List.fill(6)(None) :+ Some(WeekDayNight(420, Some(450))))
    )
    val data = ReportCards.week(week)
    assertEquals(data.water, None)
    assertEquals(data.mood.map(_.last), Some(Some(MoodState(7, 5))))
    assert(data.sleep.isDefined)
    assert(cards.week(week, look).isDefined)

  test("the weekly card draws the food row from the days' main meals and snacks, and none without it"):
    val from = LocalDate.of(2026, 11, 16)
    val food = List.fill(5)(None) ++ List(Some(WeekDayFood(3, 2)), Some(WeekDayFood(1, 0)))
    val week = WeekCardView(from, doses = None, water = None, mood = None, sleep = None, food = Some(food))
    assertEquals(ReportCards.week(week).food, Some(Vector.fill(5)(None) ++ Vector(Some(MealDay(3, 2)), Some(MealDay(1, 0)))))
    assertEquals(ReportCards.week(week.copy(food = None)).food, None)
    assert(cards.week(week, look).isDefined)

  test("a daily mean maps to the blob state of section 4.2: the stored scale's ends and middle"):
    assertEquals(ReportCards.moodState(1.0, None).valence, 1)
    assertEquals(ReportCards.moodState(3.0, None).valence, 4)
    assertEquals(ReportCards.moodState(5.0, None).valence, 7)
    assertEquals(ReportCards.moodState(3.0, None).energy, 3)
end ReportCardsSuite
