package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Reply
import dosegram.core.application.hydration.HydrationRefusal
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.hydration.WaterDayView
import dosegram.core.chat.ActionEntry
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.hydration.AmountOutOfBounds
import dosegram.core.domain.hydration.HydrationUnit
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.ports.StatusCards
import dosegram.core.ports.WaterCardView

import java.util.UUID

/** Water in chat (BOT_UX section 11.2): the card of `/water`, one entry per button press or typed amount, the user's
  * own goal, Undo, and the vessel image on request. The answer to a tap takes the place of the card it came from.
  */
private[capture] final class WaterChat(hydration: HydrationService, cards: StatusCards, tokens: Tokens):

  import WaterChat.*

  def card(turn: Turn): Reply =
    answered(turn, hydration.day(turn.accountId, EntryDay.Today)): view =>
      turn.show(turn.message("water.card", List(WaterCopy.card(dayOf(view))), cardRows(turn, view, undo = None)))

  /** A typed amount. A count of glasses is sized by the user's own first preset, not by the default one. */
  def typed(turn: Turn, parsed: WaterAmount): Reply =
    if parsed.entered.unit != HydrationUnit.Glass then add(turn, parsed)
    else
      answered(turn, hydration.presets(turn.accountId)): presets =>
        WaterAmount.of(parsed.entered, presets.head) match
          case Right(sized)            => add(turn, sized)
          case Left(AmountOutOfBounds) => turn.toast(WaterCopy.tooMuch)

  def addMillilitres(turn: Turn, millilitres: Int): Reply =
    WaterAmount.ofMillilitres(millilitres) match
      case Right(amount)           => add(turn, amount)
      case Left(AmountOutOfBounds) => turn.toast(WaterCopy.tooMuch)

  def add(turn: Turn, amount: WaterAmount): Reply =
    val command = LogWater(turn.accountId, amount, EntryDay.Today, EntrySource.Bot, turn.clientKey)
    answered(turn, hydration.log(command)): logged =>
      val text = WaterCopy.added(amount.millilitres, dayOf(logged.day), logged.goalReachedNow)
      turn.show(turn.message("water.added", List(text), cardRows(turn, logged.day, undo = Some(logged.entry.id))))

  def undo(turn: Turn, entryId: UUID): Reply =
    answered(turn, hydration.undo(turn.accountId, entryId)): removed =>
      val text = WaterCopy.undone(dayOf(removed.day))
      turn.show(turn.message("water.undone", List(text), cardRows(turn, removed.day, undo = None)))

  /** `water.goal.ask` (BUX-WATER-02): three example amounts and "No goal"; `pendingMl` is the amount to record after
    * the choice, or zero.
    */
  def askGoal(turn: Turn, pendingMl: Int): Reply =
    val examples = GoalExamplesMl.map(goal => goalButton(turn, WaterCopy.goalChoice(goal), goal, pendingMl))
    val none = goalButton(turn, WaterCopy.NoGoal, NoGoalValue, pendingMl)
    turn.show(turn.message("water.goal", List(WaterCopy.goalAsk), List(examples, List(none))))

  /** A tap on the goal card: the goal is set from today on, then the pending amount is recorded or the card shown. */
  def setGoal(turn: Turn, value: Long): Reply =
    val goalMl = (value & GoalMask).toInt
    val pendingMl = (value >> PendingShift).toInt
    val target = Option.when(goalMl != NoGoalValue)(goalMl)
    answered(turn, hydration.setGoal(turn.accountId, target)): _ =>
      if pendingMl > 0 then addMillilitres(turn, pendingMl) else card(turn)

  /** "Show as image" (BUX-WATER-06): the vessel card with the day's figures as its caption, as a new message. */
  def image(turn: Turn): Reply =
    answered(turn, hydration.day(turn.accountId, EntryDay.Today)): view =>
      view.goalMl match
        case None       => turn.toast(WaterCopy.imageNeedsGoal)
        case Some(goal) =>
          val day = dayOf(view)
          cards.water(WaterCardView(view.date, day.totalMl, goal, day.entries)) match
            case Some(attachment) => Reply(followUps = List(captioned(turn, day).copy(attachment = Some(attachment))))
            case None             => turn.toast(WaterCopy.imageUnavailable)

  private def captioned(turn: Turn, day: HydrationDay): OutboundMessage =
    turn.message("water.image", StatsCopy.waterDay(Some(day)).toList, Nil)

  private def answered[A](turn: Turn, outcome: Either[HydrationRefusal, A])(reply: A => Reply): Reply =
    outcome match
      case Right(value)  => reply(value)
      case Left(refusal) => turn.toast(refusalText(refusal))

  private def refusalText(refusal: HydrationRefusal): String =
    refusal match
      case HydrationRefusal.DailyLimitReached(limit) => WaterCopy.dailyLimit(limit)
      case HydrationRefusal.UndoWindowPassed         => WaterCopy.undoWindowPassed
      case HydrationRefusal.UnknownEntry             => WaterCopy.unknownEntry
      case HydrationRefusal.GoalOutOfBounds(_, _)    => WaterCopy.usage
      // The consent was withdrawn, or the account is gone, after the button was sent.
      case HydrationRefusal.ConsentMissing | HydrationRefusal.UnknownAccount | HydrationRefusal.PresetsInvalid =>
        ReminderCopy.staleControlToast

  /** Undo for the entry just made, the quick-add amounts, then the image and the goal. */
  private def cardRows(turn: Turn, view: WaterDayView, undo: Option[UUID]): List[List[Choice]] =
    val presets = hydration.presets(turn.accountId).getOrElse(Nil)
    val undoRow =
      undo.toList.map(entry => Choice.callback(Labels.Undo, tokens.direct(CaptureActions.WaterUndo, entry, 0)))
    val addRow = presets.map: millilitres =>
      Choice.callback(WaterCopy.preset(millilitres), accountToken(turn, CaptureActions.WaterAdd, millilitres.toLong))
    val image =
      view.goalMl.toList.map(_ =>
        Choice.callback(WaterCopy.ShowAsImage, accountToken(turn, CaptureActions.WaterImage, 0))
      )
    val goal = Choice.callback(WaterCopy.Goal, accountToken(turn, CaptureActions.WaterGoalAsk, 0))
    List(undoRow, addRow, image :+ goal)

  private def goalButton(turn: Turn, label: String, goalMl: Int, pendingMl: Int): Choice =
    Choice.callback(
      label,
      accountToken(turn, CaptureActions.WaterGoal, goalMl.toLong | (pendingMl.toLong << PendingShift))
    )

  private def accountToken(turn: Turn, action: ActionEntry, value: Long): String =
    tokens.direct(action, turn.accountId, value)

private[capture] object WaterChat:

  /** The example amounts of the goal card. They are examples, not a recommendation (BUX-WATER-02). */
  val GoalExamplesMl: List[Int] = List(1500, 2000, 2500)

  // The value of a `water.goal` token: the goal in bits 0-13 (zero means "No goal"), the pending amount above them.
  private val PendingShift = 14
  private val GoalMask = (1L << PendingShift) - 1
  private val NoGoalValue = 0

  /** The figures of a day; a day without an entry still shows the goal in force. */
  def dayOf(view: WaterDayView): HydrationDay =
    view.recorded.getOrElse(HydrationDay(totalMl = 0, entries = 0, goalMl = view.goalMl))
