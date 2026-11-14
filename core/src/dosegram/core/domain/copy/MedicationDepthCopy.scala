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

  // ---------- as needed: the user's own limits (supervisor decision, 2026-11-14) ----------

  /** `You set at most 3 a day; 2 logged today.` Only the values the user entered; nothing is computed or advised. */
  def prnFacts(maxPerDay: Option[Int], minGapMinutes: Option[Int], loggedToday: Int): String =
    val set =
      List(maxPerDay.map(n => s"at most $n a day"), minGapMinutes.map(m => s"at least ${gap(m)} between")).flatten
        .mkString(" and ")
    s"You set $set; $loggedToday logged today."

  /** The question before a log above the user's own limit. The log is never refused. */
  def prnConfirm(name: String, facts: String): String = s"$facts Log $name anyway?"

  val prnLogIt = "Log it"
  val prnCancel = "Cancel"
  val prnCancelled = "Nothing was logged."

  private def gap(minutes: Int): String =
    if minutes % 60 == 0 then s"${minutes / 60} h" else s"${minutes / 60} h ${minutes % 60} min"

  // ---------- pause for a number of days (R-REG-5) ----------

  val pauseFor = "Pause for..."
  def pauseForPrompt(name: String): String =
    s"For how long should reminders for $name stop? They come back on their own; the history stays."
  val pauseDays: List[Int] = List(3, 7, 14, 30)
  def pauseDaysLabel(days: Int): String = s"$days days"
  def pausedUntil(name: String, date: LocalDate): String =
    s"Paused $name until ${this.date(date)}. Reminders come back that day."
  def resumedOn(name: String): String = s"Reminders for $name are back on from today, as you set when pausing."

  // ---------- inventory and the refill notice (R-REG-14 to R-REG-16, BOT_UX 10.5, IMG-15) ----------

  private def units(n: BigDecimal, unit: Option[String]): String =
    s"${n.bigDecimal.stripTrailingZeros.toPlainString} ${unit.getOrElse("units")}"

  /** `refill.low`: a count and a date, from the user's own entries; no advice. */
  def refillLow(name: String, onHand: BigDecimal, unit: Option[String], until: Option[LocalDate]): String =
    val covers = until.fold("")(d => s" At your current schedule that covers until about ${date(d)}.")
    s"About ${units(onHand, unit)} of $name left by your count.$covers"

  val refillLowDiscreet = "One of your items is running low by your count."
  val countOff = "The count may be off. Update it?"
  def refilledLabel(quantity: BigDecimal): String = s"Refilled +${quantity.bigDecimal.stripTrailingZeros.toPlainString}"
  val refillShowCard = "Show card"
  val refillStop = "Stop for this pack"
  def refilled(name: String, onHand: BigDecimal, unit: Option[String]): String =
    s"Added. $name: ${units(onHand, unit)} by your count."
  val refillStopped = "No more refill notices for this pack. A refill or a new count turns them back on."
  val refillUnavailable = "That count is no longer there."

  /** The text equivalent of IMG-15 (VISUALS section 6.16). */
  def refillCardText(
      name: String,
      onHand: BigDecimal,
      unit: Option[String],
      threshold: String,
      until: Option[LocalDate]
  ): String =
    val covers = until.fold("")(d => s" At your current schedule that covers until about ${date(d)}.")
    s"$name: ${units(onHand, unit)} left by your count, at or below your threshold of $threshold.$covers " +
      "Update the count after a refill."

  def thresholdDays(days: Int): String = if days == 1 then "1 day" else s"$days days"
  def thresholdUnits(n: BigDecimal, unit: Option[String]): String = units(n, unit)

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
    CopyEntry("med.add.next", nextReminders(List("Mon 16 Nov 09:00", "Wed 18 Nov 09:00"))),
    CopyEntry("take.limits", prnFacts(Some(3), Some(240), 2)),
    CopyEntry("take.limits.confirm", prnConfirm("Ibuprofen", prnFacts(Some(3), None, 3))),
    CopyEntry("take.limits.log_it", prnLogIt),
    CopyEntry("take.limits.cancel", prnCancel),
    CopyEntry("take.limits.cancelled", prnCancelled),
    CopyEntry("med.pause_for", pauseFor),
    CopyEntry("med.pause_for.prompt", pauseForPrompt("Vitamin D")),
    CopyEntry("med.pause_for.days", pauseDaysLabel(7)),
    CopyEntry("med.paused_until", pausedUntil("Vitamin D", sample)),
    CopyEntry("med.resumed_on", resumedOn("Vitamin D")),
    CopyEntry("refill.low", refillLow("Metformin 500 mg", 6, Some("tablets"), Some(sample))),
    CopyEntry("refill.low.discreet", refillLowDiscreet),
    CopyEntry("refill.count_off", countOff),
    CopyEntry("refill.refilled_label", refilledLabel(30)),
    CopyEntry("refill.show_card", refillShowCard),
    CopyEntry("refill.stop", refillStop),
    CopyEntry("refill.refilled", refilled("Metformin", 36, Some("tablets"))),
    CopyEntry("refill.stopped", refillStopped),
    CopyEntry("refill.unavailable", refillUnavailable),
    CopyEntry(
      "refill.card.text",
      refillCardText("Metformin 500 mg", 6, Some("tablets"), thresholdDays(7), Some(sample))
    )
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
