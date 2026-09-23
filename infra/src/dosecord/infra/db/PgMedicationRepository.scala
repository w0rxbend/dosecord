package dosecord.infra.db

import dosecord.core.ports.MedicationRepository
import dosecord.core.ports.MedicationStatus
import dosecord.core.ports.NewMedication
import dosecord.core.ports.StoredMedication

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

final class PgMedicationRepository(conn: Connection) extends MedicationRepository:
  private given Connection = conn

  private given RowMapper[StoredMedication] = rs =>
    StoredMedication(
      id = rs.uuid("id"),
      accountId = rs.uuid("user_id"),
      name = rs.getString("name"),
      nameNorm = rs.getString("name_norm"),
      doseAmount = Option(rs.getBigDecimal("dose_amount")).map(BigDecimal(_)),
      doseUnit = rs.optString("dose_unit"),
      instructions = rs.optString("instructions"),
      status = MedicationStatus.fromDbValue(rs.getString("status")),
      createdAt = rs.instant("created_at")
    )

  override def insert(row: NewMedication, now: Instant): Unit =
    sql"""INSERT INTO medications (id, user_id, name, dose_amount, dose_unit, instructions, status,
                                   created_at, updated_at)
          VALUES (${row.id}, ${row.accountId}, ${row.name}, ${row.doseAmount}, ${row.doseUnit},
                  ${row.instructions}, 'active', $now, $now)""".execute()

  override def get(id: UUID): Option[StoredMedication] =
    sql"""SELECT id, user_id, name, name_norm, dose_amount, dose_unit, instructions, status, created_at
          FROM medications WHERE id = $id""".queryOne[StoredMedication]()

  override def findByNameNorm(accountId: UUID, nameNorm: String): Option[StoredMedication] =
    sql"""SELECT id, user_id, name, name_norm, dose_amount, dose_unit, instructions, status, created_at
          FROM medications
          WHERE user_id = $accountId AND name_norm = $nameNorm AND status <> 'archived'"""
      .queryOne[StoredMedication]()

  override def listForAccount(accountId: UUID): List[StoredMedication] =
    sql"""SELECT id, user_id, name, name_norm, dose_amount, dose_unit, instructions, status, created_at
          FROM medications
          WHERE user_id = $accountId AND status <> 'archived'
          ORDER BY created_at, id""".query[StoredMedication]()

  // strpos rather than LIKE: a prefix containing LIKE wildcards must stay literal.
  override def searchByNameNormPrefix(accountId: UUID, prefix: String, limit: Int): List[StoredMedication] =
    sql"""SELECT id, user_id, name, name_norm, dose_amount, dose_unit, instructions, status, created_at
          FROM medications
          WHERE user_id = $accountId AND status <> 'archived' AND strpos(name_norm, $prefix) = 1
          ORDER BY name_norm
          LIMIT $limit""".query[StoredMedication]()
