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
  val CatchUpThreshold: Duration = Duration.ofMinutes(10)

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
      // ---- taken ----
      case (OccurrenceStatus.Pending, OccurrenceEvent.Taken) => take(occ, policy, now)
      case (OccurrenceStatus.Due, OccurrenceEvent.Taken)     => take(occ, policy, now)
      case (OccurrenceStatus.Snoozed, OccurrenceEvent.Taken) => take(occ, policy, now)
      case (OccurrenceStatus.Taken, OccurrenceEvent.Taken)   =>
        Transition.unchanged(occ, Feedback.AlreadyRecorded(occ.takenAt.getOrElse(now)))
      case (OccurrenceStatus.Skipped, OccurrenceEvent.Taken) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectionPrompt))
      case (OccurrenceStatus.Missed, OccurrenceEvent.Taken)    => take(occ, policy, now)
      case (OccurrenceStatus.Unknown, OccurrenceEvent.Taken)   => take(occ, policy, now)
      case (OccurrenceStatus.Cancelled, OccurrenceEvent.Taken) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled))
      // ---- skipped ----
      case (OccurrenceStatus.Pending, OccurrenceEvent.Skipped(reason)) => skip(occ, now, reason)
      case (OccurrenceStatus.Due, OccurrenceEvent.Skipped(reason))     => skip(occ, now, reason)
      case (OccurrenceStatus.Snoozed, OccurrenceEvent.Skipped(reason)) => skip(occ, now, reason)
      case (OccurrenceStatus.Taken, OccurrenceEvent.Skipped(_))        =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.AlreadyResolved))
      case (OccurrenceStatus.Skipped, OccurrenceEvent.Skipped(_)) =>
        Transition.unchanged(occ, Feedback.AlreadyRecorded(occ.skippedAt.getOrElse(now)))
      case (OccurrenceStatus.Missed, OccurrenceEvent.Skipped(reason))  => skip(occ, now, reason)
      case (OccurrenceStatus.Unknown, OccurrenceEvent.Skipped(reason)) => skip(occ, now, reason)
      case (OccurrenceStatus.Cancelled, OccurrenceEvent.Skipped(_))    =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled))
      // ---- snoozed ----
      case (OccurrenceStatus.Pending, OccurrenceEvent.Snoozed(minutes)) => snooze(occ, policy, now, ctx, minutes)
      case (OccurrenceStatus.Due, OccurrenceEvent.Snoozed(minutes))     => snooze(occ, policy, now, ctx, minutes)
      case (OccurrenceStatus.Snoozed, OccurrenceEvent.Snoozed(minutes)) => snooze(occ, policy, now, ctx, minutes)
      case (OccurrenceStatus.Taken, OccurrenceEvent.Snoozed(_))         =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeNotAllowed))
      case (OccurrenceStatus.Skipped, OccurrenceEvent.Snoozed(_)) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeNotAllowed))
      case (OccurrenceStatus.Missed, OccurrenceEvent.Snoozed(_)) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeNotAllowed))
      case (OccurrenceStatus.Unknown, OccurrenceEvent.Snoozed(_)) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeNotAllowed))
      case (OccurrenceStatus.Cancelled, OccurrenceEvent.Snoozed(_)) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled))
      // ---- corrected ----
      case (OccurrenceStatus.Pending, OccurrenceEvent.Corrected(_)) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectOnOpenRow))
      case (OccurrenceStatus.Due, OccurrenceEvent.Corrected(_)) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectOnOpenRow))
      case (OccurrenceStatus.Snoozed, OccurrenceEvent.Corrected(_)) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectOnOpenRow))
      case (OccurrenceStatus.Taken, OccurrenceEvent.Corrected(at))    => correct(occ, now, at)
      case (OccurrenceStatus.Skipped, OccurrenceEvent.Corrected(at))  => correct(occ, now, at)
      case (OccurrenceStatus.Missed, OccurrenceEvent.Corrected(at))   => correct(occ, now, at)
      case (OccurrenceStatus.Unknown, OccurrenceEvent.Corrected(at))  => correct(occ, now, at)
      case (OccurrenceStatus.Cancelled, OccurrenceEvent.Corrected(_)) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled))
      // ---- undo ----
      case (OccurrenceStatus.Pending, OccurrenceEvent.Undo) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.NothingToUndo))
      case (OccurrenceStatus.Due, OccurrenceEvent.Undo)     => undo(occ, policy, now, ctx)
      case (OccurrenceStatus.Snoozed, OccurrenceEvent.Undo) => undo(occ, policy, now, ctx)
      case (OccurrenceStatus.Taken, OccurrenceEvent.Undo)   => undo(occ, policy, now, ctx)
      case (OccurrenceStatus.Skipped, OccurrenceEvent.Undo) => undo(occ, policy, now, ctx)
      case (OccurrenceStatus.Missed, OccurrenceEvent.Undo)  =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.NothingToUndo))
      case (OccurrenceStatus.Unknown, OccurrenceEvent.Undo) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.NothingToUndo))
      case (OccurrenceStatus.Cancelled, OccurrenceEvent.Undo) =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled))
      // ---- note_added never changes status (ADR-012) ----
      case (OccurrenceStatus.Pending, OccurrenceEvent.NoteAdded(note))   => addNote(occ, now, note)
      case (OccurrenceStatus.Due, OccurrenceEvent.NoteAdded(note))       => addNote(occ, now, note)
      case (OccurrenceStatus.Snoozed, OccurrenceEvent.NoteAdded(note))   => addNote(occ, now, note)
      case (OccurrenceStatus.Taken, OccurrenceEvent.NoteAdded(note))     => addNote(occ, now, note)
      case (OccurrenceStatus.Skipped, OccurrenceEvent.NoteAdded(note))   => addNote(occ, now, note)
      case (OccurrenceStatus.Missed, OccurrenceEvent.NoteAdded(note))    => addNote(occ, now, note)
      case (OccurrenceStatus.Unknown, OccurrenceEvent.NoteAdded(note))   => addNote(occ, now, note)
      case (OccurrenceStatus.Cancelled, OccurrenceEvent.NoteAdded(note)) => addNote(occ, now, note)

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
      // Collapse-not-replay (DESIGN.md section 7.5): one repeat carries the cadence to what would have elapsed by
      // `now`; the reminders it stands in for are recorded as one `catch_up_collapsed` action row.
      val seq = math.min(occ.reminderSeq + elapsedRepeats(occ, policy, now), policy.maxReminders)
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
        Feedback.None,
        collapseAction(occ, OccurrenceStatus.Due, now, collapsed = seq - occ.reminderSeq - 1)
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

  /** The initial fire, collapsed (DESIGN.md section 7.5): `reminder_seq` is set to what would have elapsed by `now`
    * (the initial at `due_window_start` plus one per whole `repeatEvery` since, capped at `maxReminders`), so a late
    * fire continues the cadence instead of replaying it; the reminders it stands in for are recorded as one
    * `catch_up_collapsed` action row. On time (elapsed = 1) this is the plain initial reminder.
    */
  private def fireInitial(occ: Occurrence, policy: ReminderPolicy, now: Instant, silent: Boolean): Transition =
    val seq = elapsedCadence(occ, policy, now)
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
      Feedback.None,
      collapseAction(occ, OccurrenceStatus.Due, now, collapsed = seq - 1)
    )

  // --------------------------------------------------------------------------
  // User rows
  // --------------------------------------------------------------------------

  /** Taken from an open or system-resolved row (ADR-012): within the late-log window records `effective_at = now`
    * (`taken_late` past `due_window_end`); beyond it the direct tap becomes the correction flow with an explicit time.
    * A user-resolved row (skipped) is refused above with the correction prompt instead of entering here.
    */
  private def take(occ: Occurrence, policy: ReminderPolicy, now: Instant): Transition =
    if now.isAfter(occ.scheduledFor.plusSeconds(policy.lateLogWindowMinutes.toLong * 60L)) then
      Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectionPrompt))
    else
      val late = now.isAfter(occ.dueWindowEnd)
      val row = occ.copy(
        status = OccurrenceStatus.Taken,
        takenAt = Some(now),
        effectiveAt = Some(now),
        snoozedUntil = None,
        missedAt = None,
        unknownReason = None,
        nextActionAt = None,
        epoch = occ.epoch + 1
      )
      Transition(
        row,
        Some(
          userAction(DoseActionKind.Taken, occ, OccurrenceStatus.Taken, now, effectiveAt = Some(now), takenLate = late)
        ),
        List(DispatchIntent.FinalizeControls(FinalizeReason.Resolved)),
        None,
        Feedback.Recorded(late)
      )

  /** Skipped with a reason code. Allowed from open rows and from system-resolved rows (the missed notice's [Skip]; the
    * user's word resolves an `unknown` row as real evidence). Chain child creation is declared on `Transition` but
    * inert until M7.2.
    */
  private def skip(occ: Occurrence, now: Instant, reason: Option[SkipReason]): Transition =
    val row = occ.copy(
      status = OccurrenceStatus.Skipped,
      skippedAt = Some(now),
      snoozedUntil = None,
      missedAt = None,
      unknownReason = None,
      nextActionAt = None,
      epoch = occ.epoch + 1
    )
    Transition(
      row,
      Some(userAction(DoseActionKind.Skipped, occ, OccurrenceStatus.Skipped, now, reasonCode = reason.map(_.code))),
      List(DispatchIntent.FinalizeControls(FinalizeReason.Resolved)),
      None,
      Feedback.SkippedRecorded
    )

  /** Snooze from due, or from pending via Today (ADR-012): `snoozed_until = max(now, due_window_start) + N`, bounded by
    * the next occurrence minus one minute, `scheduled_for + maxLate`, and the declared-inert chain bound;
    * `miss_deadline := max(miss_deadline, snoozed_until + missAfterSnooze)`;
    * `due_window_end := snoozed_until + onTimeGrace`. Explicit snoozes bypass quiet-hours defer (the wake tick delivers
    * with the silent flag inside quiet hours).
    */
  private def snooze(
      occ: Occurrence,
      policy: ReminderPolicy,
      now: Instant,
      ctx: DecideContext,
      minutes: Int
  ): Transition =
    if occ.snoozeCount >= policy.maxSnoozes then Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeLimitReached))
    else
      val until = snoozedUntil(occ, now, minutes)
      val bound = snoozeBound(occ, policy, ctx.nextOccurrenceScheduledFor, ctx.chainIntervalMinutes)
      if until.isAfter(bound) then Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozePastBound))
      else
        val row = occ.copy(
          status = OccurrenceStatus.Snoozed,
          snoozedUntil = Some(until),
          snoozeCount = occ.snoozeCount + 1,
          missDeadline = maxOf(occ.missDeadline, until.plusSeconds(policy.missAfterSnoozeMinutes.toLong * 60L)),
          dueWindowEnd = until.plusSeconds(policy.onTimeGraceMinutes.toLong * 60L),
          nextActionAt = Some(until),
          epoch = occ.epoch + 1
        )
        Transition(
          row,
          Some(userAction(DoseActionKind.Snoozed, occ, OccurrenceStatus.Snoozed, now)),
          List(DispatchIntent.FinalizeControls(FinalizeReason.Resolved)),
          None,
          Feedback.SnoozedUntil(until)
        )

  /** Correct from any resolved status, with an explicit `effective_at` (ADR-012); recorded as `manually_corrected` so
    * delay statistics can exclude it (DESIGN.md section 7.3).
    */
  private def correct(occ: Occurrence, now: Instant, effectiveAt: Instant): Transition =
    if effectiveAt.isAfter(now) then Transition.unchanged(occ, Feedback.Refused(Refusal.InvalidEffectiveAt))
    else
      val row = occ.copy(
        status = OccurrenceStatus.Taken,
        takenAt = Some(now),
        effectiveAt = Some(effectiveAt),
        skippedAt = None,
        missedAt = None,
        unknownReason = None,
        snoozedUntil = None,
        nextActionAt = None,
        epoch = occ.epoch + 1
      )
      Transition(
        row,
        Some(
          userAction(
            DoseActionKind.ManuallyCorrected,
            occ,
            OccurrenceStatus.Taken,
            now,
            effectiveAt = Some(effectiveAt)
          )
        ),
        List(DispatchIntent.FinalizeControls(FinalizeReason.Resolved)),
        None,
        Feedback.Corrected(effectiveAt)
      )

  /** Undo of the latest user action within the window (ADR-012): back to `due` with
    * `next_action_at = now + repeatEvery`, `miss_deadline := max(miss_deadline, now + 2 * repeatEvery)`, `reminder_seq`
    * unchanged, `taken_at/skipped_at/snoozed_until` cleared. Applies to a taken, a skipped, a snooze (status
    * `snoozed`), and a snooze whose wake already fired (status `due` again). Past the window a resolved row is refused
    * with the correction hint (M1.10). The chain-child cascade (`cancel_reason = anchor_undone`) is declared, inert
    * until M7.2. `snooze_count` deliberately stays: ADR-012 clears the instant, not the budget.
    */
  private def undo(occ: Occurrence, policy: ReminderPolicy, now: Instant, ctx: DecideContext): Transition =
    def appliesTo(action: LastUserAction): Boolean =
      (occ.status, action.kind) match
        case (OccurrenceStatus.Taken, DoseActionKind.Taken)     => true
        case (OccurrenceStatus.Skipped, DoseActionKind.Skipped) => true
        case (OccurrenceStatus.Snoozed, DoseActionKind.Snoozed) => true
        case (OccurrenceStatus.Due, DoseActionKind.Snoozed)     => true
        case _                                                  => false
    ctx.lastUndoableAction.filter(appliesTo) match
      case scala.None =>
        Transition.unchanged(occ, Feedback.Refused(Refusal.NothingToUndo))
      case Some(action)
          if Duration.between(action.occurredAt, now).compareTo(Duration.ofMinutes(policy.undoWindowMinutes)) > 0 =>
        val refusal =
          if occ.status == OccurrenceStatus.Taken || occ.status == OccurrenceStatus.Skipped
          then Refusal.UndoWindowPassedOfferCorrect
          else Refusal.UndoWindowPassed
        Transition.unchanged(occ, Feedback.Refused(refusal))
      case Some(action) =>
        val next = now.plusSeconds(policy.repeatEveryMinutes.toLong * 60L)
        val row = occ.copy(
          status = OccurrenceStatus.Due,
          takenAt = None,
          effectiveAt = None,
          skippedAt = None,
          snoozedUntil = None,
          nextActionAt = Some(next),
          missDeadline = maxOf(occ.missDeadline, now.plusSeconds(2L * policy.repeatEveryMinutes.toLong * 60L)),
          epoch = occ.epoch + 1
        )
        Transition(
          row,
          Some(userAction(DoseActionKind.Undone, occ, OccurrenceStatus.Due, now, undoesSeq = Some(action.seq))),
          List(DispatchIntent.FinalizeControls(FinalizeReason.Resolved)),
          None,
          Feedback.Undone(next)
        )

  /** `note_added` never changes status (ADR-012): the row is returned untouched (no epoch increment) and only the
    * action row is appended.
    */
  private def addNote(occ: Occurrence, now: Instant, note: String): Transition =
    Transition(
      occ,
      Some(userAction(DoseActionKind.NoteAdded, occ, occ.status, now, note = Some(note))),
      Nil,
      None,
      Feedback.NoteRecorded
    )

  // --------------------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------------------

  /** The repeat cadence of the system table: `min(now + repeatEvery, miss_deadline)` while more reminders remain, else
    * `miss_deadline`. `maxReminders` counts the initial reminder.
    */
  private def nextCadence(policy: ReminderPolicy, missDeadline: Instant, now: Instant, seq: Int): Instant =
    if seq < policy.maxReminders then minOf(now.plusSeconds(policy.repeatEveryMinutes.toLong * 60L), missDeadline)
    else missDeadline

  /** The cadence reminders that would have fired by `now` for a pending row (DESIGN.md section 7.5): the initial at
    * `due_window_start` plus one per whole `repeatEvery` elapsed since, capped at `maxReminders`. Callers only fire at
    * or after `due_window_start`, so the result is at least 1.
    */
  private def elapsedCadence(occ: Occurrence, policy: ReminderPolicy, now: Instant): Int =
    val everySeconds = policy.repeatEveryMinutes.toLong * 60L
    val since = math.max(0L, Duration.between(occ.dueWindowStart, now).getSeconds)
    val extra = if everySeconds <= 0 then 0L else since / everySeconds
    math.min(1 + extra.toInt, policy.maxReminders)

  /** The repeat reminders that would have fired by `now` for a due row: one at `next_action_at` plus one per whole
    * `repeatEvery` elapsed since (claimed rows have `next_action_at <= now`).
    */
  private def elapsedRepeats(occ: Occurrence, policy: ReminderPolicy, now: Instant): Int =
    val everySeconds = policy.repeatEveryMinutes.toLong * 60L
    val since = math.max(0L, occ.nextActionAt.map(na => Duration.between(na, now).getSeconds).getOrElse(0L))
    val extra = if everySeconds <= 0 then 0L else since / everySeconds
    1 + extra.toInt

  /** The `catch_up_collapsed` row of a collapsed fire (DESIGN.md section 7.5): records how many cadence reminders the
    * one sent reminder stands in for, so the projection fold rebuilds the `reminder_seq` jump. Empty when nothing was
    * collapsed (an on-time fire).
    */
  private def collapseAction(
      occ: Occurrence,
      newStatus: OccurrenceStatus,
      now: Instant,
      collapsed: Int
  ): List[ActionRowIntent] =
    if collapsed <= 0 then Nil
    else
      List(
        ActionRowIntent(
          DoseActionKind.CatchUpCollapsed,
          Actor.System,
          now,
          occ.status,
          newStatus,
          catchUp = true,
          collapsedReminders = collapsed
        )
      )

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

  private def userAction(
      kind: DoseActionKind,
      occ: Occurrence,
      newStatus: OccurrenceStatus,
      now: Instant,
      effectiveAt: Option[Instant] = None,
      reasonCode: Option[String] = None,
      note: Option[String] = None,
      undoesSeq: Option[Int] = None,
      takenLate: Boolean = false
  ): ActionRowIntent =
    ActionRowIntent(
      kind,
      Actor.User,
      now,
      occ.status,
      newStatus,
      effectiveAt = effectiveAt,
      reasonCode = reasonCode,
      note = note,
      undoesSeq = undoesSeq,
      takenLate = takenLate
    )

  private def minOf(a: Instant, b: Instant): Instant = if a.isBefore(b) then a else b
  private def maxOf(a: Instant, b: Instant): Instant = if a.isAfter(b) then a else b
end Decide
