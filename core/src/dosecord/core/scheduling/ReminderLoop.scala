package dosecord.core.scheduling

import dosecord.contracts.AccountId
import dosecord.contracts.Actor as ContractsActor
import dosecord.contracts.Event
import dosecord.contracts.LoopDispatch
import dosecord.core.domain.Decide
import dosecord.core.domain.DecideContext
import dosecord.core.domain.DispatchIntent
import dosecord.core.domain.FinalizeReason
import dosecord.core.domain.OccurrenceEvent
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ReminderKind
import dosecord.core.ports.Clock
import dosecord.core.ports.DeliveryTarget
import dosecord.core.ports.DomainEventBus
import dosecord.core.ports.NewDomainEvent
import dosecord.core.ports.NewDoseAction
import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.StoredOccurrence
import dosecord.core.ports.Tx
import dosecord.core.ports.UnitOfWork
import dosecord.core.ports.Wake

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object ReminderLoop:

  /** ADR-004 / DESIGN.md section 7.4: one claim batch is at most 50 rows. */
  val BatchSize = 50

  /** The fallback poll interval; LISTEN `dosecord_wake` shortens it (DESIGN.md section 7.4). */
  val PollInterval: Duration = Duration.ofSeconds(10)

  /** A row that fails its decide/persist unit is postponed by this much and its `error_count` incremented (ROADMAP
    * M1.6: quarantined with `next_action_at = now + 5 min`).
    */
  val QuarantineRetryDelay: Duration = Duration.ofMinutes(5)

  /** The safety net of DESIGN.md section 7.2: the loop materialises any schedule with
    * `materialized_through < now + SafetyNetLagTicks * pollInterval`.
    */
  val SafetyNetLagTicks = 4

  /** Thrown inside a row's savepoint when the guarded write finds the row changed underneath the tick (a user action
    * bumped the epoch mid-tick). The savepoint rolls the tick's writes for that row back; the row is skipped, not
    * quarantined (epoch fencing, DESIGN.md sections 7.3/7.4).
    */
  final case class StaleWrite(occurrenceId: UUID)
      extends RuntimeException(s"occurrence $occurrenceId changed while the tick was in flight")

/** The one reminder loop (ADR-004: "the database is the schedule, one loop, one column"). `tick` claims due rows `FOR
  * UPDATE SKIP LOCKED`, runs the pure `Decide.decide` per row under a savepoint, and writes the state change, the
  * `dose_actions` row, the `domain_events` row and the outbox rows in one transaction with no network calls; a failing
  * row is quarantined instead of stalling the batch, and epoch fencing skips a row a user action changed mid-tick.
  * `run` adds the materialiser safety net, the per-instance heartbeat (its own short transaction, outside the batch),
  * the fallback poll and the LISTEN wake-up. Appended `domain_events` rows are published to the in-process bus after
  * the transaction commits (ADR-003).
  */
