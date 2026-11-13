package dosegram.core.domain.copy

import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence

import java.util.Locale

/** Mood check-in copy for the chat (BOT_UX section 11.4). The answer to a check-in has the same shape for every value
  * (PRODUCT_SPEC R-MOO-6): the labels of [[MoodLabels]] and the time, never a number such as "4 of 5", never a comment.
  */
object CheckInCopy:

  val prompt = "How are you feeling right now?"
  val scale = "1 very unpleasant · 3 neutral · 5 very pleasant"
  val energyAsk = "How much energy do you have?"
  val energyScale = "1 very low · 5 very high"
  val AddEnergy = "Add energy"
  val undone = "Undone. The check-in was removed."
  val usage = "Send a step from 1 to 5, for example /mood 4."
  val unknownCheckin = "This check-in is no longer there."
  val undoWindowPassed = "The undo window has passed."

  /** A later tap on a prompt that already recorded its check-in (owner decision 2026-11-13: one prompt, one check-in).
    */
  def alreadyRecorded(time: String): String = s"Already recorded at $time."

  /** A later tap on a prompt whose check-in was undone or deleted: it records nothing. */
  val promptUndone = "This check-in was undone."

  /** The caption of a check-in and its complete text equivalent (BUX-MOOD-04): "Pleasant · 21:04", or with the energy
    * step "Pleasant. Energy: low. · 21:04".
    */
  def recorded(valence: Valence, energy: Option[Energy], time: String): String = s"${label(valence, energy)} · $time"

  /** The words of a check-in without its time: "Pleasant", or with the energy step "Pleasant. Energy: low." */
  def label(valence: Valence, energy: Option[Energy]): String =
    energy match
      case Some(level) =>
        s"${MoodLabels.valence(valence)}. Energy: ${MoodLabels.energy(level).toLowerCase(Locale.ROOT)}."
      case None => MoodLabels.valence(valence)

  val entries: List[CopyEntry] =
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
      CopyEntry("checkin.prompt_undone", promptUndone)
    ) ++ Valence.steps.map(step => CopyEntry(s"checkin.recorded.${step.step}", recorded(step, None, "21:04"))) ++
      Energy.steps.map(step =>
        CopyEntry(s"checkin.recorded.energy.${step.step}", recorded(Valence.steps(3), Some(step), "21:04"))
      )
end CheckInCopy
