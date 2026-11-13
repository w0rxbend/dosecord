package dosegram.core.application.reminders

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.QuietHours
import dosegram.core.domain.input.DayList
import dosegram.core.domain.input.TimeOfDay
import dosegram.core.domain.reminders.Anchor
import dosegram.core.domain.reminders.DaySet
import dosegram.core.domain.reminders.NudgePlan
import dosegram.core.domain.reminders.TimeRef

import java.time.DayOfWeek
import java.time.LocalTime

/** The argument of `/reminders` (ADR-023): empty opens the hub; the rest edits the day profile, sets a custom water
  * interval or pauses every prompt.
  */
enum ReminderCommand:
  case Hub
  case SetAnchor(anchor: Anchor, time: LocalTime)
  case SetWork(days: Set[DayOfWeek], start: LocalTime, end: LocalTime)
  case SetQuiet(quiet: QuietHours)
  case WaterCustom(plan: NudgePlan.Interval)
  case PauseAll(hours: Int)
  case Resume

object ReminderCommand:

  val MaxPauseHours = 24

  /** `None` when the argument is not one of the forms; the reply then shows the usage. */
  def parse(argument: String): Option[ReminderCommand] =
    argument.trim.toLowerCase(java.util.Locale.ROOT).split("\\s+").toList.filter(_.nonEmpty) match
      case Nil                                            => Some(Hub)
      case List("resume")                                 => Some(Resume)
      case List("pause", hours)                           => hours.toIntOption.filter(validPause).map(PauseAll(_))
      case List("quiet", "off")                           => Some(SetQuiet(QuietHours(midnight, midnight)))
      case List("quiet", range)                           => window(range).map((s, e) => SetQuiet(QuietHours(s, e)))
      case "work" :: rest if rest.nonEmpty                => work(rest)
      case "water" :: "every" :: minutes :: range :: days => waterCustom(minutes, range, days)
      case List(name, time) if Anchor.byId(name).exists(editable) =>
        clock(time).map(t => SetAnchor(Anchor.byId(name).get, toLocal(t)))
      case _ => None

  private val midnight = HhMm.unsafe("00:00")

  /** Work hours are edited as one line; the other anchors one by one. */
  private def editable(anchor: Anchor): Boolean = anchor != Anchor.WorkStart && anchor != Anchor.WorkEnd

  private def validPause(hours: Int): Boolean = hours >= 1 && hours <= MaxPauseHours

  private def work(rest: List[String]): Option[ReminderCommand] =
    window(rest.last).flatMap: (start, end) =>
      val days = if rest.size == 1 then Some(DayOfWeek.values.take(5).toSet) else weekdays(rest.init.mkString(" "))
      days.map(SetWork(_, toLocal(start), toLocal(end)))

  private def waterCustom(minutes: String, range: String, days: List[String]): Option[ReminderCommand] =
    for
      every <- minutes.toIntOption
      (start, end) <- window(range)
      daySet <- if days.isEmpty then Some(DaySet.Every) else weekdays(days.mkString(" ")).map(DaySet.Only(_))
      plan: NudgePlan.Interval = NudgePlan.Interval(
        TimeRef.At(toLocal(start)),
        TimeRef.At(toLocal(end)),
        every,
        daySet,
        false
      )
      if NudgePlan.validate(plan).isEmpty
    yield WaterCustom(plan)

  private def window(range: String): Option[(HhMm, HhMm)] =
    range.split("-").toList match
      case List(start, end) => for s <- clock(start); e <- clock(end) yield (s, e)
      case _                => None

  private def clock(text: String): Option[HhMm] = TimeOfDay.parseOne(text).toOption

  private def weekdays(text: String): Option[Set[DayOfWeek]] =
    DayList.parse(text).toOption.map(_.map(dayOf).toSet).filter(_.nonEmpty)

  private def dayOf(day: Weekday): DayOfWeek = DayOfWeek.of(day.ordinal + 1)

  def toLocal(t: HhMm): LocalTime = LocalTime.of(t.hour, t.minute)
