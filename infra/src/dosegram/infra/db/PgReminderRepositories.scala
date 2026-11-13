package dosegram.infra.db

import dosegram.core.domain.reminders.DayAnchors
import dosegram.core.domain.reminders.NudgePlan
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptNotice
import dosegram.core.domain.reminders.PromptStatus
import dosegram.core.domain.reminders.SuppressReason
import dosegram.core.domain.reminders.WorkHours
import dosegram.core.ports.DayProfileRepository
import dosegram.core.ports.PromptOccurrence
import dosegram.core.ports.PromptRepository
import dosegram.core.ports.PromptSchedule
import dosegram.core.ports.ReminderRepositories

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import scala.language.implicitConversions

/** The reminders group on one connection (ARCHITECTURE section 10.17 rule 5). */
final class PgReminderRepositories(conn: Connection) extends ReminderRepositories:
  override lazy val prompts: PromptRepository = PgPromptRepository(conn)
  override lazy val dayProfiles: DayProfileRepository = PgDayProfileRepository(conn)

/** Postgres `prompt_schedules` and `prompt_occurrences`. */
final class PgPromptRepository(conn: Connection) extends PromptRepository:

  private given Connection = conn

  private given RowMapper[PromptSchedule] = rs =>
    PromptSchedule(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      kind = kindOf(rs.getString("kind")),
      presetId = rs.getString("preset"),
      plan = NudgePlan.fromJson(rs.getString("plan")),
      ownCap = rs.getBoolean("own_cap"),
      enabled = rs.getBoolean("enabled"),
      sound = rs.getBoolean("sound"),
      pausedUntil = rs.optInstant("paused_until"),
      activeFrom = rs.instant("active_from")
    )

  private given RowMapper[PromptOccurrence] = rs =>
    PromptOccurrence(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      scheduleId = rs.uuid("schedule_id"),
      kind = kindOf(rs.getString("kind")),
      planDate = rs.localDate("local_date"),
      slotKey = rs.getString("slot_key"),
      dueAt = rs.instant("due_at"),
      status = PromptStatus.fromDb(rs.getString("status")),
      reason = rs.optString("reason").map(SuppressReason.fromDb),
      notice = rs.optString("notice").map(PromptNotice.fromDb),
      sendKey = rs.optString("send_key"),
      budgeted = rs.getBoolean("budgeted"),
      decidedAt = rs.instant("decided_at")
    )

  override def schedules(accountId: UUID): List[PromptSchedule] =
    sql"""SELECT id, account_id, kind, preset, plan, own_cap, enabled, sound, paused_until, active_from
          FROM prompt_schedules WHERE account_id = $accountId ORDER BY kind""".query[PromptSchedule]()

  override def schedule(accountId: UUID, kind: PromptKind): Option[PromptSchedule] =
    sql"""SELECT id, account_id, kind, preset, plan, own_cap, enabled, sound, paused_until, active_from
          FROM prompt_schedules WHERE account_id = $accountId AND kind = ${kind.id}""".queryOne[PromptSchedule]()

  override def save(schedule: PromptSchedule, now: Instant): PromptSchedule =
    sql"""INSERT INTO prompt_schedules
            (id, account_id, kind, preset, plan, own_cap, enabled, sound, paused_until, active_from, created_at,
             updated_at)
          VALUES (${schedule.id}, ${schedule.accountId}, ${schedule.kind.id}, ${schedule.presetId},
                  ${Jsonb(NudgePlan.toJson(schedule.plan))}, ${schedule.ownCap}, ${schedule.enabled}, ${schedule.sound},
                  ${schedule.pausedUntil}, ${schedule.activeFrom}, $now, $now)
          ON CONFLICT (account_id, kind) DO UPDATE SET preset = EXCLUDED.preset, plan = EXCLUDED.plan,
            own_cap = EXCLUDED.own_cap, enabled = EXCLUDED.enabled, sound = EXCLUDED.sound,
            paused_until = EXCLUDED.paused_until, active_from = EXCLUDED.active_from,
            updated_at = EXCLUDED.updated_at""".execute()
    this
      .schedule(schedule.accountId, schedule.kind)
      .getOrElse(throw IllegalStateException(s"prompt plan ${schedule.kind.id} vanished after its upsert"))

  override def setEnabled(accountId: UUID, kind: PromptKind, enabled: Boolean, now: Instant): Boolean =
    sql"""UPDATE prompt_schedules SET enabled = $enabled, active_from = $now, updated_at = $now
          WHERE account_id = $accountId AND kind = ${kind.id}""".execute() == 1

  override def pause(scheduleId: UUID, until: Option[Instant], now: Instant): Unit =
    sql"UPDATE prompt_schedules SET paused_until = $until, updated_at = $now WHERE id = $scheduleId".execute()
    ()

  override def enabledIds(): List[UUID] =
    sql"SELECT id FROM prompt_schedules WHERE enabled ORDER BY id".query[UUID]()

  override def lockEnabled(scheduleId: UUID): Option[PromptSchedule] =
    sql"""SELECT id, account_id, kind, preset, plan, own_cap, enabled, sound, paused_until, active_from
          FROM prompt_schedules WHERE id = $scheduleId AND enabled
          FOR UPDATE SKIP LOCKED""".queryOne[PromptSchedule]()

  override def decided(scheduleId: UUID, dates: List[LocalDate]): List[PromptOccurrence] =
    dates.flatMap: date =>
      sql"""SELECT id, account_id, schedule_id, kind, local_date, slot_key, due_at, status, reason, notice,
                   send_key, budgeted, decided_at
            FROM prompt_occurrences WHERE schedule_id = $scheduleId AND local_date = $date
            ORDER BY due_at""".query[PromptOccurrence]()

  override def decidedOn(accountId: UUID, date: LocalDate): List[PromptOccurrence] =
    sql"""SELECT id, account_id, schedule_id, kind, local_date, slot_key, due_at, status, reason, notice, send_key,
                 budgeted, decided_at
          FROM prompt_occurrences WHERE account_id = $accountId AND local_date = $date
          ORDER BY due_at""".query[PromptOccurrence]()

  override def insert(o: PromptOccurrence): Boolean =
    sql"""INSERT INTO prompt_occurrences
            (id, account_id, schedule_id, kind, local_date, slot_key, due_at, status, reason, notice, send_key,
             budgeted, decided_at)
          VALUES (${o.id}, ${o.accountId}, ${o.scheduleId}, ${o.kind.id}, ${o.planDate}, ${o.slotKey}, ${o.dueAt},
                  ${o.status.db}, ${o.reason.map(_.db)}, ${o.notice.map(_.db)}, ${o.sendKey}, ${o.budgeted},
                  ${o.decidedAt})
          ON CONFLICT (schedule_id, local_date, slot_key) DO NOTHING""".execute() == 1

  override def occurrence(id: UUID): Option[PromptOccurrence] =
    sql"""SELECT id, account_id, schedule_id, kind, local_date, slot_key, due_at, status, reason, notice, send_key,
                 budgeted, decided_at
          FROM prompt_occurrences WHERE id = $id""".queryOne[PromptOccurrence]()

  override def close(id: UUID, status: PromptStatus, at: Instant): Boolean =
    val answeredAt = Option.when(status == PromptStatus.Answered)(at)
    sql"""UPDATE prompt_occurrences SET status = ${status.db}, answered_at = $answeredAt
          WHERE id = $id AND status = 'sent'""".execute() == 1

  override def lastSent(scheduleId: UUID): Option[PromptOccurrence] =
    sql"""SELECT id, account_id, schedule_id, kind, local_date, slot_key, due_at, status, reason, notice, send_key,
                 budgeted, decided_at
          FROM prompt_occurrences WHERE schedule_id = $scheduleId AND send_key IS NOT NULL
          ORDER BY due_at DESC LIMIT 1""".queryOne[PromptOccurrence]()

  override def markNotice(id: UUID, notice: PromptNotice): Unit =
    sql"UPDATE prompt_occurrences SET notice = ${notice.db} WHERE id = $id".execute()
    ()

  private def kindOf(id: String): PromptKind =
    PromptKind.byId(id).getOrElse(throw IllegalStateException(s"unknown prompt kind '$id'"))

