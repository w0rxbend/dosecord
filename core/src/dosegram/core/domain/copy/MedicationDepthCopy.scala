package dosegram.core.domain.copy

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The copy of the medication-depth track (ROADMAP G9.8, G9.10, G9.12): the words of the further schedule kinds. Every
  * line states what the user configured; none advises.
  */
object MedicationDepthCopy:

  private val DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

  def date(day: LocalDate): String = day.format(DayFormat)

  /** `every other day from Mon 16 Nov`, `every 3 days from Mon 16 Nov`. */
  def everyNDays(everyDays: Int, anchor: LocalDate): String =
    val cadence = everyDays match
      case 1 => "every day"
      case 2 => "every other day"
      case n => s"every $n days"
    s"$cadence from ${date(anchor)}"

  /** `21 days on, 7 days off from Mon 16 Nov`. */
  def cycle(onDays: Int, offDays: Int, anchor: LocalDate): String =
    s"${days(onDays)} on, ${days(offDays)} off from ${date(anchor)}"

  private def days(n: Int): String = if n == 1 then "1 day" else s"$n days"

  private val sample = LocalDate.of(2026, 11, 16)

  def entries: List[CopyEntry] = List(
    CopyEntry("schedule.every_n_days", everyNDays(2, sample)),
    CopyEntry("schedule.cycle", cycle(21, 7, sample))
  )
end MedicationDepthCopy
