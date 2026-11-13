package dosegram.core.scheduling

import dosegram.contracts.OutboundMessage
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.reminders.PromptMessages
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.domain.EntryDay
import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationDay
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
import dosegram.core.domain.reminders.PromptStatus
import dosegram.core.domain.reminders.SuppressReason
import dosegram.core.domain.sleep.CheckinKind
import dosegram.core.ports.Clock
import dosegram.core.ports.DeliveryTarget
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.ports.PromptOccurrence
import dosegram.core.ports.PromptSchedule
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

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

  private def decidePlan(tx: Tx, planId: UUID): Boolean =
    tx.reminders.prompts.lockEnabled(planId) match
      case None       => false
      case Some(plan) =>
        val now = clock.now()
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
      recentSince = PromptExpiry.recentSince(plan.plan, slot),
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
      reachable = channel.isDefined
    )
    val previous = tx.reminders.prompts.lastSent(plan.id)
    PromptDecision.decide(input) match
      case PromptOutcome.Send(notice) =>
        val target = channel.get // `reachable` held
        previous.foreach(replace(tx, _, target, progress, now))
        send(tx, plan, slot, notice, target, now)
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
      slot: NudgeSlot,
      notice: Option[PromptNotice],
      target: DeliveryTarget,
      now: Instant
  ): Unit =
    val id = ids.next(now)
    val sendKey = s"prompt:$id"
    val inserted = tx.reminders.prompts.insert(
      occurrence(plan, slot, id, PromptStatus.Sent, None, now).copy(notice = notice, sendKey = Some(sendKey))
    )
    if inserted then
      val message = render(tx, plan, id, notice, sendKey)
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

  private def render(
      tx: Tx,
      plan: PromptSchedule,
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
      case PromptKind.Meal | PromptKind.WeeklyReport | PromptKind.MonthlyReport =>
        throw IllegalStateException(s"no sender for prompt kind ${plan.kind.id}")

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
          DomainProgress(latestCheckin(tx, plan.accountId, CheckinKind.ImUp), 0, None)
        case PromptKind.Meal | PromptKind.WeeklyReport | PromptKind.MonthlyReport => DomainProgress.Empty

  private def latestCheckin(tx: Tx, accountId: UUID, kind: CheckinKind): Option[Instant] =
    tx.tracking.sleep.latestLiveCheckin(accountId, kind).map(_.occurredAt)

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

object PromptLoop:

  /** The outbox kind of every prompt row: a send, the delete of a replaced prompt, the edit to the pause notice. */
  val OutboxKind = "prompt"
