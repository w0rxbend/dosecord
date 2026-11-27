package dosegram.core.scheduling

import dosegram.contracts.OutboundMessage
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.insights.ComposedReport
import dosegram.core.application.insights.ReportComposer
import dosegram.core.application.insights.ReportOutcome
import dosegram.core.application.reminders.PromptMessages
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.domain.EntryDay
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.nutrition.MealNudge
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.DecidedPrompt
import dosegram.core.domain.reminders.DomainProgress
import dosegram.core.domain.reminders.NudgePlan
import dosegram.core.domain.reminders.NudgeSlot
import dosegram.core.domain.reminders.NudgeSlots
import dosegram.core.domain.reminders.PromptDecision
import dosegram.core.domain.reminders.PromptExpiry
import dosegram.core.domain.reminders.PromptInput
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptNotice
import dosegram.core.domain.reminders.PromptOutcome
import dosegram.core.domain.reminders.PromptPlanner
import dosegram.core.domain.reminders.PromptPreset
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.reminders.PromptStatus
import dosegram.core.domain.reminders.SuppressReason
import dosegram.core.domain.sleep.CheckinKind
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DeliveryTarget
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.PromptOccurrence
import dosegram.core.ports.PromptSchedule
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.util.control.NonFatal

/** The prompt loop (ARCHITECTURE section 8.4, REM-12 to REM-17; ADR-023). Each pass takes every plan that is on, one
  * transaction per plan with the plan row locked (`FOR UPDATE SKIP LOCKED`), and decides the slots that fell due since
  * the last pass: the newest one by [[PromptDecision.decide]], every older one as passed over. A decision, the closing
  * of the previous prompt and the outbox rows it needs commit together, and the natural key of a decided slot makes two
  * loops, or a restart, decide it once. No vendor call happens here: the outbox dispatcher sends.
  *
  * A sent prompt replaces the previous open prompt of its kind: that message is deleted, or its send is cancelled if it
  * has not left the queue, so the chat holds at most one open prompt per kind (PRODUCT_SPEC R-SCH-25).
  */
