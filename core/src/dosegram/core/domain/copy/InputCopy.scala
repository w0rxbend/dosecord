package dosegram.core.domain.copy

import dosegram.core.domain.input.DayList
import dosegram.core.domain.input.SnoozeDuration
import dosegram.core.domain.input.TimeOfDay

/** Replies to typed input that was not understood (BOT_UX section 15.6). Each says what was not understood, quoting the
  * user's own words, what is expected, and two or three examples; the step that asked stays open.
  */
object InputCopy:

  def timeExamples = tr(
    "Type a time like 9:00, 21:30 or 9pm, or several at once: 8, 14:30 and 22.",
    "Введіть час, як-от 9:00 чи 21:30, або кілька одразу: 8, 14:30 і 22."
  )
  def durationExamples =
    tr("Type minutes or hours, like 10, 30m or 1h.", "Введіть хвилини або години, як-от 10, 30m чи 1h.")
  def dayExamples =
    tr("Type days like mon wed fri, weekdays or every day.", "Введіть дні, як-от пн ср пт, будні або щодня.")

  def time(rejection: TimeOfDay.Rejection): String =
    rejection match
      case TimeOfDay.Rejection.Empty =>
        tr(s"I did not find a time there. $timeExamples", s"Тут не вдалося знайти час. $timeExamples")
      case TimeOfDay.Rejection.NotATime(part) =>
        tr(
          s"I could not read \"$part\" as a time. $timeExamples",
          s"Не вдалося прочитати «$part» як час. $timeExamples"
        )
      case TimeOfDay.Rejection.TooMany(max) =>
        tr(
          s"That is more than $max times. In chat a medication takes up to $max times a day; type $max or fewer.",
          s"Це більше, ніж $max. У чаті для одних ліків можна задати до $max разів на день; введіть $max або менше."
        )

  def duration(rejection: SnoozeDuration.Rejection): String =
    rejection match
      case SnoozeDuration.Rejection.Empty =>
        tr(s"I did not find a length there. $durationExamples", s"Тут не вдалося знайти тривалість. $durationExamples")
      case SnoozeDuration.Rejection.NotADuration(part) =>
        tr(
          s"I could not read \"$part\" as a length of time. $durationExamples",
          s"Не вдалося прочитати «$part» як тривалість. $durationExamples"
        )
      case SnoozeDuration.Rejection.OutOfRange(min, max) =>
        tr(
          s"A snooze lasts from $min minute to ${max / 60} hours. $durationExamples",
          s"Відкласти можна на час від $min хв до ${max / 60} год. $durationExamples"
        )

  def days(rejection: DayList.Rejection): String =
    rejection match
      case DayList.Rejection.Empty =>
        tr(s"I did not find a day there. $dayExamples", s"Тут не вдалося знайти день. $dayExamples")
      case DayList.Rejection.NotADay(part) =>
        tr(s"I could not read \"$part\" as a day. $dayExamples", s"Не вдалося прочитати «$part» як день. $dayExamples")

  def entries: List[CopyEntry] = List(
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
