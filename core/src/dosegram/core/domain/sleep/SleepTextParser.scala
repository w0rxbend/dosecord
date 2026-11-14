package dosegram.core.domain.sleep

import java.time.Duration
import java.time.LocalTime
import java.util.Locale
import scala.util.matching.Regex

/** What a line of text says about sleep (BOT_UX section 11.5). */
enum SleepText:
  /** Bedtime, then wake time: recorded as the most recent finished night with those clock times. */
  case Night(bedtime: LocalTime, wakeTime: LocalTime)

  /** A length without an edge: nothing is recorded, the bot asks until when. */
  case SleptFor(length: Duration)

  /** A nap of this length that ended when the message was sent. */
  case NapFor(length: Duration)
  case NapBetween(start: LocalTime, end: LocalTime)

  /** Not a statement about sleep. */
  case NotSleep

/** The deterministic sleep grammar of BOT_UX section 11.5. No model is involved, and a line without the word "slept",
  * "sleep" or "nap" is never read as sleep.
  *
  * {{{
  * sleep     = ( "slept" / "sleep" ) time separator time  /  ( "slept" / "sleep" ) duration
  * nap       = "nap" duration  /  "nap" time separator time
  * separator = space / "-" / "to" / "until"
  * duration  = number "h" [ number [ "m" / "min" ] ]  /  number ( "m" / "min" )
  * }}}
  */
object SleepTextParser:

  private val Time = """(\d{1,2}):(\d{2})"""
  private val Separator = """\s*(?:-|–|to|until|до|\s)\s*"""
  private val TwoTimes: Regex = s"$Time$Separator$Time".r
  private val Hours: Regex = """(\d{1,2})\s*(?:h(?:ours?)?|год)(?:\s*(\d{1,2})\s*(?:m|min|хв)?)?""".r
  private val Minutes: Regex = """(\d{1,3})\s*(?:m|min|хв)""".r
  // ROADMAP G2.22: "сон", "спав", "спала" read as "slept"; "дрімав", "дрімала", "денний сон" as "nap".
  private val Line: Regex = """(slept|sleep|nap|сон|спав|спала|дрімав|дрімала|денний сон)\s+(.+)""".r
  private val NapWords = Set("nap", "дрімав", "дрімала", "денний сон")

  def free(text: String): SleepText =
    text.trim.toLowerCase(Locale.ROOT) match
      case Line(word, rest) if NapWords(word) =>
        times(rest)
          .map(SleepText.NapBetween(_, _))
          .orElse(length(rest).map(SleepText.NapFor(_)))
          .getOrElse(SleepText.NotSleep)
      case Line(_, rest) =>
        times(rest)
          .map(SleepText.Night(_, _))
          .orElse(length(rest).map(SleepText.SleptFor(_)))
          .getOrElse(SleepText.NotSleep)
      case _ => SleepText.NotSleep

  /** Two clock times and nothing else: the argument of `/sleep 23:30 7:10`. */
  def times(text: String): Option[(LocalTime, LocalTime)] =
    text.trim.toLowerCase(Locale.ROOT) match
      case TwoTimes(startHour, startMinute, endHour, endMinute) =>
        for
          start <- clockTime(startHour, startMinute)
          end <- clockTime(endHour, endMinute)
        yield (start, end)
      case _ => None

  private def length(text: String): Option[Duration] =
    val minutes = text.trim match
      case Hours(hours, more) => Some(hours.toLong * 60L + Option(more).fold(0L)(_.toLong))
      case Minutes(only)      => Some(only.toLong)
      case _                  => None
    minutes.filter(_ > 0L).map(Duration.ofMinutes)

  private def clockTime(hour: String, minute: String): Option[LocalTime] =
    Option.when(hour.toInt <= 23 && minute.toInt <= 59)(LocalTime.of(hour.toInt, minute.toInt))
