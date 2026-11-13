package dosegram.app

import dosegram.contracts.ChatAdapter
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.chat.WizardEngine
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.ports.Clock
import dosegram.core.scheduling.OutboxDispatcher
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.db.Fixtures
import dosegram.infra.db.PgSuite
import dosegram.infra.db.RowMapper
import dosegram.infra.db.sql
import ox.supervised

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions

/** ROADMAP G0.5 acceptance (ARCH-01): the assembled application, started through [[Launch.start]] exactly as
  * `dosegram run` starts it, on Testcontainers Postgres 18 with the real console adapter and an injected clock. No test
  * constructs or ticks a worker: a test seeds rows, steps the clock, types on the console, and reads what the console
  * printed and what the database holds. The last test runs the real `Main` in a child JVM and sends it SIGTERM.
  */
class LaunchPathSuite extends PgSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val monday = Instant.parse("2026-09-21T08:00:00Z")
  private val dueAfter = Duration.ofMinutes(61) // 09:01: the 09:00 dose is due, far inside the catch-up threshold
  private val callbackKey = "0:MDEyMzQ1Njc4OWFiY2RlZg"

  /** Every fork looks for work many times inside [[settleMillis]]; the materialiser period is never reached. */
  private val fast = Workers.Cadence(
    reminderPoll = Duration.ofMillis(100),
    materialiserPeriod = Duration.ofMinutes(15),
    outboxIdlePoll = Duration.ofMillis(50),
    sessionSweepPeriod = Duration.ofMillis(100)
  )
  private val settleMillis = 600L

  private lazy val fixtures = Fixtures(dataSource)

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "conversation_sessions, form_runs, callback_slots, delivery_channels, platform_identities, " +
            "worker_heartbeat, users CASCADE"
        )
      finally st.close()
    }

  // ---------- The launched application ----------

  /** `DATABASE_URL` in the form the executable takes it: credentials as pgjdbc query parameters. */
  private def databaseUrl: String =
    val separator = if pg.jdbcUrl.contains("?") then "&" else "?"
    s"${pg.jdbcUrl}${separator}user=${pg.username}&password=${pg.password}"

  private def settings(role: Role): Settings =
    Settings(
      enabledAdapters = List(Adapter.Console),
      databaseUrl = databaseUrl,
      callbackKeys = Settings.parseCallbackKeys(callbackKey).fold(error => fail(error), identity),
      role = role,
      instanceId = "launch-path",
      logLevel = LogLevel.Info,
      logFormat = LogFormat.Plain,
      consoleUserId = Some(LaunchRig.ConsoleUser),
      telegram = None,
      publicBaseUrl = None,
      healthPort = freePort(),
      environment = Environment.Development,
      miniAppUrl = None
    )

  /** One launch: `vendor` stands between the dispatcher and the real console adapter. */
  private final case class Launched(
      role: Role,
      clock: SteppedClock,
      cadence: Workers.Cadence,
      vendor: ChatAdapter => ChatAdapter
  )

  private def as(role: Role, clock: SteppedClock): Launched = Launched(role, clock, fast, console => console)

  /** Runs `test` against the application of [[Launch.start]]; the scope end cancels its forks, as a restart would. */
  private def launched[A](launch: Launched)(test: LaunchRig => A): A =
    val rig = LaunchRig(launch.clock)
    try
      supervised:
        val parts = Launch.Parts(launch.clock, Map("console" -> launch.vendor(rig.console)), launch.cadence)
        Launch.start(settings(launch.role), dataSource, parts, log = _ => ())
        test(rig)
    finally rig.close()

  // ---------- Seeding and observation ----------

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  /** One account reachable on the console, with one daily schedule materialised from `clock.now()`. */
  private def seedSchedule(clock: Clock, times: String*): UUID =
    val accountId = fixtures.account()
    fixtures.deliveryChannel(accountId, "console", s"dm:${LaunchRig.ConsoleUser}", clock.now())
    fixtures.schedule(clock, accountId, "Vitamin D", dailyAt(times*), ZoneOffset.UTC).scheduleId

  private def eventually(clue: String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(25)
    assert(condition, s"not within 30 s: $clue")

  /** Lets every fork run several more passes, so an assertion of "exactly one" also covers what would come next. */
  private def settle(): Unit = Thread.sleep(settleMillis)

  /** `(kind, status)` of every outbox row, oldest first. */
  private def outbox(): List[(String, String)] = withConnection { conn =>
    given Connection = conn
    given RowMapper[(String, String)] = rs => (rs.getString("kind"), rs.getString("status"))
    sql"SELECT kind, status::text AS status FROM outbox_messages ORDER BY created_at, send_key"
      .query[(String, String)]()
  }

  private def count(table: String): Int = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(s"SELECT count(*) FROM $table") // a table name of this suite, never input
      rs.next()
      rs.getInt(1)
    finally st.close()
  }

  private def occurrenceStatuses(scheduleId: UUID, before: Instant): List[String] = withConnection { conn =>
    given Connection = conn
    sql"""SELECT status::text FROM dose_occurrences
          WHERE schedule_id = $scheduleId AND scheduled_for < $before ORDER BY scheduled_for"""
      .query[String]()
  }

  // ---------- Acceptance ----------

  test("role all dispatches exactly one reminder for a seeded due occurrence"):
    val clock = SteppedClock(monday)
    seedSchedule(clock, "09:00")
    clock.advance(dueAfter)
    launched(as(Role.All, clock)): rig =>
      eventually("the reminder is sent")(outbox() == List("reminder" -> "sent"))
      settle()
      assertEquals(rig.messages.size, 1, s"console printed: ${rig.messages}")
      assert(rig.messages.head.contains("Time for Vitamin D"), rig.messages.head)
      assertEquals(outbox(), List("reminder" -> "sent"))

  test("role worker dispatches the due reminder and leaves the console unread"):
    val clock = SteppedClock(monday)
    seedSchedule(clock, "09:00")
    clock.advance(dueAfter)
    launched(as(Role.Worker, clock)): rig =>
      rig.typeLine("/help")
      eventually("the reminder is sent")(outbox() == List("reminder" -> "sent"))
      settle()
      assertEquals(rig.messages.size, 1, s"only the reminder, no answer to /help: ${rig.messages}")
      assertEquals(count("inbound_events"), 0)

  test("role gateway answers the console and starts no worker"):
    val clock = SteppedClock(monday)
    val scheduleId = seedSchedule(clock, "09:00")
    clock.advance(dueAfter)
    launched(as(Role.Gateway, clock)): rig =>
      rig.typeLine("/help")
      eventually("/help is answered")(rig.messages.size == 1)
      settle()
      assertEquals(count("worker_heartbeat"), 0, "no reminder loop ticked")
      assertEquals(occurrenceStatuses(scheduleId, clock.now()), List("pending"), "the due dose was not claimed")
      assertEquals(rig.messages.size, 1, s"console printed: ${rig.messages}")

  test("a NOTIFY on dosegram_wake ticks the loop long before its poll interval"):
    val clock = SteppedClock(monday)
    seedSchedule(clock, "09:00")
    val pollsEveryTenMinutes = fast.copy(reminderPoll = Duration.ofMinutes(10))
    launched(as(Role.All, clock).copy(cadence = pollsEveryTenMinutes)): rig =>
      eventually("the start-up tick wrote its heartbeat")(count("worker_heartbeat") == 1)
      clock.advance(dueAfter)
      withConnection { conn =>
        val st = conn.createStatement()
        try st.execute("NOTIFY dosegram_wake")
        finally st.close()
      }
      eventually("only the wake listener can explain this tick")(rig.messages.size == 1)

  test("a failed send is retried after its backoff and delivered once"):
    val clock = SteppedClock(monday)
    seedSchedule(clock, "09:00")
    clock.advance(dueAfter)
    launched(as(Role.All, clock).copy(vendor = FailingFirstSend(_))): rig =>
      eventually("the first attempt failed")(outbox() == List("reminder" -> "failed_retry"))
      settle()
      assertEquals(rig.messages, Nil, "nothing is sent before the backoff has passed")
      assertEquals(outbox(), List("reminder" -> "failed_retry"))
      clock.advance(OutboxDispatcher.MinBackoff)
      eventually("the retry is sent")(outbox() == List("reminder" -> "sent"))
      settle()
      assertEquals(rig.messages.size, 1, s"console printed: ${rig.messages}")

  test("a 6 h clock jump yields one digest and no stale reminder"):
    val clock = SteppedClock(monday)
    val scheduleId = seedSchedule(clock, "09:00", "12:00")
    launched(as(Role.All, clock)): rig =>
      eventually("the start-up tick wrote its heartbeat")(count("worker_heartbeat") == 1)
      clock.advance(Duration.ofHours(6))
      eventually("the digest is sent")(outbox() == List("digest" -> "sent"))
      settle()
      assertEquals(rig.messages.size, 1, s"console printed: ${rig.messages}")
      assert(
        rig.messages.head.contains("While I was away, 2 doses passed without a reminder."),
        rig.messages.head
      )
      assertEquals(outbox(), List("digest" -> "sent"), "no reminder and no missed notice for the stale doses")
      assertEquals(occurrenceStatuses(scheduleId, clock.now()), List("unknown", "unknown"))

  test("an idle wizard session gets one grace prompt and is then cancelled"):
    val clock = SteppedClock(monday)
    launched(as(Role.All, clock)): rig =>
      rig.typeLine("/start")
      eventually("the wizard prompt is printed")(rig.messages.size == 1)
      assertEquals(count("conversation_sessions"), 1)
      // The mediator marks the prompt's outbox safety row sent only after the console has printed it. A clock stepped
      // past that row's delay before then lets the dispatcher send the prompt a second time.
      eventually("the prompt's safety row is marked sent")(outbox() == List("interaction_reply" -> "sent"))
      clock.advance(WizardEngine.IdleAfter.plusSeconds(1))
      eventually("the grace prompt is printed")(rig.messages.size == 2)
      assert(rig.messages(1).contains(WizardCopy.stillThere), rig.messages(1))
      assertEquals(count("conversation_sessions"), 1, "the session survives the grace prompt")
      clock.advance(WizardEngine.GracePeriod.plusSeconds(1))
      eventually("the cancellation is printed")(rig.messages.size == 3)
      assert(rig.messages(2).contains(WizardCopy.setupCancelled), rig.messages(2))
      assertEquals(count("conversation_sessions"), 0)

  // The console's per-chat token bucket holds 5 sends and refills 1 per second (TokenBuckets.Default), so one batch
  // of 14 due reminders stays in flight for about 9 s after the fifth is printed. SIGTERM lands in the middle of it,
  // and the batch outlasts the stop of the HTTP edge (about 4 s), so it is still in flight when claiming stops.
  test("SIGTERM drains the in-flight sends of the real executable and exits 0 within 20 s"):
    val dueDoses = 14
    val now = Instant.now()
    val farFromNow = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(now.plusSeconds(12 * 3600))
    val scheduleId = seedSchedule(Clock.system, farFromNow)
    (1 to dueDoses).foreach(n => fixtures.occurrenceAt(scheduleId, now.minusSeconds(60), s"t-drain-$n"))

    val child = ChildJvm.start(
      "dosegram.app.Main",
      List("run", "--role", "all"),
      Map(
        "ENABLED_ADAPTERS" -> "console",
        "CONSOLE_USER_ID" -> LaunchRig.ConsoleUser,
        "DATABASE_URL" -> databaseUrl,
        "CALLBACK_KEYS" -> callbackKey,
        "INSTANCE_ID" -> "launch-sigterm",
        "LOG_FORMAT" -> "plain",
        "HEALTH_PORT" -> freePort().toString
      )
    )
    val fiveSent = child.awaitOutput("[#5] ", within = 60.seconds)
    assert(fiveSent, s"five reminders were not printed; output:\n${child.output}")
    child.sigterm()
    val exitCode = child.exitCode(within = 20.seconds)
    val output = child.output

    assertEquals(exitCode, Some(0), s"output:\n$output")
    val claimingStoppedAt = output.indexOf("shutdown: stopping claiming")
    val lastSendAt = output.indexOf(s"[#$dueDoses] ")
    assert(
      claimingStoppedAt >= 0 && lastSendAt > claimingStoppedAt,
      s"the batch was not in flight when claiming stopped; output:\n$output"
    )
    assert(output.contains("in-flight work drained"), s"output:\n$output")
    assert(output.contains("shutdown: pool closed"), s"output:\n$output")
    assertEquals(outbox(), List.fill(dueDoses)("reminder" -> "sent"))