final class ReminderLoop(
    uow: UnitOfWork,
    materialiser: Materialiser,
    wake: Wake,
    clock: Clock,
    instance: String,
    role: String = "worker",
    batchSize: Int = ReminderLoop.BatchSize,
    pollInterval: Duration = ReminderLoop.PollInterval,
    events: DomainEventBus = DomainEventBus.noop
):
  import ReminderLoop.*

  /** The instant of the last completed tick (set after the heartbeat commits); the
    * `dosecord_scheduler_tick_age_seconds` gauge (DESIGN.md section 10) reads it.
    */
  val lastTickCompletedAt = new AtomicReference[Instant]()

  /** The process loop (DESIGN.md section 7.4): tick (with the safety net), heartbeat, then wait for a wake or the poll
    * interval. Runs until the calling fork is cancelled (interruption propagates out of `wake.awaitOrTimeout`); callers
    * place it in an Ox fork, so the method itself needs no scope.
    */
  def run(): Unit =
    while true do
      val claimed = tickOnce(clock.now())
      wake.awaitOrTimeout(if claimed >= batchSize then Duration.ZERO else pollInterval)

  /** One iteration of the process loop: the safety net, one `tick`, then the per-instance heartbeat in its own short
    * transaction (DESIGN.md section 7.4: written after the batch, so a long batch cannot hold the heartbeat row).
    */
  def tickOnce(now: Instant): Int =
    materialiseLagging(now)
    val claimed = tick(now)
    uow.transaction(tx => tx.heartbeat.touch(instance, role, clock.now()))
    lastTickCompletedAt.set(clock.now())
    claimed

  /** The materialiser safety net: extend every schedule whose materialised window ends before
    * `now + SafetyNetLagTicks * pollInterval` (DESIGN.md section 7.2). Cheap when nothing lags (the claim returns
    * empty).
    */
  def materialiseLagging(now: Instant): Int =
    materialiser.safetyNet(now.plus(pollInterval.multipliedBy(SafetyNetLagTicks.toLong)))

  /** One batch; returns the number of rows claimed (a full batch tells `run` to re-tick immediately). The `dose_due.v1`
    * rows appended by the batch are published to the in-process bus only after the transaction commits (ADR-003).
    */
  def tick(now: Instant): Int =
    val dueEvents = List.newBuilder[NewDomainEvent]
    val claimed = uow.transaction { tx =>
      // Read once per tick (DESIGN.md section 7.3): the unknown(outage | undelivered) evidence.
      val lastHealthyTick = tx.heartbeat.maxLastTick().getOrElse(Instant.EPOCH)
      val claimed = tx.occurrences.claimDue(now, batchSize)
      claimed.foreach { occ =>
        try dueEvents ++= tx.savepoint(processRow(tx, occ, now, lastHealthyTick))
        catch
          case _: StaleWrite => () // fenced: a user action landed mid-tick and wins; skip the row
          case _: Exception  =>
            tx.occurrences.quarantine(occ.id, now.plus(QuarantineRetryDelay), now)
            ()
      }
      claimed.size
    }
    dueEvents.result().foreach(events.publish)
    claimed

  /** The per-row unit of ADR-004: decide, guarded write, action row, cancel stale queued rows, enqueue the dispatches
    * with the new epoch in the `send_key`, and append `dose_due.v1` on the pending -> due transition — all inside the
    * row's savepoint. Returns the appended domain event (empty unless the transition was pending -> due) so the caller
    * can publish it after the batch transaction commits.
    */
  private def processRow(tx: Tx, occ: StoredOccurrence, now: Instant, lastHealthyTick: Instant): List[NewDomainEvent] =
    val (policy, quiet) = tx.policies.forOccurrence(occ)
    val nextOccurrence =
      occ.scheduleId.flatMap(tx.occurrences.nextScheduledAfter(_, occ.state.scheduledFor))
    val ctx = DecideContext(
      event = OccurrenceEvent.Tick,
      delivered = tx.outbox.deliveredFor(occ.id),
      lastHealthyTick = lastHealthyTick,
      nextOccurrenceScheduledFor = nextOccurrence
    )
    val transition = Decide.decide(occ.state, policy, quiet, now, ctx)
    if transition.row != occ.state then
      val applied =
        tx.occurrences.applyTransition(occ.id, occ.version, occ.state.epoch, transition.row, now)
      if !applied then throw StaleWrite(occ.id)
      val correlationId = UUID.randomUUID().toString
      transition.action.foreach { intent =>
        tx.doseActions.append(NewDoseAction.from(intent, occ.id, occ.accountId, correlationId))
      }
      val newEpoch = transition.row.epoch
      tx.outbox.cancelOlderQueued(occ.id, newEpoch)
      transition.dispatches.foreach(enqueueDispatch(tx, occ, _, newEpoch, now))
      if occ.status == OccurrenceStatus.Pending && transition.row.status == OccurrenceStatus.Due then
        List(appendDoseDue(tx, occ, transition.row.reminderSeq, now, correlationId))
      else Nil
    else Nil

  /** One outbox row per dispatch intent per healthy primary channel; `send_key` carries the new epoch so a restart or a
    * second worker cannot double-enqueue (UNIQUE `send_key` + `ON CONFLICT DO NOTHING`), and `cancelOlderQueued` above
    * has retired the stale-epoch rows (ADR-004).
    */
  private def enqueueDispatch(
      tx: Tx,
      occ: StoredOccurrence,
      intent: DispatchIntent,
      epoch: Int,
      now: Instant
  ): Unit =
    intent match
      case DispatchIntent.Reminder(kind, silent, seq) =>
        perChannel(tx, occ): channel =>
          NewOutboxMessage(
            id = UUID.randomUUID(),
            sendKey = sendKey(occ, epoch, s"s$seq", kindTag(kind), channel),
            op = OutboxOp.Send,
            kind = "reminder",
            vendor = channel.vendor,
            accountId = Some(occ.accountId),
            occurrenceId = Some(occ.id),
            channelId = Some(channel.channelId),
            epoch = Some(epoch),
            payload = LoopDispatch.toJson(LoopDispatch.Reminder(occ.id, channel.chatId, kindTag(kind), seq, silent)),
            importance = "reminder",
            nextAttemptAt = now
          )
      case DispatchIntent.FinalizeControls(reason) =>
        perChannel(tx, occ): channel =>
          NewOutboxMessage(
            id = UUID.randomUUID(),
            sendKey = sendKey(occ, epoch, "s0", s"finalize_${reasonTag(reason)}", channel),
            op = OutboxOp.Finalize,
            kind = "reminder_finalize",
            vendor = channel.vendor,
            accountId = Some(occ.accountId),
            occurrenceId = Some(occ.id),
            channelId = Some(channel.channelId),
            epoch = Some(epoch),
            payload = LoopDispatch.toJson(LoopDispatch.FinalizeControls(occ.id, reasonTag(reason))),
            importance = "reminder",
            nextAttemptAt = now
          )
      case DispatchIntent.MissedNotice(notBefore, silent) =>
        perChannel(tx, occ): channel =>
          NewOutboxMessage(
            id = UUID.randomUUID(),
            sendKey = sendKey(occ, epoch, "s0", "missed", channel),
            op = OutboxOp.Send,
            kind = "missed_notice",
            vendor = channel.vendor,
            accountId = Some(occ.accountId),
            occurrenceId = Some(occ.id),
            channelId = Some(channel.channelId),
            epoch = Some(epoch),
            payload = LoopDispatch.toJson(LoopDispatch.MissedNotice(occ.id, channel.chatId, silent)),
            importance = "reminder",
            nextAttemptAt = notBefore.getOrElse(now)
          )

  private def perChannel(tx: Tx, occ: StoredOccurrence)(msg: DeliveryTarget => NewOutboxMessage): Unit =
    tx.channels.activePrimaryChannels(occ.accountId).foreach(channel => tx.outbox.enqueue(msg(channel)))

  /** `occ:$occurrenceId:e$epoch:s$reminderSeq:k$kind:c$channelId` (DESIGN.md section 7.4). */
  private def sendKey(occ: StoredOccurrence, epoch: Int, seq: String, kind: String, channel: DeliveryTarget): String =
    s"occ:${occ.id}:e$epoch:$seq:k$kind:c${channel.channelId}"

  private def kindTag(kind: ReminderKind): String = kind match
    case ReminderKind.Initial    => "initial"
    case ReminderKind.Repeat     => "repeat"
    case ReminderKind.SnoozeWake => "snooze_wake"

  private def reasonTag(reason: FinalizeReason): String = reason match
    case FinalizeReason.Superseded => "superseded"
    case FinalizeReason.Resolved   => "resolved"

  /** `dosecord.medication.dose_due.v1` (R30): appended exactly once, on the pending -> due transition only (repeats and
    * snooze wakes are due -> due and produce none).
    */
  private def appendDoseDue(
      tx: Tx,
      occ: StoredOccurrence,
      reminderSeq: Int,
      now: Instant,
      correlationId: String
  ): NewDomainEvent =
    val account = AccountId(occ.accountId)
    val event = Event.DoseDue(account, occ.id, reminderSeq)
    val actor = ContractsActor("dosecord", occ.accountId.toString, None, Some(account))
    val envelope = Event.toEnvelopeJson(event, actor, now, Some(correlationId), None)
    val row = NewDomainEvent(
      id = envelope.id.uuid,
      eventType = Event.DoseDueType,
      source = Event.sourceOf(event).value,
      subject = Some(actor.subject),
      accountId = Some(occ.accountId),
      correlationId = Some(correlationId),
      causationId = None,
      actorJson = envelope.actorJson,
      occurredAt = now,
      dataJson = envelope.envelopeJson
    )
    tx.domainEvents.append(row)
    row
