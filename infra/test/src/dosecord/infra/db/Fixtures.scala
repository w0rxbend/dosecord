package dosecord.infra.db

import dosecord.contracts.HhMm
import dosecord.core.domain.DstKind
import dosecord.core.domain.Occurrence
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.domain.UnknownReason
import dosecord.core.ports.Clock
import dosecord.core.ports.NewMedication
import dosecord.core.ports.NewOccurrence
import dosecord.core.ports.OccurrenceOrigin
import dosecord.core.scheduling.CreateSchedule
import dosecord.core.scheduling.RevisionOutcome
import dosecord.core.scheduling.ScheduleLifecycle

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource
import scala.language.implicitConversions

/** A deterministic clock the tests advance by hand. */
final class MutableClock(var at: Instant) extends Clock:
  override def now(): Instant = at
  def advance(by: Duration): Unit = at = at.plus(by)

/** The test-only fixture builder of ROADMAP M1.5: accounts, medications, schedules (through the real
  * `ScheduleLifecycle`, so the fixture path and the product path cannot drift), revisions and occurrences at chosen
  * instants — every later infra test seeds through here instead of hand-writing INSERTs.
  */
final class Fixtures(dataSource: DataSource):

  def uow: PgUnitOfWork = PgUnitOfWork(dataSource, Clock.system)

  def lifecycle(clock: Clock): ScheduleLifecycle = ScheduleLifecycle(uow, clock)

  def account(timezone: String = "UTC"): UUID =
    val id = UUID.randomUUID()
    val conn = dataSource.getConnection
    try {
      given Connection = conn
      sql"INSERT INTO users (id, timezone, status) VALUES ($id, $timezone, 'active')".execute()
    } finally conn.close()
    id

  def medication(
      accountId: UUID,
      name: String,
      now: Instant,
      doseAmount: Option[BigDecimal] = None,
      doseUnit: Option[String] = None,
      instructions: Option[String] = None
  ): UUID =
    val id = UUID.randomUUID()
    uow.transaction(
      _.medications.insert(NewMedication(id, accountId, name, doseAmount, doseUnit, instructions), now)
    )
    id

  /** Creates medication + schedule + revision 1 through `ScheduleLifecycle.create` (also appends
    * `schedule_created.v1`) and materialises the 48 h horizon.
    */
  def schedule(
      clock: Clock,
      accountId: UUID,
      name: String,
      rule: Rule,
      zone: ZoneId,
      policy: ReminderPolicy = ReminderPolicy.Default,
      tzFollowsUser: Boolean = true,
      doseAmount: Option[BigDecimal] = None,
      doseUnit: Option[String] = None,
      instructions: Option[String] = None
  ): RevisionOutcome =
    lifecycle(clock)
      .create(CreateSchedule(accountId, name, rule, zone, doseAmount, doseUnit, instructions, policy, tzFollowsUser))
      .fold(errors => throw new IllegalArgumentException(errors.mkString("; ")), identity)

  /** A rule edit as a revision (the generic `ScheduleLifecycle.edit`). */
  def revision(
      clock: Clock,
      scheduleId: UUID,
      rule: Rule,
      policy: Option[ReminderPolicy] = None,
      effectiveFrom: Option[Instant] = None
  ): RevisionOutcome =
    lifecycle(clock).edit(scheduleId, rule, policy, effectiveFrom)

  /** Inserts one occurrence of the schedule's current revision at a chosen instant with a chosen status (default:
    * freshly materialised `pending`). The slot key is the caller's, so fixtures can pick slots that do not collide
    * with materialised rows. `revisionOverride` points the row at another (possibly nonexistent) revision — the
    * poison-row fixture of the loop tests.
    */
  def occurrenceAt(
      scheduleId: UUID,
      scheduledFor: Instant,
      slotKey: String,
      status: OccurrenceStatus = OccurrenceStatus.Pending,
      revisionOverride: Option[Int] = None
  ): UUID =
    uow.transaction { tx =>
      val schedule = tx.schedules
        .get(scheduleId)
        .getOrElse(throw new NoSuchElementException(s"unknown schedule $scheduleId"))
      val revision = tx.revisions
        .get(scheduleId, schedule.currentRevision)
        .getOrElse(throw new IllegalStateException(s"schedule $scheduleId has no current revision"))
      val local = scheduledFor.atZone(schedule.tz)
      val base = Occurrence.scheduled(scheduledFor, revision.policy)
      val state = status match
        case OccurrenceStatus.Pending  => base
        case OccurrenceStatus.Due      => base.copy(status = OccurrenceStatus.Due)
        case OccurrenceStatus.Snoozed  =>
          base.copy(status = OccurrenceStatus.Snoozed, snoozedUntil = Some(scheduledFor))
        case OccurrenceStatus.Taken    =>
          base.copy(status = OccurrenceStatus.Taken, nextActionAt = None, takenAt = Some(scheduledFor),
            effectiveAt = Some(scheduledFor))
        case OccurrenceStatus.Skipped  =>
          base.copy(status = OccurrenceStatus.Skipped, nextActionAt = None, skippedAt = Some(scheduledFor))
        case OccurrenceStatus.Missed   =>
          base.copy(status = OccurrenceStatus.Missed, nextActionAt = None, missedAt = Some(base.missDeadline))
        case OccurrenceStatus.Unknown  =>
          base.copy(status = OccurrenceStatus.Unknown, nextActionAt = None,
            unknownReason = Some(UnknownReason.Outage))
        case OccurrenceStatus.Cancelled =>
          throw new IllegalArgumentException("cancelled rows are produced by revision reconciliation, not fixtures")
      val row = NewOccurrence(
        id = UUID.randomUUID(),
        accountId = schedule.accountId,
        medicationId = schedule.medicationId,
        scheduleId = Some(scheduleId),
        revision = Some(revisionOverride.getOrElse(schedule.currentRevision)),
        origin = OccurrenceOrigin.Scheduled,
        localDate = local.toLocalDate,
        localTime = Some(HhMm.unsafe(f"${local.getHour}%02d:${local.getMinute}%02d")),
        slotKey = slotKey,
        tz = schedule.tz,
        dstKind = DstKind.None,
        doseSnapshot = revision.doseSnapshot,
        state = state
      )
      require(tx.occurrences.insertAll(List(row)) == 1, s"fixture occurrence at $scheduledFor conflicted")
      row.id
    }

  /** A linked platform identity plus its healthy primary `delivery_channels` row (DESIGN.md section 6), so loop tests
    * have a channel to enqueue to. Returns the channel id. `role`/`priority` order fallback channels behind the
    * primary.
    */
  def deliveryChannel(
      accountId: UUID,
      vendor: String,
      chatId: String,
      now: Instant,
      role: String = "primary",
      priority: Int = 0
  ): UUID =
    val identityId = UUID.randomUUID()
    val channelId = UUID.randomUUID()
    val conn = dataSource.getConnection
    try {
      given Connection = conn
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, dm_channel_id, linked_at)
            VALUES ($identityId, $accountId, $vendor, ${s"fixture-$identityId"}, $chatId, $now)""".execute()
      sql"""INSERT INTO delivery_channels (id, account_id, platform_identity_id, role, priority, state, updated_at)
            VALUES ($channelId, $accountId, $identityId, $role, $priority, 'healthy', $now)""".execute()
    } finally conn.close()
    channelId
