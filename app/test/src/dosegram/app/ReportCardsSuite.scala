package dosegram.app

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.copy.Lang
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
import dosegram.core.domain.insights.YearSources
import dosegram.core.domain.insights.YearStats
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
      sleep: Option[Map[LocalDate, SleepNight]] = None,
      food: Option[Map[LocalDate, dosegram.core.domain.insights.MealDay]] = None
  ): MonthFigures =
    MonthStats.of(october, DayOfWeek.MONDAY, medicationDays.map(_ -> Nil), water, mood, sleep, None, food)

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

  // Supervisor decision, 2026-11-28: the card names the days its "days with records" leaves out, as the month text does.
  test("page 1 names the outage-only days in its hero and its text equivalent, in English and Ukrainian"):
    val outage = days(25 to 27)(_ => MedicationDay(Map(medication -> DoseBuckets.zero.copy(unknown = 2))))
    val month = figures(medicationDays = Some(outage), water = Some(days(1 to 20)(_ => HydrationDay(1800, 3, Some(2000)))))
    assertEquals((month.daysWithRecords, month.outageOnlyDays), (20, 3))
    assertEquals(ReportCards.monthPage(month).outageOnlyDays, 3)
    val english = cards.month(view(month), look).head
    assert(english.svg.contains("(not counted: 3 outage days)"), english.svg)
    assert(
      english.altText.contains(
        "20 days with records. Not counted: 3 days whose only doses have no record (a service outage)."
      ),
      english.altText
    )
    val ukrainian = Lang.within(Lang.Uk)(cards.month(view(month), look).head)
    assert(ukrainian.svg.contains("(не враховано: 3 дні перебою)"), ukrainian.svg)
    assert(
      ukrainian.altText.contains("Днів із записами: 20. Не враховано: 3 дні, коли всі дози без запису (перебій сервісу)."),
      ukrainian.altText
    )
    val none = figures(water = Some(days(1 to 20)(_ => HydrationDay(1800, 3, Some(2000)))))
    val plain = cards.month(view(none), look).head
    assert(!plain.svg.contains("not counted") && !plain.altText.contains("Not counted"), plain.altText)

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

  test("food at its gate has a row on page 1, a bar per week of meals logged, and its dots on page 3"):
    val meals = days(1 to 14)(day => dosegram.core.domain.insights.MealDay(2, 1, if day % 2 == 0 then 1 else 0, day % 3))
    val month = figures(food = Some(meals))
    val page = ReportCards.monthPage(month)
    assertEquals(page.meals.map(m => (m.logged, m.snacks)), Some((meals.values.map(_.mainMeals).sum, meals.values.map(_.snacks).sum)))
    assertEquals(page.weeks.map(_.mealsLogged.isDefined), Vector(true, true, true, false, false))
    val nights = ReportCards.monthNights(month, view(month))
    assertEquals(nights.food.map(_.flatten.size), Some(14))
    val pages = cards.month(view(month), look)
    assertEquals(pages.size, 2)
    assert(pages.last.altText.contains("Food: "), pages.last.altText)
    assertEquals(ReportCards.monthPage(figures(food = Some(days(1 to 13)(_ => meals.values.head)))).meals, None)

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

  test("the month in one image marks each day's recorded areas and mood, and never medication (IMG-24)"):
    val Domain = dosegram.render.tokens.Domain
    val doses = Some(days(1 to 20)(_ => MedicationDay(Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(2, 0, 0, 0))))))
    val month = figures(
      medicationDays = doses,
      water = Some(days(1 to 20)(_ => HydrationDay(1800, 3, Some(2000)))),
      mood = Some(days(5 to 6)(_ => MoodDay(1, 4, 4, 4, 0, 0)))
    )
    val poster = ReportCards.monthPoster(month).getOrElse(fail("no poster"))
    assertEquals(poster.tracked, Set(Domain.Water, Domain.Mood))
    assertEquals(poster.days.count(_.recorded == Set(Domain.Water)), 20)
    assertEquals(poster.days.count(_.mood.isDefined), 2)
    assertEquals(poster.daysWithRecords, 20)
    val page = cards.monthPoster(view(month), look).getOrElse(fail("no page"))
    assertEquals(page.templateId, "card.month.poster.v1")
    assert(page.altText.startsWith("October 2026, your month in one image. 20 of 31 days have a record."), page.altText)
    assert(!page.altText.contains("Medication"), page.altText)
    assertEquals(cards.monthPoster(view(figures(medicationDays = doses)), look), None, "medication alone has no poster")

  test("the one-year card draws a week cell per area from the week of 1 January, never medication (IMG-30)"):
    val Domain = dosegram.render.tokens.Domain
    val today = LocalDate.parse("2026-11-12")
    val doses = Some(days(1 to 20)(_ => MedicationDay(Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(2, 0, 0, 0))))))
    def year(water: Option[Map[LocalDate, HydrationDay]], mood: Option[Map[LocalDate, MoodDay]]) =
      YearStats.of(2026, today, YearSources(doses, water, mood, None, None))
    val figures = year(Some(days(1 to 20)(_ => HydrationDay(1800, 3, None))), Some(Map.empty))
    val data = ReportCards.year(figures).getOrElse(fail("no year card"))
    assertEquals(data.rows.map(_.domain), Vector(Domain.Water, Domain.Mood))
    // 1 January 2026 is a Thursday, 12 November a Thursday: 46 weeks of four, seven and four days.
    assertEquals(data.rows.head.weeks.size, 46)
    assertEquals((data.rows.head.weeks.head.days, data.rows.head.weeks.last.days), (4, 4))
    assertEquals(data.rows.head.recordedDays, 20)
    assertEquals(data.rows.head.monthly(9), 20)
    assertEquals(data.daysWithRecords, 20)
    val page = cards.year(figures, look).getOrElse(fail("no page"))
    assertEquals(page.templateId, "card.year.v1")
    assert(page.altText.startsWith("2026, your year in records, 1 Jan to 12 Nov. 20 of 316 days have a record."), page.altText)
    assert(!page.altText.contains("Medication"), page.altText)
    assertEquals(cards.year(year(None, None), look), None, "medication alone has no year card")
    assertEquals(cards.year(year(Some(Map.empty), None), look), None, "an area without a record draws no year card")
end ReportCardsSuite
