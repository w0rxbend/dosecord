package dosegram.app

import dosegram.contracts.ChatError
import dosegram.contracts.ChatRef
import dosegram.contracts.VendorOp
import dosegram.core.chat.CapabilityProfiles
import dosegram.core.chat.FakeAdapter
import dosegram.core.domain.copy.OpsCopy
import dosegram.infra.db.Database
import dosegram.infra.db.PgOpsHealth
import dosegram.infra.db.PgSuite
import dosegram.infra.db.sql

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** `/readyz` and the owner alerts over a real database: the probes read `outbox_messages` and `inbound_inbox` as the
  * dispatcher and the inbox processor leave them.
  */
class OpsHealthSuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T09:00:00Z")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try st.execute("TRUNCATE outbox_messages, inbound_inbox")
      finally st.close()
    }

  private def outboxRow(status: String, nextAttemptAt: Instant, attemptedAt: Option[Instant] = None): Unit =
    withConnection { conn =>
      given Connection = conn
      val id = UUID.randomUUID()
      sql"""INSERT INTO outbox_messages (id, send_key, op, kind, vendor, importance, payload, status, next_attempt_at,
                                         attempted_at, last_error)
            VALUES ($id, ${id.toString}, 'send', 'reminder', 'telegram', 'reminder', '{"text":"CANARY body"}'::jsonb,
                    $status, $nextAttemptAt, $attemptedAt, 'CANARY error')""".execute()
      ()
    }

  private def quarantinedUpdate(finishedAt: Instant): Unit =
    withConnection { conn =>
      given Connection = conn
      val id = UUID.randomUUID().toString
      sql"""INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, body, status, attempts, received_at,
                                       finished_at)
            VALUES ('telegram', $id, '7001', 1, NULL, 'quarantined', 5, $finishedAt, $finishedAt)""".execute()
      ()
    }

  // ---------- /readyz ----------

  test("ready with an empty outbox; the report counts due rows and the age of the oldest"):
    val probes = PgOpsHealth(dataSource)
    assertEquals(
      Readiness.check(probes, () => t0),
      Readiness.Report(true, """{"status":"ready","database":"up","outbox_pending":0,"outbox_oldest_pending_seconds":0}""")
    )
    outboxRow("queued", t0.minusSeconds(90))
    outboxRow("failed_retry", t0.minusSeconds(30))
    outboxRow("failed_retry", t0.plusSeconds(600)) // backing off: not due yet
    outboxRow("sent", t0.minusSeconds(3600))
    assertEquals(
      Readiness.check(probes, () => t0),
      Readiness.Report(true, """{"status":"ready","database":"up","outbox_pending":2,"outbox_oldest_pending_seconds":90}""")
    )

  test("not ready once the oldest due row is older than five minutes"):
    outboxRow("queued", t0.minus(Readiness.MaxPendingAge).minusSeconds(1))
    val report = Readiness.check(PgOpsHealth(dataSource), () => t0)
    assertEquals(report.ready, false)
    assert(report.json.contains(""""outbox_oldest_pending_seconds":301"""), report.json)
    assert(!report.json.contains("CANARY"), "no row content in the report")

  test("not ready when the database does not answer, and the reason stays out of the body"):
    val closed = Database.pooled(pg.jdbcUrl, pg.username, pg.password)
    closed.close()
    assertEquals(
      Readiness.check(PgOpsHealth(closed), () => t0),
      Readiness.Report(false, """{"status":"not_ready","database":"down"}""")
    )

  // ---------- owner alerts ----------

  private final class Clock(var at: Instant) extends dosegram.core.ports.Clock:
    override def now(): Instant = at

  private def alertsTo(adapter: FakeAdapter, clock: Clock, logged: StringBuilder = StringBuilder()): OwnerAlerts =
    OwnerAlerts(PgOpsHealth(dataSource), adapter, ChatRef("telegram", "7001"), clock, line => logged.append(line))

  private def sentTexts(adapter: FakeAdapter): List[(String, String, Boolean)] =
    adapter.ops.collect { case VendorOp.Send(chat, message, _, _) => (chat.chatId, message.chunks.mkString, message.silent) }

  test("a dead row and a quarantined update after the start each send one silent alert to the owner's chat"):
    val clock = Clock(t0)
    val adapter = FakeAdapter(CapabilityProfiles.Telegram, vendor = "telegram")
    outboxRow("dead", t0.minusSeconds(60), attemptedAt = Some(t0.minusSeconds(60))) // before the start: not alerted
    val alerts = alertsTo(adapter, clock)
    assertEquals(alerts.checkOnce(), 0, "nothing went wrong since the start")

    clock.at = t0.plusSeconds(60)
    outboxRow("dead", t0.plusSeconds(30), attemptedAt = Some(t0.plusSeconds(30)))
    outboxRow("dead", t0.plusSeconds(40), attemptedAt = Some(t0.plusSeconds(40)))
    quarantinedUpdate(t0.plusSeconds(50))
    assertEquals(alerts.checkOnce(), 2)
    assertEquals(
      sentTexts(adapter),
      List(("7001", OpsCopy.outboxDead(2), true), ("7001", OpsCopy.inboxQuarantined(1), true))
    )
    assert(!sentTexts(adapter).exists(_._2.contains("CANARY")))

  test("at most one alert per condition per hour; the next one counts every row since the last"):
    val clock = Clock(t0)
    val adapter = FakeAdapter(CapabilityProfiles.Telegram, vendor = "telegram")
    val alerts = alertsTo(adapter, clock)
    clock.at = t0.plusSeconds(60)
    outboxRow("dead", t0.plusSeconds(30), attemptedAt = Some(t0.plusSeconds(30)))
    assertEquals(alerts.checkOnce(), 1)

    clock.at = t0.plusSeconds(600)
    outboxRow("dead", t0.plusSeconds(300), attemptedAt = Some(t0.plusSeconds(300)))
    outboxRow("dead", t0.plusSeconds(400), attemptedAt = Some(t0.plusSeconds(400)))
    assertEquals(alerts.checkOnce(), 0, "held back inside the hour")

    clock.at = t0.plusSeconds(60).plus(OwnerAlerts.Interval)
    assertEquals(alerts.checkOnce(), 1)
    assertEquals(sentTexts(adapter).map(_._2), List(OpsCopy.outboxDead(1), OpsCopy.outboxDead(2)))
    assertEquals(alerts.checkOnce(), 0, "nothing new since that alert")

  test("an alert the vendor refuses is logged by its kind only and tried again at the next pass"):
    val clock = Clock(t0)
    val adapter = FakeAdapter(CapabilityProfiles.Telegram, vendor = "telegram")
    val logged = StringBuilder()
    val alerts = alertsTo(adapter, clock, logged)
    clock.at = t0.plusSeconds(60)
    quarantinedUpdate(t0.plusSeconds(30))
    adapter.failNext(ChatError.Retryable("CANARY vendor text"))
    assertEquals(alerts.checkOnce(), 0)
    assertEquals(logged.toString, "owner alert inbox_quarantined not sent: Retryable")
    clock.at = t0.plus(Duration.ofSeconds(120))
    assertEquals(alerts.checkOnce(), 1)
    assertEquals(sentTexts(adapter).map(_._2), List(OpsCopy.inboxQuarantined(1)))
end OpsHealthSuite
