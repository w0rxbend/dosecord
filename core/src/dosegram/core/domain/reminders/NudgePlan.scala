package dosegram.core.domain.reminders

import dosegram.core.domain.Dst
import upickle.default.ReadWriter
import upickle.default.readwriter

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The kinds of prompt (ARCHITECTURE section 8.4): one plan per kind and account. `domain` is the consent purpose the
  * prompt needs, or none for a report.
  */
enum PromptKind(val id: String, val consent: Option[String]):
  case Water extends PromptKind("water", Some("hydration"))
  case Mood extends PromptKind("mood", Some("mood"))
  case SleepBedtime extends PromptKind("sleep_bedtime", Some("sleep"))
  case SleepMorning extends PromptKind("sleep_morning", Some("sleep"))
  case Meal extends PromptKind("meal", Some("nutrition"))
  case WeeklyReport extends PromptKind("report_weekly", None)
  case MonthlyReport extends PromptKind("report_monthly", None)

object PromptKind:
  def byId(id: String): Option[PromptKind] = values.find(_.id == id)

/** A time of day given as a clock time or relative to an anchor of the day profile. */
enum TimeRef:
  case At(time: LocalTime)
  case From(anchor: Anchor, offsetMinutes: Int)

  def resolve(profile: DayProfile): LocalTime = this match
    case At(time)                    => time
    case From(anchor, offsetMinutes) => profile.timeOf(anchor).plusMinutes(offsetMinutes.toLong)

/** The days on which a plan runs. */
enum DaySet:
  case Every
  case WorkDays
  case Only(days: Set[DayOfWeek])

  def includes(day: DayOfWeek, profile: DayProfile): Boolean = this match
    case Every      => true
    case WorkDays   => profile.work.isWorkDay(day)
    case Only(days) => days(day)

/** A nudge plan (PRODUCT_SPEC R-SCH-21): when the prompts of one kind fall due. */
enum NudgePlan:

  /** Every `everyMinutes` inside a daily window. The first slot is one interval after the window opens and no slot
    * falls at or after its close; a window whose close is not after its opening crosses midnight.
    */
  case Interval(from: TimeRef, until: TimeRef, everyMinutes: Int, days: DaySet, onlyWhenBehind: Boolean)

  /** At fixed times of the day. */
  case AtTimes(times: List[TimeRef], days: DaySet)

  /** `count` moments inside a window, drawn per day from the plan's seed: one in each equal part of the window. */
  case RandomMoments(from: TimeRef, until: TimeRef, count: Int, days: DaySet)

  case Weekly(day: DayOfWeek, at: TimeRef)
  case Monthly(dayOfMonth: Int, at: TimeRef)

object NudgePlan:

  /** The bounds of a custom interval: no shorter than the minimum spacing of two prompts allows, no longer than a day.
    */
  val MinIntervalMinutes = 30
  val MaxIntervalMinutes = 720

  def validate(plan: NudgePlan): List[String] = plan match
    case Interval(_, _, every, _, _) =>
      List("interval_bounds").filter(_ => every < MinIntervalMinutes || every > MaxIntervalMinutes)
    case AtTimes(times, _)             => List("no_times").filter(_ => times.isEmpty)
    case RandomMoments(_, _, count, _) => List("count_bounds").filter(_ => count < 1 || count > 6)
    case Weekly(_, _)                  => Nil
    case Monthly(dayOfMonth, _)        => List("day_of_month").filter(_ => dayOfMonth < 1 || dayOfMonth > 28)

  // ---------- jsonb (`prompt_schedules.plan`) ----------

  private val Clock = DateTimeFormatter.ofPattern("HH:mm")

  given ReadWriter[LocalTime] = readwriter[String].bimap(Clock.format(_), LocalTime.parse(_, Clock))
  given ReadWriter[DayOfWeek] = readwriter[Int].bimap(_.getValue, DayOfWeek.of)
  given ReadWriter[Anchor] =
    readwriter[String].bimap(_.id, id => Anchor.byId(id).getOrElse(throw IllegalArgumentException(s"anchor '$id'")))
  given ReadWriter[TimeRef] = ReadWriter.derived
  given ReadWriter[DaySet] = ReadWriter.derived
  given ReadWriter[NudgePlan] = ReadWriter.derived

  def toJson(plan: NudgePlan): String = upickle.default.write(plan)
  def fromJson(json: String): NudgePlan = upickle.default.read[NudgePlan](json)

/** One due time of a plan. `planDate` is the local date of the day the slot belongs to (a window that crosses midnight
  * belongs to the day it opened on); `slotKey` names the slot inside that day.
  */
final case class NudgeSlot(planDate: LocalDate, slotKey: String, local: LocalDateTime, at: Instant)

