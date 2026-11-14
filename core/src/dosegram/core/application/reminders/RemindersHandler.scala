package dosegram.core.application.reminders

import dosegram.contracts.Block
import dosegram.contracts.CallbackRef
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.Inline
import dosegram.contracts.MessageHandle
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.hydration.HydrationRefusal
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.application.sleep.SleepInput
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.FoodCopy
import dosegram.core.domain.copy.Formats
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.ReportCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.hydration.AmountOutOfBounds
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.reminders.Anchor
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPreset
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.reminders.PromptStatus
import dosegram.core.ports.Clock
import dosegram.core.ports.PromptOccurrence
import dosegram.core.ports.Tx

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** `/reminders` and the buttons of the prompts (ADR-023, BOT_UX section 14): the hub that lists every prompt kind with
  * its preset and next time, the preset catalogue with a preview before anything is saved, the day profile, the pause
  * of every prompt, and the taps on a prompt, which close it and turn it into its outcome without buttons. Anything
  * else goes to `inner`.
  */
final class RemindersHandler(
    codec: CallbackCodec,
    clock: Clock,
    settings: ReminderSettings,
    capture: CaptureParts,
    inner: ChatHandler
) extends ChatHandler:

  import RemindersHandler.*

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val answer = event.body match
      case Inbound.CommandInvoked(Command, _, raw) =>
        Some(principal.accountId.fold(toast(IdentityCopy.createFirst))(a => command(Ctx(event, a.uuid, None, tx), raw)))
      case Inbound.InteractionSubmitted(ref, source) if handles(ref) =>
        Some(
          principal.accountId.fold(toast(ReminderCopy.staleControlToast))(a => tap(Ctx(event, a.uuid, source, tx), ref))
        )
      case _ => None
    answer.getOrElse(inner.handle(event, principal, tx))

  // ---------- /reminders ----------

  private def command(ctx: Ctx, raw: String): Reply =
    val argument = raw.trim.dropWhile(!_.isWhitespace)
    ReminderCommand.parse(argument) match
      case None                                          => toast(PromptCopy.dayUsage)
      case Some(ReminderCommand.Hub)                     => hub(ctx, None)
      case Some(ReminderCommand.Resume)                  => pauseAll(ctx, None)
      case Some(ReminderCommand.PauseAll(hours))         => pauseAll(ctx, Some(hours))
      case Some(ReminderCommand.WaterCustom(plan))       => applyPreset(ctx, PromptPresets.waterCustom(plan))
      case Some(ReminderCommand.SetAnchor(anchor, time)) =>
        profileSaved(ctx, settings.setAnchor(ctx.tx, ctx.accountId, anchor, time))
      case Some(ReminderCommand.SetWork(days, start, end)) =>
        profileSaved(ctx, settings.setWork(ctx.tx, ctx.accountId, days, start, end))
      case Some(ReminderCommand.SetQuiet(quiet)) =>
        profileSaved(ctx, settings.setQuiet(ctx.tx, ctx.accountId, quiet))

  private def profileSaved(ctx: Ctx, outcome: Either[ReminderRefusal, ?]): Reply =
    outcome match
      case Right(_)                                => dayCard(ctx, Some(PromptCopy.daySaved))
      case Left(ReminderRefusal.InvalidProfile(_)) => toast(PromptCopy.workOrder)
      case Left(_)                                 => toast(ReminderCopy.staleControlToast)

  // ---------- taps ----------

  private def tap(ctx: Ctx, ref: CallbackRef): Reply =
    ActionRegistry.byId(ref.actionId).map(_.name) match
      case Some(name) if name.startsWith("prompt.") => promptTap(ctx, name, ref)
      case Some(_) if ref.subject != ctx.accountId  => toast(ReminderCopy.staleControlToast)
      case Some("reminders.hub")                    => hub(ctx, None)
      case Some("reminders.kind")                   => kindOf(ref.value).fold(stale)(choose(ctx, _))
      case Some("reminders.preview")                => presetOf(ref.value).fold(stale)(preview(ctx, _))
      case Some("reminders.apply")                  => presetOf(ref.value).fold(stale)(applyPreset(ctx, _))
      case Some("reminders.off")                    => kindOf(ref.value).fold(stale)(turnOff(ctx, _))
      case Some("reminders.pause_all")              => pauseAll(ctx, Option.when(ref.value > 0)(ref.value.toInt))
      case Some("reminders.day")                    => dayCard(ctx, None)
      case Some("reminders.report_stop")            => kindOf(ref.value).fold(stale)(stopReport(ctx, _))
      case _                                        => stale

  private def hub(ctx: Ctx, headline: Option[String]): Reply =
    val view = settings.hub(ctx.tx, ctx.accountId)
    val lines = view.lines.map: line =>
      PromptCopy.hubLine(
        line.kind,
        line.plan.map(p => PromptCopy.presetName(p.presetId)),
        line.next.map(at => when(at, view.zone))
      )
    val work = view.profile.work
    val text = headline.toList ++ List(PromptCopy.hubTitle) ++ lines ++ List(
      PromptCopy.medicationLine(view.medicationSchedules),
      PromptCopy.dayLine(
        s"${PromptCopy.days(work.days)} ${PromptCopy.clock(work.start)}-${PromptCopy.clock(work.end)}",
        s"${view.profile.quiet.start.value}-${view.profile.quiet.end.value}"
      )
    ) ++ view.pausedUntil.toList.flatMap(until =>
      List(PromptCopy.pausedAllUntil(when(until, view.zone)), PromptCopy.pauseAllNote)
    )
    val kinds =
      view.lines.map(line => button(ctx, PromptCopy.kindButton(line.kind), "reminders.kind", line.kind.ordinal))
    val pause =
      if view.pausedUntil.isDefined then List(button(ctx, PromptCopy.Resume, "reminders.pause_all", 0))
      else PauseHours.map(h => button(ctx, PromptCopy.Pause(h), "reminders.pause_all", h))
    show(ctx, "reminders.hub", text, List(kinds, pause, List(button(ctx, PromptCopy.DayProfile, "reminders.day", 0))))

  private def choose(ctx: Ctx, kind: PromptKind): Reply =
    val presets = PromptPresets
      .forKind(kind)
      .map: preset =>
        List(button(ctx, PromptCopy.presetName(preset.id), "reminders.preview", PromptPresets.indexOf(preset)))
    val on = ctx.tx.reminders.prompts.schedule(ctx.accountId, kind).exists(_.enabled)
    val last = (if on then List(button(ctx, PromptCopy.TurnOff, "reminders.off", kind.ordinal)) else Nil) :+
      button(ctx, PromptCopy.Back, "reminders.hub", 0)
    show(ctx, "reminders.choose", List(PromptCopy.choosePreset(kind)), presets :+ last)

  private def preview(ctx: Ctx, preset: PromptPreset): Reply =
    val zone = settings.zone(ctx.tx, ctx.accountId)
    val times = settings.preview(ctx.tx, ctx.accountId, preset).map(slot => when(slot.at, zone))
    show(
      ctx,
      "reminders.preview",
      List(PromptCopy.preview(PromptCopy.presetName(preset.id), times)),
      List(
        List(
          button(ctx, PromptCopy.TurnOn, "reminders.apply", PromptPresets.indexOf(preset)),
          button(ctx, PromptCopy.Back, "reminders.kind", preset.kind.ordinal)
        )
      )
    )

  private def applyPreset(ctx: Ctx, preset: PromptPreset): Reply =
    settings.apply(ctx.tx, ctx.accountId, preset) match
      case Right(_)                             => hub(ctx, Some(PromptCopy.applied(PromptCopy.presetName(preset.id))))
      case Left(ReminderRefusal.ConsentMissing) => toast(PromptCopy.needsConsent)
      case Left(ReminderRefusal.NotWired)       => toast(PromptCopy.notWired)
      case Left(_)                              => stale

  private def turnOff(ctx: Ctx, kind: PromptKind): Reply =
    settings.turnOff(ctx.tx, ctx.accountId, kind)
    hub(ctx, Some(PromptCopy.turnedOff(kind)))

  /** "Stop these" on a scheduled report (VISUALS VA-PS-01): the report's plan is turned off, and the message says so in
    * place of its controls.
    */
  private def stopReport(ctx: Ctx, kind: PromptKind): Reply =
    if kind != PromptKind.WeeklyReport && kind != PromptKind.MonthlyReport then stale
    else
      settings.turnOff(ctx.tx, ctx.accountId, kind)
      show(ctx, "reminders.report_stopped", List(ReportCopy.stopped(kind)), Nil)

  private def pauseAll(ctx: Ctx, hours: Option[Int]): Reply =
    settings.pauseAll(ctx.tx, ctx.accountId, hours)
    hub(ctx, None)

  private def dayCard(ctx: Ctx, headline: Option[String]): Reply =
    val profile = settings.profile(ctx.tx, ctx.accountId)
    val anchors = List(
      "Wake" -> Anchor.Wake,
      "Breakfast" -> Anchor.Breakfast,
      "Lunch" -> Anchor.Lunch,
      "Dinner" -> Anchor.Dinner,
      "Bedtime" -> Anchor.Bedtime
    ).map((name, anchor) => PromptCopy.anchorLine(name, profile.timeOf(anchor)))
    val work = profile.work
    val text = headline.toList ++ List(
      PromptCopy.dayProfile(
        anchors ++ List(
          s"Work: ${PromptCopy.days(work.days)} ${PromptCopy.clock(work.start)}-${PromptCopy.clock(work.end)}",
          s"Quiet hours: ${profile.quiet.start.value}-${profile.quiet.end.value}"
        )
      ),
      PromptCopy.dayProfileHelp
    )
    show(ctx, "reminders.day", text, List(List(button(ctx, PromptCopy.Back, "reminders.hub", 0))))

  // ---------- the buttons of a prompt ----------

  private def promptTap(ctx: Ctx, name: String, ref: CallbackRef): Reply =
    ctx.tx.reminders.prompts.occurrence(ref.subject).filter(_.accountId == ctx.accountId) match
      case None                                         => stale
      case Some(occ) if occ.status != PromptStatus.Sent => toast(PromptCopy.alreadyClosed)
      case Some(occ)                                    =>
        name match
          case "prompt.water_add"   => logWater(ctx, occ, ref.value)
          case "prompt.snooze"      => snooze(ctx, occ, Duration.ofMinutes(ref.value.min(MaxSnoozeMinutes)))
          case "prompt.pause_today" => pauseToday(ctx, occ)
          case "prompt.sleep"       => sleepTap(ctx, occ, ref.value)
          case "prompt.meal"        => mealTap(ctx, occ, ref.value)
          case _                    => stale

  private def logWater(ctx: Ctx, occ: PromptOccurrence, millilitres: Long): Reply =
    WaterAmount.ofMillilitres(millilitres.min(Int.MaxValue.toLong).toInt) match
      case Left(AmountOutOfBounds) => toast(WaterCopy.tooMuch)
      case Right(amount)           =>
        val command = LogWater(ctx.accountId, amount, EntryDay.Today, EntrySource.Bot, ctx.clientKey)
        capture.hydration.log(ctx.tx, command) match
          case Left(HydrationRefusal.DailyLimitReached(limit)) => toast(WaterCopy.dailyLimit(limit))
          case Left(_)                                         => stale
          case Right(logged)                                   =>
            answered(ctx, occ)
            val day = logged.day.recorded.getOrElse(HydrationDay(0, 0, logged.day.goalMl))
            outcome(ctx, PromptCopy.logged(amount.millilitres, day))

  private def snooze(ctx: Ctx, occ: PromptOccurrence, by: Duration): Reply =
    val until = clock.now().plus(by)
    settings.pauseKind(ctx.tx, occ.scheduleId, until)
    answered(ctx, occ)
    outcome(ctx, PromptCopy.snoozed(when(until, settings.zone(ctx.tx, ctx.accountId))))

  private def pauseToday(ctx: Ctx, occ: PromptOccurrence): Reply =
    settings.pauseKind(ctx.tx, occ.scheduleId, settings.endOfToday(settings.zone(ctx.tx, ctx.accountId)))
    answered(ctx, occ)
    outcome(ctx, PromptCopy.pausedFor(occ.kind))

  private def sleepTap(ctx: Ctx, occ: PromptOccurrence, value: Long): Reply =
    val input = SleepInput(ctx.accountId, EntrySource.Bot, ctx.clientKey)
    val recorded =
      if value == PromptMessages.SleepGoing then capture.sleep.goingToSleep(ctx.tx, input).isRight
      else capture.sleep.wakeUp(ctx.tx, input).isRight
    if !recorded then stale
    else
      answered(ctx, occ)
      val label = if value == PromptMessages.SleepGoing then PromptCopy.GoingToSleep else PromptCopy.ImUp
      outcome(ctx, PromptCopy.sleepRecorded(label, when(clock.now(), settings.zone(ctx.tx, ctx.accountId))))

  /** One tap on a meal nudge records that meal now and closes the nudge. */
  private def mealTap(ctx: Ctx, occ: PromptOccurrence, value: Long): Reply =
    Option.when(value >= 0 && value < MealType.values.length)(MealType.fromOrdinal(value.toInt)) match
      case None           => stale
      case Some(mealType) =>
        val command = LogMeal(ctx.accountId, MealInput(mealType, None, None), EntrySource.Bot, ctx.clientKey)
        capture.nutrition.log(ctx.tx, command) match
          case Left(_)         => stale
          case Right(recorded) =>
            answered(ctx, occ)
            val at = recorded.entry.occurredAt.atZone(settings.zone(ctx.tx, ctx.accountId)).toLocalTime
            outcome(ctx, FoodCopy.saved(mealType, PromptCopy.clock(at), withNote = false))

  private def answered(ctx: Ctx, occ: PromptOccurrence): Unit =
    ctx.tx.reminders.prompts.close(occ.id, PromptStatus.Answered, clock.now())
    ()

  /** The prompt turns into its outcome; the buttons go, so the prompt cannot be answered twice. */
  private def outcome(ctx: Ctx, text: String): Reply = show(ctx, "prompt.outcome", List(text), Nil)

  // ---------- helpers ----------

  private def show(ctx: Ctx, kind: String, lines: List[String], rows: List[List[Choice]]): Reply =
    val message = OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks =
        rows.filter(_.nonEmpty).zipWithIndex.map((row, i) => Block.Choices(ChoiceSet(s"$kind.$i", choices = row))),
      dedupeKey = s"$kind:${ctx.event.vendor}:${ctx.event.vendorEventId}",
      correlationId = s"${ctx.event.vendor}:${ctx.event.vendorEventId}"
    )
    ctx.source match
      case Some(handle) => Reply(replace = Some(message.copy(replaces = Some(handle))))
      case None         => Reply(followUps = List(message))

  private def button(ctx: Ctx, label: String, action: String, value: Long): Choice =
    Choice.callback(label, token(action, ctx.accountId, value))

  private def token(action: String, subject: UUID, value: Long): String =
    codec.encode(CallbackMode.Direct, entry(action), subject, value).wire

  private def toast(text: String): Reply = Reply(toast = Some(text))
  private def stale: Reply = toast(ReminderCopy.staleControlToast)

