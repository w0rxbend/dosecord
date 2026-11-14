package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.ports.Clock
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.ReminderLoop

import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import scala.language.implicitConversions

import ox.fork
import ox.supervised

/** ROADMAP M1.6, LISTEN half (acceptance 6): a NOTIFY on `dosegram_wake` triggers a prompt tick against real Postgres
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
    try st.execute("NOTIFY dosegram_wake")
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

  // The loop asks for no wait after a full batch; pgjdbc reads a timeout of 0 as "block until a NOTIFY".
  test("awaitOrTimeout with a zero timeout returns at once when no NOTIFY is pending"):
    val wake = PgNotifyWake(pg.jdbcUrl, pg.username, pg.password)
    try
      val returned = java.util.concurrent.CountDownLatch(1)
      val listener = Thread.ofVirtual().start(() => { wake.awaitOrTimeout(Duration.ZERO); returned.countDown() })
      val promptly = returned.await(5, java.util.concurrent.TimeUnit.SECONDS)
      if !promptly then
        listener.interrupt()
        listener.join(5000)
      assert(promptly, "a zero wait returned without a NOTIFY")
    finally wake.close()

  test("an interrupt of a fork blocked in the wait ends the wait as a cancellation, not as a database error"):
    val wake = PgNotifyWake(pg.jdbcUrl, pg.username, pg.password)
    try
      val outcome = java.util.concurrent.atomic.AtomicReference[String]("still waiting")
      val listener = Thread
        .ofVirtual()
        .start: () =>
          try
            wake.awaitOrTimeout(Duration.ofSeconds(30))
            outcome.set("returned")
          catch
            case _: InterruptedException => outcome.set("cancelled")
            case e: Throwable            => outcome.set(s"${e.getClass.getSimpleName}")
      Thread.sleep(400) // let the listener block on the socket
      listener.interrupt()
      listener.join(10000)
      assert(!listener.isAlive, "the interrupt ended the wait")
      assertEquals(outcome.get(), "cancelled")
    finally wake.close()

  test("a LISTEN connection the database ends fails one wait, and the next wait listens on a new one"):
    val wake = PgNotifyWake(pg.jdbcUrl, pg.username, pg.password)
    try
      val terminated = withConnection { conn =>
        given Connection = conn
        sql"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query = 'LISTEN dosegram_wake'"
          .queryOne[Boolean]()
      }
      assertEquals(terminated, Some(true), "the listening backend was ended")
      // The broken connection is a database error, not a cancellation: the worker loop logs it and goes on.
      intercept[SQLException](wake.awaitOrTimeout(Duration.ofSeconds(5)))
      val listener = new Thread(() => wake.awaitOrTimeout(Duration.ofSeconds(30)))
      listener.start()
      Thread.sleep(400) // let the listener open its new connection and block on the socket
      sendNotify()
      listener.join(10000)
      assert(!listener.isAlive, "the NOTIFY woke the listener on its new connection")
    finally wake.close()

  test("run(): a NOTIFY on dosegram_wake triggers a prompt tick despite a 10-minute poll interval"):
    val clock = Clock.system
    val now = clock.now()
    val accountId = fixtures.account()
    val created = fixtures.schedule(MutableClock(now), accountId, "Vitamin D", dailyAt("06:00"), ZoneOffset.UTC)
    fixtures.deliveryChannel(accountId, "console", "dm:owner", now)

    val wake = PgNotifyWake(pg.jdbcUrl, pg.username, pg.password)
    val loop = ReminderLoop(
      PgUnitOfWork(dataSource, clock, DataKeys.development),
      Materialiser(PgUnitOfWork(dataSource, clock, DataKeys.development), clock),
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
