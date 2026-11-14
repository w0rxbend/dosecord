package dosegram.infra.db

import dosegram.core.domain.inventory.Inventory
import dosegram.core.domain.inventory.RefillThreshold
import dosegram.core.ports.InventoryRefusal
import dosegram.core.ports.InventoryRepository
import dosegram.core.ports.InventorySettings
import dosegram.core.ports.MedicationDepthRepositories
import dosegram.core.ports.PausedSchedule
import dosegram.core.ports.SchedulePauseRepository

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.language.implicitConversions

/** The medication-depth group (V9) on the connection of the surrounding transaction. */
final class PgMedicationDepthRepositories(conn: Connection) extends MedicationDepthRepositories:
  override lazy val inventory: InventoryRepository = PgInventoryRepository(conn)
  override lazy val pauses: SchedulePauseRepository = PgSchedulePauseRepository(conn)

/** `medication_inventory` and `inventory_events`. A taken dose changes the count through the trigger of V9, never here.
  */
final class PgInventoryRepository(conn: Connection) extends InventoryRepository:
  private given Connection = conn

  private val Columns =
    "medication_id, account_id, on_hand, per_intake, threshold_days, threshold_units, last_refill, count_off, " +
      "notices_stopped, last_notice_at, version, updated_at"

  private given RowMapper[Inventory] = rs =>
    val days = rs.optInt("threshold_days")
    val units = Option(rs.getBigDecimal("threshold_units")).map(BigDecimal(_))
    Inventory(
      medicationId = rs.uuid("medication_id"),
      accountId = rs.uuid("account_id"),
      onHand = BigDecimal(rs.getBigDecimal("on_hand")),
      perIntake = BigDecimal(rs.getBigDecimal("per_intake")),
      threshold = days.map(RefillThreshold.Days(_)).orElse(units.map(RefillThreshold.Units(_))),
      lastRefill = Option(rs.getBigDecimal("last_refill")).map(BigDecimal(_)),
      countOff = rs.getBoolean("count_off"),
      noticesStopped = rs.getBoolean("notices_stopped"),
      lastNoticeAt = rs.optInstant("last_notice_at"),
      version = rs.getInt("version"),
      updatedAt = rs.instant("updated_at")
    )

  override def get(accountId: UUID, medicationId: UUID): Option[Inventory] =
    SqlQuery(
      s"SELECT $Columns FROM medication_inventory WHERE account_id = ? AND medication_id = ?",
      Vector(accountId, medicationId)
    ).queryOne[Inventory]()

  override def listForAccount(accountId: UUID): List[Inventory] =
    SqlQuery(
      s"SELECT $Columns FROM medication_inventory WHERE account_id = ? ORDER BY medication_id",
      Vector(accountId)
    ).query[Inventory]()

  override def save(
      settings: InventorySettings,
      expectedVersion: Option[Int],
      now: Instant
  ): Either[InventoryRefusal, Inventory] =
    val owned = sql"""SELECT EXISTS (SELECT 1 FROM medications WHERE id = ${settings.medicationId}
                      AND user_id = ${settings.accountId})""".queryOne[Boolean]().contains(true)
    if !owned then Left(InventoryRefusal.NotFound)
    else if alreadyApplied(settings.key) then current(settings).toRight(InventoryRefusal.NotFound)
    else
      val (days, units) = thresholdColumns(settings.threshold)
      lockedRow(settings.accountId, settings.medicationId) match
        case None if expectedVersion.isEmpty =>
          sql"""INSERT INTO medication_inventory (medication_id, account_id, on_hand, per_intake, threshold_days,
                  threshold_units, updated_at)
                VALUES (${settings.medicationId}, ${settings.accountId}, ${settings.onHand}, ${settings.perIntake},
                        $days, $units, $now)""".execute()
          setEvent(settings, now)
          current(settings).toRight(InventoryRefusal.NotFound)
        case None                                                => Left(InventoryRefusal.NotFound)
        case Some(row) if !expectedVersion.contains(row.version) => Left(InventoryRefusal.VersionConflict(row))
        case Some(row)                                           =>
          val countChanged = row.onHand != settings.onHand
          sql"""UPDATE medication_inventory SET on_hand = ${settings.onHand}, per_intake = ${settings.perIntake},
                  threshold_days = $days, threshold_units = $units,
                  count_off = count_off AND NOT $countChanged, notices_stopped = notices_stopped AND NOT $countChanged,
                  version = version + 1, updated_at = $now
                WHERE medication_id = ${settings.medicationId}""".execute()
          if countChanged then setEvent(settings, now)
          current(settings).toRight(InventoryRefusal.NotFound)

  override def refill(
      accountId: UUID,
      medicationId: UUID,
      quantity: BigDecimal,
      key: String,
      now: Instant
  ): Option[Inventory] =
    lockedRow(accountId, medicationId).map: row =>
      if !alreadyApplied(key) then
        sql"""UPDATE medication_inventory SET on_hand = on_hand + $quantity, last_refill = $quantity,
                notices_stopped = false, count_off = false, version = version + 1, updated_at = $now
              WHERE medication_id = $medicationId""".execute()
        sql"""INSERT INTO inventory_events (id, account_id, medication_id, kind, quantity, on_hand_after,
                idempotency_key, occurred_at)
              VALUES (gen_random_uuid(), $accountId, $medicationId, 'refill', $quantity, ${row.onHand + quantity},
                      $key, $now)""".execute()
      get(accountId, medicationId).getOrElse(row)

  override def noticeCandidates(sentBefore: Instant, limit: Int): List[Inventory] =
    SqlQuery(
      s"""SELECT $Columns FROM medication_inventory
          WHERE (threshold_days IS NOT NULL OR threshold_units IS NOT NULL) AND NOT notices_stopped
            AND (last_notice_at IS NULL OR last_notice_at < ?)
          ORDER BY medication_id LIMIT ?""",
      Vector(sentBefore, limit)
    ).query[Inventory]()

  override def markNotice(medicationId: UUID, at: Instant): Unit =
    sql"""UPDATE medication_inventory SET last_notice_at = $at, count_off = false
          WHERE medication_id = $medicationId""".execute()
    ()

  override def stopNotices(accountId: UUID, medicationId: UUID, now: Instant): Boolean =
    sql"""UPDATE medication_inventory SET notices_stopped = true, version = version + 1, updated_at = $now
          WHERE account_id = $accountId AND medication_id = $medicationId""".execute() == 1

  override def clearCountOff(medicationId: UUID, now: Instant): Unit =
    sql"""UPDATE medication_inventory SET count_off = false, updated_at = $now
          WHERE medication_id = $medicationId AND count_off""".execute()
    ()

  private def current(settings: InventorySettings): Option[Inventory] = get(settings.accountId, settings.medicationId)

  private def lockedRow(accountId: UUID, medicationId: UUID): Option[Inventory] =
    SqlQuery(
      s"SELECT $Columns FROM medication_inventory WHERE account_id = ? AND medication_id = ? FOR UPDATE",
      Vector(accountId, medicationId)
    ).queryOne[Inventory]()

  private def alreadyApplied(key: String): Boolean =
    sql"SELECT EXISTS (SELECT 1 FROM inventory_events WHERE idempotency_key = $key)".queryOne[Boolean]().contains(true)

  private def setEvent(settings: InventorySettings, now: Instant): Unit =
    sql"""INSERT INTO inventory_events (id, account_id, medication_id, kind, quantity, on_hand_after, idempotency_key,
            occurred_at)
          VALUES (gen_random_uuid(), ${settings.accountId}, ${settings.medicationId}, 'set', ${settings.onHand},
                  ${settings.onHand}, ${settings.key}, $now)""".execute()
    ()

  private def thresholdColumns(threshold: Option[RefillThreshold]): (Option[Int], Option[BigDecimal]) =
    threshold match
      case Some(RefillThreshold.Days(days))   => (Some(days), None)
      case Some(RefillThreshold.Units(units)) => (None, Some(units))
      case None                               => (None, None)
