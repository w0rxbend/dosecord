package dosecord.infra.db

import dosecord.contracts.Event
import dosecord.contracts.HhMm
import dosecord.contracts.Weekday
import dosecord.core.domain.Evaluator
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.ports.Wake
import dosecord.core.scheduling.Materialiser
import dosecord.core.scheduling.ReminderLoop

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.language.implicitConversions

/** ROADMAP M1.6, Postgres half (Testcontainers Postgres 18): SKIP LOCKED claiming under two concurrent loops, poison
  * row isolation by real savepoints, the `dose_due.v1` exactly-once rule, the revision-policy wiring and the
  * materialiser safety net.
  */
class ReminderLoopPgSuite extends PgSuite:

  private val monday = Instant.parse("2026-09-21T08:00:00Z") // a Monday
  private val utc = ZoneId.of("UTC")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "domain_events, outbox_messages, delivery_channels, platform_identities, worker_heartbeat, users CASCADE"
        )
      finally st.close()
    }

  private lazy val fixtures = Fixtures(dataSource)

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private def loop(clock: MutableClock): ReminderLoop =
    ReminderLoop(
      PgUnitOfWork(dataSource, clock),
      Materialiser(PgUnitOfWork(dataSource, clock), clock),
      Wake.polling,
      clock
    )

  private def occurrencesOf(scheduleId: UUID): List[(UUID, String, Int, Option[Instant])] = withConnection { conn =>
    given Connection = conn
    given RowMapper[(UUID, String, Int, Option[Instant])] = rs =>
      (rs.uuid("id"), rs.getString("status"), rs.getInt("epoch"), rs.optInstant("next_action_at"))
    sql"""SELECT id, status::text AS status, epoch, next_action_at
          FROM dose_occurrences WHERE schedule_id = $scheduleId ORDER BY scheduled_for"""
      .query[(UUID, String, Int, Option[Instant])]()
  }

  // Acceptance 1: two concurrent loops produce exactly one outbox row per send_key.
  test("two concurrent loops produce exactly one outbox row per send_key"):
    val clock = MutableClock(monday)
    val due = (1 to 8).map { i =>
      val accountId = fixtures.account()
      val created = fixtures.schedule(clock, accountId, s"Med$i", dailyAt("09:00"), utc)
      fixtures.deliveryChannel(accountId, "console", s"dm:$i", monday)
      created.scheduleId
    }
    clock.advance(Duration.ofMinutes(65)) // 09:05: today's slot of every schedule is due

    val errors = ConcurrentLinkedQueue[Throwable]()
    def worker(): Thread = new Thread(() =>
      try
        val l = loop(clock)
        var n = l.tick(clock.now())
        while n > 0 do n = l.tick(clock.now())
      catch { case e: Throwable => errors.add(e); () }
    )
    val first = worker()
    val second = worker()
    first.start(); second.start(); first.join(60000); second.join(60000)
    assert(errors.isEmpty, s"loop threads failed: $errors")

    withConnection { conn =>
      given Connection = conn
      given RowMapper[(String, String)] = rs => (rs.getString("send_key"), rs.getString("status"))
      val rows = sql"SELECT send_key, status FROM outbox_messages".query[(String, String)]()
      assertEquals(rows.size, 8, "one outbox row per due occurrence")
      assertEquals(rows.map(_._1).distinct.size, 8, "one row per send_key")
      val dueEvents =
        sql"SELECT count(*) FROM domain_events WHERE type = ${Event.DoseDueType}".queryOne[Int]().getOrElse(0)
      assertEquals(dueEvents, 8, "exactly one dose_due.v1 per occurrence")
      val actions =
        sql"SELECT count(*) FROM dose_actions WHERE action = 'reminder_sent'::dose_action".queryOne[Int]().getOrElse(0)
      assertEquals(actions, 8)
    }
    due.foreach { scheduleId =>
      val today = occurrencesOf(scheduleId).head
      assertEquals((today._2, today._3), ("due", 1), s"today's occurrence of $scheduleId fired exactly once")
    }

  // Acceptance 2: a poison row leaves the batch committed and is quarantined with next_action_at = now + 5 min.
  test("a poison row leaves the batch committed and the row quarantined, then leaves the claim set at error_count 3"):
    def poisonColumnsFor(id: UUID): (Int, Option[Instant]) = withConnection { conn =>
      given Connection = conn
      given RowMapper[(Int, Option[Instant])] = rs => (rs.getInt("error_count"), rs.optInstant("next_action_at"))
      sql"SELECT error_count, next_action_at FROM dose_occurrences WHERE id = $id"
        .queryOne[(Int, Option[Instant])]()
        .getOrElse(fail("poison row missing"))
    }

    def assertPoison(now: Instant, id: UUID, errorCount: Int): Unit =
      val (count, nextAction) = poisonColumnsFor(id)
      assertEquals(count, errorCount)
      assertEquals(nextAction, Some(now.plus(ReminderLoop.QuarantineRetryDelay)))
      val status = withConnection { conn =>
        given Connection = conn
        sql"SELECT status::text FROM dose_occurrences WHERE id = $id".queryOne[String]().getOrElse("")
      }
      assertEquals(status, "pending", "quarantine keeps the row open")

    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    fixtures.deliveryChannel(accountId, "console", "dm:owner", monday)
    val poison =
      fixtures.occurrenceAt(created.scheduleId, monday.plus(Duration.ofHours(1)), "t9999", revisionOverride = Some(99))
    clock.advance(Duration.ofMinutes(65)) // 09:05: the materialised 09:00 row and the poison row are both due

    val l = loop(clock)
    assertEquals(l.tick(clock.now()), 2)

    val fired = occurrencesOf(created.scheduleId).map(r => (r._2, r._3))
    assert(fired.contains(("due", 1)), s"the healthy row fired: $fired")
    withConnection { conn =>
      given Connection = conn
      assertEquals(
        sql"SELECT count(*) FROM outbox_messages WHERE occurrence_id IS NOT NULL".queryOne[Int]().getOrElse(0),
        1,
        "only the healthy row produced a dispatch"
      )
    }
    assertPoison(clock.now(), poison, errorCount = 1)

    clock.advance(ReminderLoop.QuarantineRetryDelay)
    l.tick(clock.now())
    assertPoison(clock.now(), poison, errorCount = 2)
    clock.advance(ReminderLoop.QuarantineRetryDelay)
    l.tick(clock.now())
    assertPoison(clock.now(), poison, errorCount = 3)

    val postponedTo = poisonColumnsFor(poison)._2
    clock.advance(ReminderLoop.QuarantineRetryDelay)
    l.tick(clock.now())
    assertEquals(
      poisonColumnsFor(poison),
      (3, postponedTo),
      "at error_count >= 3 the row is quarantined: not claimed, not touched"
    )

  // Acceptance 4: a pending -> due transition appends exactly one dose_due.v1 row.
  test("a pending -> due transition appends exactly one dose_due.v1 row"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    fixtures.deliveryChannel(accountId, "console", "dm:owner", monday)
    clock.advance(Duration.ofMinutes(65))

    val l = loop(clock)
    assertEquals(l.tick(clock.now()), 1)
    clock.advance(Duration.ofMinutes(10)) // the repeat fires: due -> due
    assertEquals(l.tick(clock.now()), 1)

    withConnection { conn =>
      given Connection = conn
      given RowMapper[(UUID, Option[String])] = rs => (rs.uuid("account_id"), rs.optString("subject"))
      val dueRows =
        sql"""SELECT account_id, subject FROM domain_events WHERE type = ${Event.DoseDueType}"""
          .query[(UUID, Option[String])]()
      assertEquals(dueRows.size, 1, "exactly one dose_due.v1 across the initial fire and the repeat")
      assertEquals(dueRows.head._1, accountId)
    }

  test("the tick's policy comes from the materialising revision"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val policy = ReminderPolicy.Default.copy(maxReminders = 1)
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc, policy = policy)
    fixtures.deliveryChannel(accountId, "console", "dm:owner", monday)
    clock.advance(Duration.ofMinutes(65))

    assertEquals(loop(clock).tick(clock.now()), 1)
    val today = occurrencesOf(created.scheduleId).head
    val expectedMiss = monday.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(policy.missAfterMinutes.toLong))
    assertEquals(today._4, Some(expectedMiss), "maxReminders = 1 pins next_action_at to the miss deadline")

  test("the safety net extends a schedule whose materialized_through lags"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    val through = fixtures.uow.transaction(_.schedules.get(created.scheduleId)).get.materializedThrough
    assertEquals(through, Some(monday.plus(Evaluator.MaterialisationHorizon)))

    val l = loop(clock)
    clock.advance(Duration.ofHours(1))
    assertEquals(l.materialiseLagging(clock.now()), 0, "well inside the horizon: nothing lags")

    clock.at = monday.plus(Evaluator.MaterialisationHorizon).minusSeconds(30)
    assertEquals(l.materialiseLagging(clock.now()), 1, "materialized_through < now + 4 * TICK: the net fires")
    val extended = fixtures.uow.transaction(_.schedules.get(created.scheduleId)).get.materializedThrough
    assertEquals(extended, Some(clock.now().plus(Evaluator.MaterialisationHorizon)))
    val slots = fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId)).map(_.scheduledFor)
    assert(
      slots.contains(monday.plus(Duration.ofHours(73))), // Thursday 09:00: beyond the pre-net horizon
      "the horizon moved a full day further out"
    )
