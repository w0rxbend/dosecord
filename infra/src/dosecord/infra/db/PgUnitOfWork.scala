package dosecord.infra.db

import dosecord.core.ports.AuditRepository
import dosecord.core.ports.Clock
import dosecord.core.ports.DomainEventRepository
import dosecord.core.ports.IdentityRepository
import dosecord.core.ports.InboundEventRepository
import dosecord.core.ports.OutboxRepository
import dosecord.core.ports.RenderedMessageRepository
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
    override lazy val audit: AuditRepository = PgAuditRepository(conn, clock)
