package dosecord.core.domain

import dosecord.core.domain.copy.DoseActionKind

import java.time.Duration
import java.time.Instant

/** The events `Decide.decide` handles (DESIGN.md section 7.3). `Tick` is the reminder loop's system event; the rest are
  * user events arriving through buttons, commands or Today (wired in M1.10).
  */
enum OccurrenceEvent:
  case Tick
  case Taken
  case Skipped(reason: Option[SkipReason])
  case Snoozed(minutes: Int)
  case Corrected(effectiveAt: Instant)
  case Undo
  case NoteAdded(note: String)

/** The latest user action row still eligible for undo (taken/skipped/snoozed, not already undone), read by the caller
  * from `dose_actions`; `seq` is the row's per-occurrence sequence, recorded as `undoes_seq` on the undo.
  */
final case class LastUserAction(kind: DoseActionKind, seq: Int, occurredAt: Instant)

/** What `Decide.decide` needs beyond the row, the policy and quiet hours (DESIGN.md section 7.3): the event, whether
  * any outbox row for this occurrence was ever sent (`delivered`), the max of all workers' heartbeats
  * (`lastHealthyTick`, consulted only on `Tick`), the next occurrence of the same schedule (bounds snooze and the
  * quiet-defer fallback), the latest undoable user action, and the chain interval (declared, inert until M7.2).
  */
final case class DecideContext(
    event: OccurrenceEvent,
    delivered: Boolean = false,
    lastHealthyTick: Instant = Instant.EPOCH,
    nextOccurrenceScheduledFor: Option[Instant] = None,
    lastUndoableAction: Option[LastUserAction] = None,
    chainIntervalMinutes: Option[Int] = None
)

/** The pure, exhaustive occurrence FSM (ROADMAP M1.3, DESIGN.md section 7.3, ADR-012). Every `(status, event)` cell is
  * an explicit arm — a transition or a named no-op/refusal — so adding a status or event without an arm fails
  * compilation under `-Werror` (the exhaustivity acceptance). No I/O, no clock: callers pass `now`.
  */
