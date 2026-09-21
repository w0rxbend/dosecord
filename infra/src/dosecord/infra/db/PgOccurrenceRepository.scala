package dosecord.infra.db

import dosecord.core.domain.CancelReason
import dosecord.core.domain.DstKind
import dosecord.core.domain.Occurrence
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.UnknownReason
import dosecord.core.ports.NewOccurrence
import dosecord.core.ports.OccurrenceOrigin
import dosecord.core.ports.OccurrenceRepository
import dosecord.core.ports.StoredOccurrence
import upickle.default.write

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `dose_occurrences` (ADR-004): no-target `ON CONFLICT DO NOTHING` inserts (every unique index arbitrates,
  * including the cross-revision live-slot index), the edit transaction's `FOR UPDATE` lock on open rows, and
  * reconciliation cancels. Every query takes `now` as a bind parameter (ADR-011).
  */
final class PgOccurrenceRepository(conn: Connection) extends OccurrenceRepository:
  private given Connection = conn

  private given RowMapper[StoredOccurrence] = rs =>
    StoredOccurrence(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      medicationId = rs.uuid("medication_id"),
      scheduleId = rs.optUuid("schedule_id"),
      revision = rs.optInt("revision"),
      origin = OccurrenceOrigin.fromDbValue(rs.getString("origin")),
      localDate = rs.localDate("local_date"),
      localTime = rs.optLocalTime("local_time"),
      slotKey = rs.getString("slot_key"),
      tz = ZoneId.of(rs.getString("tz")),
      dstKind = DstKind
        .fromDbValue(rs.getString("dst_kind"))
        .getOrElse(throw new IllegalArgumentException(s"unknown dst_kind '${rs.getString("dst_kind")}'")),
      cancelReason = rs.optString("cancel_reason").map(CancelReason.fromDbValue),
      version = rs.getInt("version"),
      state = Occurrence(
        status = OccurrenceStatus.fromDbValue(rs.getString("status")),
        scheduledFor = rs.instant("scheduled_for"),
        dueWindowStart = rs.instant("due_window_start"),
        dueWindowEnd = rs.instant("due_window_end"),
        missDeadline = rs.instant("miss_deadline"),
        nextActionAt = rs.optInstant("next_action_at"),
        reminderSeq = rs.getInt("reminder_seq"),
        snoozeCount = rs.getInt("snooze_count"),
        snoozedUntil = rs.optInstant("snoozed_until"),
        lastRemindedAt = rs.optInstant("last_reminded_at"),
        takenAt = rs.optInstant("taken_at"),
        effectiveAt = rs.optInstant("effective_at"),
        skippedAt = rs.optInstant("skipped_at"),
        missedAt = rs.optInstant("missed_at"),
        unknownReason = rs.optString("unknown_reason").map(UnknownReason.fromDbValue),
        epoch = rs.getInt("epoch")
      )
    )

  override def insertAll(rows: List[NewOccurrence]): Int =
    rows.map(insert).count(_ == 1)

  private def insert(row: NewOccurrence): Int =
    sql"""INSERT INTO dose_occurrences
            (id, account_id, medication_id, schedule_id, revision, origin, local_date, local_time, slot_key,
             tz, dst_kind, scheduled_for, due_window_start, due_window_end, miss_deadline, status,
             epoch, reminder_seq, snooze_count, snoozed_until, last_reminded_at, taken_at, effective_at,
             skipped_at, missed_at, next_action_at, unknown_reason, error_count, dose_snapshot, version)
          VALUES (${row.id}, ${row.accountId}, ${row.medicationId}, ${row.scheduleId}, ${row.revision},
                  ${row.origin.dbValue}, ${row.localDate},
                  ${row.localTime.map(t => LocalTime.of(t.hour, t.minute))}, ${row.slotKey},
                  ${row.tz.getId}, ${row.dstKind.dbValue}, ${row.state.scheduledFor}, ${row.state.dueWindowStart},
                  ${row.state.dueWindowEnd}, ${row.state.missDeadline}, ${row.state.status.dbValue}::occ_status,
                  ${row.state.epoch}, ${row.state.reminderSeq}, ${row.state.snoozeCount}, ${row.state.snoozedUntil},
                  ${row.state.lastRemindedAt}, ${row.state.takenAt}, ${row.state.effectiveAt},
                  ${row.state.skippedAt}, ${row.state.missedAt}, ${row.state.nextActionAt},
                  ${row.state.unknownReason.map(_.dbValue)}, 0, ${Jsonb(write(row.doseSnapshot))}, 1)
          ON CONFLICT DO NOTHING""".execute()

  override def get(id: UUID): Option[StoredOccurrence] =
    sql"""SELECT id, account_id, medication_id, schedule_id, revision, origin, local_date, local_time, slot_key,
                 tz, dst_kind, scheduled_for, due_window_start, due_window_end, miss_deadline, status,
                 epoch, reminder_seq, snooze_count, snoozed_until, last_reminded_at, taken_at, effective_at,
                 skipped_at, missed_at, next_action_at, unknown_reason, cancel_reason, version
          FROM dose_occurrences WHERE id = $id""".queryOne[StoredOccurrence]()

  override def listBySchedule(scheduleId: UUID): List[StoredOccurrence] =
    sql"""SELECT id, account_id, medication_id, schedule_id, revision, origin, local_date, local_time, slot_key,
                 tz, dst_kind, scheduled_for, due_window_start, due_window_end, miss_deadline, status,
                 epoch, reminder_seq, snooze_count, snoozed_until, last_reminded_at, taken_at, effective_at,
                 skipped_at, missed_at, next_action_at, unknown_reason, cancel_reason, version
          FROM dose_occurrences WHERE schedule_id = $scheduleId
          ORDER BY scheduled_for, local_date, slot_key""".query[StoredOccurrence]()

  override def lockOpenRows(scheduleId: UUID): List[StoredOccurrence] =
    sql"""SELECT id, account_id, medication_id, schedule_id, revision, origin, local_date, local_time, slot_key,
                 tz, dst_kind, scheduled_for, due_window_start, due_window_end, miss_deadline, status,
                 epoch, reminder_seq, snooze_count, snoozed_until, last_reminded_at, taken_at, effective_at,
                 skipped_at, missed_at, next_action_at, unknown_reason, cancel_reason, version
          FROM dose_occurrences
          WHERE schedule_id = $scheduleId AND status IN ('pending', 'due', 'snoozed')
          ORDER BY scheduled_for
          FOR UPDATE""".query[StoredOccurrence]()

  override def cancel(ids: List[UUID], reason: CancelReason, now: Instant): Int =
    if ids.isEmpty then 0
    else
      val idArray = UuidArray(ids.toArray)
      sql"""UPDATE dose_occurrences
            SET status = 'cancelled', cancel_reason = ${reason.dbValue}, next_action_at = NULL,
                epoch = epoch + 1, version = version + 1, updated_at = $now
            WHERE id = ANY($idArray) AND status IN ('pending', 'due', 'snoozed')""".execute()

  override def hasResolvedOrDueOn(scheduleId: UUID, localDate: LocalDate, now: Instant): Boolean =
    sql"""SELECT EXISTS(
            SELECT 1 FROM dose_occurrences
            WHERE schedule_id = $scheduleId AND local_date = $localDate
              AND (status NOT IN ('pending', 'cancelled') OR (status = 'pending' AND due_window_start <= $now))
          )""".queryOne[Boolean]().getOrElse(false)
