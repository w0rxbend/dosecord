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

  /** The caption of a check-in and its complete text equivalent (BUX-MOOD-04): "Pleasant · 21:04", or with the energy
    * step "Pleasant. Energy: low. · 21:04".
    */
  def recorded(valence: Valence, energy: Option[Energy], time: String): String =
    energy match
      case Some(level) =>
        s"${MoodLabels.valence(valence)}. Energy: ${MoodLabels.energy(level).toLowerCase(Locale.ROOT)}. · $time"
      case None => s"${MoodLabels.valence(valence)} · $time"

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
      CopyEntry("checkin.undo_window_passed", undoWindowPassed)
    ) ++ Valence.steps.map(step => CopyEntry(s"checkin.recorded.${step.step}", recorded(step, None, "21:04"))) ++
      Energy.steps.map(step =>
        CopyEntry(s"checkin.recorded.energy.${step.step}", recorded(Valence.steps(3), Some(step), "21:04"))
      )
end CheckInCopy
