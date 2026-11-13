package dosegram.infra.db

import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.MoodRepository

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `mood_checkins`. `note_enc` is never selected: a note is encrypted content and no statistic reads it. The
  * rollup queue is fed by the triggers of the baseline schema, in the transaction of each write here.
  */
final class PgMoodRepository(conn: Connection) extends MoodRepository:
  private given Connection = conn

  private given RowMapper[MoodCheckin] = rs =>
    MoodCheckin(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      occurredAt = rs.instant("occurred_at"),
      localDate = rs.localDate("local_date"),
      zone = ZoneId.of(rs.getString("tz")),
      valence = step(rs, "valence", Valence.of),
      energy = rs.optInt("energy").map(_ => step(rs, "energy", Energy.of)),
      tagIds = rs.getArray("tag_ids").getArray.asInstanceOf[Array[AnyRef]].toList.map(_.asInstanceOf[UUID]),
      source = EntrySource.fromDbValue(rs.getString("source")),
      clientKey = rs.optString("client_key"),
      supersedesId = rs.optUuid("supersedes_id"),
      createdAt = rs.instant("created_at"),
      voidedAt = rs.optInstant("voided_at")
    )

  /** A stored step is inside 1 to 5 by the column CHECK; anything else is a corrupt row. */
  private def step[A](rs: ResultSet, column: String, of: Int => Option[A]): A =
    val stored = rs.getInt(column)
    of(stored).getOrElse(throw IllegalStateException(s"mood_checkins.$column holds $stored, outside 1 to 5"))

  override def insert(checkin: MoodCheckin): Boolean =
    sql"""INSERT INTO mood_checkins
            (id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, valence, energy,
             tag_ids, created_at)
          VALUES (${checkin.id}, ${checkin.accountId}, ${checkin.occurredAt}, ${checkin.localDate},
                  ${checkin.zone.getId}, ${checkin.source.dbValue}, ${checkin.clientKey}, ${checkin.supersedesId},
                  ${checkin.valence.step}, ${checkin.energy.map(_.step)}, ${UuidArray(checkin.tagIds.toArray)},
                  ${checkin.createdAt})
          ON CONFLICT (account_id, client_key) DO NOTHING""".execute() == 1

  override def get(accountId: UUID, id: UUID): Option[MoodCheckin] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, valence, energy,
                 tag_ids, created_at, voided_at
          FROM mood_checkins WHERE id = $id AND account_id = $accountId""".queryOne[MoodCheckin]()

  override def findByClientKey(accountId: UUID, clientKey: String): Option[MoodCheckin] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, valence, energy,
                 tag_ids, created_at, voided_at
          FROM mood_checkins WHERE account_id = $accountId AND client_key = $clientKey""".queryOne[MoodCheckin]()

  override def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    sql"""UPDATE mood_checkins SET voided_at = $now, void_reason = ${reason.dbValue}
          WHERE id = $id AND account_id = $accountId AND voided_at IS NULL""".execute() == 1

  override def listLive(accountId: UUID, range: DateRange): List[MoodCheckin] =
    sql"""SELECT id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, valence, energy,
                 tag_ids, created_at, voided_at
          FROM mood_checkins
          WHERE account_id = $accountId AND local_date >= ${range.start} AND local_date <= ${range.end}
            AND voided_at IS NULL
          ORDER BY occurred_at, id""".query[MoodCheckin]()
