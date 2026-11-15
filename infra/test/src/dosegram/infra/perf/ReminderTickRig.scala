package dosegram.infra.perf

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.ReminderLoop
import dosegram.core.ports.Wake
import dosegram.infra.crypto.DataKeys
import dosegram.infra.db.Fixtures
import dosegram.infra.db.MutableClock
import dosegram.infra.db.PgSuite
import dosegram.infra.db.PgUnitOfWork

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** docs/PERFORMANCE.md, reminder loop tick time: `accounts` accounts with a daily 09:00 dose and a delivery channel on
  * Testcontainers Postgres, seeded through `ScheduleLifecycle`; then the loop's `tickOnce` (safety net, one batch,
  * heartbeat) idle at 08:00 and busy from 09:05 until every due dose is claimed. Run:
  * `./mill infra.test.runMain dosegram.infra.perf.reminderTickProbe [accounts]`.
  */
abstract class ReminderTickRig extends PgSuite:

  // The suite starts its container only when it has a test. The class is abstract, so the test runner never runs it
  // as a suite; the entry stands for the measurement that `run` makes by hand.
  test("rig")(())

  private val monday = Instant.parse("2026-09-21T07:00:00Z")

  def run(accounts: Int): Unit =
    beforeAll()
    try
      val clock = MutableClock(monday)
      val fixtures = Fixtures(dataSource)
      val daily = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
      val seeding = Timings.time:
        (1 to accounts).foreach: i =>
          val account = fixtures.account()
          fixtures.schedule(clock, account, s"Probe medication $i", daily, ZoneId.of("UTC"))
          fixtures.deliveryChannel(account, "telegram", s"$i", monday)
      // 90 days of history per schedule, as a live account has: copies of today's dose, skipped, on earlier days.
      withConnection(
        _.createStatement().execute(
          """INSERT INTO dose_occurrences (id, account_id, medication_id, schedule_id, revision, origin, local_date,
               local_time, slot_key, tz, dst_kind, scheduled_for, due_window_start, due_window_end, miss_deadline,
               status, skipped_at, dose_snapshot_enc)
             SELECT gen_random_uuid(), account_id, medication_id, schedule_id, revision, origin, local_date - d,
               local_time, slot_key, tz, dst_kind, scheduled_for - d * interval '1 day',
               due_window_start - d * interval '1 day', due_window_end - d * interval '1 day',
               miss_deadline - d * interval '1 day', 'skipped', scheduled_for - d * interval '1 day', dose_snapshot_enc
             FROM dose_occurrences, generate_series(1, 90) AS d
             WHERE local_date = DATE '2026-09-21';
             ANALYZE dose_occurrences"""
        )
      )
      println(f"seeded $accounts accounts with 90 days of history in ${seeding / 1000}%.1f s")
      val uow = PgUnitOfWork(dataSource, clock, DataKeys.development)
      val loop = ReminderLoop(uow, Materialiser(uow, clock), Wake.polling, clock, instance = "probe")
      clock.advance(Duration.ofHours(1))
      val idle = Timings.of(s"idle tickOnce, $accounts accounts", 5, 50)(loop.tickOnce(clock.now()))
      clock.advance(Duration.ofMinutes(65))
      // The first batch with its statements counted and timed by the server; the log costs time, so it is not sampled.
      var firstBatch = 0
      val counted = StatementLog.during(pg, dataSource) { firstBatch = loop.tickOnce(clock.now()) }
      println(f"statements of one busy tickOnce ($firstBatch doses): ${counted.total}, " +
        f"${counted.serverMillis}%.1f ms in the server\n${counted.top(14)}")
      val busy = Vector.newBuilder[Double]
      var claimed = 0
      var last = -1
      while last != 0 do
        val millis = Timings.time { last = loop.tickOnce(clock.now()) }
        if last > 0 then
          busy += millis
          claimed += last
      val batches = Timings(s"busy tickOnce, batch of ${ReminderLoop.BatchSize}", busy.result())
      println(Timings.Header)
      println(idle.row)
      println(batches.row)
      println(f"after the counted batch, $claimed due doses in ${batches.millis.sum / 1000}%.2f s, " +
        f"${batches.millis.sum / claimed.max(1)}%.2f ms per dose")
    finally afterAll()

@main def reminderTickProbe(accounts: Int): Unit = (new ReminderTickRig {}).run(accounts)
