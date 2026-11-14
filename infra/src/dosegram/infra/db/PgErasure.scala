package dosegram.infra.db

import dosegram.core.ports.DataDomain
import dosegram.core.ports.ErasureReceipt
import dosegram.core.ports.ErasureRepository

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** The erasure job of ARCHITECTURE SEC-16 and SEC-17 on the connection of the surrounding transaction: the deletes of
  * [[ErasureCoverage]] in its order, under the erasure role with the erasure GUC (the only way past the append-only
  * guards), then back to the session role for the receipt. The login role must be a superuser or a member of
  * `dosegram_erasure`; otherwise the first statement fails and the transaction rolls back with nothing deleted.
  */
final class PgErasure(conn: Connection) extends ErasureRepository:
  private given Connection = conn

  override def eraseAccount(accountId: UUID, tombstoneId: UUID, now: Instant): ErasureReceipt =
    val counts = underErasure:
      val deleted = ErasureCoverage.tables.collect:
        case (table, Erasure.Domain(predicate, _))      => table -> delete(table, predicate, accountId)
        case (table, Erasure.AccountWide(predicate, _)) => table -> delete(table, predicate, accountId)
      deleted :+ ("audit_log" -> pseudonymise(accountId, tombstoneId))
    receipt(tombstoneId, "account", counts, now)

  override def eraseDomain(accountId: UUID, domain: DataDomain, tombstoneId: UUID, now: Instant): ErasureReceipt =
    val counts = underErasure:
      ErasureCoverage.tables.collect:
        case (table, Erasure.Domain(_, byDomain)) if byDomain.contains(domain) =>
          table -> delete(table, byDomain(domain), accountId)
    receipt(tombstoneId, domain.code, counts, now)

  /** `f` under the erasure role; the role and the GUC are back to the session's afterwards. On an exception the
    * transaction rolls back, and both settings with it.
    */
  private def underErasure[A](f: => A): A =
    statement("SET LOCAL ROLE dosegram_erasure")
    statement("SET LOCAL dosegram.erasure = 'on'")
    val result = f
    statement("SET LOCAL dosegram.erasure = 'off'")
    statement("SET LOCAL ROLE NONE")
    result

  private def statement(text: String): Unit =
    val st = conn.createStatement()
    try
      st.execute(text)
      ()
    finally st.close()

  /** The predicate is registry text, never a value; every `?` in it is the account. */
  private def delete(table: String, predicate: String, accountId: UUID): Int =
    val binds = Vector.fill(predicate.count(_ == '?'))(accountId: SqlBind)
    SqlQuery(s"DELETE FROM $table WHERE $predicate", binds).execute()

  /** Brief 06 section 10.4 item 6: the account's audit rows stay, with the tombstone in place of the account. */
  private def pseudonymise(accountId: UUID, tombstoneId: UUID): Int =
    val account = accountId.toString
    sql"""UPDATE audit_log
          SET subject_ref = CASE WHEN subject_ref = $accountId THEN $tombstoneId ELSE subject_ref END,
              actor_ref = CASE WHEN actor_ref = $account THEN ${tombstoneId.toString} ELSE actor_ref END
          WHERE subject_ref = $accountId OR actor_ref = $account""".execute()

  private def receipt(tombstoneId: UUID, scope: String, counts: List[(String, Int)], now: Instant): ErasureReceipt =
    val rowCounts = counts.filter(_._2 > 0).toMap
    sql"""INSERT INTO deletion_receipts (tombstone_id, scope, deleted_at, row_counts)
          VALUES ($tombstoneId, $scope, $now, ${Jsonb(upickle.default.write(rowCounts))})""".execute()
    ErasureReceipt(tombstoneId, rowCounts)

end PgErasure
