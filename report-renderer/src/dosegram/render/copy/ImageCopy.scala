package dosegram.render.copy

import dosegram.render.blob.MoodReading
import dosegram.render.card.DayData
import dosegram.render.card.DoseCounts
import dosegram.render.card.FoodDayData
import dosegram.render.card.HighlightRow
import dosegram.render.card.InsightData
import dosegram.render.card.InsightRule
import dosegram.render.card.MainMeal
import dosegram.render.card.MilestoneData
import dosegram.render.card.MonthDaysData
import dosegram.render.card.MonthMeals
import dosegram.render.card.MonthNightsData
import dosegram.render.card.MonthPosterData
import dosegram.render.card.MoodCalendarData
import dosegram.render.card.MoodGalleryData
import dosegram.render.card.MoodTimelineData
import dosegram.render.card.MoodWeekdayData
import dosegram.render.card.ShareFact
import dosegram.render.card.SleepEvidence
import dosegram.render.card.SleepRegularityData
import dosegram.render.card.WaterData
import dosegram.render.card.WaterProgress
import dosegram.render.card.WeekHighlightsData
import dosegram.render.chart.DoseStatus
import dosegram.render.tokens.Domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** The classes of a calendar day, which the legend names (VISUALS section 6.7). A day on which no dose was taken is
  * `Skipped` when every resolved dose was skipped and `NotConfirmed` when at least one got no answer; the two have
  * different glyphs, so the legend names them apart.
  */
enum DayClass:
  case AllTaken, Most, Some, Few, Skipped, NotConfirmed, NoRecord, StillOpen

/** The image namespace of the message catalogue: every word, number format and date format that can appear in an image,
  * a caption or a text equivalent. Templates contain no words (VA-ID-04, VA-L10N-01); they ask this port. Whole
  * sentences live here because word order differs by language. Copy records what the user did; it never advises, judges
  * or implies a cause (VA-PR-02).
  */
