package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import sttp.tapir.Schema

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The JSON documents of /api/v1 (ARCHITECTURE section 9.4): identifiers are UUID strings, instants are RFC 3339 UTC,
// local dates are YYYY-MM-DD, local times are HH:MM, decimal quantities are strings. Sentences about a schedule or a
// day are written here, by the server (MINI_APP section 15.1).

enum MedicationState(val wire: String):
  case Active extends MedicationState("active")
  case Paused extends MedicationState("paused")
  case Archived extends MedicationState("archived")

object MedicationState:
  given ReadWriter[MedicationState] = ApiJson.wireEnum(values, _.wire)
  given Schema[MedicationState] = Schema.derivedEnumeration[MedicationState](encode = Some(_.wire))

enum ScheduleState(val wire: String):
  case Active extends ScheduleState("active")
  case Paused extends ScheduleState("paused")
  case Ended extends ScheduleState("ended")

object ScheduleState:
  given ReadWriter[ScheduleState] = ApiJson.wireEnum(values, _.wire)
  given Schema[ScheduleState] = Schema.derivedEnumeration[ScheduleState](encode = Some(_.wire))

enum SchedulePattern(val wire: String):
  case FixedTimes extends SchedulePattern("fixed_times")
  case AsNeeded extends SchedulePattern("as_needed")
  case EveryNDays extends SchedulePattern("every_n_days")
  case Cycle extends SchedulePattern("cycle")

object SchedulePattern:
  given ReadWriter[SchedulePattern] = ApiJson.wireEnum(values, _.wire)
  given Schema[SchedulePattern] = Schema.derivedEnumeration[SchedulePattern](encode = Some(_.wire))

enum MedicationChange(val wire: String):
  case Pause extends MedicationChange("pause")
  case Resume extends MedicationChange("resume")
  case Archive extends MedicationChange("archive")

object MedicationChange:
  given ReadWriter[MedicationChange] = ApiJson.wireEnum(values, _.wire)
  given Schema[MedicationChange] = Schema.derivedEnumeration[MedicationChange](encode = Some(_.wire))

enum ScheduleChange(val wire: String):
  case Pause extends ScheduleChange("pause")
  case Resume extends ScheduleChange("resume")
  case End extends ScheduleChange("end")

object ScheduleChange:
  given ReadWriter[ScheduleChange] = ApiJson.wireEnum(values, _.wire)
  given Schema[ScheduleChange] = Schema.derivedEnumeration[ScheduleChange](encode = Some(_.wire))

enum DoseState(val wire: String):
  case Pending extends DoseState("pending")
  case Due extends DoseState("due")
  case Snoozed extends DoseState("snoozed")
  case Taken extends DoseState("taken")
  case TakenLate extends DoseState("taken_late")
  case Skipped extends DoseState("skipped")
  case Missed extends DoseState("missed")
  case Unknown extends DoseState("unknown")

object DoseState:
  given ReadWriter[DoseState] = ApiJson.wireEnum(values, _.wire)
  given Schema[DoseState] = Schema.derivedEnumeration[DoseState](encode = Some(_.wire))

// ---------- session ----------

/** `initData` is the raw launch string; the other members describe the client and are not trusted (MINI_APP 3.2). */
final case class SessionRequest(
    initData: String,
    deviceTimeZone: Option[String] = None,
    build: Option[String] = None,
    platform: Option[String] = None,
    version: Option[String] = None,
    colorScheme: Option[String] = None
) derives ReadWriter,
      Schema

/** The limits the client shows, so that it hard-codes none (MINI_APP 15.1). */
final case class Limits(medicationNameMax: Int, instructionsMax: Int, doseUnitMax: Int, scheduleTimesMax: Int)
    derives ReadWriter,
      Schema

/** `consents` are the purposes the account has granted now, so the first screen asks only for the domains it may show.
  */
final case class Bootstrap(timeZone: String, limits: Limits, consents: List[String] = Nil) derives ReadWriter, Schema

