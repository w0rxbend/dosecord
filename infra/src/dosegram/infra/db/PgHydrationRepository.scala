package dosegram.infra.db

import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.hydration.EnteredAmount
import dosegram.core.domain.hydration.GoalChange
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.hydration.HydrationUnit
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.DateRange
import dosegram.core.ports.HydrationRepository

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `hydration_entries`, `hydration_goals` and `hydration_presets`. The rollup queue is fed by the triggers of
  * the baseline schema, in the transaction of each write here.
  */
final class PgHydrationRepository(conn: Connection) extends HydrationRepository:
  private given Connection = conn

  private given RowMapper[HydrationEntry] = rs =>
    HydrationEntry(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      occurredAt = rs.instant("occurred_at"),
      localDate = rs.localDate("local_date"),
      zone = ZoneId.of(rs.getString("tz")),
      amount = WaterAmount(
        rs.getInt("amount_ml"),
        EnteredAmount(
          BigDecimal(rs.getBigDecimal("entered_value")),
          HydrationUnit.fromDbValue(rs.getString("entered_unit"))
        )
      ),
      source = EntrySource.fromDbValue(rs.getString("source")),
      clientKey = rs.optString("client_key"),
      createdAt = rs.instant("created_at"),
      voidedAt = rs.optInstant("voided_at")
    )

  private given RowMapper[GoalChange] = rs => GoalChange(rs.localDate("effective_from"), rs.optInt("target_ml"))

  override def insert(entry: HydrationEntry): Boolean =
    sql"""INSERT INTO hydration_entries
            (id, account_id, occurred_at, local_date, tz, source, client_key, amount_ml, entered_value,
             entered_unit, created_at)
          VALUES (${entry.id}, ${entry.accountId}, ${entry.occurredAt}, ${entry.localDate}, ${entry.zone.getId},
                  ${entry.source.dbValue}, ${entry.clientKey}, ${entry.amount.millilitres},
                  ${entry.amount.entered.value}, ${entry.amount.entered.unit.dbValue}, ${entry.createdAt})
          ON CONFLICT (account_id, client_key) DO NOTHING""".execute() == 1

  override def get(accountId: UUID, id: UUID): Option[HydrationEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, amount_ml, entered_value,
                 entered_unit, created_at, voided_at
          FROM hydration_entries WHERE id = $id AND account_id = $accountId""".queryOne[HydrationEntry]()

  override def findByClientKey(accountId: UUID, clientKey: String): Option[HydrationEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, amount_ml, entered_value,
                 entered_unit, created_at, voided_at
          FROM hydration_entries WHERE account_id = $accountId AND client_key = $clientKey"""
      .queryOne[HydrationEntry]()

  override def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    sql"""UPDATE hydration_entries SET voided_at = $now, void_reason = ${reason.dbValue}
          WHERE id = $id AND account_id = $accountId AND voided_at IS NULL""".execute() == 1

  override def listLive(accountId: UUID, range: DateRange): List[HydrationEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, amount_ml, entered_value,
                 entered_unit, created_at, voided_at
          FROM hydration_entries
          WHERE account_id = $accountId AND local_date >= ${range.start} AND local_date <= ${range.end}
            AND voided_at IS NULL
          ORDER BY occurred_at, id""".query[HydrationEntry]()

  override def setGoal(accountId: UUID, change: GoalChange, now: Instant): Unit =
    sql"""INSERT INTO hydration_goals (account_id, effective_from, target_ml, created_at)
          VALUES ($accountId, ${change.effectiveFrom}, ${change.targetMl}, $now)
          ON CONFLICT (account_id, effective_from) DO UPDATE SET target_ml = EXCLUDED.target_ml""".execute()
    ()

  override def goals(accountId: UUID, upTo: LocalDate): List[GoalChange] =
    sql"""SELECT effective_from, target_ml FROM hydration_goals
          WHERE account_id = $accountId AND effective_from <= $upTo
          ORDER BY effective_from""".query[GoalChange]()

  override def presets(accountId: UUID): List[Int] =
    sql"SELECT amount_ml FROM hydration_presets WHERE account_id = $accountId ORDER BY slot".query[Int]()

  override def replacePresets(accountId: UUID, amountsMl: List[Int]): Unit =
    sql"DELETE FROM hydration_presets WHERE account_id = $accountId".execute()
    amountsMl.zipWithIndex.foreach { (amount, index) =>
      sql"""INSERT INTO hydration_presets (account_id, slot, amount_ml)
            VALUES ($accountId, ${index + 1}, $amount)""".execute()
    }