object Decide:

  /** DESIGN.md section 7.5: a dispatch created when `now - next_action_at > 10 min` is a catch-up artifact. */
  private val CatchUpThreshold: Duration = Duration.ofMinutes(10)

  def decide(
      occ: Occurrence,
      policy: ReminderPolicy,
      quiet: QuietHoursContext,
      now: Instant,
      ctx: DecideContext
  ): Transition =
    (occ.status, ctx.event) match
      // ---- System rows: Tick (DESIGN.md section 7.3 table) ----
      case (OccurrenceStatus.Pending, OccurrenceEvent.Tick)   => tickPending(occ, policy, quiet, now, ctx)
      case (OccurrenceStatus.Due, OccurrenceEvent.Tick)       => tickDue(occ, policy, quiet, now, ctx)
      case (OccurrenceStatus.Snoozed, OccurrenceEvent.Tick)   => tickSnoozed(occ, policy, quiet, now, ctx)
      case (OccurrenceStatus.Taken, OccurrenceEvent.Tick)     => Transition.unchanged(occ, Feedback.None)
      case (OccurrenceStatus.Skipped, OccurrenceEvent.Tick)   => Transition.unchanged(occ, Feedback.None)
      case (OccurrenceStatus.Missed, OccurrenceEvent.Tick)    => Transition.unchanged(occ, Feedback.None)
      case (OccurrenceStatus.Unknown, OccurrenceEvent.Tick)   => Transition.unchanged(occ, Feedback.None)
      case (OccurrenceStatus.Cancelled, OccurrenceEvent.Tick) => Transition.unchanged(occ, Feedback.None)
      // ---- User rows: land in the second commit of M1.3 (intermediate green: system rows first) ----
      case (_, OccurrenceEvent.Taken)        => userRowPending(occ)
      case (_, OccurrenceEvent.Skipped(_))   => userRowPending(occ)
      case (_, OccurrenceEvent.Snoozed(_))   => userRowPending(occ)
      case (_, OccurrenceEvent.Corrected(_)) => userRowPending(occ)
      case (_, OccurrenceEvent.Undo)         => userRowPending(occ)
      case (_, OccurrenceEvent.NoteAdded(_)) => userRowPending(occ)

  /** The snooze options the UI may offer (ADR-012): the policy's options filtered so the resulting `snoozed_until`
    * never passes the next occurrence of the schedule minus one minute, `scheduled_for + maxLate`, or (for chains,
    * declared and inert until M7.2) `scheduled_for + interval - 1 min`. Empty when the snooze budget is spent.
    */
  def availableSnoozeOptions(
      occ: Occurrence,
      policy: ReminderPolicy,
      now: Instant,
      nextOccurrenceScheduledFor: Option[Instant],
      chainIntervalMinutes: Option[Int] = None
  ): List[Int] =
    if occ.snoozeCount >= policy.maxSnoozes then Nil
    else
      val bound = snoozeBound(occ, policy, nextOccurrenceScheduledFor, chainIntervalMinutes)
      policy.snoozeOptionsMinutes.filter { minutes =>
        !snoozedUntil(occ, now, minutes).isAfter(bound)
      }

  // --------------------------------------------------------------------------
  // System rows
  // --------------------------------------------------------------------------

  private def tickPending(
      occ: Occurrence,
      policy: ReminderPolicy,
      quiet: QuietHoursContext,
      now: Instant,
      ctx: DecideContext
  ): Transition =
    if now.isBefore(occ.dueWindowStart) then Transition.unchanged(occ, Feedback.None) // claimed early; nothing to do
    else if !now.isBefore(occ.missDeadline) then resolveAtMissDeadline(occ, policy, quiet, now, ctx)
    else
      quiet.quietEndAfter(now) match
        case Some(quietEnd)
            if policy.quietHoursMode == QuietHoursMode.Defer &&
              ctx.nextOccurrenceScheduledFor.forall(quietEnd.isBefore(_)) =>
          // Defer (ADR-012): shift the due window to quiet end, extend the miss deadline, record reminder_deferred.
          val row = occ.copy(
            dueWindowStart = quietEnd,
            missDeadline = maxOf(occ.missDeadline, quietEnd.plusSeconds(policy.missAfterMinutes.toLong * 60L)),
            nextActionAt = Some(quietEnd),
            epoch = occ.epoch + 1
          )
          Transition(
            row,
            Some(systemAction(DoseActionKind.ReminderDeferred, occ, OccurrenceStatus.Pending, now)),
            Nil,
            None,
            Feedback.None
          )
        case Some(_) if policy.quietHoursMode == QuietHoursMode.Defer =>
          // quiet_end would pass the next occurrence: fall back to silent delivery (ADR-012).
          fireInitial(occ, policy, now, silent = true)
        case Some(_) if policy.quietHoursMode == QuietHoursMode.Silent =>
          fireInitial(occ, policy, now, silent = true)
        case _ => fireInitial(occ, policy, now, silent = false)

  private def tickDue(
      occ: Occurrence,
      policy: ReminderPolicy,
      quiet: QuietHoursContext,
      now: Instant,
      ctx: DecideContext
  ): Transition =
    if !now.isBefore(occ.missDeadline) then resolveAtMissDeadline(occ, policy, quiet, now, ctx)
    else if occ.reminderSeq < policy.maxReminders then
      val seq = occ.reminderSeq + 1
      val next = nextCadence(policy, occ.missDeadline, now, seq)
      val silent = policy.quietHoursMode == QuietHoursMode.Silent && quiet.contains(now)
      val row = occ.copy(
        reminderSeq = seq,
        lastRemindedAt = Some(now),
        nextActionAt = Some(next),
        epoch = occ.epoch + 1
      )
      Transition(
        row,
        Some(systemAction(DoseActionKind.ReminderSent, occ, OccurrenceStatus.Due, now)),
        List(
          DispatchIntent.FinalizeControls(FinalizeReason.Superseded),
          DispatchIntent.Reminder(ReminderKind.Repeat, silent, seq)
        ),
        None,
        Feedback.None
      )
    else if occ.nextActionAt.contains(occ.missDeadline) then Transition.unchanged(occ, Feedback.None)
    else
      // Cadence exhausted: wait out the miss deadline (re-point the row so the loop does not re-claim it early).
      Transition(
        occ.copy(nextActionAt = Some(occ.missDeadline), epoch = occ.epoch + 1),
        None,
        Nil,
        None,
        Feedback.None
      )

  private def tickSnoozed(
      occ: Occurrence,
      policy: ReminderPolicy,
      quiet: QuietHoursContext,
      now: Instant,
      ctx: DecideContext
  ): Transition =
    if !now.isBefore(occ.missDeadline) then resolveAtMissDeadline(occ, policy, quiet, now, ctx)
    else if occ.snoozedUntil.exists(until => !now.isBefore(until)) then
      // Snooze wake (ADR-012): not counted in reminder_seq; explicit snoozes bypass quiet defer and are delivered
      // with the silent flag inside quiet hours.
      val next = minOf(now.plusSeconds(policy.repeatEveryMinutes.toLong * 60L), occ.missDeadline)
      val row = occ.copy(
        status = OccurrenceStatus.Due,
        snoozedUntil = None,
        lastRemindedAt = Some(now),
        nextActionAt = Some(next),
        epoch = occ.epoch + 1
      )
      Transition(
        row,
        Some(systemAction(DoseActionKind.ReminderSent, occ, OccurrenceStatus.Due, now)),
        List(DispatchIntent.Reminder(ReminderKind.SnoozeWake, quiet.contains(now), occ.reminderSeq)),
        None,
        Feedback.None
      )
    else Transition.unchanged(occ, Feedback.None)

  /** The miss-deadline rows of the system table (ADR-012): `missed` requires delivery evidence; without it the row
    * becomes `unknown(outage|undelivered)` — but never inside quiet hours, where the decision is postponed to quiet
    * end. The missed *notice* (never the status) is deferred to quiet end inside quiet hours.
    */
  private def resolveAtMissDeadline(
      occ: Occurrence,
      policy: ReminderPolicy,
      quiet: QuietHoursContext,
      now: Instant,
      ctx: DecideContext
  ): Transition =
    if ctx.delivered then
      val quietEnd = quiet.quietEndAfter(now)
      val row = occ.copy(
        status = OccurrenceStatus.Missed,
        missedAt = Some(now),
        snoozedUntil = None,
        nextActionAt = None,
        epoch = occ.epoch + 1
      )
      Transition(
        row,
        Some(systemAction(DoseActionKind.AutoMarkedMissed, occ, OccurrenceStatus.Missed, now)),
        List(
          DispatchIntent.MissedNotice(
            notBefore = quietEnd,
            silent = quietEnd.isEmpty && policy.quietHoursMode == QuietHoursMode.Silent
          )
        ),
        None,
        Feedback.None
      )
    else
      quiet.quietEndAfter(now) match
        case Some(quietEnd) =>
          // Quiet hours never produce unknown: hold the row open until quiet end and decide then.
          Transition(
            occ.copy(nextActionAt = Some(quietEnd), epoch = occ.epoch + 1),
            None,
            Nil,
            None,
            Feedback.None
          )
        case scala.None =>
          val reason =
            if ctx.lastHealthyTick.isBefore(occ.dueWindowStart) then UnknownReason.Outage
            else UnknownReason.Undelivered
          val row = occ.copy(
            status = OccurrenceStatus.Unknown,
            unknownReason = Some(reason),
            snoozedUntil = None,
            nextActionAt = None,
            epoch = occ.epoch + 1
          )
          Transition(
            row,
            Some(systemAction(DoseActionKind.MarkedUnknown, occ, OccurrenceStatus.Unknown, now)),
            Nil,
            None,
            Feedback.None
          )

  private def fireInitial(occ: Occurrence, policy: ReminderPolicy, now: Instant, silent: Boolean): Transition =
    val seq = 1
    val next = nextCadence(policy, occ.missDeadline, now, seq)
    val row = occ.copy(
      status = OccurrenceStatus.Due,
      reminderSeq = seq,
      lastRemindedAt = Some(now),
      nextActionAt = Some(next),
      epoch = occ.epoch + 1
    )
    Transition(
      row,
      Some(systemAction(DoseActionKind.ReminderSent, occ, OccurrenceStatus.Due, now)),
      List(DispatchIntent.Reminder(ReminderKind.Initial, silent, seq)),
      None,
      Feedback.None
    )

  // --------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------

  /** M1.3 intermediate-green placeholder: user rows are implemented in the slice's second commit. */
  private def userRowPending(occ: Occurrence): Transition =
    Transition.unchanged(occ, Feedback.Refused(Refusal.UserRowPending))

  /** The repeat cadence of the system table: `min(now + repeatEvery, miss_deadline)` while more reminders remain, else
    * `miss_deadline`. `maxReminders` counts the initial reminder.
    */
  private def nextCadence(policy: ReminderPolicy, missDeadline: Instant, now: Instant, seq: Int): Instant =
    if seq < policy.maxReminders then minOf(now.plusSeconds(policy.repeatEveryMinutes.toLong * 60L), missDeadline)
    else missDeadline

  private def snoozedUntil(occ: Occurrence, now: Instant, minutes: Int): Instant =
    val base = if now.isBefore(occ.dueWindowStart) then occ.dueWindowStart else now
    base.plusSeconds(minutes.toLong * 60L)

  private def snoozeBound(
      occ: Occurrence,
      policy: ReminderPolicy,
      nextOccurrenceScheduledFor: Option[Instant],
      chainIntervalMinutes: Option[Int]
  ): Instant =
    var bound = occ.scheduledFor.plusSeconds(policy.maxLateMinutes.toLong * 60L)
    nextOccurrenceScheduledFor.foreach(next => bound = minOf(bound, next.minusSeconds(60L)))
    chainIntervalMinutes.foreach { interval =>
      // Declared, inert until M7.2: chains bound snooze at scheduled_for + interval - 1 min (ADR-012).
      bound = minOf(bound, occ.scheduledFor.plusSeconds(interval.toLong * 60L - 60L))
    }
    bound

  private def systemAction(
      kind: DoseActionKind,
      occ: Occurrence,
      newStatus: OccurrenceStatus,
      now: Instant
  ): ActionRowIntent =
    ActionRowIntent(
      kind,
      Actor.System,
      now,
      occ.status,
      newStatus,
      catchUp = occ.nextActionAt.exists(na => Duration.between(na, now).compareTo(CatchUpThreshold) > 0)
    )

  private def minOf(a: Instant, b: Instant): Instant = if a.isBefore(b) then a else b
  private def maxOf(a: Instant, b: Instant): Instant = if a.isAfter(b) then a else b
end Decide
