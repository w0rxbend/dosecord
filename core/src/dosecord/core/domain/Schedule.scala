package dosecord.core.domain

import dosecord.contracts.HhMm
import dosecord.contracts.Json.given
import dosecord.contracts.Weekday
import upickle.default.ReadWriter

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One group of weekdays sharing the same wall times inside a `FixedTimes` rule.
  */
final case class SlotGroup(days: List[Weekday], times: List[HhMm]) derives ReadWriter

/** What a `Chain` rule anchors dose k+1 on (DESIGN.md section 7.1). Declared for exhaustivity; inert until M7.
  */
enum ChainAnchor derives ReadWriter:
  case FirstTaken, EachTaken

/** One phase of a taper: the inner `rule` applies to local dates in `[from, until)`. The inner rule must not be `Taper`
  * or `Chain` (DESIGN.md section 7.1).
  */
final case class TaperPhase(from: LocalDate, until: LocalDate, rule: Rule) derives ReadWriter:
  require(from.isBefore(until), s"taper phase start $from must be before its end $until")
  require(
    !rule.isInstanceOf[Rule.Taper] && !rule.isInstanceOf[Rule.Chain],
    "a taper phase's inner rule must not be Taper or Chain"
  )

/** Schedule rule kinds, one case per kind of DESIGN.md section 7.1, stored as jsonb on `schedule_revisions.rule` and
  * validated on every write. The enum is deliberately exhaustive from the start: `Rule.validate` and
  * `Rule.isSupportedInMvp` match on every case without a wildcard arm, so adding a case fails compilation under
  * `-Werror` (the M1.1 negative test). Only `FixedTimes` and `AsNeeded` are accepted until M7; the remaining kinds are
  * declared, explicitly inert, and never silently dropped.
  */
enum Rule derives ReadWriter:
  case FixedTimes(slotGroups: List[SlotGroup])
  case EveryNDays(everyDays: Int, times: List[HhMm])
  case EveryNWeeks(everyWeeks: Int, days: List[Weekday], times: List[HhMm])
  case Cycle(onDays: Int, offDays: Int, times: List[HhMm], anchorDate: LocalDate)
  case IntervalFixedStart(anchorTime: HhMm, intervalMinutes: Int, maxPerDay: Int, activeDays: List[Weekday])
  case Chain(
      anchor: ChainAnchor,
      intervalMinutes: Int,
      dosesPerDay: Int,
      dayStart: HhMm,
      dayCutoff: HhMm,
      firstDosePromptTime: HhMm
  )
  case Taper(phases: List[TaperPhase])
  case AsNeeded(maxPerDay: Int, minGapMinutes: Int)
  case Paused

object Rule:

  /** Whether the MVP accepts the kind on schedule writes (ROADMAP M1.1: only `FixedTimes` and `AsNeeded` until M7).
    * Exhaustive on purpose: a new [[Rule]] case without an arm here fails compilation.
    */
  def isSupportedInMvp(rule: Rule): Boolean = rule match
    case _: Rule.FixedTimes | _: Rule.AsNeeded => true
    case _: Rule.EveryNDays | _: Rule.EveryNWeeks | _: Rule.Cycle | _: Rule.IntervalFixedStart | _: Rule.Chain |
        _: Rule.Taper | Rule.Paused =>
      false

  /** Field-level validation errors for the kinds the MVP accepts. The M7 kinds below are declared for exhaustivity but
    * inert: they validate to `Nil` here and are rejected by [[isSupportedInMvp]] instead of being silently dropped.
    * Exhaustive on purpose: a new [[Rule]] case without an arm here fails compilation.
    */
  def validate(rule: Rule): List[String] = rule match
    case Rule.FixedTimes(slotGroups)             => validateFixedTimes(slotGroups)
    case Rule.AsNeeded(maxPerDay, minGapMinutes) =>
      List(
        Option.when(maxPerDay < 1)(s"maxPerDay must be >= 1, got $maxPerDay"),
        Option.when(minGapMinutes < 0)(s"minGapMinutes must be >= 0, got $minGapMinutes")
      ).flatten
    // M7 gate: declared, inert until M7 (ROADMAP M1.1).
    case _: Rule.EveryNDays         => Nil
    case _: Rule.EveryNWeeks        => Nil
    case _: Rule.Cycle              => Nil
    case _: Rule.IntervalFixedStart => Nil
    case _: Rule.Chain              => Nil
    case _: Rule.Taper              => Nil
    case Rule.Paused                => Nil

  /** Validating constructor for [[Rule.Taper]]: phases must be non-empty, sorted by `from`, contiguous and
    * non-overlapping (`phase(i+1).from == phase(i).until`; DESIGN.md section 7.1). The raw case remains constructible
    * for JSON decoding; write paths must go through here.
    */
  def taper(phases: List[TaperPhase]): Rule.Taper =
    require(phases.nonEmpty, "taper needs at least one phase")
    require(
      phases == phases.sortBy(_.from.toEpochDay),
      "taper phases must be sorted by start date"
    )
    phases.lazyZip(phases.tail).foreach { (current, next) =>
      require(
        current.until == next.from,
        s"taper phases must be contiguous and non-overlapping: ${current.until} != ${next.from}"
      )
    }
    Rule.Taper(phases)

  private def validateFixedTimes(slotGroups: List[SlotGroup]): List[String] =
    if slotGroups.isEmpty then List("FixedTimes needs at least one slot group")
    else
      slotGroups.zipWithIndex.flatMap { (group, index) =>
        List(
          Option.when(group.days.isEmpty)(s"slot group $index has no days"),
          Option.when(group.days.distinct.size != group.days.size)(s"slot group $index has duplicate days"),
          Option.when(group.times.isEmpty)(s"slot group $index has no times"),
          Option.when(group.times.distinct.size != group.times.size)(s"slot group $index has duplicate times")
        ).flatten
      }
