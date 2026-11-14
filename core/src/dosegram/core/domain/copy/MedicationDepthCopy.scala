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

  // ---------- presets in the add-medication flow (supervisor decision, 2026-11-14) ----------

  val presetsLabel = "Presets"
  val presetsPrompt = "Pick a preset. Its times come from your day in /reminders; you review them before saving."

  /** The short button label of a medication preset (`MedicationPresets` ids). */
  def presetButton(id: String): String = id match
    case "med.morning"          => "Morning"
    case "med.evening"          => "Evening"
    case "med.twice_daily"      => "Twice a day"
    case "med.three_with_meals" => "With meals"
    case "med.every_8h"         => "Every 8 h"
    case "med.every_12h"        => "Every 12 h"
    case "med.weekdays"         => "Weekdays"
    case "med.weekly"           => "Weekly"
    case "med.every_other_day"  => "Every other day"
    case "med.cycle"            => "21 days on, 7 off"
    case "med.as_needed"        => "As needed"
    case other                  => other

  def presetEcho(button: String): String = s"Preset: $button."

  /** The confirm card of a preset: `Vitamin D 1000 IU, every other day from Mon 16 Nov 07:00 Europe/Kyiv`. */
  def confirmCard(name: String, dose: Option[String], schedule: String, zone: String): String =
    s"${dose.fold(name)(d => s"$name $d")}, $schedule $zone"

  /** The first reminders of the reviewed schedule (PRODUCT_SPEC R-SCH-5). */
  def nextReminders(times: List[String]): String = s"Next reminders: ${times.mkString(", ")}."

  private val sample = LocalDate.of(2026, 11, 16)

  def entries: List[CopyEntry] = List(
    CopyEntry("schedule.every_n_days", everyNDays(2, sample)),
    CopyEntry("schedule.cycle", cycle(21, 7, sample)),
    CopyEntry("med.add.presets", presetsLabel),
    CopyEntry("med.add.presets.prompt", presetsPrompt),
    CopyEntry("med.add.preset.echo", presetEcho("Every other day")),
    CopyEntry(
      "med.add.preset.confirm",
      confirmCard("Vitamin D", Some("1000 IU"), everyNDays(2, sample) + " 07:00", "Europe/Kyiv")
    ),
    CopyEntry("med.add.next", nextReminders(List("Mon 16 Nov 09:00", "Wed 18 Nov 09:00")))
  ) ++ List(
    "med.morning",
    "med.evening",
    "med.twice_daily",
    "med.three_with_meals",
    "med.every_8h",
    "med.every_12h",
    "med.weekdays",
    "med.weekly",
    "med.every_other_day",
    "med.cycle",
    "med.as_needed"
  ).map(id => CopyEntry(s"med.add.preset.$id", presetButton(id)))
end MedicationDepthCopy
