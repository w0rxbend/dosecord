package dosegram.render

import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.*
import dosegram.render.chart.DoseStatus
import dosegram.render.chart.DoseStatus.*
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.copy.ImageCopy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.copy.GoalDays as GoalDaysOf
import dosegram.render.text.TableTextMeasurer
import dosegram.render.text.TextFitter
import dosegram.render.tokens.Domain
import dosegram.render.tokens.Theme

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** One synthetic view model and the card it renders to. No value here comes from a real account. */
final case class Fixture(name: String, render: CardContext => Vector[CardOutput])

/** The synthetic fixtures behind the goldens, the lints, the pixel checks and the contact sheet: empty, typical and
  * extreme data for every card.
  */
object Samples:
  val Fitter: TextFitter = TextFitter(TableTextMeasurer)

  /** A name no golden at a discreet or aggregate level may contain (VA-PV-01). */
  val CanaryName = "Zyqoxanil-Canary 500 mg"

  /** Markup, quotes, control and bidirectional characters, emoji, right-to-left text and a long tail. */
  val HostileName =
    "<script>alert(\"x\")</script> & 'Ωmega' ‮evil⁦ \u0007 🚀 שלום مرحبا — a name that goes on and on and on and on"

  def context(theme: Theme, privacy: Privacy, copy: ImageCopy = EnglishImageCopy): CardContext =
    CardContext(theme, privacy, copy, Fitter)

  private val Today = LocalDate.of(2026, 11, 12)

  private def reading(valence: Int, energy: Int): MoodReading = MoodReading(MoodState(valence, energy), isEnergyGiven = true)

  val moods: Vector[Fixture] = Vector(
    Fixture("mood/very-unpleasant-very-high", context => Vector(MoodCard.render(Some(reading(1, 5)), context))),
    Fixture("mood/unpleasant-low", context => Vector(MoodCard.render(Some(reading(2, 2)), context))),
    Fixture(
      "mood/neutral-no-energy",
      context => Vector(MoodCard.render(Some(MoodReading(MoodState.captured(3, None), isEnergyGiven = false)), context))
    ),
    Fixture("mood/pleasant-high", context => Vector(MoodCard.render(Some(reading(6, 4)), context))),
    Fixture("mood/very-pleasant-very-high", context => Vector(MoodCard.render(Some(reading(7, 5)), context))),
    Fixture("mood/no-entry", context => Vector(MoodCard.render(None, context)))
  )

  val partialDay: DayData = DayData(
    Today,
    Some(reading(6, 4)),
    Some(Vector(TakenOnTime, TakenLate, Skipped, Open)),
    Some(WaterProgress(1400, 2000)),
    Some(MealProgress(2, 3)),
    Some(SleepEvidence.Recorded(430, 480))
  )

  val days: Vector[Fixture] = Vector(
    Fixture("day/partial", context => Vector(DayCard.render(partialDay, context))),
    Fixture(
      "day/empty",
      context =>
        val day = DayData(
          Today,
          None,
          Some(Vector(Open, Open, Open)),
          Some(WaterProgress(0, 2000)),
          Some(MealProgress(0, 3)),
          Some(SleepEvidence.NoRecord)
        )
        Vector(DayCard.render(day, context))
    ),
    Fixture(
      "day/full",
      context =>
        val day = DayData(
          Today,
          Some(reading(7, 3)),
          Some(Vector(TakenOnTime, TakenOnTime, TakenOnTime, TakenOnTime)),
          Some(WaterProgress(2600, 2000)),
          Some(MealProgress(4, 3)),
          Some(SleepEvidence.Recorded(505, 480))
        )
        Vector(DayCard.render(day, context))
    ),
    Fixture(
      "day/missed-doses-same-mood",
      context => Vector(DayCard.render(partialDay.copy(medication = Some(Vector(Missed, Missed, Skipped, Unknown))), context))
    ),
    Fixture(
      "day/three-domains-estimate",
      context =>
        val day = DayData(
          Today,
          Some(reading(2, 2)),
          Some(Vector(TakenOnTime, Missed)),
          Some(WaterProgress(600, 2500)),
          None,
          Some(SleepEvidence.QuietWindowOnly)
        )
        Vector(DayCard.render(day, context))
    ),
    Fixture("day/mood-only", context => Vector(DayCard.render(DayData(Today, Some(reading(4, 3)), None, None, None, None), context))),
    Fixture(
      "day/two-domains",
      context =>
        val day = DayData(Today, Some(reading(5, 3)), Some(Vector(TakenOnTime, TakenLate, Skipped)), None, None, Some(SleepEvidence.Recorded(445, 480)))
        Vector(DayCard.render(day, context))
    ),
    Fixture(
      "day/one-domain",
      context => Vector(DayCard.render(DayData(Today, None, None, Some(WaterProgress(1450, 2000)), None, None), context))
    )
  )

  private def water(volumeMl: Int, entryCount: Int, lastEntry: Option[LocalTime]): WaterData =
    WaterData(Today, WaterProgress(volumeMl, 2000), entryCount, lastEntry, usesDrinkFactors = false)

  val waters: Vector[Fixture] = Vector(
    Fixture("water/empty", context => Vector(WaterCard.render(water(0, 0, None), context))),
    Fixture("water/partial", context => Vector(WaterCard.render(water(1400, 5, Some(LocalTime.of(15, 20))), context))),
    Fixture("water/first-glass", context => Vector(WaterCard.render(water(150, 1, Some(LocalTime.of(8, 5))), context))),
    Fixture("water/goal-reached", context => Vector(WaterCard.render(water(2000, 8, Some(LocalTime.of(19, 40))), context))),
    Fixture(
      "water/far-above-goal",
      context => Vector(WaterCard.render(water(4750, 14, Some(LocalTime.of(22, 10))).copy(usesDrinkFactors = true), context))
    )
  )

  private def resolved(taken: Int, resolved: Int): DayAdherence = DayAdherence.Resolved(taken, resolved, hasMissed = taken < resolved)

  // October 2026: mostly complete days, with one of every other class, and the last days still to come.
  private val octoberDays: Vector[DayAdherence] =
    Vector.tabulate(31) {
      case 2            => resolved(3, 4)
      case 6            => resolved(2, 4)
      case 10           => DayAdherence.Resolved(0, 4, hasMissed = false)
      case 12           => DayAdherence.NotKnown
      case 15           => DayAdherence.NothingScheduled
      case 19           => resolved(1, 4)
      case 23           => resolved(0, 4)
      case day if day >= 28 => DayAdherence.Open
      case _            => resolved(4, 4)
    }

  private val october = CalendarData(YearMonth.of(2026, 10), DayOfWeek.MONDAY, Some(LocalDate.of(2026, 10, 28)), None, octoberDays, 2)

  val calendars: Vector[Fixture] = Vector(
    Fixture("calendar/typical", context => Vector(AdherenceCalendarCard.render(october, context))),
    Fixture(
      "calendar/empty",
      context =>
        val empty = CalendarData(YearMonth.of(2026, 2), DayOfWeek.MONDAY, None, None, Vector.fill(28)(DayAdherence.NothingScheduled), 0)
        Vector(AdherenceCalendarCard.render(empty, context))
    ),
    Fixture(
      "calendar/six-weeks-one-medication",
      context =>
        val august = october.copy(month = YearMonth.of(2026, 8), today = None, medicationName = Some(CanaryName))
        Vector(AdherenceCalendarCard.render(august, context))
    )
  )

  private def everyDay(dayCount: Int, statuses: DoseStatus*): Vector[Vector[DoseStatus]] = Vector.fill(dayCount)(statuses.toVector)

  private val fortnight = StripData(
    LocalDate.of(2026, 10, 27),
    14,
    Vector(
      MedicationRow("Metformin 500 mg", isAsNeeded = false, everyDay(14, TakenOnTime, TakenOnTime).updated(8, Vector(TakenOnTime, Skipped)).updated(13, Vector(TakenLate, Open))),
      MedicationRow("Vitamin D", isAsNeeded = false, Vector.tabulate(14)(day => if day % 2 == 0 then Vector(TakenOnTime) else Vector.empty)),
      MedicationRow("Ibuprofen", isAsNeeded = true, Vector.tabulate(14)(day => if Set(1, 4, 9)(day) then Vector(AsNeeded) else Vector.empty))
    )
  )

  private val manyMedications = StripData(
    LocalDate.of(2026, 11, 2),
    7,
    Vector(
      MedicationRow(CanaryName, isAsNeeded = false, everyDay(7, TakenOnTime, TakenLate, Missed)),
      MedicationRow(HostileName, isAsNeeded = false, everyDay(7, TakenOnTime, Skipped, TakenOnTime, Unknown, TakenOnTime, Missed))
    ) ++ (3 to 9).map(index => MedicationRow(s"Medication number $index", isAsNeeded = index == 9, everyDay(7, if index == 9 then AsNeeded else TakenOnTime)))
  )

  val strips: Vector[Fixture] = Vector(
    Fixture("strip/fortnight", context => MedicationStripCard.render(fortnight, context)),
    Fixture("strip/paginated-hostile", context => MedicationStripCard.render(manyMedications, context)),
    Fixture("strip/empty", context => MedicationStripCard.render(StripData(LocalDate.of(2026, 11, 2), 7, Vector.empty), context))
  )

  private val typicalWeek = WeekData(
    LocalDate.of(2026, 11, 2),
    Some(everyDay(7, TakenOnTime, TakenOnTime, TakenOnTime, TakenOnTime).updated(2, Vector(TakenOnTime, TakenLate, Skipped, TakenOnTime)).updated(5, Vector(TakenOnTime, Missed, TakenOnTime, TakenOnTime))),
    Some(Vector(2100, 1900, 2400, 0, 2000, 1200, 2050).map(volume => Option.when(volume > 0)(WaterProgress(volume, 2000)))),
    Some(Vector(MealDay(3, 1), MealDay(3, 0), MealDay(2, 1), MealDay(3, 0), MealDay(3, 2), MealDay(2, 0), MealDay(3, 0)).map(Some(_))),
    Some(Vector(Some(MoodState(6, 4)), Some(MoodState(4, 3)), Some(MoodState(2, 2)), None, Some(MoodState(6, 3)), Some(MoodState(7, 5)), Some(MoodState(4, 1)))),
    Some(Vector(430, 455, 380, 0, 440, 510, -1).map { minutes =>
      if minutes > 0 then SleepEvidence.Recorded(minutes, 480) else if minutes == 0 then SleepEvidence.NoRecord else SleepEvidence.QuietWindowOnly
    })
  )

  val weeks: Vector[Fixture] = Vector(
    Fixture("week/typical", context => Vector(WeekCard.render(typicalWeek, context))),
    Fixture(
      "week/sparse",
      context =>
        val sparse = WeekData(
          typicalWeek.from,
          Some(everyDay(7, TakenOnTime).updated(3, Vector(TakenOnTime, TakenOnTime, TakenOnTime, Skipped, Missed, TakenLate))),
          Some(Vector.fill(7)(None)),
          None,
          Some(Vector.fill(7)(None).updated(1, Some(MoodState(3, 3)))),
          Some(Vector.fill(7)(SleepEvidence.QuietWindowOnly))
        )
        Vector(WeekCard.render(sparse, context))
    ),
    Fixture("week/medication-only", context => Vector(WeekCard.render(WeekData(typicalWeek.from, typicalWeek.medication, None, None, None, None), context))),
    Fixture(
      "week/medication-records",
      context =>
        val recordsOnly = WeekData(
          typicalWeek.from,
          typicalWeek.medication,
          Some(Vector.fill(7)(None)),
          None,
          Some(Vector.fill(7)(None)),
          Some(Vector.fill(7)(SleepEvidence.NoRecord))
        )
        Vector(WeekCard.render(recordsOnly, context))
    )
  )

  val milestones: Vector[Fixture] = Vector(
    Fixture("milestone/10-water", context => Vector(MilestoneCard.render(MilestoneData(MilestoneKind.WaterEntries, 10), context))),
    Fixture("milestone/50-mood", context => Vector(MilestoneCard.render(MilestoneData(MilestoneKind.MoodCheckIns, 50), context))),
    Fixture("milestone/100-meals", context => Vector(MilestoneCard.render(MilestoneData(MilestoneKind.MealsLogged, 100), context))),
    Fixture("milestone/first-year", context => Vector(MilestoneCard.render(MilestoneData(MilestoneKind.FirstYear, 1), context)))
  )

  private val SleepFrom = Today.minusDays(13)

  // Bedtimes from 22:45 to 00:30 and wake times from 06:30 to 07:45 (330 to 435 and 750 to 825 on the axis), two
  // nights without a record and one nap.
  private val sleepBars: Vector[SleepNightBar] =
    Vector.tabulate(14)(index => index).filterNot(Set(4, 9)).map { index =>
      SleepNightBar(SleepFrom.plusDays(index.toLong), 285 + (index * 37) % 105, 750 + (index * 23) % 75, isNap = false)
    } :+ SleepNightBar(Today.minusDays(2), 1200, 1230, isNap = true)

  private val sleepTypical = SleepRegularityData(
    SleepFrom,
    Today,
    sleepBars,
    nights = 12,
    averageMinutes = Some(425),
    bedtimeSpreadMinutes = Some(35),
    wakeSpreadMinutes = Some(22),
    typicalBedtime = Some(23 * 60 + 30),
    typicalWake = Some(7 * 60 + 5),
    goalMinutes = Some(450)
  )

  val sleeps: Vector[Fixture] = Vector(
    Fixture("sleep/typical", context => Vector(SleepRegularityCard.render(sleepTypical, context))),
    Fixture(
      "sleep/few",
      context =>
        Vector(
          SleepRegularityCard.render(
            sleepTypical.copy(
              bars = sleepBars.takeRight(3),
              nights = 2,
              averageMinutes = None,
              bedtimeSpreadMinutes = None,
              wakeSpreadMinutes = None,
              typicalBedtime = None,
              typicalWake = None,
              goalMinutes = None
            ),
            context
          )
        )
    ),
    Fixture(
      "sleep/empty",
      context =>
        Vector(
          SleepRegularityCard.render(
            SleepRegularityData(SleepFrom, Today, Vector.empty, 0, None, None, None, None, None, None),
            context
          )
        )
    )
  )

  private def at(hour: Int, minute: Int): LocalTime = LocalTime.of(hour, minute)

  // IMG-09: the empty, partial and full day of ROADMAP G8.3, and a day without a plan, which is a count.
  val foods: Vector[Fixture] = Vector(
    Fixture("food/empty", context => Vector(FoodDayCard.render(FoodDayData(Today, Map.empty, 0, MealProgress(0, 3)), context))),
    Fixture(
      "food/partial",
      context =>
        Vector(
          FoodDayCard.render(
            FoodDayData(Today, Map(MainMeal.Breakfast -> at(8, 10), MainMeal.Dinner -> at(19, 40)), 1, MealProgress(2, 3)),
            context
          )
        )
    ),
    Fixture(
      "food/full",
      context =>
        Vector(
          FoodDayCard.render(
            FoodDayData(
              Today,
              Map(MainMeal.Breakfast -> at(8, 10), MainMeal.Lunch -> at(13, 5), MainMeal.Dinner -> at(19, 40)),
              2,
              MealProgress(3, 3)
            ),
            context
          )
        )
    ),
    Fixture(
      "food/no-plan",
      context =>
        Vector(FoodDayCard.render(FoodDayData(Today, Map(MainMeal.Lunch -> at(12, 30)), 0, MealProgress(0, 0)), context))
    )
  )

  private def doses(taken: Int, skipped: Int, missed: Int): DoseCounts = DoseCounts(taken, skipped, missed, 0, 0, 0)

  private val octoberWeeks: Vector[MonthWeek] = Vector(
    MonthWeek(Some(doses(15, 1, 0)), Some(2100), Some(MoodState(6, 4)), Some(440)),
    MonthWeek(Some(doses(26, 1, 1)), Some(1850), Some(MoodState(5, 3)), Some(415)),
    MonthWeek(Some(doses(25, 2, 1)), None, Some(MoodState(4, 3)), Some(395)),
    MonthWeek(Some(doses(27, 0, 1)), Some(2300), None, Some(450)),
    MonthWeek(Some(doses(19, 0, 1)), Some(1950), Some(MoodState(6, 3)), None)
  )

  private val october2026 = MonthData(
    YearMonth.of(2026, 10),
    daysWithRecords = 29,
    weeks = octoberWeeks,
    doses = Some(doses(112, 4, 3)),
    water = Some(GoalDaysOf(18, 26)),
    moodCheckInDays = Some(22),
    sleep = Some((425, 24)),
    previousAdherence = Some(91)
  )

  private val october2026WithFood = october2026.copy(
    weeks = octoberWeeks.zip(Vector(Some(10), Some(17), Some(15), None, Some(12))).map((week, meals) => week.copy(mealsLogged = meals)),
    meals = Some(MonthMeals(54, 7))
  )

  val months: Vector[Fixture] = Vector(
    Fixture("month/typical", context => Vector(MonthCard.render(october2026, context))),
    Fixture(
      "month/no-medication",
      context =>
        val withoutMedication = october2026.copy(
          weeks = octoberWeeks.map(_.copy(doses = None, sleepMeanMinutes = None)),
          doses = None,
          sleep = None,
          daysWithRecords = 27
        )
        Vector(MonthCard.render(withoutMedication, context))
    ),
    // Five rows, food included: the tighter rows end above the footer with the comparison line.
    Fixture("month/with-food", context => Vector(MonthCard.render(october2026WithFood, context)))
  )

  val insights: Vector[Fixture] = Vector(
    Fixture(
      "insight/water-goal-mood",
      context =>
        val data = InsightData(InsightRule.WaterGoalMood, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 9), InsightGroup(18, 3.7), InsightGroup(9, 3.2))
        Vector(InsightCard.render(data, context))
    ),
    Fixture(
      "insight/sleep-goal-mood",
      context =>
        val data = InsightData(InsightRule.SleepGoalMood, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 9), InsightGroup(18, 3.7), InsightGroup(9, 3.0))
        Vector(InsightCard.render(data, context))
    )
  )

  val moreInsights: Vector[Fixture] = Vector(
    Fixture(
      "insight/bedtime-mood",
      context =>
        val data = InsightData(InsightRule.BedtimeMood, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 9), InsightGroup(16, 3.9), InsightGroup(11, 3.2))
        Vector(InsightCard.render(data, context))
    ),
    Fixture(
      "insight/work-day-mood",
      context =>
        val data = InsightData(InsightRule.WorkDayMood, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 9), InsightGroup(20, 3.1), InsightGroup(8, 3.8))
        Vector(InsightCard.render(data, context))
    )
  )

  private val OctoberMonth = YearMonth.of(2026, 10)

  // 22 check-in days spread over the seven levels; days 7, 13, 14, 20, 21, 27, 28, 30 and 31 have none.
  private val octoberMoods: Vector[Option[MoodState]] =
    Vector.tabulate(31) { index =>
      val day = index + 1
      Option.unless(Set(7, 13, 14, 20, 21, 27, 28, 30, 31)(day))(MoodState(1 + (day * 5) % 7, 1 + day % 5))
    }

  private val moodOctober = MoodCalendarData(OctoberMonth, DayOfWeek.MONDAY, octoberMoods)

  private def moodPoint(day: Int): Option[MoodDayPoint] =
    Option.unless(Set(5, 9)(day)) {
      val mean = StrictMath.min(5.0, 2.0 + (day * 7 % 13) / 4.0)
      val checkIns = 1 + day % 3
      val (low, high) = if checkIns == 1 then (mean.round.toInt, mean.round.toInt) else (StrictMath.floor(mean).toInt, StrictMath.ceil(mean).toInt.max(StrictMath.floor(mean).toInt + 1).min(5))
      MoodDayPoint(mean, low, high, checkIns, Option.when(day % 4 != 0)(1.0 + day % 5))
    }

  val moodCards: Vector[Fixture] = Vector(
    Fixture("mood-calendar/typical", context => Vector(MoodCalendarCard.render(moodOctober, context))),
    Fixture(
      "mood-calendar/every-day",
      context =>
        val february = MoodCalendarData(YearMonth.of(2026, 2), DayOfWeek.SUNDAY, Vector.tabulate(28)(day => Some(MoodState(1 + day % 7, 3))))
        Vector(MoodCalendarCard.render(february, context))
    ),
    Fixture("mood-timeline/fortnight", context => Vector(MoodTimelineCard.render(MoodTimelineData(LocalDate.of(2026, 10, 27), Vector.tabulate(14)(day => moodPoint(day))), context))),
    Fixture(
      "mood-timeline/month-no-energy",
      context =>
        val days = Vector.tabulate(30)(day => moodPoint(day).map(_.copy(energy = None)))
        Vector(MoodTimelineCard.render(MoodTimelineData(LocalDate.of(2026, 10, 11), days), context))
    )
  )

  // October's nights on the clock: bedtimes 22:30 to 00:45 and wake times 06:15 to 08:00, five nights missing.
  val octoberNights: SleepRegularityData =
    val bars = (1 to 31).filterNot(Set(6, 12, 19, 25, 30)).toVector.map { day =>
      SleepNightBar(OctoberMonth.atDay(day), 270 + (day * 37) % 135, 735 + (day * 29) % 105, isNap = false)
    } :+ SleepNightBar(OctoberMonth.atDay(18), 1200, 1240, isNap = true)
    SleepRegularityData(OctoberMonth.atDay(1), OctoberMonth.atEndOfMonth, bars, 26, Some(428), Some(38), Some(26), Some(23 * 60 + 40), Some(7 * 60 + 10), Some(450))

  val octoberWater: Vector[Option[MonthWaterDay]] =
    Vector.tabulate(31) { index =>
      Option.unless(Set(3, 14, 15, 22, 29)(index + 1))(MonthWaterDay(1300 + (index * 173) % 1200, Option.when(index >= 4)(2000)))
    }

  val octoberFood: Vector[Option[MonthFoodDay]] =
    Vector.tabulate(OctoberMonth.lengthOfMonth) { index =>
      Option.unless(Set(5, 12, 19, 26)(index + 1))(MonthFoodDay(1 + (index * 5) % 3, if index % 4 == 0 then 1 else 0))
    }

  val monthPages: Vector[Fixture] = Vector(
    Fixture(
      "month/nights-with-food",
      context =>
        Vector(MonthPages.nights(MonthNightsData(OctoberMonth, Some(octoberNights), None, Some(octoberFood)), context))
    ),
    Fixture(
      "month/nights-food-only",
      context => Vector(MonthPages.nights(MonthNightsData(OctoberMonth, None, None, Some(octoberFood)), context))
    ),
    Fixture(
      "month/report",
      context =>
        Vector(
          MonthCard.render(october2026, context),
          MonthPages.days(MonthDaysData(OctoberMonth, Some(october), Some(moodOctober)), context),
          MonthPages.nights(MonthNightsData(OctoberMonth, Some(octoberNights), Some(octoberWater)), context)
        )
    ),
    Fixture(
      "month/days-mood-only",
      context => Vector(MonthPages.days(MonthDaysData(OctoberMonth, None, Some(moodOctober)), context))
    ),
    Fixture(
      "month/nights-water-only",
      context => Vector(MonthPages.nights(MonthNightsData(OctoberMonth, None, Some(octoberWater)), context))
    ),
    Fixture(
      "month/days-six-weeks",
      context =>
        val august = YearMonth.of(2026, 8)
        val calendar = october.copy(month = august, today = None)
        val mood = MoodCalendarData(august, DayOfWeek.MONDAY, octoberMoods)
        Vector(MonthPages.days(MonthDaysData(august, Some(calendar), Some(mood)), context))
    )
  )

  private def shareDays(domain: dosegram.render.tokens.Domain, marks: String): ShareFact =
    ShareFact.DayCount(domain, marks.toVector.map(_ == 'x'))

  val shares: Vector[Fixture] = Vector(
    Fixture("share/water-5-of-7", context => Vector(ShareCard.render(ShareData(shareDays(dosegram.render.tokens.Domain.Water, "xx.xx.x")), context))),
    Fixture("share/sleep-7-of-7", context => Vector(ShareCard.render(ShareData(shareDays(dosegram.render.tokens.Domain.Sleep, "xxxxxxx")), context))),
    Fixture("share/mood-1-of-7", context => Vector(ShareCard.render(ShareData(shareDays(dosegram.render.tokens.Domain.Mood, "......x")), context))),
    Fixture("share/week-mood", context => Vector(ShareCard.render(ShareData(ShareFact.WeekMood(MoodState(5, 3))), context)))
  )

  // The week cards of the image round of 2026-11-15: sparse, typical and dense data for each.
  private val sparseWeek = WeekData(
    typicalWeek.from,
    Some(Vector.fill(7)(Vector.empty[DoseStatus]).updated(1, Vector(TakenOnTime)).updated(4, Vector(Open, Unknown))),
    Some(Vector.fill(7)(None).updated(2, Some(WaterProgress(800, 2000)))),
    Some(Vector.fill(7)(None)),
    Some(Vector.fill(7)(None).updated(5, Some(MoodState(3, 2)))),
    Some(Vector.fill(7)(SleepEvidence.NoRecord).updated(0, SleepEvidence.QuietWindowOnly).updated(3, SleepEvidence.Recorded(390, 480)))
  )

  private val denseWeek = WeekData(
    typicalWeek.from,
    Some(everyDay(7, TakenOnTime, TakenOnTime, TakenOnTime, TakenOnTime, TakenOnTime, TakenOnTime).updated(6, Vector(Skipped, Skipped, TakenLate, TakenLate, TakenOnTime, Missed))),
    Some(Vector(2600, 2000, 3100, 1990, 2400, 4750, 2050).map(volume => Some(WaterProgress(volume, 2000)))),
    Some(Vector(MealDay(3, 2), MealDay(4, 1), MealDay(3, 3), MealDay(3, 0), MealDay(3, 1), MealDay(3, 2), MealDay(1, 4)).map(Some(_))),
    Some(Vector(MoodState(7, 5), MoodState(6, 4), MoodState(5, 3), MoodState(4, 2), MoodState(3, 1), MoodState(2, 5), MoodState(1, 4)).map(Some(_))),
    Some(Vector(480, 515, 600, 405, 495, 720, 470).map(SleepEvidence.Recorded(_, 480)))
  )

  val glances: Vector[Fixture] = Vector(
    Fixture("week-glance/sparse", context => Vector(WeekGlanceCard.render(sparseWeek, context))),
    Fixture("week-glance/typical", context => Vector(WeekGlanceCard.render(typicalWeek, context))),
    Fixture("week-glance/dense", context => Vector(WeekGlanceCard.render(denseWeek, context))),
    Fixture("week-glance/two-domains", context => Vector(WeekGlanceCard.render(WeekData(typicalWeek.from, None, typicalWeek.water, None, typicalWeek.mood, None), context)))
  )

  val waterWeeks: Vector[Fixture] = Vector(
    Fixture("water-week/sparse", context => Vector(WaterWeekCard.render(sparseWeek.from, sparseWeek.water.get, context))),
    Fixture("water-week/typical", context => Vector(WaterWeekCard.render(typicalWeek.from, typicalWeek.water.get, context))),
    Fixture("water-week/dense", context => Vector(WaterWeekCard.render(denseWeek.from, denseWeek.water.get, context))),
    Fixture("water-week/empty", context => Vector(WaterWeekCard.render(typicalWeek.from, Vector.fill(7)(None), context)))
  )

  val sleepWeeks: Vector[Fixture] = Vector(
    Fixture("sleep-week/sparse", context => Vector(SleepWeekCard.render(sparseWeek.from, sparseWeek.sleep.get, context))),
    Fixture("sleep-week/typical", context => Vector(SleepWeekCard.render(typicalWeek.from, typicalWeek.sleep.get, context))),
    Fixture("sleep-week/dense", context => Vector(SleepWeekCard.render(denseWeek.from, denseWeek.sleep.get, context)))
  )

  private def weekdayRows(means: Vector[(Option[Double], Int)]): Vector[MoodWeekdayRow] =
    DayOfWeek.values.toVector.zip(means).map { case (day, (mean, days)) => MoodWeekdayRow(day, mean, days) }

  val moodWeekdays: Vector[Fixture] = Vector(
    Fixture(
      "mood-weekday/sparse",
      context =>
        val rows = weekdayRows(Vector((None, 1), (None, 2), (Some(3.0), 3), (None, 0), (None, 2), (Some(4.33), 3), (None, 1)))
        Vector(MoodWeekdayCard.render(MoodWeekdayData(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 11, 8), rows), context))
    ),
    Fixture(
      "mood-weekday/typical",
      context =>
        val rows = weekdayRows(Vector((Some(3.2), 7), (Some(3.0), 8), (Some(3.5), 6), (Some(2.6), 8), (Some(3.9), 7), (Some(4.4), 5), (Some(4.1), 4)))
        Vector(MoodWeekdayCard.render(MoodWeekdayData(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 11, 8), rows), context))
    ),
    Fixture(
      "mood-weekday/dense",
      context =>
        val rows = weekdayRows(Vector((Some(1.0), 12), (Some(1.8), 12), (Some(2.5), 13), (Some(3.0), 12), (Some(3.75), 12), (Some(4.5), 13), (Some(5.0), 12)))
        Vector(MoodWeekdayCard.render(MoodWeekdayData(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 11, 8), rows), context))
    )
  )

  val refills: Vector[Fixture] = Vector(
    Fixture(
      "refill/typical",
      context =>
        val label = CanaryName
        val (unit, threshold, text) = context.copy match
          case UkrainianImageCopy =>
            ("таблеток", "10 таблеток", s"$label: за вашим підрахунком лишилося 6 таблеток, це ваш поріг (10 таблеток) або менше.")
          case _ =>
            ("tablets", "10 tablets", s"$label: 6 tablets left by your count, at or below your threshold of 10 tablets.")
        Vector(
          RefillCard.render(
            RefillData(label, 6, unit, Some(10), threshold, Some(LocalDate.of(2026, 11, 15)), text),
            context
          )
        )
    )
  )

  // IMG-23: the figures are the caller's words, as the core writes them in each language (ROADMAP G9.5).
  private def trendsMonth(copy: ImageCopy): TrendsData =
    val days = 30
    def series(value: Int => Option[Double]): Vector[Option[Double]] = Vector.tabulate(days)(value)
    val doses = series(day => if day % 9 == 4 then None else Some(if day % 5 == 0 then 0.5 else 1.0))
    val water = series(day => Option.when(day % 4 != 1)(1300.0 + (day * 137) % 900))
    val mood = series(day => Option.when(day % 3 != 2)(2.0 + (day * 7 % 9) / 3.0))
    val sleep = series(day => Option.when(day % 6 != 3)(380.0 + (day * 53) % 120))
    val food = series(day => Option.when(day % 2 == 0)(1.0 + day % 3))
    val (figures, text) = copy match
      case UkrainianImageCopy =>
        (
          Vector("прийнято 52 з 56 (93%) · пропущено 2", "ціль досягнуто в 15 з 22 днів із записами", "28 позначок",
            "записано 25 ночей", "30 прийомів їжі за 15 днів"),
          "Динаміка · останні 30 днів · 10 жовтня – 8 листопада\nЛіки: прийнято 52 з 56 (93%) · пропущено 2. " +
            "Попередні 30 днів: прийнято 50 з 56 (89%).\nВода: ціль досягнуто в 15 з 22 днів із записами. " +
            "Попередні 30 днів: вода записана за 18 днів.\nНастрій: 28 позначок. Попередні 30 днів: позначки за 20 днів.\n" +
            "Сон: записано 25 ночей. Попередні 30 днів: немає даних.\nЇжа: 30 прийомів їжі за 15 днів. Попередні 30 днів: немає даних."
        )
      case _ =>
        (
          Vector("52 of 56 taken (93%) · skipped 2", "goal reached on 15 of 22 recorded days", "28 check-ins",
            "25 nights logged", "30 meals logged on 15 days"),
          "Trends · last 30 days · 10 Oct to 8 Nov\nMedication: 52 of 56 taken (93%) · skipped 2. " +
            "Previous 30 days: 50 of 56 taken (89%).\nWater: goal reached on 15 of 22 recorded days. " +
            "Previous 30 days: water logged on 18 days.\nMood: 28 check-ins. Previous 30 days: check-ins on 20 days.\n" +
            "Sleep: 25 nights logged. Previous 30 days: No data.\nFood: 30 meals logged on 15 days. Previous 30 days: No data."
        )
    val rows = Vector(
      TrendRow(Domain.Medication, figures(0), doses, TrendScale.FromZero),
      TrendRow(Domain.Water, figures(1), water, TrendScale.FromZero),
      TrendRow(Domain.Mood, figures(2), mood, TrendScale.Levels),
      TrendRow(Domain.Sleep, figures(3), sleep, TrendScale.FromZero),
      TrendRow(Domain.Food, figures(4), food, TrendScale.FromZero)
    )
    TrendsData(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 8), byWeek = false, rows, text)

  private def trendsQuarter(copy: ImageCopy): TrendsData =
    val weeks = 13
    val water = Vector.tabulate(weeks)(week => Option.when(week > 1)(1500.0 + (week * 211) % 700))
    val mood = Vector.tabulate(weeks)(week => Option.when(week != 5)(2.5 + (week % 4) * 0.5))
    val sleep = Vector.tabulate(weeks)(week => Some(400.0 + (week * 37) % 80))
    val (figures, text) = copy match
      case UkrainianImageCopy =>
        (
          Vector("у середньому 1,8 л на день", "64 позначки", "записано 78 ночей"),
          "Динаміка · останні 90 днів · 11 серпня – 8 листопада\nВода: у середньому 1,8 л на день. " +
            "Попередні 90 днів: немає даних.\nНастрій: 64 позначки. Попередні 90 днів: немає даних.\n" +
            "Сон: записано 78 ночей. Попередні 90 днів: немає даних."
        )
      case _ =>
        (
          Vector("average 1.8 l a day", "64 check-ins", "78 nights logged"),
          "Trends · last 90 days · 11 Aug to 8 Nov\nWater: average 1.8 l a day. Previous 90 days: No data.\n" +
            "Mood: 64 check-ins. Previous 90 days: No data.\nSleep: 78 nights logged. Previous 90 days: No data."
        )
    val rows = Vector(
      TrendRow(Domain.Water, figures(0), water, TrendScale.FromZero),
      TrendRow(Domain.Mood, figures(1), mood, TrendScale.Levels),
      TrendRow(Domain.Sleep, figures(2), sleep, TrendScale.FromZero)
    )
    TrendsData(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 11, 8), byWeek = true, rows, text)

  val trends: Vector[Fixture] = Vector(
    Fixture("trends/month", context => Vector(TrendsCard.render(trendsMonth(context.copy), context))),
    Fixture("trends/quarter", context => Vector(TrendsCard.render(trendsQuarter(context.copy), context)))
  )

  val all: Vector[Fixture] =
    moods ++ days ++ waters ++ calendars ++ strips ++ weeks ++ milestones ++ sleeps ++ months ++ insights ++
      moreInsights ++ moodCards ++ monthPages ++ foods ++ shares ++ glances ++ waterWeeks ++ sleepWeeks ++ moodWeekdays ++
      refills ++ trends

  /** Every card the contact sheet and the golden suite show. */
  val review: Vector[Fixture] = all