end Rule

/** How a reminder is delivered inside quiet hours (ADR-012): `Deliver` sends normally, `Silent` sends with the vendor's
  * silent flag and counts the reminder, `Defer` shifts the due window to quiet end and records `reminder_deferred`.
  */
enum QuietHoursMode derives ReadWriter:
  case Deliver, Defer, Silent

/** Reminder policy with the ADR-012 / DESIGN.md section 7.1 defaults. The values salvaged from the retired prototype
  * (offset 0, snooze [10,30,60], miss 120, max 3) match `ReminderPolicyData` in contracts. Stored as jsonb on
  * `schedule_revisions.reminder_policy`.
  */
final case class ReminderPolicy(
    initialOffsetMinutes: Int = 0,
    repeatEveryMinutes: Int = 10,
    maxReminders: Int = 3,
    missAfterMinutes: Int = 120,
    onTimeGraceMinutes: Int = 60,
    snoozeOptionsMinutes: List[Int] = List(10, 30, 60),
    maxSnoozes: Int = 3,
    missAfterSnoozeMinutes: Int = 30,
    maxLateMinutes: Int = 360,
    lateLogWindowMinutes: Int = 1440,
    undoWindowMinutes: Int = 15,
    quietHoursMode: QuietHoursMode = QuietHoursMode.Deliver,
    discreet: Boolean = false
) derives ReadWriter:
  require(initialOffsetMinutes >= -1440 && initialOffsetMinutes <= 1440, "initialOffsetMinutes must be in -1440..1440")
  require(repeatEveryMinutes >= 1 && repeatEveryMinutes <= 1440, "repeatEveryMinutes must be in 1..1440")
  require(maxReminders >= 1 && maxReminders <= 10, "maxReminders must be in 1..10")
  require(missAfterMinutes >= 1 && missAfterMinutes <= 10080, "missAfterMinutes must be in 1..10080")
  require(onTimeGraceMinutes >= 1 && onTimeGraceMinutes <= 10080, "onTimeGraceMinutes must be in 1..10080")
  require(snoozeOptionsMinutes.nonEmpty, "at least one snooze option")
  require(snoozeOptionsMinutes.forall(m => m >= 1 && m <= 1440), "snooze options must be positive and <= 1440")
  require(snoozeOptionsMinutes.distinct.size == snoozeOptionsMinutes.size, "duplicate snooze options")
  require(maxSnoozes >= 0 && maxSnoozes <= 10, "maxSnoozes must be in 0..10")
  require(missAfterSnoozeMinutes >= 1 && missAfterSnoozeMinutes <= 1440, "missAfterSnoozeMinutes must be in 1..1440")
  require(maxLateMinutes >= 1 && maxLateMinutes <= 10080, "maxLateMinutes must be in 1..10080")
  require(lateLogWindowMinutes >= 1 && lateLogWindowMinutes <= 43200, "lateLogWindowMinutes must be in 1..43200")
  require(undoWindowMinutes >= 1 && undoWindowMinutes <= 60, "undoWindowMinutes must be in 1..60")

object ReminderPolicy:
  /** The ADR-012 defaults. */
  val Default: ReminderPolicy = ReminderPolicy()

/** Quiet hours as a half-open `[start, end)` interval of local wall-clock time that may cross midnight: when
  * `start > end` the interval wraps past 24:00 (22:00-06:00 contains 23:30 and 05:59, not 12:00). Equal bounds mean "no
  * quiet hours": the interval contains nothing. Quiet hours live on the user and may be overridden per schedule
  * (DESIGN.md section 7.1); stored as jsonb on `users.quiet_hours`.
  */
final case class QuietHours(start: HhMm, end: HhMm) derives ReadWriter:
  def isActive: Boolean = start != end

  def contains(t: LocalTime): Boolean =
    val minute = t.getHour * 60 + t.getMinute
    val from = start.hour * 60 + start.minute
    val to = end.hour * 60 + end.minute
    if from == to then false
    else if from < to then minute >= from && minute < to
    else minute >= from || minute < to

/** Quiet hours as `Decide.decide` consumes them (ROADMAP M1.3): the optional hours plus the zone that gives the wall
  * times meaning. `quietEndAfter` resolves the end of the quiet interval containing `now` through M0.7's
  * `Dst.resolveLocal`, checking the wall time on `now`'s local date and the next (a wrapping interval like 22:00-06:00
  * ends on the following date). Caveat: on a fold day the end wall time resolves to the earlier instant, so the quiet
  * interval can run long by the fold length; containment and the end stay consistent because both derive from
  * `quietEndAfter`.
  */
final case class QuietHoursContext(hours: Option[QuietHours], zone: ZoneId):
  def quietEndAfter(now: Instant): Option[Instant] =
    hours.filter(_.isActive).flatMap { quiet =>
      val zoned = now.atZone(zone)
      if !quiet.contains(zoned.toLocalTime) then scala.None
      else
        List(zoned.toLocalDate, zoned.toLocalDate.plusDays(1))
          .map(date => Dst.resolveLocal(date, LocalTime.of(quiet.end.hour, quiet.end.minute), zone)._1)
          .filter(_.isAfter(now))
          .minOption
    }

  def contains(now: Instant): Boolean = quietEndAfter(now).isDefined

object QuietHoursContext:
  def none(zone: ZoneId): QuietHoursContext = QuietHoursContext(scala.None, zone)
