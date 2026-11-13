package dosegram.infra.db

import dosegram.core.ports.AccountRepository
import dosegram.core.ports.AuditRepository
import dosegram.core.ports.CallbackSlotRepository
import dosegram.core.ports.Clock
import dosegram.core.ports.DeliveryChannelRepository
import dosegram.core.ports.DomainEventRepository
import dosegram.core.ports.DoseActionRepository
import dosegram.core.ports.FormRunRepository
import dosegram.core.ports.IdentityRepository
import dosegram.core.ports.InboundEventRepository
import dosegram.core.ports.MedicationRepository
import dosegram.core.ports.OccurrenceRepository
import dosegram.core.ports.OutboxRepository
import dosegram.core.ports.PolicyRepository
import dosegram.core.ports.RenderedMessageRepository
import dosegram.core.ports.ScheduleRepository
import dosegram.core.ports.ScheduleRevisionRepository
import dosegram.core.ports.SessionRepository
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork
import dosegram.core.ports.WorkerHeartbeatRepository

import java.sql.Connection
import javax.sql.DataSource

/** Postgres [[UnitOfWork]]: one pooled connection, `f` runs against it, commit on success, rollback on any exception
  * (ADR-002/003: one transaction per event).
  */
final class PgUnitOfWork(dataSource: DataSource, clock: Clock) extends UnitOfWork:

  override def transaction[A](f: Tx => A): A =
    val conn = dataSource.getConnection
    conn.setAutoCommit(false)
    try
      val result = f(PgTx(conn))
      conn.commit()
      result
    catch
      case e: Throwable =>
        conn.rollback()
        throw e
    finally conn.close()

  private final class PgTx(conn: Connection) extends Tx:
    override lazy val sessions: SessionRepository = PgSessionRepository(conn, clock)
    override lazy val outbox: OutboxRepository = PgOutboxRepository(conn)
    override lazy val renderedMessages: RenderedMessageRepository = PgRenderedMessageRepository(conn)
    override lazy val inboundEvents: InboundEventRepository = PgInboundEventRepository(conn)
    override lazy val domainEvents: DomainEventRepository = PgDomainEventRepository(conn)
    override lazy val identities: IdentityRepository = PgIdentityRepository(conn)
    override lazy val accounts: AccountRepository = PgAccountRepository(conn)
    override lazy val audit: AuditRepository = PgAuditRepository(conn, clock)
    override lazy val slots: CallbackSlotRepository = PgCallbackSlotRepository(conn)
    override lazy val formRuns: FormRunRepository = PgFormRunRepository(conn)
    override lazy val medications: MedicationRepository = PgMedicationRepository(conn)
    override lazy val schedules: ScheduleRepository = PgScheduleRepository(conn)
    override lazy val revisions: ScheduleRevisionRepository = PgScheduleRevisionRepository(conn)
    override lazy val occurrences: OccurrenceRepository = PgOccurrenceRepository(conn)
    override lazy val doseActions: DoseActionRepository = PgDoseActionRepository(conn)
    override lazy val policies: PolicyRepository = PgPolicyRepository(conn)
    override lazy val channels: DeliveryChannelRepository = PgDeliveryChannelRepository(conn)
    override lazy val heartbeat: WorkerHeartbeatRepository = PgWorkerHeartbeatRepository(conn)

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
          throw e