end PgInventoryRepository

/** `medication_schedules.resume_on` and `end_date`. */
final class PgSchedulePauseRepository(conn: Connection) extends SchedulePauseRepository:
  private given Connection = conn

  override def setResumeOn(scheduleId: UUID, resumeOn: Option[LocalDate], now: Instant): Unit =
    sql"UPDATE medication_schedules SET resume_on = $resumeOn, updated_at = $now WHERE id = $scheduleId".execute()
    ()

  override def resumeOn(scheduleId: UUID): Option[LocalDate] =
    sql"SELECT resume_on FROM medication_schedules WHERE id = $scheduleId AND resume_on IS NOT NULL"
      .queryOne[LocalDate]()

  override def dueResumes(now: Instant, limit: Int): List[PausedSchedule] =
    given RowMapper[PausedSchedule] = rs =>
      PausedSchedule(rs.uuid("id"), rs.uuid("user_id"), rs.getObject("resume_on", classOf[LocalDate]))
    sql"""SELECT id, user_id, resume_on FROM medication_schedules
          WHERE status = 'paused' AND resume_on IS NOT NULL AND resume_on <= ($now AT TIME ZONE tz)::date
          ORDER BY resume_on, id LIMIT $limit FOR UPDATE SKIP LOCKED""".query[PausedSchedule]()

  override def setEndDate(scheduleId: UUID, endDate: Option[LocalDate], now: Instant): Unit =
    sql"UPDATE medication_schedules SET end_date = $endDate, updated_at = $now WHERE id = $scheduleId".execute()
    ()
end PgSchedulePauseRepository
