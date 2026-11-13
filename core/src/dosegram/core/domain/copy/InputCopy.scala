package dosegram.core.domain.copy

import dosegram.core.domain.input.DayList
import dosegram.core.domain.input.SnoozeDuration
import dosegram.core.domain.input.TimeOfDay

/** Replies to typed input that was not understood (BOT_UX section 15.6). Each says what was not understood, quoting the
  * user's own words, what is expected, and two or three examples; the step that asked stays open.
  */
object InputCopy:

  val timeExamples = "Type a time like 9:00, 21:30 or 9pm, or several at once: 8, 14:30 and 22."
  val durationExamples = "Type minutes or hours, like 10, 30m or 1h."
  val dayExamples = "Type days like mon wed fri, weekdays or every day."

  def time(rejection: TimeOfDay.Rejection): String =
    rejection match
      case TimeOfDay.Rejection.Empty          => s"I did not find a time there. $timeExamples"
      case TimeOfDay.Rejection.NotATime(part) => s"I could not read \"$part\" as a time. $timeExamples"
      case TimeOfDay.Rejection.TooMany(max)   =>
        s"That is more than $max times. In chat a medication takes up to $max times a day; type $max or fewer."

  def duration(rejection: SnoozeDuration.Rejection): String =
    rejection match
      case SnoozeDuration.Rejection.Empty              => s"I did not find a length there. $durationExamples"
      case SnoozeDuration.Rejection.NotADuration(part) =>
        s"I could not read \"$part\" as a length of time. $durationExamples"
      case SnoozeDuration.Rejection.OutOfRange(min, max) =>
        s"A snooze lasts from $min minute to ${max / 60} hours. $durationExamples"

  def days(rejection: DayList.Rejection): String =
    rejection match
      case DayList.Rejection.Empty         => s"I did not find a day there. $dayExamples"
      case DayList.Rejection.NotADay(part) => s"I could not read \"$part\" as a day. $dayExamples"

  val entries: List[CopyEntry] = List(
    CopyEntry("input.time.empty", time(TimeOfDay.Rejection.Empty)),
    CopyEntry("input.time.not_a_time", time(TimeOfDay.Rejection.NotATime("9:75"))),
    CopyEntry("input.time.too_many", time(TimeOfDay.Rejection.TooMany(TimeOfDay.MaxTimes))),
    CopyEntry("input.duration.empty", duration(SnoozeDuration.Rejection.Empty)),
    CopyEntry("input.duration.not_a_duration", duration(SnoozeDuration.Rejection.NotADuration("soon"))),
    CopyEntry(
      "input.duration.out_of_range",
      duration(SnoozeDuration.Rejection.OutOfRange(SnoozeDuration.MinMinutes, SnoozeDuration.MaxMinutes))
    ),
    CopyEntry("input.days.empty", days(DayList.Rejection.Empty)),
    CopyEntry("input.days.not_a_day", days(DayList.Rejection.NotADay("funday")))
  )
end InputCopy
