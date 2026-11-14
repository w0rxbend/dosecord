package dosegram.infra.db

import dosegram.core.application.exports.ExportMessages
import dosegram.core.application.exports.ExportWorker
import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.insights.RollupService
import dosegram.core.ports.IdSource
import dosegram.core.ports.RecordSummaryRenderer
import dosegram.core.ports.Wake
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.ReminderLoop
import dosegram.infra.FilePurgeList

import java.nio.file.Files
import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import scala.language.implicitConversions

/** Finding 1 of the verification of 2026-11-14 on Testcontainers Postgres 18, from the production code path: a user
  * onboarded through the chat adds a medication with the chat wizard, gets its reminder, lists it with `/meds` and
  * exports the records. No column of any table then holds the medication's name or instructions as plaintext: the
  * stored replies and the queued messages that carry them are sealed (V13).
  */
class TransportSealingPgSuite extends PgSuite:

  private val name = "Zyxcanary"
  private val instructions = "canary-after-breakfast"

  private def tables: List[String] =
    withConnection: conn =>
      given Connection = conn
      sql"""SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name""".query[String]()

  /** Every text, JSON and byte column of the schema, as `table.column`, that holds `needle` in some row. */
  private def columnsHolding(needle: String): List[String] =
    withConnection: conn =>
      given Connection = conn
      given RowMapper[(String, String, String)] = rs => (rs.getString(1), rs.getString(2), rs.getString(3))
      val columns = sql"""SELECT table_name::text, column_name::text, data_type::text FROM information_schema.columns
                          WHERE table_schema = 'public'
                            AND data_type IN ('text', 'jsonb', 'json', 'bytea', 'character varying', 'ARRAY')"""
        .query[(String, String, String)]()
        .filter((table, _, _) => tables.contains(table))
      columns.flatMap: (table, column, kind) =>
        val test =
          if kind == "bytea" then s"position(convert_to(?, 'UTF8') IN \"$column\") > 0"
          else s"lower(\"$column\"::text) LIKE '%' || lower(?) || '%'"
        val st = conn.prepareStatement(s"SELECT count(*) FROM $table WHERE $test")
        try
          st.setString(1, needle)
          val rs = st.executeQuery()
          rs.next()
          Option.when(rs.getInt(1) > 0)(s"$table.$column")
        finally st.close()

  test("after a reminder, a /meds reply and an export no column holds a medication name or its instructions"):
    ox.supervised:
      val purge = FilePurgeList(Files.createTempFile("purge-list", ".txt"))
      val rig = ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), purge, "seal-1")
      rig.onboard()
      rig.line("/meds")
      rig.consent()
      rig.tap("No medications yet", "Add medication")
      rig.line(name)
      rig.line("5 mg")
      rig.tap("When should I remind you", "09:00")
      rig.line(instructions)
      rig.tap("Which days?", "Every day")
      rig.tap(name, "Create")
      // The 09:00 reminder: the loop queues it five minutes after the start, which is 09:00 in Kyiv.
      rig.clock.advance(Duration.ofMinutes(5))
      ReminderLoop(rig.uow, Materialiser(rig.uow, rig.clock), Wake.polling, rig.clock, "sealing-suite")
        .tick(rig.clock.now())
      val reminders = withConnection: conn =>
        given Connection = conn
        sql"SELECT count(*) FROM outbox_messages WHERE kind = 'reminder'".queryOne[Int]()
      assertEquals(reminders, Some(1), "the reminder is queued")
      assert(rig.line("/meds").exists(_.contains(name)), "the /meds reply names the medication")
      rig.line("/export")
      rig.tap("Export all your records", "Export my data")
      val worker = ExportWorker(rig.uow, rig.clock, IdSource.uuidV7(SecureRandom()),
        RecordSummaries(RollupService(rig.uow, rig.clock)), (_ => Right("%PDF".getBytes)): RecordSummaryRenderer,
        ExportMessages(rig.codec), _ => ())
      assert(worker.runOnce(), "the export is made")

      assertEquals(columnsHolding(name), Nil, "columns that hold the medication name")
      assertEquals(columnsHolding(instructions), Nil, "columns that hold the instructions")