trait ImageCopy:
  // Shared.
  def footer: String
  def detailsBelow: String

  /** The one-year card (IMG-30), one block per language. */
  def year: YearImageCopy
  def domainName(domain: Domain): String
  def itemLabel(position: Int): String
  def dayDate(date: LocalDate): String
  def dateRange(from: LocalDate, to: LocalDate): String
  def monthYear(month: YearMonth): String

  /** One letter, for a column too narrow for more (the mood calendar of a month). */
  def weekdayInitial(day: DayOfWeek): String

  /** The head of a day column of a week: one or two letters from the locale (VISUALS section 6), two where one letter
    * would leave two days alike.
    */
  def weekdayHead(day: DayOfWeek): String
  def weekdayShort(day: DayOfWeek): String
  def time(time: LocalTime): String
  def percent(value: Int): String
  def duration(minutes: Int): String
  def noData: String

  // Mood (IMG-02).
  def valenceName(valenceLevel: Int): String
  def energyLine(energyLevel: Int): String
  def moodLabel(reading: MoodReading): String
  def noMoodEntry: String
  def moodText(mood: Option[MoodReading]): String
  def moodLoggedDiscreet: String

  // Day (IMG-03).
  def todayTitle: String
  def takenOf(taken: Int, counted: Int): String
  def nothingLoggedYet: String
  def mealsOf(logged: Int, planned: Int): String
  def fromYourRecords: String
  def estimateOnly: String
  def dayText(day: DayData): String
  def dayDiscreet: String

  // Food (IMG-09).
  def foodTitle: String
  def mainMeal(meal: MainMeal): String
  def snacksLabel: String
  def snackCount(count: Int): String

  /** The value under the snacks label of the food card, which has room for a short word. */
  def snackSlot(count: Int): String
  def foodSummary(food: FoodDayData): String
  def foodText(food: FoodDayData): String
  def foodDiscreet: String

  // Water (IMG-08).
  def waterTitle: String
  def waterFraction(progress: WaterProgress): String
  def waterTick(volumeMl: Int): String
  def waterTopTick(volumeMl: Int): String
  def entries(count: Int): String
  def lastEntryAt(time: LocalTime): String
  def goalIsYours: String
  def noWaterToday: String
  def waterText(water: WaterData): String
  def waterDiscreet: String

  // Adherence calendar (IMG-06).
  def calendarTitle: String
  def dayClassName(dayClass: DayClass): String
  def blankDayNote: String
  def resolvedDosesTaken(taken: Int, resolved: Int): String
  def unknownDosesNotCounted(count: Int): String
  def calendarText(lines: CalendarLines): String
  def calendarDiscreet: String

  // Per-medication strip (IMG-07).
  def stripTitle: String
  def pageOf(page: Int, pageCount: Int): String
  def statusName(status: DoseStatus): String
  def asNeededName(name: String): String
  def logsOnDays(logs: Int, days: Int): String
  def stripEmpty: String

  /** One strip row's figures, headline first: the one formatter of the row's summary on the card (the headline) and of
    * its line in the caption (all of them), counted as the chat's `/stats meds` counts them. An open dose is "still to
    * answer" and never in the denominator (supervisor decision, 2026-11-28).
    */
  def stripFigures(counts: DoseCounts): Vector[String]
  def stripText(lines: StripLines): String
  def stripDiscreet: String

  // Week (IMG-04).
  def weekTitle: String
  def weekDoses(counts: DoseCounts): String
  def goalReachedDays(days: GoalDays): String
  def mealsLogged(count: Int): String
  def checkInDays(count: Int): String
  def sleepAverage(minutes: Int): String
  def noRecordsThisWeek: String
  def yourGoal: String
  def noRecordMark: String
  def estimateMark: String
  def snackMark: String
  def weekText(week: WeekSummary): String
  def weekDiscreet: String

  // Sleep regularity (IMG-12).
  def sleepTitle: String
  def sleepAxisHour(hour: Int): String
  def sleepRowDate(date: LocalDate): String
  def sleepAverageOf(minutes: Int, nights: Int): String
  def sleepSpread(bedtimeMinutes: Int, wakeMinutes: Int): String
  def sleepEntriesMark: String
  def sleepNapMark: String

  /** The letter beside a nap bar, which the legend explains (VA-ID-04: no word in a template). */
  def sleepNapLetter: String
  def sleepTypicalMark: String
  def sleepGoal(minutes: Int): String
  def sleepNoNights: String
  def sleepText(sleep: SleepRegularityData): String
  def sleepDiscreet: String

  // Month (IMG-05, page 1).
  def monthTitle: String
  def weekNumber(week: Int): String
  def monthHeroDoses(counts: DoseCounts): Vector[String]
  def monthHeroDays(days: Int): Vector[String]
  def monthPrevious(month: YearMonth, percent: Int): String
  def monthText(month: MonthSummary): String
  def monthDiscreet: String

  // Insight (IMG-13). The fixed non-causal sentence is part of the template (VA-IN-11).
  def insightTitle: String
  def insightGroup(rule: InsightRule, isFirst: Boolean): String
  def insightValue(value: Double): String
  def insightDays(days: Int): String
  def insightScaleNote(outcome: Domain): String
  def insightNotCause: String
  def insightText(insight: InsightData): String
  def insightDiscreet: String

  // Mood calendar (IMG-11) and mood timeline (IMG-10).
  def moodCalendarTitle: String
  def noCheckIn: String
  def moodCalendarFact(checkInDays: Int): String
  def moodCalendarText(calendar: MoodCalendarData): String
  def moodCalendarDiscreet: String
  def moodTimelineTitle: String
  def energyLabel: String
  def moodTimelineFact(checkInDays: Int, days: Int): String
  def moodTimelineText(timeline: MoodTimelineData): String
  def moodTimelineDiscreet: String

  // Month, pages 2 and 3 (IMG-05).
  def monthDaysTitle: String
  def monthNightsTitle: String
  def sleepNights(nights: Int): String
  def monthWaterFact(goalDays: Option[GoalDays], recordedDays: Int): String
  def waterLoggedMark: String

  /** Food on the month's pages (VISUALS section 6.6): the meals logged and the snacks apart, as the weekly card. */
  def monthFood(meals: MonthMeals): String
  def monthFoodFact(meals: MonthMeals, days: Int): String
  def mealLoggedMark: String

  /** The title of page 3 when it draws the food panel beside at most one of the sleep and water panels. */
  def monthNightsFoodTitle(withSleep: Boolean, withWater: Boolean): String
  def napMark: String
  def monthDaysText(page: MonthDaysData): String
  def monthNightsText(page: MonthNightsData): String

  // Milestone (IMG-14).
  def milestoneLabel(milestone: MilestoneData): String
  def milestoneText(milestone: MilestoneData): String

  // Shareable card (IMG-17): the fact in three lines, its text, and the product's wordmark.
  def shareDayCount(fact: ShareFact.DayCount): (String, String, String)
  def shareMoodPeriod: String
  def shareText(fact: ShareFact): String
  def shareDiscreet: String
  def shareWordmark: String = "Dosegram"

  // Refill (IMG-15). The label, the count, the unit and the threshold come from the user's own entries.
  def refillTitle: String
  def refillDiscreetLabel: String
  def refillSource: String
  def refillThreshold(threshold: String): String
  def refillUnitsLeft(unit: String): String
  def refillCovers(date: LocalDate): String

  // The week cards of the image round of 2026-11-15 (IMG-19 to IMG-22). A weekly average is drawn from three recorded
  // days (VISUALS section 7.1); below that the hero is the count of days.
  def glanceTitle: String
  def glanceFillNote: String

  /** The legend's note for a medication day with nothing scheduled, which the glance leaves without a cell. */
  def glanceBlankNote: String
  def glanceDiscreet: String
  def waterWeekTitle: String
  def litresAmount(volumeMl: Int): String
  def averageADay(days: Int): String
  def daysLogged(days: Int): String
  def waterWeekText(from: LocalDate, days: Vector[Option[WaterProgress]]): String
  def waterWeekDiscreet: String
  def sleepWeekTitle: String
  def averageOverNights(nights: Int): String
  def nightsLogged(nights: Int): String
  def sleepWeekText(from: LocalDate, nights: Vector[SleepEvidence]): String
  def sleepWeekDiscreet: String
  def moodWeekdayTitle: String
  def weekdayName(day: DayOfWeek): String
  def moodWeekdayDays(days: Int): String
  def moodWeekdayTooFew: String
  def moodWeekdayText(data: MoodWeekdayData): String
  def moodWeekdayDiscreet: String
  def trendsTitle: String
  def trendsPeriod(from: LocalDate, to: LocalDate, byWeek: Boolean): String
  def trendsScaleNote: String
  def trendsDiscreet: String

  // Image round 4 (2026-11-27): a week of IMG-20 or IMG-21 with nothing logged.
  def waterWeekNone: String
  def sleepWeekNone: String

  // The month in one image (IMG-24).
  def posterSubtitle: String
  def posterOrderNote: String
  def posterFact(days: Int, total: Int): String
  def posterText(data: MonthPosterData): String
  def posterDiscreet: String

  // Image round 5 (2026-11-28): the mood gallery of `/mood history` (IMG-26) and the week highlights (IMG-25).
  def galleryTitle: String
  def galleryFact(count: Int): String
  def galleryOrderNote: String
  def galleryText(data: MoodGalleryData): String
  def galleryDiscreet: String
  def highlightTitle: String
  def highlightDayMark: String
  def highlightOtherMark: String
  def highlightObservationTitle(days: Int): String

  /** A row's figure line: the highest days and the figure, or the recorded days when a highlight needs more. */
  def highlightLine(from: LocalDate, row: HighlightRow): String
  def highlightText(data: WeekHighlightsData): String
  def highlightDiscreet: String

