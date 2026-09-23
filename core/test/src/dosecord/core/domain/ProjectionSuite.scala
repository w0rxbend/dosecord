package dosecord.core.domain

import dosecord.core.domain.copy.DoseActionKind

import java.time.Instant

/** Named pins for `Projection.fold` (ROADMAP M1.4b), complementing the random-sequence property with scenarios that
    * must keep meaning what they mean: the empty log, the snooze-wake reminder not counted in `reminder_seq`, undo
    * clearing the resolution, and `note_added` leaving the lifecycle untouched.
    */
class ProjectionSuite extends munit.FunSuite:

  private val policy: ReminderPolicy = ReminderPolicy()
  private val scheduledFor: Instant = Instant.parse("2026-03-01T09:00:00Z")
  private val seed: Occurrence = Occurrence.scheduled(scheduledFor, policy)
  private val noQuiet: QuietHoursContext = QuietHoursContext.none(FsmGens.utc)

  private def decide(row: Occurrence, now: Instant, ctx: DecideContext): Transition =
    Decide.decide(row, policy, noQuiet, now, ctx)

  test("fold of an empty action log is the seed projection"):
    assertEquals(Projection.fold(Nil, seed), Projection.of(seed))

  test("fold reproduces the FSM projection across remind, snooze, wake, take"):
    val atDue = seed.dueWindowStart
    val t1 = decide(seed, atDue, DecideContext(OccurrenceEvent.Tick, delivered = true))
    assertEquals(t1.row.status, OccurrenceStatus.Due)
    val t2 = decide(t1.row, atDue.plusSeconds(60), DecideContext(OccurrenceEvent.Snoozed(10)))
    assertEquals(t2.row.status, OccurrenceStatus.Snoozed)
    val wakeAt = t2.row.snoozedUntil.get
    val t3 = decide(t2.row, wakeAt, DecideContext(OccurrenceEvent.Tick, delivered = true))
    assertEquals(t3.row.status, OccurrenceStatus.Due)
    val t4 = decide(t3.row, wakeAt.plusSeconds(120), DecideContext(OccurrenceEvent.Taken))
    assertEquals(t4.row.status, OccurrenceStatus.Taken)
    val actions = List(t1, t2, t3, t4).flatMap(_.action)
    // Four actions: reminder_sent, snoozed, reminder_sent (wake), taken.
    assertEquals(actions.map(_.action).size, 4)
    val folded = Projection.fold(actions, seed)
    assertEquals(folded, Projection.of(t4.row))
    // The wake reminder is not counted (ADR-012): one counted reminder, one snooze.
    assertEquals(folded.reminderSeq, 1)
    assertEquals(folded.snoozeCount, 1)
    assertEquals(folded.lastRemindedAt, Some(wakeAt))
    assertEquals(folded.effectiveAt, Some(wakeAt.plusSeconds(120)))

  test("fold mirrors undo clearing the resolution and correction setting an explicit effective_at"):
    val atDue = seed.dueWindowStart
    val t1 = decide(seed, atDue, DecideContext(OccurrenceEvent.Tick, delivered = true))
    val takenAt = atDue.plusSeconds(300)
    val t2 = decide(t1.row, takenAt, DecideContext(OccurrenceEvent.Taken))
    val undoAt = takenAt.plusSeconds(60)
    val t3 = decide(
      t2.row,
      undoAt,
      DecideContext(OccurrenceEvent.Undo, lastUndoableAction = Some(LastUserAction(DoseActionKind.Taken, 2, takenAt)))
    )
    assertEquals(t3.row.status, OccurrenceStatus.Due)
    val soFar = List(t1, t2, t3).flatMap(_.action)
    assertEquals(Projection.fold(soFar, seed), Projection.of(t3.row))
    val skippedAt = undoAt.plusSeconds(120)
    val t4 = decide(t3.row, skippedAt, DecideContext(OccurrenceEvent.Skipped(Some(SkipReason.Forgot))))
    val correctedEffective = scheduledFor.plusSeconds(600)
    val t5 = decide(t4.row, skippedAt.plusSeconds(3600), DecideContext(OccurrenceEvent.Corrected(correctedEffective)))
    val actions = soFar ++ List(t4, t5).flatMap(_.action)
    val folded = Projection.fold(actions, seed)
    assertEquals(folded, Projection.of(t5.row))
    assertEquals(folded.status, OccurrenceStatus.Taken)
    assertEquals(folded.skippedAt, None)
    assertEquals(folded.effectiveAt, Some(correctedEffective))

  test("fold rebuilds the collapsed reminder_seq jump of a catch-up fire (M1.8)"):
    // 09:00 due window, repeatEvery 10, maxReminders 3: a tick at 09:25 collapses the initial plus two repeats
    // into one late reminder (DESIGN.md section 7.5).
    val t1 = decide(seed, scheduledFor.plusSeconds(1500), DecideContext(OccurrenceEvent.Tick, delivered = false))
    assertEquals(t1.row.reminderSeq, 3)
    val actions = t1.action.toList ++ t1.additionalActions
    assertEquals(actions.map(_.action), List(DoseActionKind.ReminderSent, DoseActionKind.CatchUpCollapsed))
    assertEquals(Projection.fold(actions, seed), Projection.of(t1.row))

  test("note_added appends an action but never moves the projection"):
    val atDue = seed.dueWindowStart
    val t1 = decide(seed, atDue, DecideContext(OccurrenceEvent.Tick, delivered = true))
    val t2 = decide(t1.row, atDue.plusSeconds(30), DecideContext(OccurrenceEvent.NoteAdded("with breakfast")))
    val before = Projection.fold(List(t1).flatMap(_.action), seed)
    val after = Projection.fold(List(t1, t2).flatMap(_.action), seed)
    assertEquals(t2.action.map(_.action), Some(DoseActionKind.NoteAdded))
    assertEquals(after, before)
    assertEquals(after, Projection.of(t2.row))
end ProjectionSuite
