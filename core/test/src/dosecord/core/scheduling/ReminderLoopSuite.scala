package dosecord.core.scheduling

import dosecord.contracts.Event
import dosecord.core.chat.MediatorFakes
import dosecord.core.domain.DoseSnapshot
import dosecord.core.domain.DstKind
import dosecord.core.domain.Occurrence
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.domain.copy.DoseActionKind
import dosecord.core.ports.DeliveryTarget
import dosecord.core.ports.NewOccurrence
import dosecord.core.ports.NewSchedule
import dosecord.core.ports.NewScheduleRevision
import dosecord.core.ports.OccurrenceOrigin
import dosecord.core.ports.Wake

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** ROADMAP M1.6, pure half: the tick's decide-persist unit over the in-memory ports — the pending -> due firing, the
  * `dose_due.v1` exactly-once rule, the policy lookup from the materialising revision, and the quarantine of a poison
  * row. Concurrency, real savepoints and LISTEN/NOTIFY are the Testcontainers half (`ReminderLoopPgSuite`).
  */
class ReminderLoopSuite extends munit.FunSuite:

  private val utc = ZoneId.of("UTC")
  private val t0 = Instant.parse("2026-09-21T09:00:00Z")

  private final case class Seed(accountId: UUID, scheduleId: UUID, occurrenceId: UUID, channelId: UUID)

  private def seed(
      uow: MediatorFakes.InMemoryUnitOfWork,
      scheduledFor: Instant,
      policy: ReminderPolicy = ReminderPolicy.Default,
      revision: Int = 1
  ): Seed =
    val accountId = UUID.randomUUID()
    val medicationId = UUID.randomUUID()
    val scheduleId = UUID.randomUUID()
    val occurrenceId = UUID.randomUUID()
    val channelId = UUID.randomUUID()
    val snapshot = DoseSnapshot("Vitamin D", None, None, None)
    uow.transaction { tx =>
      tx.schedules.insert(
        NewSchedule(scheduleId, medicationId, accountId, "fixed_times", utc, true, LocalDate.of(2026, 9, 21)),
        scheduledFor
      )
      tx.revisions.append(
        NewScheduleRevision(
          UUID.randomUUID(),
          scheduleId,
          revision = 1,
          effectiveFrom = scheduledFor,
          tz = utc,
          rule = Rule.AsNeeded(1, 0),
          policy = policy,
          doseSnapshot = snapshot,
          createdBy = "user",
          reason = None
        ),
        scheduledFor
      )
      tx.occurrences.insertAll(
        List(
          NewOccurrence(
            id = occurrenceId,
            accountId = accountId,
            medicationId = medicationId,
            scheduleId = Some(scheduleId),
            revision = Some(revision),
            origin = OccurrenceOrigin.Scheduled,
            localDate = LocalDate.of(2026, 9, 21),
            localTime = None,
            slotKey = "t0900",
            tz = utc,
            dstKind = DstKind.None,
            doseSnapshot = snapshot,
            state = Occurrence.scheduled(scheduledFor, policy)
          )
        )
      )
      ()
    }
    uow.channels.register(accountId, DeliveryTarget(channelId, "console", Some("dm:owner")))
    Seed(accountId, scheduleId, occurrenceId, channelId)

  private def loop(uow: MediatorFakes.InMemoryUnitOfWork): ReminderLoop =
    ReminderLoop(uow, Materialiser(uow, MediatorFakes.FixedClock(t0)), Wake.polling, MediatorFakes.FixedClock(t0))

  test("a pending row past its due window fires once: due, action row, one outbox row, one dose_due.v1"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val s = seed(uow, t0)

    assertEquals(loop(uow).tick(t0.plusSeconds(60)), 1)

    val occ = uow.occurrences.get(s.occurrenceId).get
    assertEquals(occ.status, OccurrenceStatus.Due)
    assertEquals(occ.state.epoch, 1)
    assertEquals(occ.state.reminderSeq, 1)

    val enqueued = uow.outbox.allEnqueued
    assertEquals(enqueued.size, 1)
    assertEquals(
      enqueued.head.sendKey,
      s"occ:${s.occurrenceId}:e1:s1:kinitial:c${s.channelId}",
      "send_key carries the new epoch (DESIGN.md section 7.4)"
    )
    assertEquals(enqueued.head.epoch, Some(1))

    assertEquals(uow.doseActions.all.map(_.action), List(DoseActionKind.ReminderSent))
    val events = uow.domainEvents.all.map(_.eventType)
    assertEquals(events, List(Event.DoseDueType))

  test("a repeat tick is due -> due and appends no second dose_due.v1"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val s = seed(uow, t0)
    val l = loop(uow)

    l.tick(t0.plusSeconds(60))
    l.tick(t0.plusSeconds(60).plus(Duration.ofMinutes(10)))

    val occ = uow.occurrences.get(s.occurrenceId).get
    assertEquals(occ.state.reminderSeq, 2)
    // The repeat carries a finalize of the superseded message plus the new reminder, both at the new epoch.
    assertEquals(uow.outbox.allEnqueued.map(_.epoch), List(Some(1), Some(2), Some(2)))
    assertEquals(
      uow.domainEvents.all.count(_.eventType == Event.DoseDueType),
      1,
      "exactly one dose_due.v1 per pending -> due transition"
    )

  test("nothing due claims nothing"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    seed(uow, t0)
    assertEquals(loop(uow).tick(t0.minusSeconds(60)), 0)
    assertEquals(uow.outbox.allEnqueued, Nil)

  test("the policy comes from the materialising revision (maxReminders = 1 pins next_action_at to the miss deadline)"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val policy = ReminderPolicy.Default.copy(maxReminders = 1)
    val s = seed(uow, t0, policy)

    assertEquals(loop(uow).tick(t0.plusSeconds(60)), 1)
    val occ = uow.occurrences.get(s.occurrenceId).get
    assertEquals(occ.state.nextActionAt, Some(occ.state.missDeadline))

  test("a poison row is quarantined with next_action_at = now + 5 min and leaves the batch alone"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val healthy = seed(uow, t0)
    val poison = seed(uow, t0, revision = 99) // no such revision: the policy lookup throws

    val now = t0.plusSeconds(60)
    assertEquals(loop(uow).tick(now), 2, "both rows claimed; the poison one fails under its savepoint")

    val healthyRow = uow.occurrences.get(healthy.occurrenceId).get
    assertEquals(healthyRow.status, OccurrenceStatus.Due, "the healthy row still fired")
    val poisonRow = uow.occurrences.get(poison.occurrenceId).get
    assertEquals(poisonRow.status, OccurrenceStatus.Pending)
    assertEquals(poisonRow.state.nextActionAt, Some(now.plus(ReminderLoop.QuarantineRetryDelay)))
    assertEquals(uow.occurrences.errorCount(poison.occurrenceId), 1)
    assertEquals(
      uow.outbox.allEnqueued.count(_.occurrenceId.contains(poison.occurrenceId)),
      0,
      "no dispatch for the poison row"
    )

  test("a row at the quarantine threshold is no longer claimed"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val poison = seed(uow, t0, revision = 99)
    val l = loop(uow)
    var now = t0.plusSeconds(60)
    (1 to 3).foreach { _ =>
      l.tick(now)
      now = now.plus(ReminderLoop.QuarantineRetryDelay)
    }
    assertEquals(uow.occurrences.errorCount(poison.occurrenceId), 3)
    val before = uow.occurrences.get(poison.occurrenceId).get.state.nextActionAt
    l.tick(now)
    val after = uow.occurrences.get(poison.occurrenceId).get
    assertEquals(after.state.nextActionAt, before, "quarantined rows stay out of the claim set")
    assertEquals(uow.occurrences.errorCount(poison.occurrenceId), 3)
