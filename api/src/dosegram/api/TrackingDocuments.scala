package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import sttp.tapir.Schema

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The JSON documents of the hydration, mood and calendar resources (ARCHITECTURE section 9.5). Amounts are whole
// millilitres, the stored unit (MINI_APP MA-HYD-3). A mood value is the stored step from 1 to 5, and `blobState` names
// the drawn state of `GET /mood/blob-states`. Every sentence is written here, by the server (MINI_APP section 15.1).

// ---------- hydration ----------

/** One local day of water against the user's own goal. `text` holds the figures in words; `goalMl`, `percent` and
  * `vesselLabel`, the accessible name of the vessel (MA-HYD-2), exist only on a day with a goal.
  */
final case class HydrationDayView(
    date: LocalDate,
    totalMl: Int,
    entries: Int,
    text: String,
    goalMl: Option[Int] = None,
    percent: Option[Int] = None,
    vesselLabel: Option[String] = None
) derives ReadWriter,
      Schema

final case class HydrationEntryView(
    id: UUID,
    date: LocalDate,
    time: String,
    occurredAt: Instant,
    amountMl: Int,
    text: String
) derives ReadWriter,
      Schema

final case class HydrationEntries(from: LocalDate, to: LocalDate, items: List[HydrationEntryView])
    derives ReadWriter,
      Schema

/** A drink of now, in millilitres. */
final case class HydrationEntryInput(amountMl: Int) derives ReadWriter, Schema

/** The entry and its day after it. `goalReachedNow` is true only for the entry that first takes the day to the goal. */
final case class HydrationLogged(entry: HydrationEntryView, day: HydrationDayView, goalReachedNow: Boolean)
    derives ReadWriter,
      Schema

/** The bounds of PRODUCT_SPEC R-HYD-1 to R-HYD-3 and R-HYD-12; they guard input and recommend nothing. */
final case class HydrationLimits(
    entryMinMl: Int,
    entryMaxMl: Int,
    goalMinMl: Int,
    goalMaxMl: Int,
    presetsMax: Int,
    entriesPerDayMax: Int
) derives ReadWriter,
      Schema

/** The user's own goal, absent when there is none, and the quick-add amounts. */
final case class HydrationSettings(
    presetsMl: List[Int],
    limits: HydrationLimits,
    version: Long,
    goalMl: Option[Int] = None
) derives ReadWriter,
      Schema

/** The editable set: an absent goal removes the goal from today on. */
final case class HydrationSettingsInput(presetsMl: List[Int], goalMl: Option[Int] = None) derives ReadWriter, Schema

/** Today, the quick-add amounts and the days of a range as a chart. `noDataText` is set, and the chart is absent, when
  * the range has no entry.
  */
final case class HydrationSummary(
    from: LocalDate,
    to: LocalDate,
    today: HydrationDayView,
    presetsMl: List[Int],
    chart: Option[Chart] = None,
    noDataText: Option[String] = None
) derives ReadWriter,
      Schema

// ---------- mood ----------

final case class MoodStop(step: Int, label: String) derives ReadWriter, Schema

/** The scale as data (MINI_APP section 7.9): the stops of both controls with their labels, the revision of the blob
  * states, and the sentence that every mood screen shows (MA-MOOD-7).
  */
final case class MoodScale(
    valence: List[MoodStop],
    energy: List[MoodStop],
    blobRevision: String,
    statement: String
) derives ReadWriter,
      Schema

final case class MoodCheckinView(
    id: UUID,
    date: LocalDate,
    time: String,
    occurredAt: Instant,
    valence: Int,
    label: String,
    blobState: String,
    energy: Option[Int] = None
) derives ReadWriter,
      Schema

final case class MoodCheckins(from: LocalDate, to: LocalDate, items: List[MoodCheckinView]) derives ReadWriter, Schema

/** A check-in of now: the valence step, and the energy step when the user gave one. */
final case class MoodCheckinInput(valence: Int, energy: Option[Int] = None) derives ReadWriter, Schema

/** One local day of mood. `text` and `blobState` describe the latest check-in, or say that there is none. */
final case class MoodDayView(
    date: LocalDate,
    checkIns: Int,
    text: String,
    blobState: String,
    latest: Option[MoodCheckinView] = None
) derives ReadWriter,
      Schema

final case class MoodRecordedView(checkin: MoodCheckinView, day: MoodDayView) derives ReadWriter, Schema

/** One day of the strip: the daily mean as a drawn state, `blob.none` on a day without a check-in. `name` says the same
  * in words.
  */
final case class MoodSummaryDay(date: LocalDate, label: String, name: String, blobState: String, checkIns: Int)
    derives ReadWriter,
      Schema

final case class MoodSummary(from: LocalDate, to: LocalDate, today: MoodDayView, days: List[MoodSummaryDay])
    derives ReadWriter,
      Schema

