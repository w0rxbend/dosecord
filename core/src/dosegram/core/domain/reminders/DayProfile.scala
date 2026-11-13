package dosegram.core.domain.reminders

import dosegram.contracts.HhMm
import dosegram.core.domain.QuietHours

import java.time.DayOfWeek
import java.time.LocalTime

/** A named time of the user's day. Reminder presets refer to anchors instead of clock times, so moving an anchor moves
  * every reminder that uses it.
  */
enum Anchor(val id: String):
  case Wake extends Anchor("wake")
  case Breakfast extends Anchor("breakfast")
  case Lunch extends Anchor("lunch")
  case Dinner extends Anchor("dinner")
  case Bedtime extends Anchor("bedtime")
  case WorkStart extends Anchor("work_start")
  case WorkEnd extends Anchor("work_end")

object Anchor:
  def byId(id: String): Option[Anchor] = values.find(_.id == id)

/** The days and hours the user works. `start` before `end`: a working day does not cross midnight. */
final case class WorkHours(days: Set[DayOfWeek], start: LocalTime, end: LocalTime):
  require(start.isBefore(end), s"work hours must start before they end: $start-$end")

  def isWorkDay(day: DayOfWeek): Boolean = days(day)

object WorkHours:
  val Weekdays: Set[DayOfWeek] =
    Set(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

  /** Monday to Friday, 09:00 to 18:00. */
  val Default: WorkHours = WorkHours(Weekdays, LocalTime.of(9, 0), LocalTime.of(18, 0))

  /** The days as a bit set, Monday in bit 0, as `day_profiles.work_days` stores them. */
  def bitsOf(days: Set[DayOfWeek]): Int = days.foldLeft(0)((bits, day) => bits | (1 << (day.getValue - 1)))

  def daysOf(bits: Int): Set[DayOfWeek] = DayOfWeek.values.filter(day => (bits & (1 << (day.getValue - 1))) != 0).toSet

/** The user's day: the anchors, the work hours and the quiet hours. Quiet hours are the account setting of
  * `users.quiet_hours`; an account that never set them gets [[DayProfile.DefaultQuiet]] for its prompts.
  */
final case class DayProfile(
    wake: LocalTime,
    breakfast: LocalTime,
    lunch: LocalTime,
    dinner: LocalTime,
    bedtime: LocalTime,
    work: WorkHours,
    quiet: QuietHours
):

  def timeOf(anchor: Anchor): LocalTime = anchor match
    case Anchor.Wake      => wake
    case Anchor.Breakfast => breakfast
    case Anchor.Lunch     => lunch
    case Anchor.Dinner    => dinner
    case Anchor.Bedtime   => bedtime
    case Anchor.WorkStart => work.start
    case Anchor.WorkEnd   => work.end

  def withAnchor(anchor: Anchor, time: LocalTime): Either[String, DayProfile] = anchor match
    case Anchor.Wake      => Right(copy(wake = time))
    case Anchor.Breakfast => Right(copy(breakfast = time))
    case Anchor.Lunch     => Right(copy(lunch = time))
    case Anchor.Dinner    => Right(copy(dinner = time))
    case Anchor.Bedtime   => Right(copy(bedtime = time))
    case Anchor.WorkStart => workHours(work.days, time, work.end)
    case Anchor.WorkEnd   => workHours(work.days, work.start, time)

  def workHours(days: Set[DayOfWeek], start: LocalTime, end: LocalTime): Either[String, DayProfile] =
    if days.isEmpty then Left("no_work_days")
    else if !start.isBefore(end) then Left("work_order")
    else Right(copy(work = WorkHours(days, start, end)))

object DayProfile:

  /** 22:00 to 07:00 in the account zone (BOT_UX section 14.3, PROPOSED). */
  val DefaultQuiet: QuietHours = QuietHours(HhMm.unsafe("22:00"), HhMm.unsafe("07:00"))

  val Default: DayProfile = DayProfile(
    wake = LocalTime.of(7, 0),
    breakfast = LocalTime.of(8, 0),
    lunch = LocalTime.of(13, 0),
    dinner = LocalTime.of(19, 0),
    bedtime = LocalTime.of(23, 0),
    work = WorkHours.Default,
    quiet = DefaultQuiet
  )

/** The stored part of a day profile: everything but the quiet hours, which are an account setting. */
final case class DayAnchors(
    wake: LocalTime,
    breakfast: LocalTime,
    lunch: LocalTime,
    dinner: LocalTime,
    bedtime: LocalTime,
    work: WorkHours
):
  def withQuiet(quiet: QuietHours): DayProfile = DayProfile(wake, breakfast, lunch, dinner, bedtime, work, quiet)

object DayAnchors:
  def of(profile: DayProfile): DayAnchors =
    DayAnchors(profile.wake, profile.breakfast, profile.lunch, profile.dinner, profile.bedtime, profile.work)
