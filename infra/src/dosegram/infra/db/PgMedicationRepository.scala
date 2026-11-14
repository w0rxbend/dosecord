package dosegram.infra.db

import dosegram.core.ports.KeyService
import dosegram.core.ports.MedicationEdit
import dosegram.core.ports.MedicationRepository
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.NewMedication
import dosegram.core.ports.StoredMedication

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `medications`. The name and the instructions are sealed with the account's data key (ROADMAP G2.4,
  * ARCHITECTURE SEC-01); `name_hmac`, the keyed hash of the normalised name, replaces `name_norm` for find-or-create
  * and for the one-live-medication-per-name index. A prefix search opens the account's names and filters them here: a
  * keyed hash matches whole names only.
  */
final class PgMedicationRepository(conn: Connection, keys: KeyService) extends MedicationRepository:
  import SealedColumns.MedicationInstructions
  import SealedColumns.MedicationName

  private given Connection = conn

  private val Columns =
    "id, user_id, name_enc, dose_amount, dose_unit, instructions_enc, status, created_at, updated_at"

  private given RowMapper[StoredMedication] = rs =>
    val id = rs.uuid("id")
    val accountId = rs.uuid("user_id")
    val name = keys.openText(MedicationName.forAccount(accountId, id), rs.getBytes("name_enc"))
    StoredMedication(
      id = id,
      accountId = accountId,
      name = name,
      nameNorm = SealedColumns.medicationNameNorm(name),
      doseAmount = Option(rs.getBigDecimal("dose_amount")).map(BigDecimal(_)),
      doseUnit = rs.optString("dose_unit"),
      instructions = Option(rs.getBytes("instructions_enc"))
        .map(keys.openText(MedicationInstructions.forAccount(accountId, id), _)),
      status = MedicationStatus.fromDbValue(rs.getString("status")),
      createdAt = rs.instant("created_at"),
      updatedAt = rs.instant("updated_at")
    )

  private final case class Sealed(name: Array[Byte], nameHmac: Array[Byte], instructions: Option[Array[Byte]])

  private def sealedOf(accountId: UUID, id: UUID, name: String, instructions: Option[String]): Sealed =
    Sealed(
      keys.sealText(MedicationName.forAccount(accountId, id), name),
      keys.lookup(MedicationName, accountId, name),
      instructions.map(keys.sealText(MedicationInstructions.forAccount(accountId, id), _))
    )

  private def select(where: String, binds: SqlBind*): List[StoredMedication] =
    SqlQuery(s"SELECT $Columns FROM medications WHERE $where", binds.toVector).query[StoredMedication]()

  override def insert(row: NewMedication, now: Instant): Unit =
    val sealedRow = sealedOf(row.accountId, row.id, row.name, row.instructions)
    sql"""INSERT INTO medications (id, user_id, name_enc, name_hmac, dose_amount, dose_unit, instructions_enc, status,
                                   created_at, updated_at)
          VALUES (${row.id}, ${row.accountId}, ${sealedRow.name}, ${sealedRow.nameHmac}, ${row.doseAmount}, ${row.doseUnit},
                  ${sealedRow.instructions}, 'active', $now, $now)""".execute()
    ()

  override def get(id: UUID): Option[StoredMedication] =
    select("id = ?", id).headOption

  override def findByNameNorm(accountId: UUID, nameNorm: String): Option[StoredMedication] =
    select(
      "user_id = ? AND name_hmac = ? AND status <> 'archived'",
      accountId,
      keys.lookup(MedicationName, accountId, nameNorm)
    ).headOption

  override def listForAccount(accountId: UUID): List[StoredMedication] =
    select("user_id = ? AND status <> 'archived' ORDER BY created_at, id", accountId)

  override def searchByNameNormPrefix(accountId: UUID, prefix: String, limit: Int): List[StoredMedication] =
    listForAccount(accountId).filter(_.nameNorm.startsWith(prefix)).sortBy(_.nameNorm).take(limit)

  override def listAllForAccount(accountId: UUID): List[StoredMedication] =
    select("user_id = ? ORDER BY created_at, id", accountId)

  override def replace(edit: MedicationEdit, now: Instant): Option[StoredMedication] =
    val sealedRow = sealedOf(edit.accountId, edit.id, edit.name, edit.instructions)
    SqlQuery(
      s"""UPDATE medications
          SET name_enc = ?, name_hmac = ?, dose_amount = ?, dose_unit = ?, instructions_enc = ?, status = ?,
              updated_at = GREATEST(?, updated_at + interval '1 microsecond')
          WHERE id = ? AND user_id = ? AND updated_at = ?
          RETURNING $Columns""",
      Vector[SqlBind](
        sealedRow.name,
        sealedRow.nameHmac,
        edit.doseAmount,
        edit.doseUnit,
        sealedRow.instructions,
        edit.status.dbValue,
        now,
        edit.id,
        edit.accountId,
        edit.expectedUpdatedAt
      )
    ).queryOne[StoredMedication]()
