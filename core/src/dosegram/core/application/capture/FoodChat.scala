package dosegram.core.application.capture

import dosegram.contracts.AppRoute
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.Reply
import dosegram.core.application.nutrition.FoodViews
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.application.nutrition.MealRefusal
import dosegram.core.application.nutrition.NutritionService
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.FoodCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.nutrition.MealLine
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealTimes
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.reminders.DayAnchors
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.ports.Clock
import dosegram.core.ports.StatusCards

import java.util.UUID

/** Meals in chat (BOT_UX section 11.3, PRODUCT_SPEC section 8.12): `/food` with one button per meal type, the typed
  * quick-log `lunch: soup`, and Undo. A tap records the meal at the current time; the words of a meal are stored as
  * typed and never echoed or interpreted. A photo is not a meal in this wave: it is answered and never downloaded.
  */
private[capture] final class FoodChat(nutrition: NutritionService, cards: StatusCards, clock: Clock, tokens: Tokens):

  /** The meals logged today and the four meal buttons, the one that fits the time of day marked; with a meal logged,
    * also "Show as image".
    */
  def card(turn: Turn, hint: Option[String] = None): Reply =
    answered(turn, nutrition.today(turn.tx, turn.accountId)): day =>
      val logged = day.entries.map(entry => entry.mealType -> turn.localTime(entry.occurredAt))
      val lines = Option.when(logged.nonEmpty)(FoodCopy.today(logged)).toList ++ (FoodCopy.which :: hint.toList)
      val image = Option.when(logged.nonEmpty):
        Choice.callback(FoodCopy.ShowAsImage, tokens.direct(CaptureActions.FoodImage, turn.accountId, 0))
      turn.show(turn.message("food.which", lines, buttons(turn) :+ image.toList))

  /** IMG-09 for today, sent as a new photo with the day in words as its caption (VISUALS VA-TX-01). */
  def image(turn: Turn): Reply =
    val outcome =
      for
        day <- nutrition.today(turn.tx, turn.accountId)
        plan <- nutrition.plan(turn.tx, turn.accountId)
      yield (day, plan)
    answered(turn, outcome): (day, plan) =>
      if day.entries.isEmpty then turn.toast(FoodCopy.imageEmpty)
      else
        val logged = day.entries.map(entry => entry.mealType -> turn.localTime(entry.occurredAt))
        val caption = List(FoodCopy.today(logged), FoodCopy.day(FoodViews.regular(day, plan)))
        cards.food(FoodViews.card(day, plan), turn.look, Some(turn.event.chat)) match
          case Some(attachment) =>
            Reply(followUps = List(turn.message("food.image", caption, Nil).copy(attachment = Some(attachment))))
          case None => turn.toast(FoodCopy.imageUnavailable)

  /** Typed text or the argument of `/food`: a meal line records at once, anything else asks which meal. */
  def typed(turn: Turn, line: MealLine): Reply =
    line match
      case MealLine.Logged(mealType, text) => record(turn, mealType, text)
      case MealLine.TooLong(limit)         => turn.toast(FoodCopy.tooLong(limit))
      case MealLine.Ambiguous              => card(turn, Some(FoodCopy.ambiguousHint))
      case MealLine.NotMeal                => turn.toast(FoodCopy.usage)

  /** One tap on a meal button: that meal now, without words. */
  def tap(turn: Turn, mealType: MealType): Reply = record(turn, mealType, None)

  def undo(turn: Turn, mealId: UUID): Reply =
    answered(turn, nutrition.undo(turn.tx, turn.accountId, mealId)): _ =>
      turn.show(turn.message("food.undone", List(FoodCopy.undone), Nil))

  /** A photo (BUX-FOOD-05): the photo stage is not released, so nothing is fetched or stored. */
  def photo(turn: Turn): Reply = turn.show(turn.message("food.photo", List(FoodCopy.photoLater), Nil))

  private def record(turn: Turn, mealType: MealType, text: Option[MealText]): Reply =
    val command = LogMeal(turn.accountId, MealInput(mealType, text, at = None), EntrySource.Bot, turn.clientKey)
    answered(turn, nutrition.log(turn.tx, command)): recorded =>
      val entry = recorded.entry
      val line = FoodCopy.saved(entry.mealType, turn.localTime(entry.occurredAt), withNote = entry.text.nonEmpty)
      val rows = List(
        List(
          Choice.callback(Labels.Undo, tokens.direct(CaptureActions.FoodUndo, entry.id, 0)),
          Choice.openApp(FoodCopy.EditInApp, FoodChat.DiaryRoute)
        )
      )
      turn.show(turn.message("food.saved", List(line), rows))

  private def buttons(turn: Turn): List[List[Choice]] =
    val suggested = mealTimes(turn).suggest(clock.now().atZone(turn.zone).toLocalTime)
    MealType.values.toList
      .map: mealType =>
        val style = if mealType == suggested then ChoiceStyle.Primary else ChoiceStyle.Secondary
        val token = tokens.direct(CaptureActions.FoodLog, turn.accountId, mealType.ordinal.toLong)
        Choice.callback(FoodCopy.label(mealType), token, style)
      .grouped(2)
      .toList

  private def mealTimes(turn: Turn): MealTimes =
    val anchors = turn.tx.reminders.dayProfiles.anchors(turn.accountId).getOrElse(DayAnchors.of(DayProfile.Default))
    MealTimes(anchors.breakfast, anchors.lunch, anchors.dinner)

  private def answered[A](turn: Turn, outcome: Either[MealRefusal, A])(reply: A => Reply): Reply =
    outcome match
      case Right(value)  => reply(value)
      case Left(refusal) => turn.toast(refusalText(refusal))

  private def refusalText(refusal: MealRefusal): String =
    refusal match
      case MealRefusal.UnknownMeal      => FoodCopy.unknown
      case MealRefusal.UndoWindowPassed => FoodCopy.undoWindowPassed
      // The consent was withdrawn, or the account is gone, after the button was sent; the chat never sets a time.
      case MealRefusal.ConsentMissing | MealRefusal.UnknownAccount | MealRefusal.InFuture =>
        ReminderCopy.staleControlToast

private[capture] object FoodChat:

  /** The food diary of the Mini App (MINI_APP section 3.4, S-08). */
  val DiaryRoute: AppRoute = AppRoute("food")
