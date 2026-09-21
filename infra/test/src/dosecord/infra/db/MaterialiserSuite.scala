package dosecord.infra.db

import dosecord.contracts.HhMm
import dosecord.contracts.Weekday
import dosecord.core.domain.Evaluator
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.scheduling.Materialiser

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue

/** ROADMAP M1.5, materialiser half (Testcontainers Postgres 18): horizon extension with `materialized_through`
  * tracking, paused-schedule skipping, and two concurrent materialisers producing identical row sets (acceptance 4).
  */
class MaterialiserSuite extends PgSuite:

  private val monday = Instant.parse("2026-09-21T08:00:00Z") // a Monday
  private val utc = ZoneId.of("UTC")
  private val kyiv = ZoneId.of("Europe/Kyiv")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "domain_events, users CASCADE"
        )
      finally st.close()
    }

  private lazy val fixtures = Fixtures(dataSource)

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private def materialiser(clock: MutableClock): Materialiser =
    Materialiser(PgUnitOfWork(dataSource, clock), clock)

  test("runOnce extends the horizon, advances materialized_through, and a second run inserts nothing"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    // Create materialised Mon + Tue 09:00 and set materialized_through = Wed 08:00.

    clock.advance(Duration.ofHours(6)) // 14:00
    assertEquals(materialiser(clock).runOnce(), 1)
    val rows = fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId))
    assertEquals(
      rows.map(_.scheduledFor),
      List(
        Instant.parse("2026-09-21T09:00:00Z"),
        Instant.parse("2026-09-22T09:00:00Z"),
        Instant.parse("2026-09-23T09:00:00Z") // the horizon moved 6 h: Wednesday appears
      )
    )
    val through = fixtures.uow.transaction(_.schedules.get(created.scheduleId)).get.materializedThrough
    assertEquals(through, Some(monday.plus(Duration.ofHours(54))))

    assertEquals(materialiser(clock).runOnce(), 0, "nothing due once materialized_through reaches the horizon")
    assertEquals(
      fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId)).size,
      rows.size,
      "re-materialisation is idempotent"
    )

  test("runOnce skips paused schedules"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    fixtures.lifecycle(clock).pause(created.scheduleId)

    clock.advance(Duration.ofHours(6))
    assertEquals(materialiser(clock).runOnce(), 0)
    assertEquals(
      fixtures.uow
        .transaction(_.occurrences.listBySchedule(created.scheduleId))
        .count(_.status != OccurrenceStatus.Cancelled),
      0,
      "a paused schedule materialises nothing"
    )

  test("two concurrent materialisers produce identical row sets"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = List(
      fixtures.schedule(clock, accountId, "MedA", dailyAt("09:00", "21:00"), utc),
      fixtures.schedule(clock, accountId, "MedB", dailyAt("07:30", "19:45"), kyiv),
      fixtures.schedule(clock, accountId, "MedC", dailyAt("12:00"), utc)
    )
    clock.advance(Duration.ofHours(6))

    val errors = ConcurrentLinkedQueue[Throwable]()
    def onThread(m: Materialiser): Thread = new Thread(() =>
      try { (1 to 3).foreach(_ => m.runOnce()); () }
      catch { case e: Throwable => errors.add(e); () }
    )
    val first = onThread(materialiser(clock))
    val second = onThread(materialiser(clock))
    first.start(); second.start(); first.join(60000); second.join(60000)
    assert(errors.isEmpty, s"materialiser threads failed: $errors")

    // The row set equals the pure evaluator's output over the covered window, per schedule.
    created.foreach { outcome =>
      val (schedule, revision) = fixtures.uow.transaction { tx =>
        (tx.schedules.get(outcome.scheduleId).get, tx.revisions.latest(outcome.scheduleId).get)
      }
      val expected = Evaluator
        .occurrences(
          Materialiser.toEvaluator(schedule, revision),
          monday,
          monday.plus(Duration.ofHours(54))
        )
        .map(candidate => (candidate.localDate, candidate.slotKey, candidate.scheduledFor))
        .toSet
      val actual = fixtures.uow
        .transaction(_.occurrences.listBySchedule(outcome.scheduleId))
        .map(row => (row.localDate, row.slotKey, row.scheduledFor))
      assertEquals(actual.toSet, expected, s"row set of schedule ${outcome.scheduleId}")
      assertEquals(actual.size, actual.distinct.size, "no duplicate rows")
    }
