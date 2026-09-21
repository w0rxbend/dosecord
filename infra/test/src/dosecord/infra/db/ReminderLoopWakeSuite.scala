package dosecord.infra.db

import dosecord.contracts.HhMm
import dosecord.contracts.Weekday
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.ports.Clock
import dosecord.core.scheduling.Materialiser
import dosecord.core.scheduling.ReminderLoop

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import scala.language.implicitConversions

import ox.fork
import ox.supervised

/** ROADMAP M1.6, LISTEN half (acceptance 6): a NOTIFY on `dosecord_wake` triggers a prompt tick against real Postgres
  * LISTEN/NOTIFY — at the wake primitive level and end to end through `ReminderLoop.run` with a poll interval so long
  * that only the wake can explain the tick.
  */
class ReminderLoopWakeSuite extends PgSuite:

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

  private def sendNotify(): Unit = withConnection { conn =>
    val st = conn.createStatement()
    try st.execute("NOTIFY dosecord_wake")
    finally st.close()
  }

  private def waitFor(deadline: Duration)(cond: => Boolean): Boolean =
    val until = Instant.now().plus(deadline)
    var ok = false
    while !ok && Instant.now().isBefore(until) do
      ok = cond
      if !ok then Thread.sleep(100)
    ok

  test("awaitOrTimeout blocks until a NOTIFY arrives, and waits out the timeout otherwise"):
    val wake = PgNotifyWake(pg.jdbcUrl, pg.username, pg.password)
    try
      val timeoutStart = System.nanoTime()
      wake.awaitOrTimeout(Duration.ofMillis(250))
      val timeoutElapsed = (System.nanoTime() - timeoutStart) / 1000000
      assert(timeoutElapsed >= 200, s"the fallback poll waits out the timeout, took ${timeoutElapsed} ms")

      val elapsed = AtomicLong(-1)
      val listener = new Thread(() =>
        val start = System.nanoTime()
        wake.awaitOrTimeout(Duration.ofSeconds(30))
        elapsed.set((System.nanoTime() - start) / 1000000)
        ()
      )
      listener.start()
      Thread.sleep(400) // let the listener block on the socket
      sendNotify()
      listener.join(10000)
      assert(!listener.isAlive, "the NOTIFY woke the listener")
      assert(elapsed.get() >= 0 && elapsed.get() < 10000, s"prompt wake, took ${elapsed.get()} ms")
    finally wake.close()

  test("run(): a NOTIFY on dosecord_wake triggers a prompt tick despite a 10-minute poll interval"):
    val clock = Clock.system
    val now = clock.now()
    val accountId = fixtures.account()
    val created = fixtures.schedule(MutableClock(now), accountId, "Vitamin D", dailyAt("06:00"), ZoneOffset.UTC)
    fixtures.deliveryChannel(accountId, "console", "dm:owner", now)

    val wake = PgNotifyWake(pg.jdbcUrl, pg.username, pg.password)
    val loop = ReminderLoop(
      PgUnitOfWork(dataSource, clock),
      Materialiser(PgUnitOfWork(dataSource, clock), clock),
      wake,
      clock,
      instance = "wake-pg",
      pollInterval = Duration.ofMinutes(10)
    )
    try
      supervised:
        val _ = fork(loop.run())
        assert(
          waitFor(Duration.ofSeconds(15))(
            withConnection { conn =>
              given Connection = conn
              sql"SELECT count(*) FROM worker_heartbeat WHERE instance = 'wake-pg'".queryOne[Int]().getOrElse(0) == 1
            }
          ),
          "the loop ticked once at startup (heartbeat written)"
        )
        // A dose that falls due after the startup tick: only the wake can fire it before the 10-minute poll.
        val due = fixtures.occurrenceAt(created.scheduleId, clock.now().minusSeconds(60), "t-wake")
        sendNotify()
        val fired = waitFor(Duration.ofSeconds(15))(
          withConnection { conn =>
            given Connection = conn
            sql"SELECT status::text FROM dose_occurrences WHERE id = $due"
              .queryOne[String]()
              .contains("due")
          }
        )
        assert(fired, "the wake tick fired the due reminder promptly, long before the poll interval")
    finally wake.close()

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))
