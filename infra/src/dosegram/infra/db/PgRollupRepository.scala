package dosegram.infra.db

import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupKey
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.domain.insights.RollupMetricsJson
import dosegram.core.ports.RollupRepository

import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `daily_rollups` and `rollup_dirty` (ARCHITECTURE section 11.1). A claim deletes the queue rows it takes
  * under `FOR UPDATE SKIP LOCKED`: a key that a source write is still marking is locked by that write and is left for
  * the next claim, so a marker is never consumed before the row that caused it is visible.
  */
final class PgRollupRepository(conn: Connection) extends RollupRepository:
  private given Connection = conn

  private given RowMapper[RollupKey] = rs =>
    RollupKey(rs.uuid("account_id"), rs.localDate("local_date"), RollupDomain.fromDbValue(rs.getString("domain")))

  /** The domains this build computes. A key of a domain that arrives with a later slice stays in the queue. */
  private val domains: Array[String] = RollupDomain.values.map(_.dbValue)

  override def claimDirty(limit: Int): List[RollupKey] =
    sql"""DELETE FROM rollup_dirty d
          USING (SELECT account_id, local_date, domain FROM rollup_dirty
                 WHERE domain = ANY($domains)
                 ORDER BY marked_at, account_id, local_date, domain
                 LIMIT $limit
                 FOR UPDATE SKIP LOCKED) claimed
          WHERE d.account_id = claimed.account_id AND d.local_date = claimed.local_date
            AND d.domain = claimed.domain
          RETURNING d.account_id, d.local_date, d.domain""".query[RollupKey]()

  override def claimDirtyIn(accountId: UUID, domain: RollupDomain, range: DateRange): List[LocalDate] =
    sql"""DELETE FROM rollup_dirty d
          USING (SELECT account_id, local_date, domain FROM rollup_dirty
                 WHERE account_id = $accountId AND domain = ${domain.dbValue}
                   AND local_date >= ${range.start} AND local_date <= ${range.end}
                 ORDER BY local_date
                 FOR UPDATE SKIP LOCKED) claimed
          WHERE d.account_id = claimed.account_id AND d.local_date = claimed.local_date
            AND d.domain = claimed.domain
          RETURNING d.local_date""".query[LocalDate]()

  override def put(key: RollupKey, metrics: RollupMetrics, computedAt: Instant): Unit =
    sql"""INSERT INTO daily_rollups (account_id, local_date, domain, metrics, rollup_version, computed_at)
          VALUES (${key.accountId}, ${key.localDate}, ${key.domain.dbValue},
                  ${Jsonb(RollupMetricsJson.encode(metrics))}, ${RollupMetricsJson.Version}, $computedAt)
          ON CONFLICT (account_id, local_date, domain) DO UPDATE
            SET metrics = EXCLUDED.metrics, rollup_version = EXCLUDED.rollup_version,
                computed_at = EXCLUDED.computed_at""".execute()
    ()

  override def remove(key: RollupKey): Unit =
    sql"""DELETE FROM daily_rollups
          WHERE account_id = ${key.accountId} AND local_date = ${key.localDate}
            AND domain = ${key.domain.dbValue}""".execute()
    ()

  override def list(accountId: UUID, domain: RollupDomain, range: DateRange): Map[LocalDate, RollupMetrics] =
    given RowMapper[(LocalDate, RollupMetrics)] = rs =>
      rs.localDate("local_date") -> RollupMetricsJson.decode(domain, rs.getString("metrics"))
    sql"""SELECT local_date, metrics FROM daily_rollups
          WHERE account_id = $accountId AND domain = ${domain.dbValue}
            AND local_date >= ${range.start} AND local_date <= ${range.end}
            AND rollup_version = ${RollupMetricsJson.Version}""".query[(LocalDate, RollupMetrics)]().toMap

  override def markDirty(accountId: UUID, domain: RollupDomain, range: DateRange, now: Instant): Unit =
    sql"""INSERT INTO rollup_dirty (account_id, local_date, domain, marked_at)
          SELECT $accountId, day::date, ${domain.dbValue}, $now
          FROM generate_series(${range.start}::timestamp, ${range.end}::timestamp, interval '1 day') AS day
          ON CONFLICT (account_id, local_date, domain) DO UPDATE SET marked_at = EXCLUDED.marked_at""".execute()
    ()
