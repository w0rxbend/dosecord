package dosegram.core.domain.copy

import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepKind

/** The sleep copy of the chat (BOT_UX sections 11.5 and 21.9). It states what the user entered and where each figure
  * comes from; it rates nothing and names no reference duration (PRODUCT_SPEC R-SLP-19).
  */
object SleepCopy:
  val GoingToSleep = "Going to sleep"
  val ImUp = "I'm up"
  val noNight = "No night logged yet."
  val ask = "When did you sleep? Send bedtime and wake time, for example: slept 23:30 7:10."
  val usage = "Send /sleep, or bedtime and wake time, for example: /sleep 23:30 7:10."
  val qualityAsk = "How did you sleep? 1 very poorly · 5 very well"
  val undone = "Sleep entry removed."
  val startUndone = "Sleep check-in removed."
  val unknown = "That sleep entry is no longer there."
  val undoWindowPassed = "This can no longer be undone here. Change it in the app."
  val overlaps = "This overlaps a night or nap you already logged. Change that one in the app."
  val nightExists = "That day already has a night. Change it in the app."
  val invalid = "Those times are not a night: the wake time must be after the bedtime and in the past."
  val tooShort = "That is shorter than an hour, so it is not saved as a night. The check-in stays open."

  def started(time: String): String = s"Sleep check-in started at $time."

  /** "Slept 23:32 to 07:10, 7 h 38 min (your check-ins)." */
  def slept(episode: SleepEpisode, bedtime: String, wakeTime: String): String =
    val noun = if episode.kind == SleepKind.Nap then "Napped" else "Slept"
    s"$noun $bedtime to $wakeTime, ${StatsCopy.duration(episode.duration.toMinutes.toInt)} (${sourceOf(episode)})."

  def rated(step: Int): String = s"Your rating: $step of 5."

  /** Where an episode comes from: both edges tapped are check-ins, anything typed is the user's entry. */
  def sourceOf(episode: SleepEpisode): String =
    val edges = List(episode.interval.onset.source, episode.interval.wake.source)
    if edges.forall(_ == EdgeSource.CheckIn) then StatsCopy.SleepSource else "your entry"

  val entries: List[CopyEntry] =
    List(
      CopyEntry("sleep.button.night", GoingToSleep),
      CopyEntry("sleep.button.up", ImUp),
      CopyEntry("sleep.none", noNight),
      CopyEntry("sleep.night", started("23:32")),
      CopyEntry("sleep.ask", ask),
      CopyEntry("sleep.usage", usage),
      CopyEntry("sleep.quality.ask", qualityAsk),
      CopyEntry("sleep.quality.rated", rated(4)),
      CopyEntry("sleep.undone", undone),
      CopyEntry("sleep.night.undone", startUndone),
      CopyEntry("sleep.unknown", unknown),
      CopyEntry("sleep.undo_window_passed", undoWindowPassed),
      CopyEntry("sleep.overlap", overlaps),
      CopyEntry("sleep.night_exists", nightExists),
      CopyEntry("sleep.invalid", invalid),
      CopyEntry("sleep.too_short", tooShort)
    )
