package dosecord.infra.db

import dosecord.core.ports.AuditEntry
import dosecord.core.ports.AuditRepository
import dosecord.core.ports.Clock
import upickle.default.ReadWriter

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

final class PgAuditRepository(conn: Connection, clock: Clock) extends AuditRepository:
  private given Connection = conn

  // The detail document is the whole entry; `kind`/`outcome` stay queryable columns.
  private given ReadWriter[AuditEntry] = upickle.default.macroRW

  override def append(entry: AuditEntry): Unit =
    sql"""INSERT INTO auth_audit_log (id, user_id, platform_identity_id, kind, outcome, detail, occurred_at)
          VALUES (${UUID.randomUUID()}, ${entry.userId}, ${entry.platformIdentityId}, ${entry.kind}, ${entry.outcome},
                  ${Jsonb(upickle.default.write(entry))}, ${clock.now()})""".execute()
