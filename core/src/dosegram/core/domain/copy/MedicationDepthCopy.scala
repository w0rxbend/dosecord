package dosegram.core.domain.copy

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** The copy of the medication-depth track (ROADMAP G9.8, G9.10, G9.12): the words of the further schedule kinds. Every
  * line states what the user configured; none advises.
  */
object MedicationDepthCopy:

  private def DayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Formats.locale)

  def date(day: LocalDate): String = day.format(DayFormat)

  /** `every other day from Mon 16 Nov`, `every 3 days from Mon 16 Nov`. */
  def everyNDays(everyDays: Int, anchor: LocalDate): String =
    val cadence = everyDays match
      case 1 => tr("every day", "щодня")
      case 2 => tr("every other day", "через день")
      case n => tr(s"every $n days", s"раз на $n ${UkNouns.DaysOf(n)}")
    tr(s"$cadence from ${date(anchor)}", s"$cadence, з ${date(anchor)}")

  /** `21 days on, 7 days off from Mon 16 Nov`. */
  def cycle(onDays: Int, offDays: Int, anchor: LocalDate): String =
    tr(
      s"${days(onDays)} on, ${days(offDays)} off from ${date(anchor)}",
      s"${days(onDays)} з прийомом, ${days(offDays)} перерви, з ${date(anchor)}"
    )

  private def days(n: Int): String = counted(n, "day", "days", UkNouns.Days)

  // ---------- presets in the add-medication flow (supervisor decision, 2026-11-14) ----------

  def presetsLabel: String = tr("Presets", "Готові плани")
  def presetsPrompt: String = tr(
    "Pick a preset. Its times come from your day in /reminders; you review them before saving.",
    "Виберіть готовий план. Його час береться з вашого дня в /reminders; ви переглянете його перед збереженням."
  )

  /** The short button label of a medication preset (`MedicationPresets` ids). */
  def presetButton(id: String): String = id match
    case "med.morning"          => tr("Morning", "Зранку")
    case "med.evening"          => tr("Evening", "Увечері")
    case "med.twice_daily"      => tr("Twice a day", "Двічі на день")
    case "med.three_with_meals" => tr("With meals", "З їжею")
    case "med.every_8h"         => tr("Every 8 h", "Кожні 8 год")
    case "med.every_12h"        => tr("Every 12 h", "Кожні 12 год")
    case "med.weekdays"         => tr("Weekdays", "Будні")
    case "med.weekly"           => tr("Weekly", "Щотижня")
    case "med.every_other_day"  => tr("Every other day", "Через день")
    case "med.cycle"            => tr("21 days on, 7 off", "21 день так, 7 ні")
    case "med.as_needed"        => tr("As needed", "За потреби")
    case other                  => other

  def presetEcho(button: String): String = tr(s"Preset: $button.", s"Готовий план: $button.")

  /** The confirm card of a preset: `Vitamin D 1000 IU, every other day from Mon 16 Nov 07:00 Europe/Kyiv`. */
  def confirmCard(name: String, dose: Option[String], schedule: String, zone: String): String =
    s"${dose.fold(name)(d => s"$name $d")}, $schedule $zone"

  /** The first reminders of the reviewed schedule (PRODUCT_SPEC R-SCH-5). */
  def nextReminders(times: List[String]): String =
    tr(s"Next reminders: ${times.mkString(", ")}.", s"Наступні нагадування: ${times.mkString(", ")}.")

  // ---------- as needed: the user's own limits (supervisor decision, 2026-11-14) ----------

  /** `You set at most 3 a day; 2 logged today.` Only the values the user entered; nothing is computed or advised. */
  def prnFacts(maxPerDay: Option[Int], minGapMinutes: Option[Int], loggedToday: Int): String =
    val set =
      List(
        maxPerDay.map(n => tr(s"at most $n a day", s"не більше $n на день")),
        minGapMinutes.map(m => tr(s"at least ${gap(m)} between", s"щонайменше ${gap(m)} між прийомами"))
      ).flatten
        .mkString(tr(" and ", " і "))
    tr(s"You set $set; $loggedToday logged today.", s"Ви задали: $set; сьогодні записано $loggedToday.")

  /** The question before a log above the user's own limit. The log is never refused. */
  def prnConfirm(name: String, facts: String): String =
    tr(s"$facts Log $name anyway?", s"$facts Усе одно записати $name?")

  def prnLogIt: String = tr("Log it", "Записати")
  def prnCancel: String = tr("Cancel", "Скасувати")
  def prnCancelled: String = tr("Nothing was logged.", "Нічого не записано.")

  private def gap(minutes: Int): String =
    val (hours, rest) = (minutes / 60, minutes % 60)
    if rest == 0 then tr(s"$hours h", s"$hours год") else tr(s"$hours h $rest min", s"$hours год $rest хв")

  // ---------- pause for a number of days (R-REG-5) ----------

  def pauseFor: String = tr("Pause for...", "Пауза на...")
  def pauseForPrompt(name: String): String = tr(
    s"For how long should reminders for $name stop? They come back on their own; the history stays.",
    s"На скільки зупинити нагадування для $name? Вони повернуться самі; історія залишиться."
  )
  val pauseDays: List[Int] = List(3, 7, 14, 30)
  def pauseDaysLabel(days: Int): String = counted(days, "day", "days", UkNouns.Days)
  def pausedUntil(name: String, date: LocalDate): String = tr(
    s"Paused $name until ${this.date(date)}. Reminders come back that day.",
    s"$name призупинено до ${this.date(date)}. Того дня нагадування повернуться."
  )
  def resumedOn(name: String): String = tr(
    s"Reminders for $name are back on from today, as you set when pausing.",
    s"Нагадування для $name знову ввімкнені з сьогодні, як ви задали під час паузи."
  )

  // ---------- inventory and the refill notice (R-REG-14 to R-REG-16, BOT_UX 10.5, IMG-15) ----------

  private def units(n: BigDecimal, unit: Option[String]): String =
    s"${decimal(n.bigDecimal.stripTrailingZeros.toPlainString)} ${unit.getOrElse(unitsWord)}"

  /** The unit word of a count whose dose unit is not countable. */
  def unitsWord: String = tr("units", "од.")

  private def coversUntil(until: Option[LocalDate]): String =
    until.fold("")(d =>
      tr(
        s" At your current schedule that covers until about ${date(d)}.",
        s" За поточним розкладом цього вистачить приблизно до ${date(d)}."
      )
    )

  /** `refill.low`: a count and a date, from the user's own entries; no advice. */
  def refillLow(name: String, onHand: BigDecimal, unit: Option[String], until: Option[LocalDate]): String =
    tr(
      s"About ${units(onHand, unit)} of $name left by your count.${coversUntil(until)}",
      s"$name: за вашим підрахунком лишилося близько ${units(onHand, unit)}.${coversUntil(until)}"
    )

  def refillLowDiscreet: String =
    tr("One of your items is running low by your count.", "За вашим підрахунком одна з ваших позицій закінчується.")
  def countOff: String = tr("The count may be off. Update it?", "Залишок може бути неточним. Оновити його?")
  def refilledLabel(quantity: BigDecimal): String =
    val plain = decimal(quantity.bigDecimal.stripTrailingZeros.toPlainString)
    tr(s"Refilled +$plain", s"Поповнено +$plain")
  def refillShowCard: String = tr("Show card", "Показати картку")
  def refillStop: String = tr("Stop for this pack", "Досить для цієї пачки")
  def refilled(name: String, onHand: BigDecimal, unit: Option[String]): String =
    tr(
      s"Added. $name: ${units(onHand, unit)} by your count.",
      s"Додано. $name: ${units(onHand, unit)} за вашим підрахунком."
    )
  def refillStopped: String = tr(
    "No more refill notices for this pack. A refill or a new count turns them back on.",
    "Для цієї пачки сповіщень про поповнення більше не буде. Поповнення або новий залишок знову їх увімкне."
  )
  def refillUnavailable: String = tr("That count is no longer there.", "Цього залишку вже немає.")

  /** The text equivalent of IMG-15 (VISUALS section 6.16). */
  def refillCardText(
      name: String,
      onHand: BigDecimal,
      unit: Option[String],
      threshold: String,
      until: Option[LocalDate]
  ): String =
    tr(
      s"$name: ${units(onHand, unit)} left by your count, at or below your threshold of $threshold.${coversUntil(until)} " +
        "Update the count after a refill.",
      s"$name: за вашим підрахунком лишилося ${units(onHand, unit)}, на вашому порозі ($threshold) або нижче." +
        s"${coversUntil(until)} Після поповнення оновіть залишок."
    )

  def thresholdDays(days: Int): String = counted(days, "day", "days", UkNouns.Days)
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
