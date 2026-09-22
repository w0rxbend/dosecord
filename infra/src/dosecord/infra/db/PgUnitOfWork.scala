package dosecord.infra.db

import dosecord.core.ports.AccountRepository
import dosecord.core.ports.AuditRepository
import dosecord.core.ports.CallbackSlotRepository
import dosecord.core.ports.Clock
import dosecord.core.ports.DomainEventRepository
import dosecord.core.ports.DoseActionRepository
import dosecord.core.ports.FormRunRepository
import dosecord.core.ports.IdentityRepository
import dosecord.core.ports.InboundEventRepository
import dosecord.core.ports.MedicationRepository
import dosecord.core.ports.MoodCheckinRepository
import dosecord.core.ports.OccurrenceRepository
import dosecord.core.ports.OutboxRepository
import dosecord.core.ports.RenderedMessageRepository
import dosecord.core.ports.ScheduleRepository
import dosecord.core.ports.ScheduleRevisionRepository
import dosecord.core.ports.SessionRepository
import dosecord.core.ports.Tx
import dosecord.core.ports.UnitOfWork

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
    override lazy val moodCheckins: MoodCheckinRepository = PgMoodCheckinRepository(conn)
    override lazy val audit: AuditRepository = PgAuditRepository(conn, clock)
    override lazy val slots: CallbackSlotRepository = PgCallbackSlotRepository(conn)
    override lazy val formRuns: FormRunRepository = PgFormRunRepository(conn)
    override lazy val medications: MedicationRepository = PgMedicationRepository(conn)
    override lazy val schedules: ScheduleRepository = PgScheduleRepository(conn)
    override lazy val revisions: ScheduleRevisionRepository = PgScheduleRevisionRepository(conn)
    override lazy val occurrences: OccurrenceRepository = PgOccurrenceRepository(conn)
    override lazy val doseActions: DoseActionRepository = PgDoseActionRepository(conn)
