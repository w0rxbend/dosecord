package dosegram.render.copy

import dosegram.render.blob.MoodReading
import dosegram.render.card.DayData
import dosegram.render.card.DoseCounts
import dosegram.render.card.MilestoneData
import dosegram.render.card.WaterData
import dosegram.render.card.WaterProgress
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
  def domainName(domain: Domain): String
  def itemLabel(position: Int): String
  def dayDate(date: LocalDate): String
  def dateRange(from: LocalDate, to: LocalDate): String
  def monthYear(month: YearMonth): String
  def weekdayInitial(day: DayOfWeek): String
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
  def fromYourCheckIns: String
  def estimateOnly: String
  def dayText(day: DayData): String
  def dayDiscreet: String

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
  def weekText(week: WeekSummary): String
  def weekDiscreet: String

  // Milestone (IMG-14).
  def milestoneLabel(milestone: MilestoneData): String
  def milestoneText(milestone: MilestoneData): String

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