/** The token is returned once and is never stored by the server in this form (API-05). */
final case class SessionCreated(token: String, expiresAt: Instant, idleTimeoutSeconds: Long, bootstrap: Bootstrap)
    derives ReadWriter,
      Schema

// ---------- medication-registry ----------

/** The editable set of a medication. `PATCH` carries the whole set: an absent optional member clears the value. */
final case class MedicationInput(
    name: String,
    doseAmount: Option[String] = None,
    doseUnit: Option[String] = None,
    instructions: Option[String] = None
) derives ReadWriter,
      Schema

final case class Medication(
    id: UUID,
    name: String,
    status: MedicationState,
    version: Long,
    createdAt: Instant,
    schedules: List[Schedule],
    doseAmount: Option[String] = None,
    doseUnit: Option[String] = None,
    instructions: Option[String] = None
) derives ReadWriter,
      Schema

final case class MedicationSummary(
    id: UUID,
    name: String,
    status: MedicationState,
    version: Long,
    scheduleSummary: String,
    doseText: Option[String] = None
) derives ReadWriter,
      Schema

final case class MedicationList(items: List[MedicationSummary]) derives ReadWriter, Schema

/** `resumeOn` (with `pause` only, ROADMAP G9.12): the local date the medication resumes on by itself (R-REG-5). */
final case class MedicationStateChange(change: MedicationChange, resumeOn: Option[LocalDate] = None)
    derives ReadWriter,
      Schema

final case class MedicationOptions(doseUnits: List[String], limits: Limits) derives ReadWriter, Schema

// ---------- schedule-and-reminders ----------

/** A schedule as the builder edits it. `weekdays` are ISO numbers, 1 is Monday; an empty list means every day.
  * `fixed_times` needs `times`; `as_needed` takes `maxPerDay` and `minGapMinutes`. The dates are set when a schedule is
  * created and cannot be changed by a revision. `every_n_days` needs `times` and `everyDays`; `cycle` needs `times`,
  * `onDays` and `offDays`; both count from `anchorDate`, which defaults to the start date, else today (ROADMAP G9.8).
  */
final case class ScheduleDraft(
    pattern: SchedulePattern,
    times: List[String] = Nil,
    weekdays: List[Int] = Nil,
    maxPerDay: Option[Int] = None,
    minGapMinutes: Option[Int] = None,
    startDate: Option[LocalDate] = None,
    endDate: Option[LocalDate] = None,
    everyDays: Option[Int] = None,
    onDays: Option[Int] = None,
    offDays: Option[Int] = None,
    anchorDate: Option[LocalDate] = None
) derives ReadWriter,
      Schema

final case class Schedule(
    id: UUID,
    medicationId: UUID,
    status: ScheduleState,
    revision: Int,
    pattern: SchedulePattern,
    times: List[String],
    weekdays: List[Int],
    timeZone: String,
    startDate: LocalDate,
    summary: String,
    maxPerDay: Option[Int] = None,
    minGapMinutes: Option[Int] = None,
    endDate: Option[LocalDate] = None,
    everyDays: Option[Int] = None,
    onDays: Option[Int] = None,
    offDays: Option[Int] = None,
    anchorDate: Option[LocalDate] = None,
    resumeOn: Option[LocalDate] = None
) derives ReadWriter,
      Schema

final case class ScheduleList(items: List[Schedule]) derives ReadWriter, Schema

/** `endDate` (with `end` only, ROADMAP G9.12): "course ends on", the last local date of the schedule (R-SCH-4); without
  * it the schedule ends now.
  */
final case class ScheduleStateChange(change: ScheduleChange, endDate: Option[LocalDate] = None)
    derives ReadWriter,
      Schema

final case class ScheduleOptions(
    patterns: List[SchedulePattern],
    timesMax: Int,
    previewDays: Int,
    timeZone: String,
    today: LocalDate
) derives ReadWriter,
      Schema

/** A pure evaluation of an unsaved draft; nothing is written. */
final case class SchedulePreviewRequest(draft: ScheduleDraft) derives ReadWriter, Schema

