package dosegram.core.application.capture

import dosegram.contracts.CallbackRef
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.MediaKind
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.application.CommandRegistry
import dosegram.core.application.privacy.PrivacyMessages
import dosegram.core.application.reminders.WaterOffer
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.CheckInCopy
import dosegram.core.domain.copy.FoodCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.SleepCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.hydration.AmountOutOfBounds
import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.hydration.WaterText
import dosegram.core.domain.hydration.WaterTextParser
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.insights.TrendPeriod
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealLine
import dosegram.core.domain.nutrition.MealTextParser
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.sleep.SleepText
import dosegram.core.domain.sleep.SleepTextParser
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DoseMark
import dosegram.core.ports.Tx
import ox.discard

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
  private val consent = ConsentFlow(tokens, clock, PrivacyMessages(codec))
  private val water = WaterChat(parts.hydration, parts.cards, parts.delivery, tokens)
  private val mood = MoodChat(parts.mood, parts.cards, parts.delivery, clock, tokens)
  private val share = ShareChat(parts, tokens)
  private val stats = StatsChat(parts, tokens, share)
  private val sleep = SleepChat(parts.sleep, parts.cards, parts.delivery, clock, tokens, parts.appOpens)
  private val months = MonthAndInsightsChat(parts, clock, tokens)
  private val years = YearChat(parts, clock, tokens)
  private val food = FoodChat(parts.nutrition, parts.cards, parts.delivery, clock, tokens, parts.appOpens)
  private val imports = ImportChat(tokens)
  private val meds = MedsChat(parts, clock, tokens)

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val answer = principal.accountId match
      case Some(account) =>
        val zone = ZoneId.of(tx.accounts.timezoneOf(account.uuid).getOrElse("UTC"))
        route(Turn(event, principal, account.uuid, zone, source = None, tx))
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
      case Inbound.CommandInvoked(name, _, raw) if commandNames(name)        => Some(onCommand(turn, name, raw))
      case Inbound.InteractionSubmitted(ref, source) if isCaptureAction(ref) =>
        Some(onTap(turn.copy(source = source), ref))
      case Inbound.MessageReceived(text, _, _)             => onText(turn, text)
      case Inbound.MediaReceived(MediaKind.Photo, _, _)    => Some(food.photo(turn))
      case Inbound.MediaReceived(MediaKind.Document, _, _) => Some(imports.document(turn))
      case _                                               => None

  private def onCommand(turn: Turn, name: String, raw: String): Reply =
    val argument = argumentOf(raw)
    name match
      case FoodCommand | MealCommand => foodCommand(turn, rawArgumentOf(raw))
      case WaterCommand              => waterCommand(turn, argument)
      case MoodCommand               => moodCommand(turn, argument)
      case StatsCommand              => statsCommand(turn, argument)
      case SleepCommand              => sleepCommand(turn, argument)
      case InsightsCommand           => months.insights(turn)
      case ImportCommand => consent.guarded(turn, ConsentPurpose.Import, Pending.ImportCard)(imports.card(turn))
      // The fourth capture command, `/privacy`: `route` lets no other name in.
      case _ => consent.privacy(turn)

  private def statsCommand(turn: Turn, argument: String): Reply =
    if argument == MonthArgument then
      consent.guarded(turn, ConsentPurpose.Medication, Pending.Stats)(months.month(turn, months.currentIndex(turn)))
    else if argument == YearChat.Argument then
      consent.guarded(turn, ConsentPurpose.Medication, Pending.Stats)(years.year(turn, years.currentYear(turn)))
    else if argument == StatsChat.TrendsArgument then
      consent.guarded(turn, ConsentPurpose.Medication, Pending.Stats)(stats.trends(turn, TrendPeriod.Month))
    else if argument == MedsChat.Argument then
      consent.guarded(turn, ConsentPurpose.Medication, Pending.Stats)(meds.card(turn, MedsChat.DefaultDays))
    else statsPeriod(turn, argument)

  private def statsPeriod(turn: Turn, argument: String): Reply =
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

  private def sleepCommand(turn: Turn, argument: String): Reply =
    if argument.isEmpty then consent.guarded(turn, ConsentPurpose.Sleep, Pending.SleepCard)(sleep.card(turn))
    else
      SleepTextParser.times(argument) match
        case Some((bedtime, wakeTime)) => recordSleep(turn, SleepText.Night(bedtime, wakeTime))
        case None                      => turn.toast(SleepCopy.usage)

  /** A typed night or nap. Without the consent it is held until the confirming tap (BUX-CONS-04), which records it. */
  private def recordSleep(turn: Turn, text: SleepText): Reply =
    if !turn.tx.consents.isGranted(turn.accountId, ConsentPurpose.Sleep) then
      sleep.entryOf(turn, text).foreach(turn.tx.tracking.sleep.hold(turn.accountId, _, clock.now().plus(HeldFor)))
    consent.guarded(turn, ConsentPurpose.Sleep, Pending.SleepTyped)(sleep.typed(turn, text))

  /** `/food` (and `/meal`): the meal buttons, or a meal given as the argument. The words keep the case they were typed
    * in.
    */
  private def foodCommand(turn: Turn, argument: String): Reply =
    if argument.isEmpty then consent.guarded(turn, ConsentPurpose.Nutrition, Pending.FoodCard)(food.card(turn))
    else
      MealTextParser.argument(argument) match
        case MealLine.NotMeal => turn.toast(FoodCopy.usage)
        case line             => recordFood(turn, line)

  /** A typed meal. Without the consent its words are held until the confirming tap (BUX-CONS-04), which records it. */
  private def recordFood(turn: Turn, line: MealLine): Reply =
    line match
      case MealLine.Logged(mealType, words) if !turn.tx.consents.isGranted(turn.accountId, ConsentPurpose.Nutrition) =>
        words.foreach(turn.tx.tracking.meals.hold(turn.accountId, _, clock.now().plus(HeldFor)))
        consent.guarded(turn, ConsentPurpose.Nutrition, Pending.FoodTyped(mealType.ordinal))(food.typed(turn, line))
      case _ => consent.guarded(turn, ConsentPurpose.Nutrition, Pending.FoodCard)(food.typed(turn, line))

  private def foodButton(turn: Turn, mealType: MealType): Reply =
    consent.guarded(turn, ConsentPurpose.Nutrition, Pending.FoodLog(mealType.ordinal))(food.tap(turn, mealType))

  private def moodCommand(turn: Turn, argument: String): Reply =
    if argument.isEmpty then consent.guarded(turn, ConsentPurpose.Mood, Pending.MoodPrompt)(mood.prompt(turn))
    else if HistoryArguments(argument) then
      consent.guarded(turn, ConsentPurpose.Mood, Pending.MoodHistory)(mood.history(turn))
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
          case _              =>
            SleepTextParser.free(text) match
              case SleepText.NotSleep =>
                MealTextParser.free(text) match
                  case MealLine.NotMeal => None
                  case line             => Some(recordFood(turn, line))
              case sleepText => Some(recordSleep(turn, sleepText))

  private def recordWater(turn: Turn, millilitres: Int)(record: => Reply): Reply =
    consent.guarded(turn, ConsentPurpose.Hydration, Pending.WaterAdd(millilitres))(record)

  private def recordMood(turn: Turn, valence: Valence): Reply =
    consent.guarded(turn, ConsentPurpose.Mood, Pending.MoodCheckIn(valence.step))(mood.checkIn(turn, valence))

  /** A step on a mood prompt: one check-in per prompt message. */
  private def moodButton(turn: Turn, valence: Valence): Reply =
    consent.guarded(turn, ConsentPurpose.Mood, Pending.MoodCheckIn(valence.step))(mood.checkInFromPrompt(turn, valence))

  private def onTap(turn: Turn, ref: CallbackRef): Reply =
    ActionRegistry.byId(ref.actionId) match
      case Some(CaptureActions.WaterUndo)         => water.undo(turn, ref.subject)
      case Some(CaptureActions.MoodEnergy)        => mood.energyStep(turn, ref.subject, ref.value)
      case Some(CaptureActions.MoodUndo)          => mood.undo(turn, ref.subject)
      case Some(CaptureActions.SleepRate)         => sleep.rate(turn, ref.subject, ref.value)
      case Some(CaptureActions.SleepUndo)         => sleep.undo(turn, ref.subject)
      case Some(CaptureActions.SleepStartUndo)    => sleep.undoStart(turn, ref.subject)
      case Some(CaptureActions.SleepBedtime)      => sleep.bedtime(turn, ref.subject, ref.value)
      case Some(CaptureActions.SleepAdjust)       => sleep.adjust(turn, ref.subject, ref.value)
      case Some(CaptureActions.SleepAdjustSave)   => sleep.adjustSave(turn, ref.subject, ref.value)
      case Some(CaptureActions.SleepAdjustCancel) => sleep.adjustCancel(turn, ref.subject)
      case Some(CaptureActions.FoodUndo)          => food.undo(turn, ref.subject)
      case Some(CaptureActions.FoodPick)          => food.pick(turn, ref.subject)
      case Some(CaptureActions.FoodEdit)          => food.edit(turn, ref.subject, ref.value)
      case Some(CaptureActions.FoodDelete)        => food.delete(turn, ref.subject)
      case Some(CaptureActions.FoodRestore)       => food.restore(turn, ref.subject)
      // Every other button names the account it was made for: a tap from another account is a stale button.
      case Some(action) if ref.subject == turn.accountId => onAccountTap(turn, action, ref.value)
      case _                                             => stale(turn)

  private def onAccountTap(turn: Turn, action: ActionEntry, value: Long): Reply =
    action match
      case CaptureActions.ConsentStep =>
        ConsentRequest.of(value).map(consentStep(turn, _)).getOrElse(stale(turn))
      case CaptureActions.ConsentWithdraw => consent.withdraw(turn, value)(stop(turn, _))
      case CaptureActions.WaterAdd        => waterButton(turn, value)
      case CaptureActions.WaterGoal       =>
        val reply = water.setGoal(turn, value)
        if WaterChat.plansFollow(value) then withWaterOffer(turn, reply) else reply
      case CaptureActions.WaterGoalAsk    => water.askGoal(turn, pendingMl = 0)
      case CaptureActions.WaterImage      => imageOf(turn, Pending.WaterImage)(water.image(turn))
      case CaptureActions.MoodCheckIn     => Valence.of(value.toInt).map(moodButton(turn, _)).getOrElse(stale(turn))
      case CaptureActions.StatsShow       => stats.show(turn, value)
      case CaptureActions.StatsTrends     => stats.showTrends(turn, value)
      case CaptureActions.SleepStart      => sleep.goingToSleep(turn)
      case CaptureActions.SleepUp         => sleep.wakeUp(turn)
      case CaptureActions.SleepNap        => sleep.nap(turn)
      case CaptureActions.SleepImage      => imageOf(turn, Pending.SleepImage)(sleep.image(turn))
      case CaptureActions.StatsImage      => statsImage(turn, value)
      case CaptureActions.StatsMonth      => months.month(turn, value.toInt)
      case CaptureActions.StatsMonthImage =>
        imageOf(turn, Pending.MonthImage(value.toInt))(months.monthImage(turn, value.toInt))
      case CaptureActions.StatsYear      => years.year(turn, value.toInt)
      case CaptureActions.StatsYearImage =>
        imageOf(turn, Pending.YearImage(value.toInt))(years.yearImage(turn, value.toInt))
      case CaptureActions.PrivacyOpen     => consent.privacy(turn)
      case CaptureActions.InsightsShow    => months.insights(turn)
      case CaptureActions.InsightsImage   => imageOf(turn, Pending.Insights(image = true))(months.insightImage(turn))
      case CaptureActions.InsightsCompare =>
        val fresh = turn.copy(source = None)
        consent.guarded(fresh, ConsentPurpose.CrossDomainPatterns, Pending.Insights(image = false))(
          months.insights(fresh)
        )
      case CaptureActions.FoodLog        => mealOf(value).map(foodButton(turn, _)).getOrElse(stale(turn))
      case CaptureActions.FoodImage      => imageOf(turn, Pending.FoodImage)(food.image(turn))
      case CaptureActions.FoodRecent     => if value == FoodChat.BackToCard then food.card(turn) else food.recent(turn)
      case CaptureActions.StatsShare     => share.picker(turn)
      case CaptureActions.StatsShareSend => share.send(turn.copy(source = None), value)
      case CaptureActions.ImportTemplate => imports.template(turn, value).getOrElse(stale(turn))
      case CaptureActions.StatsMeds      => medsShown(turn)(meds.card(turn, value.toInt))
      case CaptureActions.MoodHistoryImage =>
        moodShown(turn)(imageOf(turn, Pending.MoodHistoryImage)(mood.historyImage(turn)))
      case CaptureActions.StatsMedsImage =>
        MedsChat
          .daysOf(value)
          .fold(stale(turn))(days => medsShown(turn)(imageOf(turn, Pending.MedsImage(days))(meds.image(turn, days))))
      case _ => stale(turn)

  /** A quick-add button. The amount is checked again here: the bounds may have changed since the button was made. */
  private def waterButton(turn: Turn, value: Long): Reply =
    WaterAmount.ofMillilitres(value.min(Int.MaxValue.toLong).toInt) match
      case Right(amount)           => recordWater(turn, amount.millilitres)(water.add(turn, amount))
      case Left(AmountOutOfBounds) => turn.toast(WaterCopy.tooMuch)

  /** "Not now" forgets a typed meal or night held for the consent; every other tap goes on as the card says. */
  private def consentStep(turn: Turn, request: ConsentRequest): Reply =
    (request.stage, request.pending) match
      case (ConsentStage.Decline, Pending.FoodTyped(_)) =>
        turn.tx.tracking.meals.takeHeld(turn.accountId, clock.now()).discard
      case (ConsentStage.Decline, Pending.SleepTyped) =>
        turn.tx.tracking.sleep.takeHeld(turn.accountId, clock.now()).discard
      case _ => ()
    consent.step(turn, request)(resume(turn, _))

  /** The input that opened a consent card, run after the confirming tap. Turning water on asks for the goal first
    * (BUX-WATER-02), with the typed amount kept for after the choice.
    */
  private def resume(turn: Turn, pending: Pending): Reply =
    val fresh = turn.copy(source = None)
    pending match
      case Pending.WaterCard             => water.askGoalBeforePlans(fresh, pendingMl = 0)
      case Pending.WaterAdd(millilitres) => water.askGoalBeforePlans(fresh, millilitres)
      case Pending.MoodPrompt            => mood.prompt(fresh)
      case Pending.MoodCheckIn(step)     => Valence.of(step).map(mood.checkIn(fresh, _)).getOrElse(stale(turn))
      case Pending.MoodHistory           => mood.history(fresh)
      case Pending.MoodHistoryImage      => moodShown(fresh)(mood.historyImage(fresh))
      case Pending.Stats                 => stats.card(fresh, StatsPeriod.Last7Days)
      case Pending.WaterImage            => water.image(fresh)
      case Pending.DayImage              => stats.dayImage(fresh, dosesToday(fresh))
      case Pending.WeekImage             => stats.weekImage(fresh)
      case Pending.WaterWeekImage        => stats.waterWeekImage(fresh)
      case Pending.SleepWeekImage        => stats.sleepWeekImage(fresh)
      case Pending.MoodWeekdayImage      => stats.moodWeekdayImage(fresh)
      case Pending.TrendsImage(days)     => trendsImage(fresh, days.toLong)
      case Pending.Medication(screen)    => openMedication(fresh, screen)
      case Pending.SleepCard             => sleep.card(fresh)
      case Pending.SleepTyped            =>
        turn.tx.tracking.sleep
          .takeHeld(turn.accountId, clock.now())
          .fold(sleep.card(fresh))(sleep.recordTyped(fresh, _))
      case Pending.SleepImage        => sleep.image(fresh)
      case Pending.Insights(image)   => if image then months.insightImage(fresh) else months.insights(fresh)
      case Pending.MonthImage(index) => months.monthImage(fresh, index)
      case Pending.YearImage(year)   => years.yearImage(fresh, year)
      case Pending.FoodCard          => food.card(fresh)
      case Pending.FoodLog(code)     => mealOf(code.toLong).map(food.tap(fresh, _)).getOrElse(stale(turn))
      case Pending.FoodTyped(code)   =>
        val words = turn.tx.tracking.meals.takeHeld(turn.accountId, clock.now())
        mealOf(code.toLong).map(meal => food.typed(fresh, MealLine.Logged(meal, words))).getOrElse(stale(turn))
      case Pending.FoodImage       => food.image(fresh)
      case Pending.ImportCard      => imports.card(fresh)
      case Pending.MedsImage(days) => medsShown(fresh)(meds.image(fresh, days))

  /** After the goal choice that follows the water consent, the water prompts are offered (ADR-023). */
  private def withWaterOffer(turn: Turn, reply: Reply): Reply =
    reply.copy(followUps = reply.followUps :+ WaterOffer.message(codec, turn.accountId, turn.clientKey))

  /** The medication screen the consent was asked for, as its own command answers it. */
  private def openMedication(turn: Turn, screen: MedicationScreen): Reply =
    val command = Inbound.CommandInvoked(screen.command, Map.empty, s"/${screen.command}")
    inner.handle(turn.event.copy(body = command, interaction = None), turn.principal, turn.tx)

  /** What ends with a consent besides the consent itself (PRODUCT_SPEC R-IDC-10). */
  private def stop(turn: Turn, purpose: ConsentPurpose): Unit =
    purpose match
      case ConsentPurpose.Medication => parts.withdrawal.stop(turn.tx, turn.accountId, clock.now())
      case ConsentPurpose.Hydration | ConsentPurpose.Mood | ConsentPurpose.Sleep | ConsentPurpose.CrossDomainPatterns |
          ConsentPurpose.ImagesInChat | ConsentPurpose.Nutrition | ConsentPurpose.Import =>
        ()

  /** `stats.image`: 7 is the week at a glance, 20 to 22 the week cards, 30 and 90 the trends, and every other value the
    * daily summary. The card is drawn only after the images consent, because `image` is by-name.
    */
  private def statsImage(turn: Turn, value: Long): Reply =
    def send(pending: Pending)(image: => Reply): Reply = imageOf(turn, pending)(image)
    if value == StatsChat.WeekImageValue then send(Pending.WeekImage)(stats.weekImage(turn))
    else if value == Pending.WaterWeekImage.argument.toLong then
      send(Pending.WaterWeekImage)(stats.waterWeekImage(turn))
    else if value == Pending.SleepWeekImage.argument.toLong then
      send(Pending.SleepWeekImage)(stats.sleepWeekImage(turn))
    else if value == Pending.MoodWeekdayImage.argument.toLong then
      send(Pending.MoodWeekdayImage)(stats.moodWeekdayImage(turn))
    else if StatsChat.trendPeriodOf(value).isDefined then
      send(Pending.TrendsImage(value.toInt))(trendsImage(turn, value))
    else send(Pending.DayImage)(stats.dayImage(turn, dosesToday(turn)))

  /** IMG-23 of 30 or 90 days; any other value is a stale control. */
  private def trendsImage(turn: Turn, days: Long): Reply =
    StatsChat.trendPeriodOf(days).fold(stale(turn))(stats.trendsImage(turn, _))

  /** A button of an old `/stats meds` card shows medications only while the medication consent holds; otherwise it
    * answers with the consent prompt as a new message, as every medication button does (PRODUCT_SPEC R-IDC-9).
    */
  private def medsShown(turn: Turn)(shown: => Reply): Reply =
    consent.guarded(turn.copy(source = None), ConsentPurpose.Medication, Pending.Stats)(shown)

  /** A button of an old `/mood history` card shows check-ins only while the mood consent holds; otherwise it asks for
    * the consent in a message of its own, as `/mood` does.
    */
  private def moodShown(turn: Turn)(shown: => Reply): Reply =
    consent.guarded(turn.copy(source = None), ConsentPurpose.Mood, Pending.MoodHistory)(shown)

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
  val SleepCommand: String = CommandRegistry.Sleep.name
  val InsightsCommand: String = CommandRegistry.Insights.name
  val FoodCommand: String = CommandRegistry.Food.name
  val MealCommand: String = CommandRegistry.Meal.name
  val ImportCommand: String = CommandRegistry.Import.name
  val commandNames: Set[String] =
    (CommandRegistry.capture :+ CommandRegistry.Insights :+ CommandRegistry.Import).map(_.name).toSet + MealCommand

  /** How long a typed meal or night is held while its consent card is open. */
  val HeldFor: java.time.Duration = java.time.Duration.ofMinutes(30)

  /** `/stats month`: the current month so far. */
  val MonthArgument = "month"

  /** Free text is parsed before the consent is known, so a glass has the default size here; [[WaterChat.typed]] sizes
    * it by the user's own preset.
    */
  val DefaultGlass: WaterTextParser = WaterTextParser(HydrationBounds.DefaultPresetsMl.head)

  /** `/mood history`, in either shipped language. */
  val HistoryArguments: Set[String] = Set("history", "історія")

  private val MoodLine = """(?iu)(?:mood|настрій)\s+(\d)""".r

  /** The text after the command name: `/water 300` gives `300`. */
  def argumentOf(raw: String): String = rawArgumentOf(raw).toLowerCase(java.util.Locale.ROOT)

  /** The text after the command name as typed: the words of a meal keep their case. */
  def rawArgumentOf(raw: String): String = raw.trim.dropWhile(!_.isWhitespace).trim

  /** The meal type a button value names: its ordinal. */
  def mealOf(value: Long): Option[MealType] =
    Option.when(value >= 0 && value < MealType.values.length)(MealType.fromOrdinal(value.toInt))