final class PromptLoop(
    uow: UnitOfWork,
    clock: Clock,
    ids: IdSource,
    messages: PromptMessages,
    hydration: HydrationService,
    settings: ReminderSettings,
    reports: ReportComposer,
    onError: (UUID, Throwable) => Unit
):

  /** One pass over every plan that is on; returns the number of plans that recorded a decision. */
  def tick(): Int =
    val plans = uow.transaction(_.reminders.prompts.enabledIds())
    plans.count: id =>
      try uow.transaction(decidePlan(_, id))
      catch
        case NonFatal(e) =>
          onError(id, e)
          false

  /** One pass of the report offers (ROADMAP conflict X-19): each report is offered once per account that was never
    * asked, at its own send time after the first eligible period, never in quiet hours or bedtime mode. The offer
    * leaves the plan in place, off, so it is never asked again.
    */
  def offerReports(): Unit =
    List(PromptPresets.WeeklyReport, PromptPresets.MonthlyReport).foreach: preset =>
      uow
        .transaction(_.reminders.prompts.accountsWithout(preset.kind))
        .foreach: accountId =>
          try uow.transaction(tx => Lang.within(tx.accounts.languageOf(accountId))(offer(tx, accountId, preset)))
          catch case NonFatal(e) => onError(accountId, e)

  private def offer(tx: Tx, accountId: UUID, preset: PromptPreset): Unit =
    val now = clock.now()
    val zone = settings.zone(tx, accountId)
    val profile = settings.profile(tx, accountId)
    val local = now.atZone(zone)
    val dueNow = NudgeSlots
      .on(preset.plan, profile, local.toLocalDate, zone, ReminderSettings.seedOf(accountId))
      .exists(slot => !slot.at.isAfter(now) && now.isBefore(slot.at.plus(PromptLoop.OfferWindow)))
    val asleep =
      openNightAt(tx, accountId).exists(at => Duration.between(at, now).compareTo(PromptDecision.BedtimeHold) < 0)
    if dueNow && !asleep && !profile.quiet.contains(local.toLocalTime) then
      for
        target <- tx.channels.activePrimaryChannels(accountId).find(_.chatId.isDefined)
        message <- reports.offer(
          tx,
          accountId,
          preset.kind,
          local.toLocalDate,
          s"report-offer:${preset.kind.id}:$accountId"
        )
      do
        settings.markOffered(tx, accountId, preset)
        enqueueTo(tx, accountId, target, message, now)

  /** VA-PS-06: a report the user did not follow by any use of the bot or the Mini App within 24 hours is closed as
    * expired, one followed is answered; after three expired reports in a row the report type is turned off and one line
    * says so with Resume. Only reports sent since the plan last took its form count, so Resume starts afresh.
    */
  private def settleReport(tx: Tx, plan: PromptSchedule, now: Instant): Boolean =
    tx.reminders.prompts
      .lastSent(plan.id)
      .filter(open => open.status == PromptStatus.Sent && !open.dueAt.plus(PromptLoop.ReportSeenWithin).isAfter(now))
      .foreach: open =>
        val seen = tx.identities.lastInteraction(plan.accountId).exists(_.isAfter(open.dueAt))
        tx.reminders.prompts.close(open.id, if seen then PromptStatus.Answered else PromptStatus.Expired, now)
    val recent =
      tx.reminders.prompts.recentSent(plan.id, PromptLoop.PauseAfterUnseen).filter(_.dueAt.isAfter(plan.activeFrom))
    val unseen = recent.size == PromptLoop.PauseAfterUnseen && recent.forall(_.status == PromptStatus.Expired)
    if unseen then
      settings.turnOff(tx, plan.accountId, plan.kind)
      tx.channels
        .activePrimaryChannels(plan.accountId)
        .find(_.chatId.isDefined)
        .foreach: target =>
          val notice = reports.pausedNotice(plan.kind, plan.accountId, s"report-paused:${recent.head.id}")
          enqueueTo(tx, plan.accountId, target, notice, now)
    unseen

  private def enqueueTo(tx: Tx, accountId: UUID, target: DeliveryTarget, message: OutboundMessage, now: Instant): Unit =
    tx.outbox.enqueue(
      NewOutboxMessage(
        id = ids.next(now),
        sendKey = message.dedupeKey,
        op = OutboxOp.Send,
        kind = PromptLoop.OutboxKind,
        vendor = target.vendor,
        accountId = Some(accountId),
        channelId = Some(target.channelId),
        payload = OutboundMessage.toJson(message),
        importance = "info",
        nextAttemptAt = now
      )
    )
    ()

  private def decidePlan(tx: Tx, planId: UUID): Boolean =
    tx.reminders.prompts.lockEnabled(planId) match
      case None => false
      // Every prompt, report and notice of the plan is written in the account's language (ROADMAP G2.22).
      case Some(plan) => Lang.within(tx.accounts.languageOf(plan.accountId))(decideLocked(tx, plan))

  private def decideLocked(tx: Tx, plan: PromptSchedule): Boolean =
    val now = clock.now()
    if PromptLoop.isReport(plan.kind) && settleReport(tx, plan, now) then true
    else decideSlots(tx, plan, now)

  private def decideSlots(tx: Tx, plan: PromptSchedule, now: Instant): Boolean =
    val zone = settings.zone(tx, plan.accountId)
    val profile = settings.profile(tx, plan.accountId)
    val due = NudgeSlots.dueBy(plan.plan, profile, zone, ReminderSettings.seedOf(plan.accountId), now)
    val decided = tx.reminders.prompts.decided(plan.id, due.map(_.planDate).distinct)
    val recorded = decided.map(d => (d.planDate, d.slotKey)).toSet
    val split = PromptPlanner.split(due, recorded, plan.activeFrom, now, PromptExpiry.of(plan.plan))
    split.passed.foreach(slot => record(tx, plan, slot, PromptStatus.Suppressed, Some(SuppressReason.CatchUp)))
    split.stale.foreach(slot => record(tx, plan, slot, PromptStatus.Suppressed, Some(SuppressReason.Stale)))
    split.current.foreach(decideSlot(tx, plan, profile, zone, _, decided, now))
    split.passed.nonEmpty || split.stale.nonEmpty || split.current.nonEmpty

  private def decideSlot(
      tx: Tx,
      plan: PromptSchedule,
      profile: DayProfile,
      zone: ZoneId,
      slot: NudgeSlot,
      decided: List[PromptOccurrence],
      now: Instant
  ): Unit =
    val accountId = plan.accountId
    val progress = progressOf(tx, plan, zone, now)
    val accountDay = tx.reminders.prompts.decidedOn(accountId, slot.planDate)
    val channel = tx.channels.activePrimaryChannels(accountId).find(_.chatId.isDefined)
    val input = PromptInput(
      slot = slot,
      kind = plan.kind,
      plan = plan.plan,
      ownCap = plan.ownCap,
      slotsToday = NudgeSlots.on(plan.plan, profile, slot.planDate, zone, ReminderSettings.seedOf(accountId)).size,
      recentSince = PromptExpiry.recentSince(plan.kind, plan.plan, slot),
      window = windowOf(plan.plan, profile, slot, zone),
      quiet = profile.quiet,
      pausedUntil = (plan.pausedUntil.toList ++ tx.reminders.dayProfiles.pausedUntil(accountId)).maxOption,
      progress = progress,
      today =
        decided.filter(_.planDate == slot.planDate).map(d => DecidedPrompt(d.dueAt, d.status, d.reason, d.notice)),
      sharedSentToday = accountDay.count(o => o.budgeted && o.status.wasSent),
      budget = PromptDecision.DefaultBudget,
      lastOtherPromptAt = accountDay.filter(o => o.scheduleId != plan.id && o.status.wasSent).map(_.dueAt).maxOption,
      consented = settings.consented(tx, accountId, plan.kind),
      reachable = channel.isDefined,
      bedtimeSince = openNightAt(tx, accountId)
    )
    val previous = tx.reminders.prompts.lastSent(plan.id)
    // A meal nudge is answered by any entry of its meal on its day, however long before the slot it was made (G8.4).
    val outcome =
      if input.consented && mealLogged(tx, plan, profile, slot) then PromptOutcome.Suppress(SuppressReason.RecentEntry)
      else PromptDecision.decide(input)
    outcome match
      case PromptOutcome.Send(notice) =>
        val target = channel.get // `reachable` held
        if PromptLoop.isReport(plan.kind) then
          val monthlyToday =
            accountDay.exists(o => o.kind == PromptKind.MonthlyReport && o.status.wasSent && o.scheduleId != plan.id)
          val withImages =
            if plan.kind == PromptKind.DailyReport then !periodReportOn(tx, plan, profile, zone, slot, accountDay)
            else !monthlyToday
          sendReport(tx, plan, slot, previous, target, withImages, now)
        // The previous prompt is being sent and has no handle yet, so it could be neither cancelled nor deleted: the
        // slot stays undecided until a later pass (finding 18 of the review of 2026-11-27).
        else if previous.exists(_.sendKey.exists(tx.outbox.sendInFlight)) then ()
        else
          previous.foreach(replace(tx, _, target, progress, now))
          send(tx, plan, SlotOf(slot, profile), notice, target, now)
      case PromptOutcome.Suppress(reason, announce) =>
        record(tx, plan, slot, PromptStatus.Suppressed, Some(reason))
        if announce then
          for
            target <- channel
            open <- previous if open.status == PromptStatus.Sent
          do announcePause(tx, plan, open, target, progress, now)

  private def send(
      tx: Tx,
      plan: PromptSchedule,
      due: SlotOf,
      notice: Option[PromptNotice],
      target: DeliveryTarget,
      now: Instant
  ): Unit =
    val id = ids.next(now)
    val sendKey = s"prompt:$id"
    val inserted = tx.reminders.prompts.insert(
      occurrence(plan, due.slot, id, PromptStatus.Sent, None, now).copy(notice = notice, sendKey = Some(sendKey))
    )
    if inserted then
      val message = render(tx, plan, due, id, notice, sendKey)
      tx.outbox.enqueue(
        NewOutboxMessage(
          id = ids.next(now),
          sendKey = sendKey,
          op = OutboxOp.Send,
          kind = PromptLoop.OutboxKind,
          vendor = target.vendor,
          accountId = Some(plan.accountId),
          channelId = Some(target.channelId),
          payload = OutboundMessage.toJson(message),
          importance = "info",
          nextAttemptAt = now
        )
      )
      ()

  /** A report is composed when it falls due, from the records of its period; a period without records is recorded as
    * passed over and sends nothing (VA-PS-05). A sent report stays in the chat: the next one closes it, never deletes
    * it. Its images go first and its text with the "Stop these" control last, under the prompt's send key.
    */
  private def sendReport(
      tx: Tx,
      plan: PromptSchedule,
      slot: NudgeSlot,
      previous: Option[PromptOccurrence],
      target: DeliveryTarget,
      withImages: Boolean,
      now: Instant
  ): Unit =
    val id = ids.next(now)
    val sendKey = s"prompt:$id"
    val silent = !plan.sound
    val outcome = plan.kind match
      case PromptKind.MonthlyReport => reports.monthly(tx, plan.accountId, slot.planDate, sendKey, silent)
      case PromptKind.DailyReport   => reports.daily(tx, plan.accountId, slot.planDate, sendKey, silent, withImages)
      case _                        => reports.weekly(tx, plan.accountId, slot.planDate, sendKey, silent, withImages)
    outcome match
      case ReportOutcome.NoRecords    => record(tx, plan, slot, PromptStatus.Suppressed, Some(SuppressReason.NoRecords))
      case ReportOutcome.Send(report) =>
        previous.filter(_.status == PromptStatus.Sent).foreach(close(tx, _, DomainProgress.Empty, now))
        val inserted = tx.reminders.prompts.insert(
          occurrence(plan, slot, id, PromptStatus.Sent, None, now).copy(sendKey = Some(sendKey))
        )
        if inserted then enqueueReport(tx, plan, report, target, now)

  private def enqueueReport(
      tx: Tx,
      plan: PromptSchedule,
      report: ComposedReport,
      target: DeliveryTarget,
      now: Instant
  ): Unit =
    // A claimed batch has no order among rows due at one instant, so each image falls due a millisecond before the
    // next and the last before the text: the card or the album always goes first (ReportComposer).
    val messages = report.images :+ report.main
    messages.zipWithIndex.foreach: (message, index) =>
      enqueueTo(tx, plan.accountId, target, message, now.minusMillis((messages.size - 1 - index).toLong))

  private def render(
      tx: Tx,
      plan: PromptSchedule,
      due: SlotOf,
      id: UUID,
      notice: Option[PromptNotice],
      key: String
  ): OutboundMessage =
    val silent = !plan.sound
    plan.kind match
      case PromptKind.Water =>
        val day = hydration.day(tx, plan.accountId, EntryDay.Today).toOption
        val figures = day.flatMap(_.recorded).getOrElse(HydrationDay(0, 0, day.flatMap(_.goalMl)))
        val presets = hydration.presets(tx, plan.accountId).getOrElse(HydrationBounds.DefaultPresetsMl)
        messages.water(id, figures, presets, notice, silent, key)
      case PromptKind.Mood                                   => messages.mood(plan.accountId, id, notice, silent, key)
      case PromptKind.SleepBedtime | PromptKind.SleepMorning => messages.sleep(plan.kind, id, notice, silent, key)
      case PromptKind.Meal => messages.meal(id, MealNudge.mealOf(due.profile, due.slot), notice, silent, key)
      case PromptKind.WeeklyReport | PromptKind.MonthlyReport | PromptKind.DailyReport =>
        throw IllegalStateException(s"a report is composed by sendReport, not rendered: ${plan.kind.id}")

  /** The previous prompt of the plan leaves the chat: an open one is closed (answered when an entry followed it) and
    * its message removed, and so is the pause notice it may have become. A prompt answered by a tap stays: its message
    * is now the outcome of what the user did.
    */
  private def replace(
      tx: Tx,
      previous: PromptOccurrence,
      target: DeliveryTarget,
      progress: DomainProgress,
      now: Instant
  ): Unit =
    previous.status match
      case PromptStatus.Sent =>
        close(tx, previous, progress, now)
        remove(tx, previous, target, now)
      case PromptStatus.Expired if previous.notice.contains(PromptNotice.PausedForToday) =>
        remove(tx, previous, target, now)
      case _ => ()

  private def remove(tx: Tx, previous: PromptOccurrence, target: DeliveryTarget, now: Instant): Unit =
    previous.sendKey.foreach: key =>
      tx.outbox.recordedHandle(target.vendor, key) match
        case Some(handle) => followUp(tx, previous, target, OutboxOp.Delete, s"$key:delete", handle, "{}", now)
        case None         => tx.outbox.cancelQueued(key)

  /** Turns the open prompt into the one-line pause notice: the kind pauses for the day and says so once. */
  private def announcePause(
      tx: Tx,
      plan: PromptSchedule,
      previous: PromptOccurrence,
      target: DeliveryTarget,
      progress: DomainProgress,
      now: Instant
  ): Unit =
    close(tx, previous, progress, now)
    tx.reminders.prompts.markNotice(previous.id, PromptNotice.PausedForToday)
    previous.sendKey.foreach: key =>
      tx.outbox
        .recordedHandle(target.vendor, key)
        .foreach: handle =>
          val noticeKey = s"$key:paused"
          val payload = OutboundMessage.toJson(messages.pausedNotice(plan.kind, noticeKey))
          followUp(tx, previous, target, OutboxOp.Edit, noticeKey, handle, payload, now)

  private def close(tx: Tx, previous: PromptOccurrence, progress: DomainProgress, now: Instant): Unit =
    val answered = progress.lastEntryAt.exists(_.isAfter(previous.dueAt))
    tx.reminders.prompts.close(previous.id, if answered then PromptStatus.Answered else PromptStatus.Expired, now)
    ()

  private def followUp(
      tx: Tx,
      previous: PromptOccurrence,
      target: DeliveryTarget,
      op: OutboxOp,
      sendKey: String,
      handle: String,
      payload: String,
      now: Instant
  ): Unit =
    tx.outbox.enqueue(
      NewOutboxMessage(
        id = ids.next(now),
        sendKey = sendKey,
        op = op,
        kind = PromptLoop.OutboxKind,
        vendor = target.vendor,
        accountId = Some(previous.accountId),
        channelId = Some(target.channelId),
        payload = payload,
        target = Some(handle),
        importance = "info",
        nextAttemptAt = now
      )
    )
    ()

  private def record(
      tx: Tx,
      plan: PromptSchedule,
      slot: NudgeSlot,
      status: PromptStatus,
      reason: Option[SuppressReason]
  ): Unit =
    val now = clock.now()
    tx.reminders.prompts.insert(occurrence(plan, slot, ids.next(now), status, reason, now))
    ()

  private def occurrence(
      plan: PromptSchedule,
      slot: NudgeSlot,
      id: UUID,
      status: PromptStatus,
      reason: Option[SuppressReason],
      now: Instant
  ): PromptOccurrence =
    PromptOccurrence(
      id = id,
      accountId = plan.accountId,
      scheduleId = plan.id,
      kind = plan.kind,
      planDate = slot.planDate,
      slotKey = slot.slotKey,
      dueAt = slot.at,
      status = status,
      reason = reason,
      notice = None,
      sendKey = None,
      budgeted = !plan.ownCap,
      decidedAt = now
    )

  /** Whether a weekly or monthly report is sent, or due, on the morning summary's day: the summary then goes as text,
    * so the chat gets at most one unprompted personal image that day (PRODUCT_SPEC section 3.2, BOT_UX BUX-IMG-03), and
    * the period's report keeps its card (supervisor decision, 2026-11-27).
    */
  private def periodReportOn(
      tx: Tx,
      plan: PromptSchedule,
      profile: DayProfile,
      zone: ZoneId,
      slot: NudgeSlot,
      accountDay: List[PromptOccurrence]
  ): Boolean =
    def periodic(kind: PromptKind): Boolean = PromptLoop.isReport(kind) && kind != PromptKind.DailyReport
    val sent = accountDay.exists(o => periodic(o.kind) && o.status.wasSent)
    val due = tx.reminders.prompts
      .schedules(plan.accountId)
      .filter(other => other.enabled && periodic(other.kind))
      .exists(other =>
        NudgeSlots.on(other.plan, profile, slot.planDate, zone, ReminderSettings.seedOf(plan.accountId)).nonEmpty
      )
    sent || due

  /** What the user recorded in the prompt's domain, read at due time (REM-14). Without the consent nothing is read. */
  private def progressOf(tx: Tx, plan: PromptSchedule, zone: ZoneId, now: Instant): DomainProgress =
    if !settings.consented(tx, plan.accountId, plan.kind) then DomainProgress.Empty
    else
      val today = now.atZone(zone).toLocalDate
      val recent = DateRange.between(today.minusDays(1), today)
      plan.kind match
        case PromptKind.Water =>
          val view = hydration.day(tx, plan.accountId, EntryDay.Today).toOption
          val last = tx.tracking.hydration.listLive(plan.accountId, recent).map(_.occurredAt).maxOption
          DomainProgress(last, view.flatMap(_.recorded).fold(0)(_.totalMl), view.flatMap(_.goalMl))
        case PromptKind.Mood =>
          DomainProgress(tx.tracking.mood.listLive(plan.accountId, recent).map(_.occurredAt).maxOption, 0, None)
        case PromptKind.SleepBedtime =>
          DomainProgress(latestCheckin(tx, plan.accountId, CheckinKind.GoingToSleep), 0, None)
        case PromptKind.SleepMorning =>
          val nights = tx.tracking.sleep.liveEpisodes(plan.accountId, recent).filter(_.kind == SleepKind.Main)
          val wakes = latestCheckin(tx, plan.accountId, CheckinKind.ImUp).toList ++ nights.map(_.interval.wake.at)
          DomainProgress(wakes.maxOption, 0, None)
        case PromptKind.Meal =>
          DomainProgress(tx.tracking.meals.listLive(plan.accountId, recent).map(_.occurredAt).maxOption, 0, None)
        case PromptKind.WeeklyReport | PromptKind.MonthlyReport | PromptKind.DailyReport => DomainProgress.Empty

  /** Whether a meal slot's own meal has an entry on the slot's day. Only a meal plan reads meals, and only with the
    * consent, which the caller checked.
    */
  private def mealLogged(tx: Tx, plan: PromptSchedule, profile: DayProfile, slot: NudgeSlot): Boolean =
    plan.kind == PromptKind.Meal &&
      MealNudge.isLogged(
        MealNudge.mealOf(profile, slot),
        tx.tracking.meals.listLive(plan.accountId, DateRange.single(slot.planDate))
      )

  private def latestCheckin(tx: Tx, accountId: UUID, kind: CheckinKind): Option[Instant] =
    tx.tracking.sleep.latestLiveCheckin(accountId, kind).map(_.occurredAt)

  /** The "Going to sleep" of the night still open, which puts the account in bedtime mode (R-SLP-23): later than the
    * wake of every recorded episode. Read only with the sleep consent; [[PromptDecision]] bounds how long it holds.
    */
  private def openNightAt(tx: Tx, accountId: UUID): Option[Instant] =
    if !tx.consents.isGranted(accountId, ConsentPurpose.Sleep) then None
    else
      val closedUntil = tx.tracking.sleep.latestLiveEpisode(accountId).map(_.interval.wake.at)
      latestCheckin(tx, accountId, CheckinKind.GoingToSleep).filter(at => closedUntil.forall(_.isBefore(at)))

  private def windowOf(
      plan: NudgePlan,
      profile: DayProfile,
      slot: NudgeSlot,
      zone: ZoneId
  ): Option[(Instant, Instant)] =
    plan match
      case NudgePlan.Interval(from, until, _, _, _) =>
        val open = slot.planDate.atTime(from.resolve(profile))
        val closeTime = until.resolve(profile)
        val close =
          if closeTime.isAfter(open.toLocalTime) then slot.planDate.atTime(closeTime)
          else slot.planDate.plusDays(1).atTime(closeTime)
        Some(open.atZone(zone).toInstant -> close.atZone(zone).toInstant)
      case _ => None

/** A slot to send and the day profile it was decided with: a meal nudge names its meal by the profile's anchors. */
private final case class SlotOf(slot: NudgeSlot, profile: DayProfile)

object PromptLoop:

  def isReport(kind: PromptKind): Boolean = PromptKind.Reports(kind)

  /** The outbox kind of every prompt row: a send, the delete of a replaced prompt, the edit to the pause notice. */
  val OutboxKind = "prompt"

  /** How long after its send time a report may still be offered, so a restart late in the day asks nothing. */
  val OfferWindow: Duration = Duration.ofHours(3)

  /** A report followed by a use of the bot or the Mini App within this time was seen (VA-PS-06). */
  val ReportSeenWithin: Duration = Duration.ofHours(24)

  /** Reports in a row not seen after which the report type pauses (VA-PS-06). */
  val PauseAfterUnseen = 3