/** `note` is the server's sentence for a clock change on that day. */
final case class PreviewOccurrence(time: String, at: Instant, note: Option[String] = None) derives ReadWriter, Schema

final case class PreviewDay(date: LocalDate, label: String, occurrences: List[PreviewOccurrence])
    derives ReadWriter,
      Schema

final case class SchedulePreview(summary: String, timeZone: String, days: List[PreviewDay]) derives ReadWriter, Schema

// ---------- daily-overview ----------

final case class DoseOccurrence(
    id: UUID,
    medicationId: UUID,
    medicationName: String,
    scheduledTime: String,
    scheduledAt: Instant,
    state: DoseState,
    doseText: Option[String] = None,
    resolvedTime: Option[String] = None,
    snoozedUntil: Option[String] = None
) derives ReadWriter,
      Schema

/** One local day of the account. `nowMinute` is the minute of the day on the account's clock, present for today. */
final case class DayView(
    date: LocalDate,
    dateLabel: String,
    timeZone: String,
    isToday: Boolean,
    previousDate: LocalDate,
    nextDate: LocalDate,
    occurrences: List[DoseOccurrence],
    nowMinute: Option[Int] = None
) derives ReadWriter,
      Schema

// ---------- insights-and-correlations ----------

enum InsightDomain(val wire: String):
  case Medication extends InsightDomain("medication")
  case Hydration extends InsightDomain("hydration")
  case Mood extends InsightDomain("mood")
  case Sleep extends InsightDomain("sleep")

object InsightDomain:
  given ReadWriter[InsightDomain] = ApiJson.wireEnum(values, _.wire)
  given Schema[InsightDomain] = Schema.derivedEnumeration[InsightDomain](encode = Some(_.wire))

/** Today's water against the user's own goal; `label` is the server's sentence for it. */
final case class InsightVessel(valueMl: Int, goalMl: Int, label: String) derives ReadWriter, Schema

/** The domain of a dashboard block (MINI_APP section 7.11): the calendar domains and food, under the purpose name the
  * consents use. Food has a block here before it has a history calendar.
  */
enum OverviewDomain(val wire: String):
  case Medication extends OverviewDomain("medication")
  case Hydration extends OverviewDomain("hydration")
  case Mood extends OverviewDomain("mood")
  case Sleep extends OverviewDomain("sleep")
  case Nutrition extends OverviewDomain("nutrition")

object OverviewDomain:
  given ReadWriter[OverviewDomain] = ApiJson.wireEnum(values, _.wire)
  given Schema[OverviewDomain] = Schema.derivedEnumeration[OverviewDomain](encode = Some(_.wire))

/** The statistics of one domain in the server's words. `noDataText` is set when the period has no record of the domain;
  * then there are no lines and no chart. `charts` are the series of the period, each shown only when a day of the
  * period has a record (the gate of [[dosegram.core.domain.insights.DaySeries]]). `comparison` is the user's own
  * previous period of the same length, in words with both figures (MA-INS-3). `spark` is a small decorative line of the
  * period by bucket, whose text is the block's lines. `details` are further figures of the domain (bedtime regularity,
  * a gate still to reach), and `medications` the per-medication counts of the period.
  */
final case class InsightBlock(
    id: OverviewDomain,
    title: String,
    lines: List[String],
    noDataText: Option[String] = None,
    vessel: Option[InsightVessel] = None,
    charts: List[Chart] = Nil,
    comparison: Option[String] = None,
    spark: Option[Scene] = None,
    details: List[String] = Nil,
    medications: List[InsightMedicationRow] = Nil
) derives ReadWriter,
      Schema

/** One block per domain whose consent is active. `notice` is set when there is none. `ranges` are the periods the
  * server computes, so the client offers no other (MINI_APP section 15.1).
  */
final case class InsightsOverview(
    rangeDays: Int,
    rangeLabel: String,
    timeZone: String,
    ranges: List[Int],
    blocks: List[InsightBlock],
    notice: Option[String] = None
) derives ReadWriter,
      Schema
