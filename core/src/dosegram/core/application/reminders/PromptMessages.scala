package dosegram.core.application.reminders

import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.CheckInCopy
import dosegram.core.domain.copy.FoodCopy
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptNotice

import java.util.UUID

/** The messages of the prompts (BOT_UX section 14, ADR-023). A prompt's buttons name the prompt occurrence, so a tap
  * closes that prompt; the mood steps name the account and record through the mood flow of the chat.
  */
final class PromptMessages(codec: CallbackCodec):

  import PromptMessages.*

  /** The water prompt: the day so far, the user's quick-add amounts, "Snooze 1 h" and "Pause for today". */
  def water(
      occurrenceId: UUID,
      day: HydrationDay,
      presetsMl: List[Int],
      notice: Option[PromptNotice],
      silent: Boolean,
      key: String
  ): OutboundMessage =
    val add = presetsMl.map(ml => Choice.callback(WaterCopy.preset(ml), token(WaterAdd, occurrenceId, ml.toLong)))
    message(
      PromptCopy.waterNudge(day) :: noticeLines(notice),
      List(add, closeRow(occurrenceId)),
      silent,
      key
    )

  def mood(
      accountId: UUID,
      occurrenceId: UUID,
      notice: Option[PromptNotice],
      silent: Boolean,
      key: String
  ): OutboundMessage =
    val steps =
      Valence.steps.map(step => Choice.callback(step.step.toString, token(MoodStep, accountId, step.step.toLong)))
    message(
      CheckInCopy.prompt :: CheckInCopy.scale :: noticeLines(notice),
      List(steps, closeRow(occurrenceId)),
      silent,
      key
    )

  def sleep(
      kind: PromptKind,
      occurrenceId: UUID,
      notice: Option[PromptNotice],
      silent: Boolean,
      key: String
  ): OutboundMessage =
    val (text, label, value) =
      if kind == PromptKind.SleepBedtime then (PromptCopy.bedtimeNudge, PromptCopy.GoingToSleep, SleepGoing)
      else (PromptCopy.morningNudge, PromptCopy.ImUp, SleepUp)
    message(
      text :: noticeLines(notice),
      List(List(Choice.callback(label, token(SleepTap, occurrenceId, value))), closeRow(occurrenceId)),
      silent,
      key
    )

  /** A meal nudge: one button for the slot's meal, or one per meal type for a slot at another time. */
  def meal(
      occurrenceId: UUID,
      meal: Option[MealType],
      notice: Option[PromptNotice],
      silent: Boolean,
      key: String
  ): OutboundMessage =
    val buttons = meal.fold(MealType.values.toList.map(mealType => FoodCopy.label(mealType) -> mealType))(mealType =>
      List(FoodCopy.logButton(mealType) -> mealType)
    )
    val log =
      buttons.map((label, mealType) => Choice.callback(label, token(MealTap, occurrenceId, mealType.ordinal.toLong)))
    message(FoodCopy.nudge(meal) :: noticeLines(notice), List(log, closeRow(occurrenceId)), silent, key)

  /** The one-line state an open prompt is edited to when its kind pauses for the day. */
  def pausedNotice(kind: PromptKind, key: String): OutboundMessage =
    val text = if kind == PromptKind.Water then PromptCopy.pausedToday else PromptCopy.pausedTodayOf(kind)
    message(List(text), Nil, silent = true, key)

  private def closeRow(occurrenceId: UUID): List[Choice] =
    List(
      Choice.callback(PromptCopy.Snooze1h, token(Snooze, occurrenceId, SnoozeMinutes.toLong)),
      Choice.callback(PromptCopy.PauseToday, token(PauseToday, occurrenceId, 0))
    )

  private def noticeLines(notice: Option[PromptNotice]): List[String] =
    notice.toList.map:
      case PromptNotice.Widened        => PromptCopy.widened
      case PromptNotice.PausedForToday => PromptCopy.pausedToday

  private def token(action: String, subject: UUID, value: Long): String =
    val entry = ActionRegistry.byName(action).getOrElse(throw IllegalStateException(s"action '$action' is missing"))
    codec.encode(CallbackMode.Direct, entry, subject, value).wire

object PromptMessages:
  val WaterAdd = "prompt.water_add"
  val Snooze = "prompt.snooze"
  val PauseToday = "prompt.pause_today"
  val SleepTap = "prompt.sleep"
  val MealTap = "prompt.meal"
  val MoodStep = "mood.checkin"
  val SnoozeMinutes = 60
  val SleepGoing = 0L
  val SleepUp = 1L

  private def message(lines: List[String], rows: List[List[Choice]], silent: Boolean, key: String): OutboundMessage =
    OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks =
        rows.filter(_.nonEmpty).zipWithIndex.map((row, i) => Block.Choices(ChoiceSet(s"prompt.$i", choices = row))),
      silent = silent,
      dedupeKey = key,
      correlationId = key
    )
