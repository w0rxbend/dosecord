package dosegram.infra.db

import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealTextTooLong
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.MealRepository

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `meals` and `meal_plans` (V4). The rollup queue is fed by the trigger of V4, in the transaction of each
  * write here. The words of a meal are read only to be returned to their owner; no query of a figure selects them.
  */
final class PgMealRepository(conn: Connection) extends MealRepository:
  private given Connection = conn

  private given RowMapper[MealEntry] = rs =>
    MealEntry(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      occurredAt = rs.instant("occurred_at"),
      localDate = rs.localDate("local_date"),
      zone = ZoneId.of(rs.getString("tz")),
      mealType = mealType(rs.getString("meal_type")),
      text = rs.optString("description").flatMap(stored(rs, _)),
      source = EntrySource.fromDbValue(rs.getString("source")),
      clientKey = rs.optString("client_key"),
      supersedesId = rs.optUuid("supersedes_id"),
      createdAt = rs.instant("created_at"),
      voidedAt = rs.optInstant("voided_at")
    )

  /** A stored meal type and description are inside their CHECKs; anything else is a corrupt row. */
  private def mealType(stored: String): MealType =
    MealType.fromDbValue(stored).getOrElse(throw IllegalStateException(s"meals.meal_type holds '$stored'"))

  private def stored(rs: ResultSet, description: String): Option[MealText] =
    MealText.of(description) match
      case Right(text)                  => text
      case Left(MealTextTooLong(limit)) =>
        throw IllegalStateException(s"meals.description of ${rs.getString("id")} is longer than $limit")

  override def insert(entry: MealEntry): Boolean =
    sql"""INSERT INTO meals
            (id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type, description,
             created_at)
          VALUES (${entry.id}, ${entry.accountId}, ${entry.occurredAt}, ${entry.localDate}, ${entry.zone.getId},
                  ${entry.source.dbValue}, ${entry.clientKey}, ${entry.supersedesId}, ${entry.mealType.dbValue},
                  ${entry.text.map(_.value)}, ${entry.createdAt})
          ON CONFLICT (account_id, client_key) DO NOTHING""".execute() == 1

  override def get(accountId: UUID, id: UUID): Option[MealEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type, description,
                 created_at, voided_at
          FROM meals WHERE id = $id AND account_id = $accountId""".queryOne[MealEntry]()

  override def findByClientKey(accountId: UUID, clientKey: String): Option[MealEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type, description,
                 created_at, voided_at
          FROM meals WHERE account_id = $accountId AND client_key = $clientKey""".queryOne[MealEntry]()

  override def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    sql"""UPDATE meals SET voided_at = $now, void_reason = ${reason.dbValue}
          WHERE id = $id AND account_id = $accountId AND voided_at IS NULL""".execute() == 1

  override def listLive(accountId: UUID, range: DateRange): List[MealEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type, description,
                 created_at, voided_at
          FROM meals
          WHERE account_id = $accountId AND local_date >= ${range.start} AND local_date <= ${range.end}
            AND voided_at IS NULL
          ORDER BY occurred_at, id""".query[MealEntry]()

  override def recentTexts(accountId: UUID, mealType: MealType, limit: Int): List[MealText] =
    sql"""SELECT description FROM meals
          WHERE account_id = $accountId AND meal_type = ${mealType.dbValue} AND voided_at IS NULL
            AND description IS NOT NULL
          GROUP BY description
          ORDER BY max(occurred_at) DESC
          LIMIT $limit""".query[String]().flatMap(MealText.of(_).toOption.flatten)

  override def plan(accountId: UUID): Option[MealPlan] =
    sql"SELECT planned FROM meal_plans WHERE account_id = $accountId"
      .queryOne[Array[String]]()
      .map(planned => MealPlan(planned.toSet.map(mealType)))

  override def savePlan(accountId: UUID, plan: MealPlan, now: Instant): Unit =
    val planned = MealType.main.filter(plan.planned).map(_.dbValue).toArray
    sql"""INSERT INTO meal_plans (account_id, planned, updated_at) VALUES ($accountId, $planned, $now)
          ON CONFLICT (account_id) DO UPDATE SET planned = EXCLUDED.planned, updated_at = EXCLUDED.updated_at"""
      .execute()
    ()
end PgMealRepository
