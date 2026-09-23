package dosecord.core.domain

import dosecord.core.domain.copy.DoseActionKind

import java.time.Instant
import java.time.ZoneId

/** ROADMAP M1.3: one named test per `(status, event)` cell of the ADR-012 decide table, plus the branch points inside
  * the system rows. Times are UTC; `t("09:05")` is 2026-03-10 at that wall time.
  */
class DecideSuite extends munit.FunSuite:

  private val utc: ZoneId = ZoneId.of("UTC")
  private val noQuiet: QuietHoursContext = QuietHoursContext.none(utc)
  private def quiet(start: String, end: String): QuietHoursContext = FsmGens.quietZone(start, end)

  private def t(time: String): Instant = Instant.parse(s"2026-03-10T$time:00Z")
  private def t1(time: String): Instant = Instant.parse(s"2026-03-11T$time:00Z")

  private val policy: ReminderPolicy = ReminderPolicy.Default
  private val scheduledFor: Instant = t("09:00")
  // Default derived window: due 09:00, on-time until 10:00, missed at 11:00, late-log until 2026-03-11T09:00.

  private def pendingOcc: Occurrence = Occurrence.scheduled(scheduledFor, policy)
  private def pendingOccAt(sf: Instant): Occurrence = Occurrence.scheduled(sf, policy)

  private def dueOcc(seq: Int = 1, nextActionAt: Option[Instant] = None): Occurrence =
    pendingOcc.copy(
      status = OccurrenceStatus.Due,
      reminderSeq = seq,
      lastRemindedAt = Some(scheduledFor),
      nextActionAt = nextActionAt.orElse(Some(scheduledFor.plusSeconds(600)))
    )

  private def snoozedOcc(until: Instant): Occurrence =
    dueOcc().copy(
      status = OccurrenceStatus.Snoozed,
      snoozedUntil = Some(until),
      snoozeCount = 1,
      nextActionAt = Some(until)
    )

  private def takenOcc(at: Instant): Occurrence =
    dueOcc().copy(
      status = OccurrenceStatus.Taken,
      takenAt = Some(at),
      effectiveAt = Some(at),
      nextActionAt = None
    )

  private def skippedOcc(at: Instant): Occurrence =
    dueOcc().copy(status = OccurrenceStatus.Skipped, skippedAt = Some(at), nextActionAt = None)

  private def missedOcc(at: Instant): Occurrence =
    dueOcc().copy(status = OccurrenceStatus.Missed, missedAt = Some(at), nextActionAt = None)

  private def unknownOcc: Occurrence =
    dueOcc().copy(
      status = OccurrenceStatus.Unknown,
      unknownReason = Some(UnknownReason.Undelivered),
      nextActionAt = None
    )

  private def cancelledOcc: Occurrence =
    pendingOcc.copy(status = OccurrenceStatus.Cancelled, nextActionAt = None)

  private def tick(
      delivered: Boolean = false,
      lastHealthyTick: Instant = Instant.EPOCH,
      nextOccurrence: Option[Instant] = None
  ): DecideContext =
    DecideContext(
      OccurrenceEvent.Tick,
      delivered = delivered,
      lastHealthyTick = lastHealthyTick,
      nextOccurrenceScheduledFor = nextOccurrence
    )

  // --------------------------------------------------------------------------
  // System rows: (pending, tick)
  // --------------------------------------------------------------------------

  test("(pending, tick) before due_window_start: no-op"):
    val occ = pendingOcc
    val result = Decide.decide(occ, policy, noQuiet, t("08:59"), tick())
    assertEquals(result, Transition.unchanged(occ, Feedback.None))

  test("(pending, tick) at due_window_start: due, seq = 1, initial reminder dispatched"):
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("09:00"), tick())
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.reminderSeq, 1)
    assertEquals(result.row.lastRemindedAt, Some(t("09:00")))
    assertEquals(result.row.nextActionAt, Some(t("09:10"))) // min(now + repeatEvery, miss_deadline)
    assertEquals(result.row.epoch, 1)
    assertEquals(result.dispatches, List(DispatchIntent.Reminder(ReminderKind.Initial, silent = false, 1)))
    assertEquals(result.action.map(_.action), Some(DoseActionKind.ReminderSent))
    assertEquals(result.action.map(_.actor), Some(Actor.System))
    assertEquals(result.feedback, Feedback.None)

  test("(pending, tick) with maxReminders = 1: next_action_at is the miss deadline"):
    val single = policy.copy(maxReminders = 1)
    val result = Decide.decide(pendingOcc, single, noQuiet, t("09:00"), tick())
    assertEquals(result.row.reminderSeq, 1)
    assertEquals(result.row.nextActionAt, Some(t("11:00")))

  test("(pending, tick) inside quiet hours, mode deliver: normal delivery"):
    val night = pendingOccAt(t("23:00"))
    val result = Decide.decide(
      night,
      policy.copy(quietHoursMode = QuietHoursMode.Deliver),
      quiet("22:00", "06:00"),
      t("23:00"),
      tick()
    )
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.dispatches, List(DispatchIntent.Reminder(ReminderKind.Initial, silent = false, 1)))

  test("(pending, tick) inside quiet hours, mode silent: the reminder carries the silent flag and counts"):
    val night = pendingOccAt(t("23:00"))
    val result = Decide.decide(
      night,
      policy.copy(quietHoursMode = QuietHoursMode.Silent),
      quiet("22:00", "06:00"),
      t("23:00"),
      tick()
    )
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.reminderSeq, 1)
    assertEquals(result.dispatches, List(DispatchIntent.Reminder(ReminderKind.Initial, silent = true, 1)))

  test("(pending, tick inside quiet defer): reminder_deferred, due window shifted to quiet end"): // named cell
    val night = pendingOccAt(t("23:00"))
    val result = Decide.decide(
      night,
      policy.copy(quietHoursMode = QuietHoursMode.Defer),
      quiet("22:00", "06:00"),
      t("23:00"),
      tick(nextOccurrence = Some(t1("23:00")))
    )
    assertEquals(result.row.status, OccurrenceStatus.Pending)
    assertEquals(result.row.dueWindowStart, t1("06:00"))
    assertEquals(result.row.nextActionAt, Some(t1("06:00")))
    // miss_deadline extended: max(01:00, 06:00 + 120 min)
    assertEquals(result.row.missDeadline, t1("08:00"))
    assertEquals(result.row.epoch, 1)
    assertEquals(result.action.map(_.action), Some(DoseActionKind.ReminderDeferred))
    assertEquals(result.dispatches, Nil)
    assertEquals(result.feedback, Feedback.None)

  test("(pending, tick) quiet defer with no next occurrence: deferred"):
    val night = pendingOccAt(t("23:00"))
    val result = Decide.decide(
      night,
      policy.copy(quietHoursMode = QuietHoursMode.Defer),
      quiet("22:00", "06:00"),
      t("23:00"),
      tick(nextOccurrence = None)
    )
    assertEquals(result.action.map(_.action), Some(DoseActionKind.ReminderDeferred))
    assertEquals(result.row.dueWindowStart, t1("06:00"))

  test("(pending, tick) quiet defer when quiet_end would pass the next occurrence: falls back to silent delivery"):
    val night = pendingOccAt(t("23:00"))
    val result = Decide.decide(
      night,
      policy.copy(quietHoursMode = QuietHoursMode.Defer),
      quiet("22:00", "06:00"),
      t("23:00"),
      tick(nextOccurrence = Some(t1("05:00")))
    )
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.dispatches, List(DispatchIntent.Reminder(ReminderKind.Initial, silent = true, 1)))

  test("(pending, tick) past miss_deadline with delivery evidence: missed, notice dispatched"):
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("11:00"), tick(delivered = true))
    assertEquals(result.row.status, OccurrenceStatus.Missed)
    assertEquals(result.row.missedAt, Some(t("11:00")))
    assertEquals(result.row.nextActionAt, None)
    assertEquals(result.action.map(_.action), Some(DoseActionKind.AutoMarkedMissed))
    assertEquals(result.dispatches, List(DispatchIntent.MissedNotice(notBefore = None, silent = false)))

  test("(pending, tick) past miss_deadline without delivery, workers healthy: unknown(undelivered)"):
    val result =
      Decide.decide(pendingOcc, policy, noQuiet, t("11:00"), tick(delivered = false, lastHealthyTick = t("10:00")))
    assertEquals(result.row.status, OccurrenceStatus.Unknown)
    assertEquals(result.row.unknownReason, Some(UnknownReason.Undelivered))
    assertEquals(result.row.nextActionAt, None)
    assertEquals(result.action.map(_.action), Some(DoseActionKind.MarkedUnknown))

  test("(pending, tick) past miss_deadline without delivery, no healthy tick across the window: unknown(outage)"):
    val result =
      Decide.decide(pendingOcc, policy, noQuiet, t("11:00"), tick(delivered = false, lastHealthyTick = t("08:00")))
    assertEquals(result.row.status, OccurrenceStatus.Unknown)
    assertEquals(result.row.unknownReason, Some(UnknownReason.Outage))

  test("(pending, tick) past miss_deadline without delivery inside quiet hours: postponed, never unknown"):
    val result = Decide.decide(pendingOcc, policy, quiet("10:00", "13:00"), t("11:30"), tick(delivered = false))
    assertEquals(result.row.status, OccurrenceStatus.Pending)
    assertEquals(result.row.nextActionAt, Some(t("13:00")))
    assertEquals(result.action, None)
    assertEquals(result.dispatches, Nil)

  test("(pending, tick) past miss_deadline with delivery inside quiet hours: missed now, notice deferred to quiet end"):
    val result = Decide.decide(
      pendingOcc,
      policy,
      quiet("10:00", "13:00"),
      t("11:30"),
      tick(delivered = true)
    )
    assertEquals(result.row.status, OccurrenceStatus.Missed)
    assertEquals(result.row.missedAt, Some(t("11:30")))
    assertEquals(result.dispatches, List(DispatchIntent.MissedNotice(notBefore = Some(t("13:00")), silent = false)))

  test("(pending, tick) more than 10 min after next_action_at: the action row is flagged catch_up"):
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("09:11"), tick())
    assertEquals(result.action.map(_.catchUp), Some(true))
    val onTime = Decide.decide(pendingOcc, policy, noQuiet, t("09:05"), tick())
    assertEquals(onTime.action.map(_.catchUp), Some(false))

  // ---- M1.8: collapse-not-replay (DESIGN.md section 7.5) ----

  test("(pending, tick) a jump inside the window collapses the cadence: one late reminder at the elapsed seq"):
    // 09:00 due window, repeatEvery 10, maxReminders 3: by 09:25 the initial and two repeats would have fired.
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("09:25"), tick())
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.reminderSeq, 3, "seq is what would have elapsed, so the cadence continues")
    assertEquals(result.row.nextActionAt, Some(t("11:00")), "cadence exhausted: park at the miss deadline")
    assertEquals(result.row.epoch, 1)
    assertEquals(
      result.dispatches,
      List(DispatchIntent.Reminder(ReminderKind.Initial, silent = false, 3)),
      "one late reminder, not a replay of the three"
    )
    assertEquals(result.action.map(_.action), Some(DoseActionKind.ReminderSent))
    assertEquals(result.action.map(_.catchUp), Some(true))
    assertEquals(
      result.additionalActions.map(a => (a.action, a.collapsedReminders)),
      List((DoseActionKind.CatchUpCollapsed, 2)),
      "the two folded reminders are recorded as one catch_up_collapsed row"
    )

  test("(pending, tick) a jump past cadence exhaustion but before the miss deadline fires the last reminder once"):
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("10:30"), tick())
    assertEquals(result.row.reminderSeq, 3, "capped at maxReminders")
    assertEquals(result.row.nextActionAt, Some(t("11:00")))
    assertEquals(result.dispatches, List(DispatchIntent.Reminder(ReminderKind.Initial, silent = false, 3)))
    assertEquals(result.additionalActions.map(_.collapsedReminders), List(2))

  test("(pending, tick) inside one repeat period: the plain initial fire records no collapse"):
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("09:05"), tick())
    assertEquals(result.row.reminderSeq, 1)
    assertEquals(result.additionalActions, Nil)

  test("(due, tick) a jump across repeat points collapses to the elapsed seq with a catch_up_collapsed row"):
    // due seq 1, next repeat at 09:10; by 09:35 three repeats would have fired, capped at maxReminders 3.
    val result = Decide.decide(dueOcc(seq = 1), policy, noQuiet, t("09:35"), tick())
    assertEquals(result.row.reminderSeq, 3)
    assertEquals(result.row.nextActionAt, Some(t("11:00")))
    assertEquals(
      result.dispatches,
      List(
        DispatchIntent.FinalizeControls(FinalizeReason.Superseded),
        DispatchIntent.Reminder(ReminderKind.Repeat, silent = false, 3)
      )
    )
    assertEquals(
      result.additionalActions.map(a => (a.action, a.priorStatus, a.newStatus, a.collapsedReminders)),
      List((DoseActionKind.CatchUpCollapsed, OccurrenceStatus.Due, OccurrenceStatus.Due, 1))
    )

  test("(due, tick) an on-time repeat records no collapse row"):
    val result = Decide.decide(dueOcc(seq = 1), policy, noQuiet, t("09:10"), tick())
    assertEquals(result.row.reminderSeq, 2)
    assertEquals(result.additionalActions, Nil)

  test("(due, tick) a small jump of one repeat period collapses exactly one step"):
    val result = Decide.decide(dueOcc(seq = 1), policy, noQuiet, t("09:15"), tick())
    assertEquals(result.row.reminderSeq, 2)
    assertEquals(result.additionalActions, Nil, "one elapsed repeat is the ordinary +1, not a collapse")

  // --------------------------------------------------------------------------
  // System rows: (due, tick)
  // --------------------------------------------------------------------------

  test("(due, tick) before miss deadline: repeat dispatched, seq + 1, previous reminder finalized"):
    val result = Decide.decide(dueOcc(seq = 1), policy, noQuiet, t("09:10"), tick())
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.reminderSeq, 2)
    assertEquals(result.row.nextActionAt, Some(t("09:20")))
    assertEquals(result.row.epoch, 1)
    assertEquals(
      result.dispatches,
      List(
        DispatchIntent.FinalizeControls(FinalizeReason.Superseded),
        DispatchIntent.Reminder(ReminderKind.Repeat, silent = false, 2)
      )
    )
    assertEquals(result.action.map(_.action), Some(DoseActionKind.ReminderSent))

  test("(due, tick) reaching maxReminders: next_action_at becomes the miss deadline"):
    val result = Decide.decide(dueOcc(seq = 2), policy, noQuiet, t("09:20"), tick())
    assertEquals(result.row.reminderSeq, 3)
    assertEquals(result.row.nextActionAt, Some(t("11:00")))

  test("(due, tick) with cadence exhausted: no-op parked at the miss deadline"):
    val parked = dueOcc(seq = 3, nextActionAt = Some(t("11:00")))
    val result = Decide.decide(parked, policy, noQuiet, t("10:00"), tick())
    assertEquals(result, Transition.unchanged(parked, Feedback.None))

  test("(due, tick) with cadence exhausted but an early next_action_at: re-pointed to the miss deadline"):
    val occ = dueOcc(seq = 3, nextActionAt = Some(t("10:00")))
    val result = Decide.decide(occ, policy, noQuiet, t("10:00"), tick())
    assertEquals(result.row.nextActionAt, Some(t("11:00")))
    assertEquals(result.action, None)
    assertEquals(result.dispatches, Nil)

  test("(due, tick) past miss deadline with delivery: missed"):
    val result = Decide.decide(dueOcc(), policy, noQuiet, t("11:00"), tick(delivered = true))
    assertEquals(result.row.status, OccurrenceStatus.Missed)
    assertEquals(result.action.map(_.action), Some(DoseActionKind.AutoMarkedMissed))

  test("(due, tick) past miss deadline without delivery: unknown"):
    val result =
      Decide.decide(dueOcc(), policy, noQuiet, t("11:00"), tick(delivered = false, lastHealthyTick = t("10:30")))
    assertEquals(result.row.status, OccurrenceStatus.Unknown)
    assertEquals(result.row.unknownReason, Some(UnknownReason.Undelivered))

  test("(due, tick) past miss deadline without delivery inside quiet hours: postponed, stays due"):
    val occ = dueOcc()
    val result = Decide.decide(occ, policy, quiet("10:00", "13:00"), t("11:30"), tick(delivered = false))
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.nextActionAt, Some(t("13:00")))
    assertEquals(result.action, None)

  test("(due, tick) repeat inside quiet hours, mode silent: the repeat carries the silent flag"):
    val night = pendingOccAt(t("23:00")).copy(
      status = OccurrenceStatus.Due,
      reminderSeq = 1,
      lastRemindedAt = Some(t("23:00")),
      nextActionAt = Some(t("23:10"))
    )
    val result = Decide.decide(
      night,
      policy.copy(quietHoursMode = QuietHoursMode.Silent),
      quiet("22:00", "06:00"),
      t("23:10"),
      tick()
    )
    assertEquals(
      result.dispatches,
      List(
        DispatchIntent.FinalizeControls(FinalizeReason.Superseded),
        DispatchIntent.Reminder(ReminderKind.Repeat, silent = true, 2)
      )
    )

  // --------------------------------------------------------------------------
  // System rows: (snoozed, tick)
  // --------------------------------------------------------------------------

  test("(snoozed, tick) before snoozed_until: no-op"):
    val occ = snoozedOcc(t("09:30"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:29"), tick())
    assertEquals(result, Transition.unchanged(occ, Feedback.None))

  test("(snoozed, tick) at snoozed_until: due, snooze reminder not counted in seq"):
    val result = Decide.decide(snoozedOcc(t("09:30")), policy, noQuiet, t("09:30"), tick())
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.snoozedUntil, None)
    assertEquals(result.row.reminderSeq, 1) // unchanged
    assertEquals(result.row.nextActionAt, Some(t("09:40")))
    assertEquals(result.dispatches, List(DispatchIntent.Reminder(ReminderKind.SnoozeWake, silent = false, 1)))
    assertEquals(result.action.map(_.action), Some(DoseActionKind.ReminderSent))

  test("(snoozed, tick) at wake inside quiet hours: delivered with the silent flag"):
    val night = pendingOccAt(t("23:00")).copy(
      status = OccurrenceStatus.Snoozed,
      reminderSeq = 1,
      snoozeCount = 1,
      snoozedUntil = Some(t("23:30")),
      lastRemindedAt = Some(t("23:00")),
      nextActionAt = Some(t("23:30"))
    )
    val result = Decide.decide(night, policy, quiet("22:00", "06:00"), t("23:30"), tick())
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.dispatches, List(DispatchIntent.Reminder(ReminderKind.SnoozeWake, silent = true, 1)))

  test("(snoozed, tick) past miss deadline with delivery: missed"):
    val result = Decide.decide(snoozedOcc(t("09:30")), policy, noQuiet, t("11:00"), tick(delivered = true))
    assertEquals(result.row.status, OccurrenceStatus.Missed)
    assertEquals(result.row.snoozedUntil, None)

  test("(snoozed, tick) past miss deadline without delivery: unknown"):
    val result =
      Decide.decide(snoozedOcc(t("09:30")), policy, noQuiet, t("11:00"), tick(delivered = false, lastHealthyTick = t("10:00")))
    assertEquals(result.row.status, OccurrenceStatus.Unknown)

  // --------------------------------------------------------------------------
  // System rows: ticks on resolved rows are no-ops
  // --------------------------------------------------------------------------

  test("(taken, tick): no-op"):
    val occ = takenOcc(t("09:03"))
    assertEquals(Decide.decide(occ, policy, noQuiet, t("09:10"), tick()), Transition.unchanged(occ, Feedback.None))

  test("(skipped, tick): no-op"):
    val occ = skippedOcc(t("09:03"))
    assertEquals(Decide.decide(occ, policy, noQuiet, t("09:10"), tick()), Transition.unchanged(occ, Feedback.None))

  test("(missed, tick): no-op"):
    val occ = missedOcc(t("11:00"))
    assertEquals(Decide.decide(occ, policy, noQuiet, t("11:10"), tick()), Transition.unchanged(occ, Feedback.None))

  test("(unknown, tick): no-op"):
    val occ = unknownOcc
    assertEquals(Decide.decide(occ, policy, noQuiet, t("11:10"), tick()), Transition.unchanged(occ, Feedback.None))

  test("(cancelled, tick): no-op"):
    val occ = cancelledOcc
    assertEquals(Decide.decide(occ, policy, noQuiet, t("09:10"), tick()), Transition.unchanged(occ, Feedback.None))

  // --------------------------------------------------------------------------
  // User rows: taken
  // --------------------------------------------------------------------------

  private def user(
      event: OccurrenceEvent,
      nextOccurrence: Option[Instant] = None,
      lastAction: Option[LastUserAction] = None
  ): DecideContext =
    DecideContext(event, nextOccurrenceScheduledFor = nextOccurrence, lastUndoableAction = lastAction)

  test("(pending, taken): taken on time via Today"):
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("08:50"), user(OccurrenceEvent.Taken))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.row.takenAt, Some(t("08:50")))
    assertEquals(result.row.effectiveAt, Some(t("08:50")))
    assertEquals(result.row.nextActionAt, None)
    assertEquals(result.feedback, Feedback.Recorded(takenLate = false))
    assertEquals(result.action.map(_.action), Some(DoseActionKind.Taken))
    assertEquals(result.action.map(_.actor), Some(Actor.User))

  test("(due, taken) within the on-time grace: recorded on time"):
    val result = Decide.decide(dueOcc(), policy, noQuiet, t("09:45"), user(OccurrenceEvent.Taken))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.feedback, Feedback.Recorded(takenLate = false))
    assertEquals(result.action.map(_.takenLate), Some(false))
    assertEquals(result.dispatches, List(DispatchIntent.FinalizeControls(FinalizeReason.Resolved)))

  test("(due, taken) after due_window_end: taken_late"):
    val result = Decide.decide(dueOcc(), policy, noQuiet, t("10:30"), user(OccurrenceEvent.Taken))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.feedback, Feedback.Recorded(takenLate = true))
    assertEquals(result.action.map(_.takenLate), Some(true))
    assertEquals(result.action.flatMap(_.effectiveAt), Some(t("10:30")))

  test("(snoozed, taken): taken, snooze cleared"):
    val result = Decide.decide(snoozedOcc(t("09:30")), policy, noQuiet, t("09:20"), user(OccurrenceEvent.Taken))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.row.snoozedUntil, None)

  test("(taken, taken): semantic no-op — Already recorded"): // named cell
    val occ = takenOcc(t("09:03"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Taken))
    assertEquals(result, Transition.unchanged(occ, Feedback.AlreadyRecorded(t("09:03"))))

  test("(skipped, taken): refused, correction prompt"):
    val occ = skippedOcc(t("09:03"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Taken))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectionPrompt)))

  test("(missed, taken) within the late-log window: taken"):
    val result = Decide.decide(missedOcc(t("11:00")), policy, noQuiet, t("12:00"), user(OccurrenceEvent.Taken))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.row.missedAt, None)
    assertEquals(result.action.map(_.action), Some(DoseActionKind.Taken))

  test("(missed, taken, now = +72 h): beyond the late-log window, the tap becomes the correction flow"): // named cell
    val occ = missedOcc(t("11:00"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:00").plusSeconds(72L * 3600L), user(OccurrenceEvent.Taken))
    assertEquals(result.row, occ) // no state change
    assertEquals(result.action, None)
    assertEquals(result.dispatches, Nil)
    assertEquals(result.feedback, Feedback.Refused(Refusal.CorrectionPrompt))

  test("(unknown, taken) within the late-log window: taken, unknown cleared"):
    val result = Decide.decide(unknownOcc, policy, noQuiet, t("12:00"), user(OccurrenceEvent.Taken))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.row.unknownReason, None)

  test("(unknown, taken) beyond the late-log window: correction flow"):
    val occ = unknownOcc
    val result = Decide.decide(occ, policy, noQuiet, t1("10:00"), user(OccurrenceEvent.Taken))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectionPrompt)))

  test("(cancelled, taken): refused"):
    val occ = cancelledOcc
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Taken))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled)))

  // --------------------------------------------------------------------------
  // User rows: skipped
  // --------------------------------------------------------------------------

  test("(pending, skipped): skipped"):
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("08:50"), user(OccurrenceEvent.Skipped(None)))
    assertEquals(result.row.status, OccurrenceStatus.Skipped)
    assertEquals(result.row.skippedAt, Some(t("08:50")))
    assertEquals(result.row.nextActionAt, None)
    assertEquals(result.feedback, Feedback.SkippedRecorded)
    assertEquals(result.action.map(_.action), Some(DoseActionKind.Skipped))

  test("(due, skipped) with a reason code: the code lands on the action row"):
    val result =
      Decide.decide(dueOcc(), policy, noQuiet, t("09:05"), user(OccurrenceEvent.Skipped(Some(SkipReason.RanOut))))
    assertEquals(result.row.status, OccurrenceStatus.Skipped)
    assertEquals(result.action.flatMap(_.reasonCode), Some("ran_out"))
    assertEquals(result.dispatches, List(DispatchIntent.FinalizeControls(FinalizeReason.Resolved)))

  test("(snoozed, skipped): skipped"):
    val result = Decide.decide(snoozedOcc(t("09:30")), policy, noQuiet, t("09:20"), user(OccurrenceEvent.Skipped(None)))
    assertEquals(result.row.status, OccurrenceStatus.Skipped)
    assertEquals(result.row.snoozedUntil, None)

  test("(missed, skipped): the missed notice's [Skip] records skipped"):
    val result = Decide.decide(missedOcc(t("11:00")), policy, noQuiet, t("11:05"), user(OccurrenceEvent.Skipped(None)))
    assertEquals(result.row.status, OccurrenceStatus.Skipped)
    assertEquals(result.row.missedAt, None)

  test("(unknown, skipped): user evidence resolves the row as skipped"):
    val result = Decide.decide(unknownOcc, policy, noQuiet, t("12:00"), user(OccurrenceEvent.Skipped(None)))
    assertEquals(result.row.status, OccurrenceStatus.Skipped)
    assertEquals(result.row.unknownReason, None)

  test("(taken, skipped): refused — already resolved"):
    val occ = takenOcc(t("09:03"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Skipped(None)))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.AlreadyResolved)))

  test("(skipped, skipped): semantic no-op"):
    val occ = skippedOcc(t("09:03"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Skipped(None)))
    assertEquals(result, Transition.unchanged(occ, Feedback.AlreadyRecorded(t("09:03"))))

  test("(cancelled, skipped): refused"):
    val occ = cancelledOcc
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Skipped(None)))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled)))

  // --------------------------------------------------------------------------
  // User rows: snoozed
  // --------------------------------------------------------------------------

  test("(due, snoozed 30m): snoozed_until = now + 30, deadlines extended"):
    val result = Decide.decide(dueOcc(), policy, noQuiet, t("09:05"), user(OccurrenceEvent.Snoozed(30)))
    assertEquals(result.row.status, OccurrenceStatus.Snoozed)
    assertEquals(result.row.snoozedUntil, Some(t("09:35")))
    assertEquals(result.row.snoozeCount, 1)
    // miss_deadline := max(11:00, 09:35 + 30 min); due_window_end := 09:35 + 60 min
    assertEquals(result.row.missDeadline, t("11:00"))
    assertEquals(result.row.dueWindowEnd, t("10:35"))
    assertEquals(result.row.nextActionAt, Some(t("09:35")))
    assertEquals(result.action.map(_.action), Some(DoseActionKind.Snoozed))
    assertEquals(result.feedback, Feedback.SnoozedUntil(t("09:35")))

  test("(due, snoozed) close to the deadline: miss_deadline extends to snoozed_until + missAfterSnooze"):
    val result = Decide.decide(dueOcc(), policy, noQuiet, t("10:45"), user(OccurrenceEvent.Snoozed(30)))
    assertEquals(result.row.snoozedUntil, Some(t("11:15")))
    assertEquals(result.row.missDeadline, t("11:45"))

  test("(pending, snoozed) before due_window_start: anchored at the window start, not at now"):
    val result = Decide.decide(pendingOcc, policy, noQuiet, t("08:00"), user(OccurrenceEvent.Snoozed(10)))
    assertEquals(result.row.status, OccurrenceStatus.Snoozed)
    assertEquals(result.row.snoozedUntil, Some(t("09:10"))) // max(now, due_window_start) + 10

  test("(snoozed, snoozed): re-snooze within budget"):
    val result = Decide.decide(snoozedOcc(t("09:30")), policy, noQuiet, t("09:20"), user(OccurrenceEvent.Snoozed(60)))
    assertEquals(result.row.status, OccurrenceStatus.Snoozed)
    assertEquals(result.row.snoozedUntil, Some(t("10:20")))
    assertEquals(result.row.snoozeCount, 2)

  test("(due, snoozed) past the next occurrence: refused"):
    val occ = dueOcc()
    val result = Decide.decide(
      occ,
      policy,
      noQuiet,
      t("09:05"),
      user(OccurrenceEvent.Snoozed(60), nextOccurrence = Some(t("09:30")))
    )
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozePastBound)))

  test("(due, snoozed) past scheduled_for + maxLate: refused"):
    val occ = dueOcc()
    val result = Decide.decide(occ, policy, noQuiet, t("14:45"), user(OccurrenceEvent.Snoozed(30)))
    // maxLate = 360 min: bound is 15:00; 14:45 + 30 = 15:15 passes it
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozePastBound)))

  test("(due, snoozed) with the snooze budget spent: refused"):
    val occ = dueOcc().copy(snoozeCount = 3)
    val result = Decide.decide(occ, policy, noQuiet, t("09:05"), user(OccurrenceEvent.Snoozed(10)))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeLimitReached)))

  test("(taken, snoozed): refused"):
    val occ = takenOcc(t("09:03"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Snoozed(10)))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeNotAllowed)))

  test("(skipped, snoozed): refused"):
    val occ = skippedOcc(t("09:03"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Snoozed(10)))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeNotAllowed)))

  test("(missed, snoozed): refused"):
    val occ = missedOcc(t("11:00"))
    val result = Decide.decide(occ, policy, noQuiet, t("11:05"), user(OccurrenceEvent.Snoozed(10)))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeNotAllowed)))

  test("(unknown, snoozed): refused"):
    val occ = unknownOcc
    val result = Decide.decide(occ, policy, noQuiet, t("12:00"), user(OccurrenceEvent.Snoozed(10)))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.SnoozeNotAllowed)))

  test("(cancelled, snoozed): refused"):
    val occ = cancelledOcc
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Snoozed(10)))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled)))

  // --------------------------------------------------------------------------
  // User rows: corrected
  // --------------------------------------------------------------------------

  test("(taken, corrected): taken with an explicit effective_at, recorded as manually_corrected"):
    val result =
      Decide.decide(takenOcc(t("09:03")), policy, noQuiet, t("12:00"), user(OccurrenceEvent.Corrected(t("09:00"))))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.row.effectiveAt, Some(t("09:00")))
    assertEquals(result.action.map(_.action), Some(DoseActionKind.ManuallyCorrected))
    assertEquals(result.action.flatMap(_.effectiveAt), Some(t("09:00")))
    assertEquals(result.feedback, Feedback.Corrected(t("09:00")))

  test("(skipped, corrected): becomes taken at the explicit time"):
    val result =
      Decide.decide(skippedOcc(t("09:03")), policy, noQuiet, t("12:00"), user(OccurrenceEvent.Corrected(t("09:00"))))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.row.skippedAt, None)
    assertEquals(result.row.effectiveAt, Some(t("09:00")))

  test("(missed, corrected): becomes taken at the explicit time"):
    val result =
      Decide.decide(missedOcc(t("11:00")), policy, noQuiet, t("12:00"), user(OccurrenceEvent.Corrected(t("09:00"))))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.row.missedAt, None)

  test("(unknown, corrected): becomes taken at the explicit time"):
    val result =
      Decide.decide(unknownOcc, policy, noQuiet, t("12:00"), user(OccurrenceEvent.Corrected(t("09:00"))))
    assertEquals(result.row.status, OccurrenceStatus.Taken)
    assertEquals(result.row.unknownReason, None)

  test("(pending, corrected): refused — correct applies to resolved rows"):
    val occ = pendingOcc
    val result = Decide.decide(occ, policy, noQuiet, t("08:50"), user(OccurrenceEvent.Corrected(t("08:45"))))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectOnOpenRow)))

  test("(due, corrected): refused — correct applies to resolved rows"):
    val occ = dueOcc()
    val result = Decide.decide(occ, policy, noQuiet, t("09:05"), user(OccurrenceEvent.Corrected(t("09:00"))))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectOnOpenRow)))

  test("(snoozed, corrected): refused — correct applies to resolved rows"):
    val occ = snoozedOcc(t("09:30"))
    val result = Decide.decide(occ, policy, noQuiet, t("09:20"), user(OccurrenceEvent.Corrected(t("09:00"))))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.CorrectOnOpenRow)))

  test("(taken, corrected) with a future effective_at: refused"):
    val occ = takenOcc(t("09:03"))
    val result = Decide.decide(occ, policy, noQuiet, t("12:00"), user(OccurrenceEvent.Corrected(t("13:00"))))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.InvalidEffectiveAt)))

  test("(cancelled, corrected): refused"):
    val occ = cancelledOcc
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Corrected(t("09:00"))))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled)))

  // --------------------------------------------------------------------------
  // User rows: undo
  // --------------------------------------------------------------------------

  test("(taken, undo) within 15 min: back to due, resolution cleared, reminder re-armed"):
    val result = Decide.decide(
      takenOcc(t("09:03")),
      policy,
      noQuiet,
      t("09:10"),
      user(OccurrenceEvent.Undo, lastAction = Some(LastUserAction(DoseActionKind.Taken, 4, t("09:03"))))
    )
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.takenAt, None)
    assertEquals(result.row.effectiveAt, None)
    assertEquals(result.row.nextActionAt, Some(t("09:20"))) // now + repeatEvery
    assertEquals(result.row.reminderSeq, 1) // unchanged
    assertEquals(result.action.map(_.action), Some(DoseActionKind.Undone))
    assertEquals(result.action.flatMap(_.undoesSeq), Some(4))
    assertEquals(result.feedback, Feedback.Undone(t("09:20")))

  test("(taken, undo, now = +20 min): refused with the correction hint"): // named cell
    val occ = takenOcc(t("09:03"))
    val result = Decide.decide(
      occ,
      policy,
      noQuiet,
      t("09:23"),
      user(OccurrenceEvent.Undo, lastAction = Some(LastUserAction(DoseActionKind.Taken, 4, t("09:03"))))
    )
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.UndoWindowPassedOfferCorrect)))

  test("(skipped, undo) within 15 min: back to due"):
    val result = Decide.decide(
      skippedOcc(t("09:03")),
      policy,
      noQuiet,
      t("09:10"),
      user(OccurrenceEvent.Undo, lastAction = Some(LastUserAction(DoseActionKind.Skipped, 4, t("09:03"))))
    )
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.skippedAt, None)
    assertEquals(result.row.nextActionAt, Some(t("09:20")))

  test("(skipped, undo) past the window: refused with the correction hint"):
    val occ = skippedOcc(t("09:03"))
    val result = Decide.decide(
      occ,
      policy,
      noQuiet,
      t("09:23"),
      user(OccurrenceEvent.Undo, lastAction = Some(LastUserAction(DoseActionKind.Skipped, 4, t("09:03"))))
    )
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.UndoWindowPassedOfferCorrect)))

  test("(snoozed, undo) within 15 min: back to due, snoozed_until cleared"):
    val result = Decide.decide(
      snoozedOcc(t("09:30")),
      policy,
      noQuiet,
      t("09:20"),
      user(OccurrenceEvent.Undo, lastAction = Some(LastUserAction(DoseActionKind.Snoozed, 4, t("09:10"))))
    )
    assertEquals(result.row.status, OccurrenceStatus.Due)
    assertEquals(result.row.snoozedUntil, None)
    assertEquals(result.row.nextActionAt, Some(t("09:30")))

  test("(snoozed, undo) past the window: refused"):
    val occ = snoozedOcc(t("09:30"))
    val result = Decide.decide(
      occ,
      policy,
      noQuiet,
      t("09:30"),
      user(OccurrenceEvent.Undo, lastAction = Some(LastUserAction(DoseActionKind.Snoozed, 4, t("09:10"))))
    )
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.UndoWindowPassed)))

  test("(due, undo) after a snooze wake within the window: due with an extended deadline"): // named cell
    val tight = policy.copy(missAfterMinutes = 20, missAfterSnoozeMinutes = 5)
    val quiet0 = noQuiet
    // 09:00 due (seq 1, miss at 09:20); snooze 10m at 09:05 -> until 09:15; wake at 09:15 -> due again.
    val snoozed = Decide.decide(
      Occurrence.scheduled(t("09:00"), tight).copy(
        status = OccurrenceStatus.Due,
        reminderSeq = 1,
        lastRemindedAt = Some(t("09:00")),
        nextActionAt = Some(t("09:10"))
      ),
      tight,
      quiet0,
      t("09:05"),
      user(OccurrenceEvent.Snoozed(10))
    )
    assertEquals(snoozed.row.snoozedUntil, Some(t("09:15")))
    assertEquals(snoozed.row.missDeadline, t("09:20")) // max(09:20, 09:15 + 5 min)
    val woken = Decide.decide(snoozed.row, tight, quiet0, t("09:15"), tick())
    assertEquals(woken.row.status, OccurrenceStatus.Due)
    val undone = Decide.decide(
      woken.row,
      tight,
      quiet0,
      t("09:18"),
      user(OccurrenceEvent.Undo, lastAction = Some(LastUserAction(DoseActionKind.Snoozed, 4, t("09:05"))))
    )
    assertEquals(undone.row.status, OccurrenceStatus.Due)
    assertEquals(undone.row.missDeadline, t("09:38")) // extended: max(09:20, 09:18 + 2 * 10 min)
    assertEquals(undone.row.nextActionAt, Some(t("09:28")))
    assertEquals(undone.action.map(_.action), Some(DoseActionKind.Undone))

  test("(due, undo) with nothing undoable: refused"):
    val occ = dueOcc()
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Undo))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.NothingToUndo)))

  test("(pending, undo): refused — nothing to undo"):
    val occ = pendingOcc
    val result = Decide.decide(occ, policy, noQuiet, t("08:50"), user(OccurrenceEvent.Undo))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.NothingToUndo)))

  test("(missed, undo): refused — a system resolution is not undoable"):
    val occ = missedOcc(t("11:00"))
    val result = Decide.decide(occ, policy, noQuiet, t("11:05"), user(OccurrenceEvent.Undo))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.NothingToUndo)))

  test("(unknown, undo): refused — a system resolution is not undoable"):
    val occ = unknownOcc
    val result = Decide.decide(occ, policy, noQuiet, t("12:00"), user(OccurrenceEvent.Undo))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.NothingToUndo)))

  test("(cancelled, undo): refused"):
    val occ = cancelledOcc
    val result = Decide.decide(occ, policy, noQuiet, t("09:10"), user(OccurrenceEvent.Undo))
    assertEquals(result, Transition.unchanged(occ, Feedback.Refused(Refusal.RowCancelled)))

  // --------------------------------------------------------------------------
  // User rows: note_added never changes status
  // --------------------------------------------------------------------------

  private def assertNote(occ: Occurrence, now: Instant): Unit =
    val result = Decide.decide(occ, policy, noQuiet, now, user(OccurrenceEvent.NoteAdded("with food")))
    assertEquals(result.row, occ) // untouched: no status change, no epoch increment
    assertEquals(result.action.map(_.action), Some(DoseActionKind.NoteAdded))
    assertEquals(result.action.flatMap(_.note), Some("with food"))
    assertEquals(result.action.map(_.priorStatus), Some(occ.status))
    assertEquals(result.action.map(_.newStatus), Some(occ.status))
    assertEquals(result.dispatches, Nil)
    assertEquals(result.feedback, Feedback.NoteRecorded)

  test("(pending, note_added): action appended, status unchanged"):
    assertNote(pendingOcc, t("08:50"))

  test("(due, note_added): action appended, status unchanged"):
    assertNote(dueOcc(), t("09:05"))

  test("(snoozed, note_added): action appended, status unchanged"):
    assertNote(snoozedOcc(t("09:30")), t("09:20"))

  test("(taken, note_added): action appended, status unchanged"):
    assertNote(takenOcc(t("09:03")), t("09:10"))

  test("(skipped, note_added): action appended, status unchanged"):
    assertNote(skippedOcc(t("09:03")), t("09:10"))

  test("(missed, note_added): action appended, status unchanged"):
    assertNote(missedOcc(t("11:00")), t("11:05"))

  test("(unknown, note_added): action appended, status unchanged"):
    assertNote(unknownOcc, t("12:00"))

  test("(cancelled, note_added): action appended, status unchanged"):
    assertNote(cancelledOcc, t("09:10"))

  // --------------------------------------------------------------------------
  // Table coverage
  // --------------------------------------------------------------------------

  test("the decide table covers every (status, event) pair: 56 explicit cells, every result well-formed"):
    val events: List[OccurrenceEvent] = List(
      OccurrenceEvent.Tick,
      OccurrenceEvent.Taken,
      OccurrenceEvent.Skipped(Some(SkipReason.Forgot)),
      OccurrenceEvent.Snoozed(30),
      OccurrenceEvent.Corrected(t("09:30")),
      OccurrenceEvent.Undo,
      OccurrenceEvent.NoteAdded("n")
    )
    val fixtures: List[Occurrence] =
      List(pendingOcc, dueOcc(), snoozedOcc(t("09:30")), takenOcc(t("09:03")), skippedOcc(t("09:03")),
        missedOcc(t("11:00")), unknownOcc, cancelledOcc)
    assertEquals(fixtures.map(_.status).distinct.size, OccurrenceStatus.values.size)
    assertEquals(events.size, 7)
    val cells =
      for
        occ <- fixtures
        event <- events
      yield (occ.status, event, Decide.decide(occ, policy, noQuiet, t("09:20"), DecideContext(event, delivered = true, lastHealthyTick = t("08:00"))))
    assertEquals(cells.size, 56)
    cells.foreach { case (status, event, result) =>
      assert(
        FsmGens.holdsSchemaInvariants(result.row),
        s"invariants broken by ($status, $event): ${result.row}"
      )
    }


  // --------------------------------------------------------------------------
  // QuietHoursContext
  // --------------------------------------------------------------------------

  test("quietEndAfter resolves the end of a same-day interval"):
    val ctx = quiet("13:00", "17:00")
    assertEquals(ctx.quietEndAfter(t("14:00")), Some(t("17:00")))
    assertEquals(ctx.quietEndAfter(t("18:00")), None)

  test("quietEndAfter resolves the end of a wrapping interval on both sides of midnight"):
    val ctx = quiet("22:00", "06:00")
    assertEquals(ctx.quietEndAfter(t("23:30")), Some(t1("06:00")))
    assertEquals(ctx.quietEndAfter(t1("05:00")), Some(t1("06:00")))
    assertEquals(ctx.quietEndAfter(t("12:00")), None)

  test("quietEndAfter is empty for inactive hours and for no hours"):
    assertEquals(FsmGens.quietZone("22:00", "22:00").quietEndAfter(t("23:00")), None)
    assertEquals(noQuiet.quietEndAfter(t("23:00")), None)
end DecideSuite
