package dosegram.infra.db

import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealTextTooLong
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.KeyOwner
import dosegram.core.ports.KeyService
import dosegram.core.ports.MealRepository
import dosegram.core.ports.SealedField

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `meals` and `meal_plans` (V4). The rollup queue is fed by the trigger of V4, in the transaction of each
  * write here. The words of a meal are read only to be returned to their owner; no query of a figure selects them. They
  * are sealed with the account's data key, and `description_hmac` groups equal words without reading them (V10).
  */
final class PgMealRepository(conn: Connection, keys: KeyService) extends MealRepository:
  import SealedColumns.MealWords

  private given Connection = conn

  private given RowMapper[MealEntry] = rs =>
    val id = rs.uuid("id")
    val accountId = rs.uuid("account_id")
    MealEntry(
      id = id,
      accountId = accountId,
      occurredAt = rs.instant("occurred_at"),
      localDate = rs.localDate("local_date"),
      zone = ZoneId.of(rs.getString("tz")),
      mealType = mealType(rs.getString("meal_type")),
      text = words(rs, accountId, id),
      source = EntrySource.fromDbValue(rs.getString("source")),
      clientKey = rs.optString("client_key"),
      supersedesId = rs.optUuid("supersedes_id"),
      createdAt = rs.instant("created_at"),
      voidedAt = rs.optInstant("voided_at")
    )

  /** The held words live in a `callback_slots` row that no button names: its id is derived from the account, its
    * payload holds the words sealed with the account's key, and its expiry is the hold's (supervisor decision,
    * 2026-11-14; the schema of V13 was frozen, so no column of its own). Account deletion deletes it with the account's
    * other slots.
    */
  override def hold(accountId: UUID, words: MealText, until: Instant): Unit =
    val id = PgMealRepository.heldSlot(accountId)
    val sealedWords =
      Base64.getEncoder.encodeToString(keys.sealText(PgMealRepository.heldField(accountId, id), words.value))
    val payload = ujson.write(ujson.Obj(PgMealRepository.HeldKey -> sealedWords))
    sql"""INSERT INTO callback_slots (id, account_id, chat, payload, expires_at)
          VALUES ($id, $accountId, ${Jsonb("{}")}, ${Jsonb(payload)}, $until)
          ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload, expires_at = EXCLUDED.expires_at""".execute()
    ()

  override def takeHeld(accountId: UUID, now: Instant): Option[MealText] =
    val id = PgMealRepository.heldSlot(accountId)
    given RowMapper[(String, Instant)] = rs => (rs.getString("payload"), rs.instant("expires_at"))
    val row = sql"""DELETE FROM callback_slots WHERE id = $id AND account_id = $accountId
                    RETURNING payload, expires_at""".queryOne[(String, Instant)]()
    row
      .filter((_, until) => !until.isBefore(now))
      .flatMap: (payload, _) =>
        val sealedWords = Base64.getDecoder.decode(ujson.read(payload)(PgMealRepository.HeldKey).str)
        stored(id, keys.openText(PgMealRepository.heldField(accountId, id), sealedWords))

  /** A stored meal type and description are inside their CHECKs; anything else is a corrupt row. */
  private def mealType(stored: String): MealType =
    MealType.fromDbValue(stored).getOrElse(throw IllegalStateException(s"meals.meal_type holds '$stored'"))

  private def words(rs: ResultSet, accountId: UUID, id: UUID): Option[MealText] =
    Option(rs.getBytes("description_enc")).flatMap: sealedWords =>
      stored(id, keys.openText(MealWords.forAccount(accountId, id), sealedWords))

  private def stored(id: UUID, description: String): Option[MealText] =
    MealText.of(description) match
      case Right(text)                  => text
      case Left(MealTextTooLong(limit)) =>
        throw IllegalStateException(s"meals.description_enc of $id is longer than $limit")

  override def insert(entry: MealEntry): Boolean =
    val text = entry.text.map(_.value)
    val sealedWords = text.map(keys.sealText(MealWords.forAccount(entry.accountId, entry.id), _))
    val wordsHash = text.map(keys.lookup(MealWords, entry.accountId, _))
    sql"""INSERT INTO meals
            (id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type,
             description_enc, description_hmac, created_at)
          VALUES (${entry.id}, ${entry.accountId}, ${entry.occurredAt}, ${entry.localDate}, ${entry.zone.getId},
                  ${entry.source.dbValue}, ${entry.clientKey}, ${entry.supersedesId}, ${entry.mealType.dbValue},
                  $sealedWords, $wordsHash, ${entry.createdAt})
          ON CONFLICT (account_id, client_key) DO NOTHING""".execute() == 1

  override def get(accountId: UUID, id: UUID): Option[MealEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type,
                 description_enc, created_at, voided_at
          FROM meals WHERE id = $id AND account_id = $accountId""".queryOne[MealEntry]()

  override def findByClientKey(accountId: UUID, clientKey: String): Option[MealEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type,
                 description_enc, created_at, voided_at
          FROM meals WHERE account_id = $accountId AND client_key = $clientKey""".queryOne[MealEntry]()

  override def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    sql"""UPDATE meals SET voided_at = $now, void_reason = ${reason.dbValue}
          WHERE id = $id AND account_id = $accountId AND voided_at IS NULL""".execute() == 1

  override def listLive(accountId: UUID, range: DateRange): List[MealEntry] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, meal_type,
                 description_enc, created_at, voided_at
          FROM meals
          WHERE account_id = $accountId AND local_date >= ${range.start} AND local_date <= ${range.end}
            AND voided_at IS NULL
          ORDER BY occurred_at, id""".query[MealEntry]()

  override def recentTexts(accountId: UUID, mealType: MealType, limit: Int): List[MealText] =
    given RowMapper[Option[MealText]] = rs =>
      val id = rs.uuid("id")
      stored(id, keys.openText(MealWords.forAccount(accountId, id), rs.getBytes("description_enc")))
    sql"""SELECT (array_agg(id ORDER BY occurred_at DESC, id DESC))[1] AS id,
                 (array_agg(description_enc ORDER BY occurred_at DESC, id DESC))[1] AS description_enc
          FROM meals
          WHERE account_id = $accountId AND meal_type = ${mealType.dbValue} AND voided_at IS NULL
            AND description_enc IS NOT NULL
          GROUP BY description_hmac
          ORDER BY max(occurred_at) DESC
          LIMIT $limit""".query[Option[MealText]]().flatten

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

object PgMealRepository:

  /** The payload field of the held words. */
  private val HeldKey = "heldMealWords"

  /** The slot of an account's held words: the same id for every hold of the account, so a later hold replaces it. */
  private def heldSlot(accountId: UUID): UUID =
    UUID.nameUUIDFromBytes(s"dosegram/held-meal/$accountId".getBytes(java.nio.charset.StandardCharsets.UTF_8))

  private def heldField(accountId: UUID, slot: UUID): SealedField =
    SealedField(KeyOwner.Account(accountId), "callback_slots", "payload", slot.toString)
