package dosegram.core.domain.copy

import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.insights.HydrationDay

/** Water copy for the chat (BOT_UX section 11.2 and the transcript of section 21.7). Every line states what was logged
  * against the user's own goal: no line suggests an amount or comments on a total (PRODUCT_SPEC section 8.8).
  */
object WaterCopy:

  val goalAsk =
    "Set a daily amount to count towards? Pick any amount that suits you. Dosegram does not recommend an amount."
  val NoGoal = "No goal"
  val Goal = "Goal"
  val ShowAsImage = "Show as image"
  val nothingLogged = "Water today: nothing logged yet."
  val usage = "Send an amount, for example /water 300."
  val undoWindowPassed = "The undo window has passed."
  val unknownEntry = "This entry is no longer there."
  val imageUnavailable = "The image is not available right now. The figures above are complete."
  val imageNeedsGoal = "The image shows progress to your own goal. Set one with Goal first."

  /** `water.too_much`: the bound is an input-error guard, not guidance. */
  val tooMuch = s"One entry can be at most ${HydrationBounds.MaxEntryMl} ml. Send a smaller amount."

  def preset(millilitres: Int): String = s"+ $millilitres ml"
  def goalChoice(millilitres: Int): String = s"${StatsCopy.litres(millilitres, 2)} l"
  def dailyLimit(limit: Int): String = s"A day holds at most $limit water entries."

  /** The water card of `/water`. */
  def card(day: HydrationDay): String = if day.entries == 0 then nothingLogged else s"Water today: ${figures(day)}"

  /** `water.added`: the echo names the amount and the new daily total; "Daily goal reached." is said once. */
  def added(millilitres: Int, day: HydrationDay, goalReachedNow: Boolean): String =
    val reached = if goalReachedNow then " Daily goal reached." else ""
    s"Water +$millilitres ml. Today: ${figures(day)}.$reached"

  def undone(day: HydrationDay): String = s"Undone. Water today: ${figures(day)}."

  def goalSet(millilitres: Option[Int]): String =
    millilitres.map(goal => s"Daily goal: ${goalChoice(goal)}.").getOrElse("No daily goal.")

  private def figures(day: HydrationDay): String =
    day.progress match
      case Some(progress) =>
        s"${StatsCopy.litres(progress.totalMl, 2)} of ${StatsCopy.litres(progress.goalMl, 2)} l (${progress.percent}%)"
      case None => s"${StatsCopy.litres(day.totalMl, 2)} l"

  private val sampleDay = HydrationDay(totalMl = 1250, entries = 4, goalMl = Some(2000))

  val entries: List[CopyEntry] = List(
    CopyEntry("water.goal.ask", goalAsk),
    CopyEntry("water.goal.none", NoGoal),
    CopyEntry("water.goal.button", Goal),
    CopyEntry("water.goal.choice", goalChoice(2000)),
    CopyEntry("water.goal.set", goalSet(Some(2000))),
    CopyEntry("water.goal.unset", goalSet(None)),
    CopyEntry("water.show_as_image", ShowAsImage),
    CopyEntry("water.nothing_logged", nothingLogged),
    CopyEntry("water.usage", usage),
    CopyEntry("water.undo_window_passed", undoWindowPassed),
    CopyEntry("water.unknown_entry", unknownEntry),
    CopyEntry("water.image_unavailable", imageUnavailable),
    CopyEntry("water.image_needs_goal", imageNeedsGoal),
    CopyEntry("water.too_much", tooMuch),
    CopyEntry("water.preset", preset(250)),
    CopyEntry("water.daily_limit", dailyLimit(HydrationBounds.MaxEntriesPerDay)),
    CopyEntry("water.card", card(sampleDay)),
    CopyEntry("water.card.no_goal", card(sampleDay.copy(goalMl = None))),
    CopyEntry("water.added", added(250, sampleDay, goalReachedNow = false)),
    CopyEntry("water.added.goal_reached", added(500, sampleDay.copy(totalMl = 2000), goalReachedNow = true)),
    CopyEntry("water.undone", undone(sampleDay))
  )
end WaterCopy