/** Postgres `day_profiles`. */
final class PgDayProfileRepository(conn: Connection) extends DayProfileRepository:

  private given Connection = conn

  private given RowMapper[DayAnchors] = rs =>
    DayAnchors(
      wake = rs.getObject("wake", classOf[LocalTime]),
      breakfast = rs.getObject("breakfast", classOf[LocalTime]),
      lunch = rs.getObject("lunch", classOf[LocalTime]),
      dinner = rs.getObject("dinner", classOf[LocalTime]),
      bedtime = rs.getObject("bedtime", classOf[LocalTime]),
      work = WorkHours(
        WorkHours.daysOf(rs.getInt("work_days")),
        rs.getObject("work_start", classOf[LocalTime]),
        rs.getObject("work_end", classOf[LocalTime])
      )
    )

  private given RowMapper[Option[Instant]] = _.optInstant("prompts_paused_until")

  override def anchors(accountId: UUID): Option[DayAnchors] =
    sql"""SELECT wake, breakfast, lunch, dinner, bedtime, work_days, work_start, work_end
          FROM day_profiles WHERE account_id = $accountId""".queryOne[DayAnchors]()

  override def save(accountId: UUID, a: DayAnchors, now: Instant): Unit =
    sql"""INSERT INTO day_profiles
            (account_id, wake, breakfast, lunch, dinner, bedtime, work_days, work_start, work_end, updated_at)
          VALUES ($accountId, ${a.wake}, ${a.breakfast}, ${a.lunch}, ${a.dinner}, ${a.bedtime},
                  ${WorkHours.bitsOf(a.work.days)}, ${a.work.start}, ${a.work.end}, $now)
          ON CONFLICT (account_id) DO UPDATE SET wake = EXCLUDED.wake, breakfast = EXCLUDED.breakfast,
            lunch = EXCLUDED.lunch, dinner = EXCLUDED.dinner, bedtime = EXCLUDED.bedtime,
            work_days = EXCLUDED.work_days, work_start = EXCLUDED.work_start, work_end = EXCLUDED.work_end,
            updated_at = EXCLUDED.updated_at""".execute()
    ()

  override def pausedUntil(accountId: UUID): Option[Instant] =
    sql"SELECT prompts_paused_until FROM day_profiles WHERE account_id = $accountId"
      .queryOne[Option[Instant]]()
      .flatten

  override def pauseAll(accountId: UUID, until: Option[Instant], default: DayAnchors, now: Instant): Unit =
    if anchors(accountId).isEmpty then save(accountId, default, now)
    sql"""UPDATE day_profiles SET prompts_paused_until = $until, updated_at = $now
          WHERE account_id = $accountId""".execute()
    ()