// ---------- mood blob (MINI_APP section 7.9, VISUALS_AND_ANALYTICS section 4) ----------

/** The colours of a drawn state in one colour scheme, as `#rrggbb`. */
final case class BlobColours(
    fillInner: String,
    fillOuter: String,
    outline: String,
    ground: String,
    background: String
) derives ReadWriter,
      Schema

/** The face in units of the blob radius: each eye is `cx, cy, rx, ry`, the mouth is a quadratic curve
  * `x1, y1, cx, cy, x2, y2`, each brow is a line `x1, y1, x2, y2`.
  */
final case class BlobFace(
    ink: String,
    eyes: List[List[Double]],
    mouth: List[Double],
    strokeWidth: Double,
    brows: List[List[Double]]
) derives ReadWriter,
      Schema

/** One drawn state in units of the blob radius around the origin, y downward. `outline` is a closed path: the start
  * point, then six numbers per cubic segment (two control points and the end). States of one energy level have the same
  * number of segments in the same order, so the states the valence slider moves between blend number by number
  * (MA-MOOD-5). `gradient` is the centre and radius of the fill, `ground` the ellipse under the blob as `cx, cy, rx,
  * ry`. The "no entry" state has a dotted outline and no face.
  */
final case class BlobState(
    id: String,
    outline: List[Double],
    outlineWidth: Double,
    isDotted: Boolean,
    gradient: List[Double],
    ground: List[Double],
    light: BlobColours,
    dark: BlobColours,
    face: Option[BlobFace] = None
) derives ReadWriter,
      Schema

/** The global, versioned geometry of the blob. It holds no data of any user. */
final case class MoodBlobStates(revision: String, states: List[BlobState]) derives ReadWriter, Schema

// ---------- daily-overview ----------

enum TimelineKind(val wire: String):
  case Dose extends TimelineKind("dose")
  case Water extends TimelineKind("water")
  case Mood extends TimelineKind("mood")

object TimelineKind:
  given ReadWriter[TimelineKind] = ApiJson.wireEnum(values, _.wire)
  given Schema[TimelineKind] = Schema.derivedEnumeration[TimelineKind](encode = Some(_.wire))

/** One entry of a local day at its effective time. A dose carries its state, and its scheduled time when that differs
  * from the time it was answered: the two are never merged (MINI_APP MA-DAY-1).
  */
final case class TimelineEntry(
    kind: TimelineKind,
    id: UUID,
    time: String,
    minute: Int,
    text: String,
    state: Option[DoseState] = None,
    scheduledTime: Option[String] = None,
    blobState: Option[String] = None
) derives ReadWriter,
      Schema

/** The water of a day with the quick-add amounts of the account. */
final case class DayHydration(day: HydrationDayView, presetsMl: List[Int]) derives ReadWriter, Schema

// ---------- history calendar (MINI_APP section 7.12) ----------

enum CalendarCellState(val wire: String):
  /** The day has a value; `step` places it on the ramp of the domain. */
  case Value extends CalendarCellState("value")

  /** Nothing scheduled, or no entry: not the lowest step of the ramp (MA-HIST-4). */
  case Empty extends CalendarCellState("empty")
  case Future extends CalendarCellState("future")

object CalendarCellState:
  given ReadWriter[CalendarCellState] = ApiJson.wireEnum(values, _.wire)
  given Schema[CalendarCellState] = Schema.derivedEnumeration[CalendarCellState](encode = Some(_.wire))

/** How a cell is drawn: its state, its step on the ramp from 1, the "no record" marker of a medication day and, for
  * mood, the drawn state whose colour the cell takes.
  */
final case class CalendarSwatch(
    state: CalendarCellState,
    step: Option[Int] = None,
    hasNoRecord: Boolean = false,
    blobState: Option[String] = None
) derives ReadWriter,
      Schema

/** One day of the month. `name` is the accessible name with the date and the value in words; `value` is the short form
  * printed in the cell on request (MA-HIST-1, MA-HIST-3).
  */
final case class CalendarDay(date: LocalDate, swatch: CalendarSwatch, name: String, value: Option[String] = None)
    derives ReadWriter,
      Schema

final case class CalendarLegendEntry(label: String, swatch: CalendarSwatch) derives ReadWriter, Schema

/** One month of one domain. `month` and its neighbours are `YYYY-MM`; `nextMonth` is absent for the current month.
  * `steps` is the length of the ramp, `weekStart` the ISO number of the first weekday, `domains` the domains the
  * account has turned on.
  */
final case class InsightCalendar(
    domain: InsightDomain,
    month: String,
    monthLabel: String,
    previousMonth: String,
    steps: Int,
    weekStart: Int,
    days: List[CalendarDay],
    legend: List[CalendarLegendEntry],
    summary: String,
    domains: List[InsightDomain],
    nextMonth: Option[String] = None
) derives ReadWriter,
      Schema
