package dosegram.infra.db

import dosegram.core.ports.InboxEntry
import dosegram.core.ports.InboundInbox
import dosegram.core.ports.PendingInboxEntry

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** `inbound_inbox` on Testcontainers Postgres 18 (ROADMAP G1.7, ARCHITECTURE sections 7.2 and 15.6): the durable store
  * of received updates, its per-chat order, its quarantine and its deleting job under a fake clock. A second store
  * object over the same database stands for a restarted process.
  */
class InboundInboxPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-13T09:00:00Z")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection(_.createStatement().execute("TRUNCATE inbound_inbox, inbound_events"))

  private def entry(sequence: Long, chat: String = "7001"): InboxEntry =
    InboxEntry("telegram", s"telegram:update:$sequence", chat, sequence, s"""{"update_id":$sequence}""")

  private def count(query: String): Int =
    withConnection: conn =>
      val rows = conn.createStatement().executeQuery(query) // a query of this suite, never input
      rows.next()
      rows.getInt(1)

  private def bodies: Int = count("SELECT count(*) FROM inbound_inbox WHERE body IS NOT NULL")
  private def rows: Int = count("SELECT count(*) FROM inbound_inbox")

  private def inboundEvent(id: String, receivedAt: Instant): Unit =
    withConnection: conn =>
      given Connection = conn
      sql"""INSERT INTO inbound_events (vendor, vendor_event_id, event_id, received_at)
            VALUES ('telegram', $id, ${UUID.randomUUID()}, $receivedAt)""".execute()
      ()

  test("a stored entry is pending for another store object, with its body and its receipt time"):
    PgInboundInbox(dataSource).store(entry(1), t0)
    assertEquals(PgInboundInbox(dataSource).pending(perChat = 10, limit = 10), List(PendingInboxEntry(entry(1), t0)))

  test("TG-04: the same update stored twice is one row, and a processed one is not offered again"):
    val inbox = PgInboundInbox(dataSource)
    inbox.store(entry(1), t0)
    inbox.store(entry(1), t0.plusSeconds(5))
    assertEquals(rows, 1)
    inbox.markProcessed(entry(1), t0.plusSeconds(1))
    inbox.store(entry(1), t0.plusSeconds(10))
    assertEquals(rows, 1)
    assertEquals(inbox.pending(10, 10), Nil)

  test("per chat the pending entries come in sequence order, at most perChat of each chat"):
    val inbox = PgInboundInbox(dataSource)
    List(entry(5), entry(3, "8002"), entry(2), entry(4), entry(1, "8002")).foreach(inbox.store(_, t0))
    val pending = inbox.pending(perChat = 2, limit = 10).map(_.entry)
    assertEquals(pending.filter(_.chatKey == "7001").map(_.sequence), List(2L, 4L))
    assertEquals(pending.filter(_.chatKey == "8002").map(_.sequence), List(1L, 3L))
    assertEquals(inbox.pending(perChat = 10, limit = 1).size, 1)

  test("a processed entry loses its body at once and the next one of its chat comes up"):
    val inbox = PgInboundInbox(dataSource)
    inbox.store(entry(1), t0)
    inbox.store(entry(2), t0)
    inbox.markProcessed(entry(1), t0.plusSeconds(1))
    assertEquals(bodies, 1)
    assertEquals(inbox.pending(perChat = 1, limit = 10).map(_.entry.sequence), List(2L))

  test("TG-09: the third failure quarantines an entry and its chat moves on"):
    val inbox = PgInboundInbox(dataSource)
    inbox.store(entry(1), t0)
    inbox.store(entry(2), t0)
    assert(!inbox.recordFailure(entry(1), maxAttempts = 3, t0))
    assert(!inbox.recordFailure(entry(1), maxAttempts = 3, t0))
    assertEquals(inbox.pending(perChat = 1, limit = 10).map(_.entry.sequence), List(1L), "still pending")
    assert(inbox.recordFailure(entry(1), maxAttempts = 3, t0))
    assertEquals(inbox.pending(perChat = 1, limit = 10).map(_.entry.sequence), List(2L))
    assertEquals(count("SELECT count(*) FROM inbound_inbox WHERE status = 'quarantined' AND body IS NOT NULL"), 1)

  test("under a fake clock a quarantined body is gone after 24 hours and not before"):
    val inbox = PgInboundInbox(dataSource)
    inbox.store(entry(1), t0)
    (1 to 3).foreach(_ => inbox.recordFailure(entry(1), maxAttempts = 3, t0))
    assertEquals(inbox.purge(t0.plus(InboundInbox.BodyLifetime).minusSeconds(1)).bodies, 0)
    assertEquals(bodies, 1)
    assertEquals(inbox.purge(t0.plus(InboundInbox.BodyLifetime)).bodies, 1)
    assertEquals(bodies, 0)
    assertEquals(rows, 1, "the row without its body stays")

  test("a pending body older than 24 hours is deleted too, and the entry is no longer offered"):
    val inbox = PgInboundInbox(dataSource)
    inbox.store(entry(1), t0)
    inbox.purge(t0.plus(InboundInbox.BodyLifetime))
    assertEquals(bodies, 0)
    assertEquals(inbox.pending(10, 10), Nil)

  test("under a fake clock an inbox row and an inbound_events row are gone after 7 days and not before"):
    val inbox = PgInboundInbox(dataSource)
    inbox.store(entry(1), t0)
    inbox.markProcessed(entry(1), t0)
    inboundEvent("telegram:update:1", t0)
    val before = inbox.purge(t0.plus(InboundInbox.RowLifetime).minusSeconds(1))
    assertEquals((before.inboxRows, before.inboundEvents), (0, 0))
    assertEquals((rows, count("SELECT count(*) FROM inbound_events")), (1, 1))
    val after = inbox.purge(t0.plus(InboundInbox.RowLifetime))
    assertEquals((after.inboxRows, after.inboundEvents), (1, 1))
    assertEquals((rows, count("SELECT count(*) FROM inbound_events")), (0, 0))

  test("the schema refuses a pending row without a body and a processed row with one"):
    withConnection: conn =>
      given Connection = conn
      intercept[java.sql.SQLException]:
        sql"""INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, received_at)
              VALUES ('telegram', 'telegram:update:9', '7001', 9, $t0)""".execute()
      intercept[java.sql.SQLException]:
        sql"""INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, body, status, received_at,
                finished_at)
              VALUES ('telegram', 'telegram:update:9', '7001', 9, '{}', 'processed', $t0, $t0)""".execute()

  // The suites connect as the owner of the schema; production connects as a member of dosegram_app.
  test("the application role may store, process and run the deleting job"):
    withFreshConnection: conn =>
      val statement = conn.createStatement()
      statement.execute("SET ROLE dosegram_app")
      statement.execute(
        "INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, body, received_at) " +
          "VALUES ('telegram', 'telegram:update:1', '7001', 1, '{}', '2026-11-13T09:00:00Z')"
      )
      statement.execute("UPDATE inbound_inbox SET attempts = 1 WHERE false")
      statement.execute("DELETE FROM inbound_inbox WHERE false")
      statement.execute("DELETE FROM inbound_events WHERE false")

  test("BodyLifetime and RowLifetime are the periods of ARCHITECTURE section 15.6"):
    assertEquals(InboundInbox.BodyLifetime, Duration.ofHours(24))
    assertEquals(InboundInbox.RowLifetime, Duration.ofDays(7))
