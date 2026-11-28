package dosegram.infra.db

import dosegram.core.ports.AccountRepository
import dosegram.core.ports.AuditLog
import dosegram.core.ports.AuditRepository
import dosegram.core.ports.CallbackSlotRepository
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentLedger
import dosegram.core.ports.DataDomain
import dosegram.core.ports.DeliveryChannelRepository
import dosegram.core.ports.DomainEventRepository
import dosegram.core.ports.DoseActionRepository
import dosegram.core.ports.DoseHistoryRepository
import dosegram.core.ports.ErasureReceipt
import dosegram.core.ports.ErasureRepository
import dosegram.core.ports.ExportRepository
import dosegram.core.ports.FormRunRepository
import dosegram.core.ports.HydrationRepository
import dosegram.core.ports.IdSource
import dosegram.core.ports.IdentityRepository
import dosegram.core.ports.ImportRepository
import dosegram.core.ports.InboundEventRepository
import dosegram.core.ports.KeyOwner
import dosegram.core.ports.MealRepository
import dosegram.core.ports.MedicationDepthRepositories
import dosegram.core.ports.MedicationRepository
import dosegram.core.ports.MoodRepository
import dosegram.core.ports.OccurrenceRepository
import dosegram.core.ports.OutboxRepository
import dosegram.core.ports.PolicyRepository
import dosegram.core.ports.ReminderRepositories
import dosegram.core.ports.RenderedMessageRepository
import dosegram.core.ports.ReportQueue
import dosegram.core.ports.RollupRepository
import dosegram.core.ports.ScheduleRepository
import dosegram.core.ports.ScheduleRevisionRepository
import dosegram.core.ports.SessionRepository
import dosegram.core.ports.SleepRepository
import dosegram.core.ports.TrackingRepositories
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork
import dosegram.core.ports.WorkerHeartbeatRepository
import dosegram.infra.crypto.DataKeys

import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Postgres [[UnitOfWork]]: one pooled connection, `f` runs against it, commit on success, rollback on any exception
  * (ADR-002/003: one transaction per event). Every repository group, the tracking domains and the consent ledger
  * included, is bound to that one connection (ROADMAP G2.1). The repositories of Class A stores seal and open their
  * values with `keys` on the same connection, so a data key created for a write commits with it (ROADMAP G2.4).
  *
  * Row-level security (ROADMAP G2.3, V14): [[transaction]] runs as `dosegram_worker`, the documented cross-account
  * role, and [[transactionAs]] and [[Tx.actAs]] narrow it to `dosegram_app` with one account as the claim. Both
  * settings are local to the transaction, so a pooled connection never carries them into the next one.
  */
