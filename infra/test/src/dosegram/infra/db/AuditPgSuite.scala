package dosegram.infra.db

import dosegram.core.application.Application
import dosegram.core.application.exports.ExportMessages
import dosegram.core.application.exports.ExportOrder
import dosegram.core.application.exports.ExportWorker
import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.insights.RollupService
import dosegram.core.ports.AuditEventType
import dosegram.core.ports.IdSource
import dosegram.core.ports.RecordSummaryRenderer
import dosegram.core.ports.TicketRedemption
import dosegram.infra.FilePurgeList

import java.nio.file.Files
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import scala.language.implicitConversions

/** ROADMAP G2.16 on Testcontainers Postgres 18: `audit_log` is append-only for the application role and for the owner
  * outside erasure; every event of the catalogue that a use case of this version produces is written by it, on the
  * product's code path; and no audit row holds a medication name, a meal's words or any message text.
  */
class AuditPgSuite extends PgSuite:

  /** The written events that the API suites drive (the session service lives in the `api` module). */
  private val drivenByApi: Set[AuditEventType] = Set(AuditEventType.SessionCreated, AuditEventType.SessionRevoked)

  private def withRig(user: String)(test: ProductChatRig => Unit): Unit =
    val purge = FilePurgeList(Files.createTempFile("purge", ".txt"))
    ox.supervised(test(ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), purge, user)))

  private def auditRows(): List[String] =
    withConnection: conn =>
      given Connection = conn
      sql"SELECT r::text FROM audit_log AS r".query[String]()

  private def insertRow(conn: Connection): UUID =
    given Connection = conn
    val id = UUID.randomUUID()
    sql"""INSERT INTO audit_log (id, occurred_at, event_type, actor_type, actor_ref, object_type, action, outcome, channel)
          VALUES ($id, ${ProductChatRig.Start}, 'consent.granted', 'system', 'test', 'consent', 'grant', 'success', 'bot')"""
      .execute()
    id

  test("an UPDATE or DELETE of audit_log is refused for the application role and for the owner outside erasure"):
    withFreshConnection: conn =>
      given Connection = conn
      val id = insertRow(conn)
      conn.createStatement().execute("SET ROLE dosegram_app")
      intercept[SQLException](sql"UPDATE audit_log SET outcome = 'denied' WHERE id = $id".execute())
      intercept[SQLException](sql"DELETE FROM audit_log WHERE id = $id".execute())
      conn.createStatement().execute("RESET ROLE")
      intercept[SQLException](sql"UPDATE audit_log SET outcome = 'denied' WHERE id = $id".execute())
      intercept[SQLException](sql"DELETE FROM audit_log WHERE id = $id".execute())
      assertEquals(sql"SELECT outcome FROM audit_log WHERE id = $id".queryOne[String](), Some("success"))

  test("under erasure only actor_ref and subject_ref may change"):
    withFreshConnection: conn =>
      given Connection = conn
      val id = insertRow(conn)
      conn.setAutoCommit(false)
      conn.createStatement().execute("SET LOCAL ROLE dosegram_erasure")
      conn.createStatement().execute("SET LOCAL dosegram.erasure = 'on'")
      assertEquals(sql"UPDATE audit_log SET actor_ref = 'tombstone' WHERE id = $id".execute(), 1)
      intercept[SQLException](sql"UPDATE audit_log SET event_type = 'record.updated' WHERE id = $id".execute())
      conn.rollback()

  test("free text cannot enter: every code column is checked by shape"):
    withConnection: conn =>
      given Connection = conn
      intercept[SQLException]:
        sql"""INSERT INTO audit_log (id, occurred_at, event_type, actor_type, actor_ref, object_type, action, outcome,
                                     channel, reason_code)
              VALUES (${UUID.randomUUID()}, ${ProductChatRig.Start}, 'consent.granted', 'system', 'test', 'consent', 'grant', 'success',
                      'bot', 'took Ibuprofen 400 mg')""".execute()

  test("every written event of the catalogue is written by its use case, and no row holds health content"):
    withRig("audit-1"): rig =>
      val account = rig.onboard()
      rig.line("lunch: canary soup with secretword")
      rig.consent()
      rig.line("lunch: canary soup with secretword")
      rig.line("/language")
      rig.tap("Choose the language", "English")
      rig.line("/meds")
      rig.consent()
      rig.line("/privacy")
      rig.tap("Your consents", "Withdraw: meal records")
      // An export asked for by the app and confirmed in the chat, made by the worker, fetched with a ticket.
      val ids = IdSource.uuidV7(SecureRandom())
      val requests = Application.exportRequests(rig.clock, rig.codec)
      val job = rig.uow.transaction(requests.fromApp(_, account, ExportOrder.FullArchive)).toOption.get
      rig.tapQueued("The app asked for an export", "Make it")
      val worker = ExportWorker(
        rig.uow,
        rig.clock,
        ids,
        RecordSummaries(RollupService(rig.uow, rig.clock)),
        (_ => Right("%PDF".getBytes)): RecordSummaryRenderer,
        ExportMessages(rig.codec),
        _ => ()
      )
      while worker.runOnce() do ()
      val ticket = Array.fill[Byte](32)(7)
      rig.uow.transaction(
        _.exports.issueTicket(account, job.id, ticket, rig.clock.now(), rig.clock.now().plusSeconds(60))
      )
      assert(rig.uow.transaction(_.exports.redeem(TicketRedemption(ticket, "10.0.0"), rig.clock.now())).isRight)
      // The deletions, from the app and from the chat.
      val deletions = Application.deletionRequests(rig.clock, rig.codec)
      assert(rig.uow.transaction(deletions.account(_, account)))
      rig.tapQueued("The app asked to delete your account", "Continue")
      rig.line("/privacy")
      rig.tap("Your consents", "Delete data")
      rig.tap("What do you want to delete?", "Delete water records")
      rig.tap("Delete your water records?", "Delete this data")
      rig.tap("Last step", "Delete my account")
      rig.line("/start")
      rig.tap("Welcome to Dosegram", "Water")
      rig.tap("I agree that", "I agree")
      rig.tap("Please confirm", "Confirm")
      rig.tap("Pick your timezone", "Europe/Kyiv")
      rig.tap("Correct?", "Yes")
      rig.line("/menu")
      rig.tap("What would you like to do?", "Account")
      rig.tap("Account", "Timezone")
      rig.tap("Pick your timezone", "Europe/Kyiv")
      rig.tap("Correct?", "Yes")

      val written = withConnection: conn =>
        given Connection = conn
        sql"SELECT DISTINCT event_type FROM audit_log".query[String]().toSet
      val expected = AuditEventType.values.filter(_.written).toSet -- drivenByApi
      assertEquals(expected.map(_.code) -- written, Set.empty[String], "written events no row shows")
      assertEquals(
        written -- AuditEventType.values.filter(_.written).map(_.code).toSet,
        Set.empty[String],
        "rows of an event the catalogue does not mark as written"
      )
      val rows = auditRows()
      List("canary", "secretword", "Nick", "audit-1").foreach: needle =>
        assertEquals(rows.filter(_.contains(needle)), Nil, s"an audit row holds '$needle'")
