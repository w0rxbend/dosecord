package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.SleepCopy
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepBounds
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepKind

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A message of the sleep flow before it becomes an outbound message: its lines and its rows of buttons. */
final case class SleepCard(lines: List[String], rows: List[List[Choice]])

/** "Adjust times" before Save: each edge moved by a whole number of 15-minute steps (BOT_UX BUX-SLEEP-04). The steps
  * travel in the buttons' values, so nothing is stored until Save.
  */
final case class SleepShifts(onsetSteps: Int, wakeSteps: Int):
  def onset: Duration = SleepBounds.AdjustStep.multipliedBy(onsetSteps.toLong)
  def wake: Duration = SleepBounds.AdjustStep.multipliedBy(wakeSteps.toLong)
  def moveOnset(by: Int): SleepShifts = SleepShifts.clamped(onsetSteps + by, wakeSteps)
  def moveWake(by: Int): SleepShifts = SleepShifts.clamped(onsetSteps, wakeSteps + by)

object SleepShifts:
  val Zero: SleepShifts = SleepShifts(0, 0)

  /** Eight hours either way is the furthest one edge moves in chat; the app edits any time. */
  val MaxSteps = 32
  private val Offset = 64
  private val Base = 256

  private def clamped(onset: Int, wake: Int): SleepShifts =
    SleepShifts(Math.clamp(onset.toLong, -MaxSteps, MaxSteps), Math.clamp(wake.toLong, -MaxSteps, MaxSteps))

  def value(shifts: SleepShifts): Long = ((shifts.onsetSteps + Offset) * Base + shifts.wakeSteps + Offset).toLong

  /** `None` for a value no button of this flow carries. */
  def of(value: Long): Option[SleepShifts] =
    val onset = (value / Base).toInt - Offset
    val wake = (value % Base).toInt - Offset
    Option.when(value >= 0 && Math.abs(onset) <= MaxSteps && Math.abs(wake) <= MaxSteps)(SleepShifts(onset, wake))

/** The sleep messages that more than one surface sends: the recorded night with its rating, "Adjust times" and Undo
  * (BOT_UX section 21.9), the "Adjust times" view, and the bedtime chips of an "I'm up" without an open night
  * (PRODUCT_SPEC R-SLP-4). The chat's `/sleep` flow and the morning touchpoint both answer with them. `token` encodes a
  * direct-mode button: the action's name, the subject and the value.
  */
object SleepCards:
  private val Clock = DateTimeFormatter.ofPattern("HH:mm")

  /** Bedtime chips around the bedtime of the user's day, half an hour apart. */
  private val ChipOffsetsMinutes = List(-60, -30, 0, 30, 60)

  def localTime(at: Instant, zone: ZoneId): String = Clock.withZone(zone).format(at)

  /** The night or nap as recorded: the rating question while a night has no rating, then "Adjust times" and Undo. */
  def recorded(episode: SleepEpisode, zone: ZoneId, token: (String, UUID, Long) => String): SleepCard =
    val night = sleptLine(episode, zone)
    val rate = Option.when(episode.kind == SleepKind.Main && episode.quality.isEmpty):
      Quality.steps.map(step => Choice.callback(step.step.toString, token("sleep.rate", episode.id, step.step.toLong)))
    val rated = episode.quality.map(quality => SleepCopy.rated(quality.step))
    val lines = night :: rate.map(_ => SleepCopy.qualityAsk).toList ++ rated.toList
    SleepCard(lines, rate.toList :+ closeRow(episode.id, token))

  /** The two edges as Save would store them, with a step either way for each, Save and Cancel. */
  def adjusting(
      episode: SleepEpisode,
      shifts: SleepShifts,
      zone: ZoneId,
      token: (String, UUID, Long) => String
  ): SleepCard =
    def step(label: String, moved: SleepShifts): Choice =
      Choice.callback(label, token("sleep.adjust", episode.id, SleepShifts.value(moved)))
    val onset = localTime(episode.interval.onset.at.plus(shifts.onset), zone)
    val wake = localTime(episode.interval.wake.at.plus(shifts.wake), zone)
    SleepCard(
      List(SleepCopy.adjusting(onset, wake)),
      List(
        List(step(SleepCopy.BedtimeEarlier, shifts.moveOnset(-1)), step(SleepCopy.BedtimeLater, shifts.moveOnset(1))),
        List(step(SleepCopy.WakeEarlier, shifts.moveWake(-1)), step(SleepCopy.WakeLater, shifts.moveWake(1))),
        List(
          Choice.callback(SleepCopy.Save, token("sleep.adjust_save", episode.id, SleepShifts.value(shifts))),
          Choice.callback(Labels.Cancel, token("sleep.adjust_cancel", episode.id, 0))
        )
      )
    )

  /** "When did you go to sleep?" with chips around the user's own bedtime, after an "I'm up" with no open night. */
  def bedtimeAsk(wakeCheckinId: UUID, bedtime: LocalTime, token: (String, UUID, Long) => String): SleepCard =
    val chips = ChipOffsetsMinutes.map: offset =>
      val time = bedtime.plusMinutes(offset.toLong)
      Choice.callback(Clock.format(time), token("sleep.bedtime", wakeCheckinId, time.toSecondOfDay / 60L))
    SleepCard(List(SleepCopy.bedtimeAsk), List(chips))

  def sleptLine(episode: SleepEpisode, zone: ZoneId): String =
    SleepCopy.slept(episode, localTime(episode.interval.onset.at, zone), localTime(episode.interval.wake.at, zone))

  private def closeRow(episodeId: UUID, token: (String, UUID, Long) => String): List[Choice] =
    List(
      Choice.callback(SleepCopy.AdjustTimes, token("sleep.adjust", episodeId, SleepShifts.value(SleepShifts.Zero))),
      Choice.callback(Labels.Undo, token("sleep.undo", episodeId, 0))
    )
end SleepCards
