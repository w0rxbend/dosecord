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
