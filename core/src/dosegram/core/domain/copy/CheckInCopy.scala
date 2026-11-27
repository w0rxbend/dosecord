package dosegram.core.domain.copy

import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Mood check-in copy for the chat (BOT_UX section 11.4). The answer to a check-in has the same shape for every value
  * (PRODUCT_SPEC R-MOO-6): the labels of [[MoodLabels]] and the time, never a number such as "4 of 5", never a comment.
  */
object CheckInCopy:

  def prompt: String = tr("How are you feeling right now?", "Як ви почуваєтеся зараз?")
  def scale: String =
    tr("1 very unpleasant · 3 neutral · 5 very pleasant", "1 дуже неприємно · 3 нейтрально · 5 дуже приємно")
  def energyAsk: String = tr("How much energy do you have?", "Скільки у вас енергії?")
  def energyScale: String = tr("1 very low · 5 very high", "1 дуже мало · 5 дуже багато")
  def AddEnergy: String = tr("Add energy", "Додати енергію")
  def undone: String = tr("Undone. The check-in was removed.", "Скасовано. Запис настрою видалено.")
  def usage: String =
    tr(
      "Send a step from 1 to 5, for example /mood 4, or /mood history for your recent check-ins.",
      "Надішліть число від 1 до 5, наприклад /mood 4, або /mood history, щоб побачити останні позначки."
    )
  def unknownCheckin: String = tr("This check-in is no longer there.", "Цього запису настрою вже немає.")
  def undoWindowPassed: String = tr("The undo window has passed.", "Час на скасування минув.")

  /** A later tap on a prompt that already recorded its check-in (owner decision 2026-11-13: one prompt, one check-in).
    */
  def alreadyRecorded(time: String): String = tr(s"Already recorded at $time.", s"Уже записано о $time.")

  /** A later tap on a prompt whose check-in was undone or deleted: it records nothing. */
  def promptUndone: String = tr("This check-in was undone.", "Цей запис настрою скасовано.")

  /** The caption of a check-in and its complete text equivalent (BUX-MOOD-04): "Pleasant · 21:04", or with the energy
    * step "Pleasant. Energy: low. · 21:04".
    */
  def recorded(valence: Valence, energy: Option[Energy], time: String): String = s"${label(valence, energy)} · $time"

  /** The words of a check-in without its time: "Pleasant", or with the energy step "Pleasant. Energy: low." */
  def label(valence: Valence, energy: Option[Energy]): String =
    energy match
      case Some(level) =>
        val energyWord = MoodLabels.energy(level).toLowerCase(Locale.ROOT)
        tr(
          s"${MoodLabels.valence(valence)}. Energy: $energyWord.",
          s"${MoodLabels.valence(valence)}. Енергія: $energyWord."
        )
      case None => MoodLabels.valence(valence)

  // `/mood history` (image round 5, supervisor decision of 2026-11-28): the latest check-ins as recorded, oldest first.

  /** The head of `/mood history`: how many of the latest check-ins follow. */
  def historyTitle(count: Int): String =
    tr(
      s"Your latest ${if count == 1 then "mood check-in" else s"$count mood check-ins"}, oldest first:",
      s"Останні позначки настрою (${count}), спочатку найдавніші:"
    )

  /** One check-in of `/mood history` at its own local day and time: "Mon 9 Nov 08:10 · Pleasant". */
  def historyLine(date: LocalDate, time: String, valence: Valence, energy: Option[Energy]): String =
    val day = s"${DateTimeFormatter.ofPattern("EEE", Formats.locale).format(date)} ${Formats.dayMonth.format(date)}"
    s"$day $time · ${label(valence, energy)}"

  def historyEmpty: String =
    tr(
      "No mood check-in yet. Send /mood to record one.",
      "Позначок настрою ще немає. Надішліть /mood, щоб записати першу."
    )

  def entries: List[CopyEntry] =
    List(
      CopyEntry("checkin.prompt", prompt),
      CopyEntry("checkin.scale", scale),
      CopyEntry("checkin.energy.ask", energyAsk),
      CopyEntry("checkin.energy.scale", energyScale),
      CopyEntry("checkin.energy.add", AddEnergy),
      CopyEntry("checkin.undone", undone),
      CopyEntry("checkin.usage", usage),
      CopyEntry("checkin.unknown", unknownCheckin),
      CopyEntry("checkin.undo_window_passed", undoWindowPassed),
      CopyEntry("checkin.already_recorded", alreadyRecorded("21:04")),
      CopyEntry("checkin.prompt_undone", promptUndone),
      CopyEntry("checkin.history.title", historyTitle(14)),
      CopyEntry("checkin.history.line", historyLine(LocalDate.of(2026, 11, 9), "08:10", Valence.steps(3), None)),
      CopyEntry("checkin.history.empty", historyEmpty)
    ) ++ Valence.steps.map(step => CopyEntry(s"checkin.recorded.${step.step}", recorded(step, None, "21:04"))) ++
      Energy.steps.map(step =>
        CopyEntry(s"checkin.recorded.energy.${step.step}", recorded(Valence.steps(3), Some(step), "21:04"))
      )
end CheckInCopy