final class PgUnitOfWork(dataSource: DataSource, clock: Clock, keys: DataKeys) extends UnitOfWork:

  // The ids of consent events: time-ordered, so the ledger reads back in the order it was written.
  private val consentEventIds = IdSource.uuidV7(SecureRandom())

  override def transaction[A](f: Tx => A): A =
    inTransaction(None, f)

  override def transactionAs[A](account: UUID)(f: Tx => A): A =
    inTransaction(Some(account), f)

  private def inTransaction[A](account: Option[UUID], f: Tx => A): A =
    RowSecurity.transaction(dataSource, account)(conn => f(PgTx(conn, account)))

  private final class PgTx(conn: Connection, initialAccount: Option[UUID]) extends Tx:
    private var boundAccount = initialAccount

    override def actAs(account: UUID): Unit =
      boundAccount match
        case Some(bound) if bound == account => ()
        case Some(_)                         =>
          throw IllegalStateException("a transaction acts for one account; it is already bound to another")
        case None =>
          RowSecurity.asAccount(conn, account)
          boundAccount = Some(account)

    private lazy val keyService = keys.on(conn)
    private val reads = TransactionReads()
    override lazy val sessions: SessionRepository = PgSessionRepository(conn, clock, keyService)
    override lazy val outbox: OutboxRepository = PgOutboxRepository(conn, keyService)
    override lazy val renderedMessages: RenderedMessageRepository = PgRenderedMessageRepository(conn, keyService)
    override lazy val inboundEvents: InboundEventRepository = PgInboundEventRepository(conn, keyService)
    override lazy val domainEvents: DomainEventRepository = PgDomainEventRepository(conn, keyService)
    override lazy val identities: IdentityRepository = PgIdentityRepository(conn)
    override lazy val accounts: AccountRepository = PgAccountRepository(conn, reads)
    override lazy val audit: AuditRepository = PgAuditRepository(conn, clock)
    override lazy val slots: CallbackSlotRepository = PgCallbackSlotRepository(conn, keyService)
    override lazy val formRuns: FormRunRepository = PgFormRunRepository(conn, keyService)
    override lazy val medications: MedicationRepository = PgMedicationRepository(conn, keyService)
    override lazy val schedules: ScheduleRepository = PgScheduleRepository(conn)
    override lazy val revisions: ScheduleRevisionRepository = PgScheduleRevisionRepository(conn, keyService)
    override lazy val occurrences: OccurrenceRepository = PgOccurrenceRepository(conn, keyService)
    override lazy val doseActions: DoseActionRepository = PgDoseActionRepository(conn, keyService)
    override lazy val policies: PolicyRepository = PgPolicyRepository(conn)
    override lazy val channels: DeliveryChannelRepository = PgDeliveryChannelRepository(conn)
    override lazy val heartbeat: WorkerHeartbeatRepository = PgWorkerHeartbeatRepository(conn)
    override lazy val auditLog: AuditLog = PgAuditLog(conn, clock, consentEventIds)
    // An erasure deletes account rows behind the repositories' backs: what this transaction read of them is forgotten.
    override lazy val erasure: ErasureRepository = new ErasureRepository:
      private val erasing = PgErasure(conn, account => keys.forget(KeyOwner.Account(account)))
      override def eraseAccount(accountId: UUID, tombstoneId: UUID, now: Instant): ErasureReceipt =
        try erasing.eraseAccount(accountId, tombstoneId, now)
        finally reads.forgetAll()
      override def eraseDomain(accountId: UUID, domain: DataDomain, tombstoneId: UUID, now: Instant): ErasureReceipt =
        try erasing.eraseDomain(accountId, domain, tombstoneId, now)
        finally reads.forgetAll()
    override lazy val consents: ConsentLedger = PgConsentLedger(conn, consentEventIds, auditLog, reads)
    override lazy val tracking: TrackingRepositories = new TrackingRepositories:
      override lazy val hydration: HydrationRepository = PgHydrationRepository(conn)
      override lazy val mood: MoodRepository = PgMoodRepository(conn)
      override lazy val rollups: RollupRepository = PgRollupRepository(conn)
      override lazy val doseHistory: DoseHistoryRepository = PgDoseHistoryRepository(conn)
      override lazy val sleep: SleepRepository = PgSleepRepository(conn, keyService)
      override lazy val meals: MealRepository = PgMealRepository(conn, keyService)
    override lazy val reminders: ReminderRepositories = PgReminderRepositories(conn)
    override lazy val exports: ExportRepository = PgExportRepository(conn, consentEventIds, auditLog, keyService)
    override lazy val reports: ReportQueue = PgReportQueue(conn, keyService)
    override lazy val medicationDepth: MedicationDepthRepositories = PgMedicationDepthRepositories(conn)
    override lazy val imports: ImportRepository = PgImportRepository(conn, keyService)

    override def savepoint[A](f: => A): A =
      val sp = conn.setSavepoint()
      try
        val result = f
        conn.releaseSavepoint(sp)
        result
      catch
        case e: Throwable =>
          conn.rollback(sp)
          conn.releaseSavepoint(sp)
          // A read inside the savepoint may have seen its own writes, which are gone now.
          reads.forgetAll()
          throw e
