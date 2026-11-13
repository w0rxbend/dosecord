package dosegram.core.application.capture

import dosegram.contracts.CallbackRef
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.application.CommandRegistry
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.CheckInCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.hydration.AmountOutOfBounds
import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.hydration.WaterText
import dosegram.core.domain.hydration.WaterTextParser
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DoseMark
import dosegram.core.ports.Tx

import java.time.ZoneId

/** The capture surface of the chat (BOT_UX sections 5.5, 11, 12.5 and 20.1): `/water`, `/mood`, `/stats`, `/privacy`,
  * their buttons, and the quick-capture text of the water and mood grammars. Every domain is behind its consent: the
  * first use shows the two-stage consent and then runs the input that opened it. Anything else goes to `inner`.
  *
  * The tracking use cases run on the mediator's transaction (ROADMAP G2.1): an entry commits with its inbound event or
  * not at all, and a capture event holds one connection. The client key of an entry is still the inbound event, so a
  * redelivered update records nothing twice (ARCHITECTURE section 11.4).
  */
final class CaptureHandler(codec: CallbackCodec, clock: Clock, parts: CaptureParts, inner: ChatHandler)
    extends ChatHandler:

  import CaptureHandler.*

  private val tokens = Tokens(codec)
  private val consent = ConsentFlow(tokens, clock)
  private val water = WaterChat(parts.hydration, parts.cards, tokens)
  private val mood = MoodChat(parts.mood, parts.cards, tokens)
  private val stats = StatsChat(parts, tokens)

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val answer = principal.accountId match
      case Some(account) =>
        val zone = ZoneId.of(tx.accounts.timezoneOf(account.uuid).getOrElse("UTC"))
        route(Turn(event, account.uuid, zone, source = None, tx))
      case None => unlinked(event)
    answer.getOrElse(inner.handle(event, principal, tx))

  /** Without an account a capture command points at `/start`; a capture button cannot belong to this sender. */
  private def unlinked(event: InboundEvent): Option[Reply] =
    event.body match
      case Inbound.CommandInvoked(name, _, _) if commandNames(name) =>
        Some(Reply(toast = Some(IdentityCopy.createFirst)))
      case Inbound.InteractionSubmitted(ref, _) if isCaptureAction(ref) =>
        Some(Reply(toast = Some(ReminderCopy.staleControlToast)))
      case _ => None

  /** `None` when the event is not a capture input: the next handler takes it. */
  private def route(turn: Turn): Option[Reply] =
    turn.event.body match
      case Inbound.CommandInvoked(name, _, raw) if commandNames(name) => Some(onCommand(turn, name, argumentOf(raw)))
      case Inbound.InteractionSubmitted(ref, source) if isCaptureAction(ref) =>
        Some(onTap(turn.copy(source = source), ref))
      case Inbound.MessageReceived(text, _, _) => onText(turn, text)
      case _                                   => None

  private def onCommand(turn: Turn, name: String, argument: String): Reply =
    name match
      case WaterCommand => waterCommand(turn, argument)
      case MoodCommand  => moodCommand(turn, argument)
      case StatsCommand => statsCommand(turn, argument)
      // The fourth capture command, `/privacy`: `route` lets no other name in.
      case _ => consent.privacy(turn)

  private def statsCommand(turn: Turn, argument: String): Reply =
    StatsChat.periodOf(argument) match
      case Some(period) => consent.guarded(turn, ConsentPurpose.Medication, Pending.Stats)(stats.card(turn, period))
      case None         => turn.toast(StatsCardCopy.usage)

  private def waterCommand(turn: Turn, argument: String): Reply =
    if argument.isEmpty then consent.guarded(turn, ConsentPurpose.Hydration, Pending.WaterCard)(water.card(turn))
    else
      DefaultGlass.inWaterPrompt(argument) match
        case WaterText.Amount(amount) => recordWater(turn, amount.millilitres)(water.typed(turn, amount))
        case WaterText.OutOfBounds    => turn.toast(WaterCopy.tooMuch)
        case WaterText.MissingUnit | WaterText.NotWater => turn.toast(WaterCopy.usage)

  private def moodCommand(turn: Turn, argument: String): Reply =
    if argument.isEmpty then consent.guarded(turn, ConsentPurpose.Mood, Pending.MoodPrompt)(mood.prompt(turn))
    else
      argument.toIntOption.flatMap(Valence.of) match
        case Some(valence) => recordMood(turn, valence)
        case None          => turn.toast(CheckInCopy.usage)

  /** Quick capture (BOT_UX section 11.1 rule 9): text that is an amount of water or `mood <step>` is recorded at once.
    * A bare number is not an amount, so it stays with the next handler.
    */
  private def onText(turn: Turn, text: String): Option[Reply] =
    DefaultGlass.free(text) match
      case WaterText.Amount(amount) => Some(recordWater(turn, amount.millilitres)(water.typed(turn, amount)))
      case WaterText.OutOfBounds    => Some(turn.toast(WaterCopy.tooMuch))
      case WaterText.MissingUnit | WaterText.NotWater =>
        text.trim match
          case MoodLine(step) => step.toIntOption.flatMap(Valence.of).map(recordMood(turn, _))
          case _              => None

  private def recordWater(turn: Turn, millilitres: Int)(record: => Reply): Reply =
    consent.guarded(turn, ConsentPurpose.Hydration, Pending.WaterAdd(millilitres))(record)

  private def recordMood(turn: Turn, valence: Valence): Reply =
    consent.guarded(turn, ConsentPurpose.Mood, Pending.MoodCheckIn(valence.step))(mood.checkIn(turn, valence))

  private def onTap(turn: Turn, ref: CallbackRef): Reply =
    ActionRegistry.byId(ref.actionId) match
      case Some(CaptureActions.WaterUndo)  => water.undo(turn, ref.subject)
      case Some(CaptureActions.MoodEnergy) => mood.energyStep(turn, ref.subject, ref.value)
      case Some(CaptureActions.MoodUndo)   => mood.undo(turn, ref.subject)
      // Every other button names the account it was made for: a tap from another account is a stale button.
      case Some(action) if ref.subject == turn.accountId => onAccountTap(turn, action, ref.value)
      case _                                             => stale(turn)

  private def onAccountTap(turn: Turn, action: ActionEntry, value: Long): Reply =
    action match
      case CaptureActions.ConsentStep =>
        ConsentRequest.of(value).map(consent.step(turn, _)(resume(turn, _))).getOrElse(stale(turn))
      case CaptureActions.ConsentWithdraw => consent.withdraw(turn, value)
      case CaptureActions.WaterAdd        => waterButton(turn, value)
      case CaptureActions.WaterGoal       => water.setGoal(turn, value)
      case CaptureActions.WaterGoalAsk    => water.askGoal(turn, pendingMl = 0)
      case CaptureActions.WaterImage      => imageOf(turn, Pending.WaterImage)(water.image(turn))
      case CaptureActions.MoodCheckIn     => Valence.of(value.toInt).map(recordMood(turn, _)).getOrElse(stale(turn))
      case CaptureActions.StatsShow       => stats.show(turn, value)
      case CaptureActions.StatsImage      => imageOf(turn, Pending.DayImage)(stats.dayImage(turn, dosesToday(turn)))
      case _                              => stale(turn)

  /** A quick-add button. The amount is checked again here: the bounds may have changed since the button was made. */
  private def waterButton(turn: Turn, value: Long): Reply =
    WaterAmount.ofMillilitres(value.min(Int.MaxValue.toLong).toInt) match
      case Right(amount)           => recordWater(turn, amount.millilitres)(water.add(turn, amount))
      case Left(AmountOutOfBounds) => turn.toast(WaterCopy.tooMuch)

  /** The input that opened a consent card, run after the confirming tap. Turning water on asks for the goal first
    * (BUX-WATER-02), with the typed amount kept for after the choice.
    */
  private def resume(turn: Turn, pending: Pending): Reply =
    val fresh = turn.copy(source = None)
    pending match
      case Pending.WaterCard             => water.askGoal(fresh, pendingMl = 0)
      case Pending.WaterAdd(millilitres) => water.askGoal(fresh, millilitres)
      case Pending.MoodPrompt            => mood.prompt(fresh)
      case Pending.MoodCheckIn(step)     => Valence.of(step).map(mood.checkIn(fresh, _)).getOrElse(stale(turn))
      case Pending.Stats                 => stats.card(fresh, StatsPeriod.Last7Days)
      case Pending.WaterImage            => water.image(fresh)
      case Pending.DayImage              => stats.dayImage(fresh, dosesToday(fresh))

  /** A personal image needs the `images_in_chat` consent first (BOT_UX BUX-IMG-04). The consent is asked in a message
    * of its own, so the card the request came from stays as it is.
    */
  private def imageOf(turn: Turn, pending: Pending)(image: => Reply): Reply =
    consent.guarded(turn.copy(source = None), ConsentPurpose.ImagesInChat, pending)(image)

  private def dosesToday(turn: Turn): Option[List[DoseMark]] =
    Option.when(turn.tx.consents.isGranted(turn.accountId, ConsentPurpose.Medication)):
      DoseMarks.of(turn.tx, turn.accountId, clock.now().atZone(turn.zone).toLocalDate)

  private def stale(turn: Turn): Reply = turn.toast(ReminderCopy.staleControlToast)

  private def isCaptureAction(ref: CallbackRef): Boolean =
    ActionRegistry.byId(ref.actionId).exists(CaptureActions.all)

private object CaptureHandler:
  val WaterCommand: String = CommandRegistry.Water.name
  val MoodCommand: String = CommandRegistry.Mood.name
  val StatsCommand: String = CommandRegistry.Stats.name
  val commandNames: Set[String] = CommandRegistry.capture.map(_.name).toSet

  /** Free text is parsed before the consent is known, so a glass has the default size here; [[WaterChat.typed]] sizes
    * it by the user's own preset.
    */
  val DefaultGlass: WaterTextParser = WaterTextParser(HydrationBounds.DefaultPresetsMl.head)

  private val MoodLine = """(?i)mood\s+(\d)""".r

  /** The text after the command name: `/water 300` gives `300`. */
  def argumentOf(raw: String): String = raw.trim.dropWhile(!_.isWhitespace).trim.toLowerCase(java.util.Locale.ROOT)
