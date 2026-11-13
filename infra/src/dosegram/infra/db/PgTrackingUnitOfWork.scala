package dosegram.infra.db

import dosegram.core.ports.DoseHistoryRepository
import dosegram.core.ports.HydrationRepository
import dosegram.core.ports.MoodRepository
import dosegram.core.ports.RollupRepository
import dosegram.core.ports.TrackingTx
import dosegram.core.ports.TrackingUnitOfWork

import java.sql.Connection
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource
import scala.language.implicitConversions

/** Postgres [[TrackingUnitOfWork]]: one pooled connection per transaction, commit on success, rollback on any
  * exception. It mirrors `PgUnitOfWork` for the tracking repositories until ROADMAP G2.1 gives both one handle.
  */
final class PgTrackingUnitOfWork(dataSource: DataSource) extends TrackingUnitOfWork:

  override def transaction[A](f: TrackingTx => A): A =
    val conn = dataSource.getConnection
    conn.setAutoCommit(false)
    try
      val result = f(PgTrackingTx(conn))
      conn.commit()
      result
    catch
      case e: Throwable =>
        conn.rollback()
        throw e
    finally conn.close()

  private final class PgTrackingTx(conn: Connection) extends TrackingTx:
    private given Connection = conn
    override lazy val hydration: HydrationRepository = PgHydrationRepository(conn)
    override lazy val mood: MoodRepository = PgMoodRepository(conn)
    override lazy val rollups: RollupRepository = PgRollupRepository(conn)
    override lazy val doseHistory: DoseHistoryRepository = PgDoseHistoryRepository(conn)

    override def accountZone(accountId: UUID): Option[ZoneId] =
      sql"SELECT timezone FROM users WHERE id = $accountId".queryOne[String]().map(ZoneId.of)