/** Slot generation: pure, in wall-clock time, resolved through the daylight-saving rules of ADR-011 (a slot in a gap
  * moves forward by the gap, a slot in a fold takes the earlier instant). Two slots that resolve to one instant are one
  * slot.
  */
object NudgeSlots:

  private val KeyFormat = DateTimeFormatter.ofPattern("HHmm")

  /** The slots of `date` in time order. `seed` makes the random moments of one plan stable for a day. */
  def on(plan: NudgePlan, profile: DayProfile, date: LocalDate, zone: ZoneId, seed: Long): List[NudgeSlot] =
    val locals = plan match
      case NudgePlan.Interval(from, until, every, days, _) =>
        if days.includes(date.getDayOfWeek, profile) then interval(from, until, every, profile, date) else Nil
      case NudgePlan.AtTimes(times, days) =>
        if days.includes(date.getDayOfWeek, profile) then
          times.map(t => date.atTime(t.resolve(profile))).distinct.sorted
        else Nil
      case NudgePlan.RandomMoments(from, until, count, days) =>
        if days.includes(date.getDayOfWeek, profile) then random(from, until, count, profile, date, seed) else Nil
      case NudgePlan.Weekly(day, at) =>
        if date.getDayOfWeek == day then List(date.atTime(at.resolve(profile))) else Nil
      case NudgePlan.Monthly(dayOfMonth, at) =>
        if date.getDayOfMonth == dayOfMonth then List(date.atTime(at.resolve(profile))) else Nil
    resolved(date, locals, zone)

  /** The slots of the dates around `now` whose instant is at or before `now`, oldest first: the dates of yesterday and
    * today in the zone, so that a window crossing midnight is seen from both sides.
    */
  def dueBy(plan: NudgePlan, profile: DayProfile, zone: ZoneId, seed: Long, now: Instant): List[NudgeSlot] =
    val today = now.atZone(zone).toLocalDate
    List(today.minusDays(1), today)
      .flatMap(on(plan, profile, _, zone, seed))
      .filter(!_.at.isAfter(now))

  /** The next slots after `now`, at most `limit`, looking up to `days` days ahead: the preview of a preset. */
  def upcoming(
      plan: NudgePlan,
      profile: DayProfile,
      zone: ZoneId,
      seed: Long,
      now: Instant,
      limit: Int,
      days: Int = 8
  ): List[NudgeSlot] =
    val today = now.atZone(zone).toLocalDate
    (-1 to days).iterator
      .flatMap(offset => on(plan, profile, today.plusDays(offset.toLong), zone, seed))
      .filter(_.at.isAfter(now))
      .take(limit)
      .toList

  private def interval(
      from: TimeRef,
      until: TimeRef,
      every: Int,
      profile: DayProfile,
      date: LocalDate
  ): List[LocalDateTime] =
    val open = date.atTime(from.resolve(profile))
    val closeTime = until.resolve(profile)
    val close =
      if closeTime.isAfter(open.toLocalTime) then date.atTime(closeTime) else date.plusDays(1).atTime(closeTime)
    Iterator
      .iterate(open.plusMinutes(every.toLong))(_.plusMinutes(every.toLong))
      .takeWhile(_.isBefore(close))
      .toList

  private def random(
      from: TimeRef,
      until: TimeRef,
      count: Int,
      profile: DayProfile,
      date: LocalDate,
      seed: Long
  ): List[LocalDateTime] =
    val open = date.atTime(from.resolve(profile))
    val closeTime = until.resolve(profile)
    val close =
      if closeTime.isAfter(open.toLocalTime) then date.atTime(closeTime) else date.plusDays(1).atTime(closeTime)
    val span = java.time.Duration.between(open, close).toMinutes
    val part = span / count
    val rng = java.util.SplittableRandom(seed ^ date.toEpochDay * 0x9e3779b97f4a7c15L)
    (0 until count).toList.map: index =>
      // Five-minute steps inside each part, so a moment reads like a time a person would pick.
      val steps = math.max(part / 5, 1L)
      open.plusMinutes(index * part + rng.nextLong(steps) * 5)

  private def resolved(date: LocalDate, locals: List[LocalDateTime], zone: ZoneId): List[NudgeSlot] =
    val slots = locals.map: local =>
      val dayMark = if local.toLocalDate == date then "" else "n"
      NudgeSlot(date, s"$dayMark${KeyFormat.format(local)}", local, Dst.resolveLocal(local, zone)._1)
    slots
      .foldLeft(List.empty[NudgeSlot]): (kept, slot) =>
        if kept.exists(_.at == slot.at) then kept else slot :: kept
      .reverse
      .sortBy(_.at)