object RemindersHandler:

  val Command = "reminders"
  val PauseHours: List[Int] = List(1, 3)
  val MaxSnoozeMinutes = 180L

  private def Clock = DateTimeFormatter.ofPattern("EEE HH:mm", Formats.locale)

  /** A time the user reads: the weekday and the clock time in the account zone. */
  def when(at: Instant, zone: ZoneId): String = Clock.withZone(zone).format(at)

  private def entry(name: String): ActionEntry =
    ActionRegistry.byName(name).getOrElse(throw IllegalStateException(s"action '$name' is missing"))

  private val handled: Set[Int] =
    ActionRegistry.entries.filter(e => e.name.startsWith("prompt.") || e.name.startsWith("reminders.")).map(_.id).toSet

  def handles(ref: CallbackRef): Boolean = handled(ref.actionId)

  def kindOf(value: Long): Option[PromptKind] = PromptKind.values.lift(value.toInt).filter(PromptPresets.wired)

  def presetOf(value: Long): Option[PromptPreset] = PromptPresets.all.lift(value.toInt)

  /** One inbound event of a linked account: the message a tap came from, and the transaction of the event. */
  private final case class Ctx(event: InboundEvent, accountId: UUID, source: Option[MessageHandle], tx: Tx):
    def clientKey: String = s"${event.vendor}:${event.vendorEventId}"