/** The figures of page 1 of the monthly report; an absent domain is not shown. `sleep` is the mean and the nights. */
final case class MonthSummary(
    month: YearMonth,
    daysWithRecords: Int,
    doses: Option[DoseCounts],
    water: Option[GoalDays],
    moodCheckInDays: Option[Int],
    sleep: Option[(Int, Int)],
    previousAdherence: Option[Int],
    meals: Option[MonthMeals] = None
)

/** The counts behind the calendar's text equivalent. */
final case class CalendarLines(
    month: YearMonth,
    medicationLabel: Option[String],
    dayCounts: Vector[(DayClass, Int)],
    nothingScheduledDays: Int,
    taken: Int,
    resolved: Int,
    unknownDoseCount: Int
)

/** One medication of the strip text: its label, and either scheduled-dose counts or as-needed logs on days. */
final case class StripLine(label: String, counts: DoseCounts, asNeededDays: Option[Int])

final case class StripLines(from: LocalDate, to: LocalDate, rows: Vector[StripLine])

/** Days on which the water goal was reached, out of days with a log. */
final case class GoalDays(reached: Int, recorded: Int)

/** Sleep over the week: the mean of recorded nights, and nights known only as an estimate, which are not averaged. */
final case class SleepWeek(averageMinutes: Option[Int], recordedNights: Int, estimateNights: Int)

/** The figures of the weekly card, one per tracked domain; an absent domain is not tracked. The image headlines and the
  * text equivalent are both worded from these values.
  */
final case class WeekSummary(
    from: LocalDate,
    to: LocalDate,
    doses: Option[DoseCounts],
    water: Option[GoalDays],
    mealsLogged: Option[Int],
    checkInDays: Option[Int],
    sleep: Option[SleepWeek]
)
