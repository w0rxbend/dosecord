package dosecord.infra.db

import dosecord.core.ports.WorkerHeartbeatRepository

import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import scala.language.implicitConversions

/** Postgres `worker_heartbeat`: one row per instance, upserted after each batch (DESIGN.md section 7.4). */
final class PgWorkerHeartbeatRepository(conn: Connection) extends WorkerHeartbeatRepository:
  private given Connection = conn

  override def touch(instance: String, role: String, now: Instant): Unit =
    sql"""INSERT INTO worker_heartbeat (instance, role, last_tick_at) VALUES ($instance, $role, $now)
          ON CONFLICT (instance) DO UPDATE SET role = $role, last_tick_at = $now""".execute()
    ()

  override def maxLastTick(): Option[Instant] =
    // MAX() over an empty table yields one NULL row, so map the nullable column explicitly.
    given RowMapper[Option[Instant]] = rs => Option(rs.getObject(1, classOf[OffsetDateTime])).map(_.toInstant)
    sql"SELECT MAX(last_tick_at) FROM worker_heartbeat".queryOne[Option[Instant]]().flatten
