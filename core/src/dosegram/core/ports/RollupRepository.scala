package dosegram.core.ports

import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupKey
import dosegram.core.domain.insights.RollupMetrics

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `daily_rollups` and its work queue `rollup_dirty` (ARCHITECTURE section 11.1). A rollup is derived data: it is
  * recomputed in full from the source rows of its key and can always be rebuilt (INS-01). The database marks a key
  * dirty in the transaction of the write that changed its source rows (INS-02).
  */
trait RollupRepository:

  /** Takes up to `limit` dirty keys off the queue, oldest first, skipping keys another transaction is writing or
    * claiming. A claimed key is gone from the queue when the transaction commits and is back when it rolls back.
    */
  def claimDirty(limit: Int): List[RollupKey]

  /** Takes the dirty keys of one account and domain inside the range, for a read that must not show a stale day. */
  def claimDirtyIn(accountId: UUID, domain: RollupDomain, range: DateRange): List[LocalDate]

  /** Stores the recomputed metrics of a key, replacing what was there. */
  def put(key: RollupKey, metrics: RollupMetrics, computedAt: Instant): Unit

  /** Removes the rollup of a key whose source rows no longer give a figure (a day with no record has no row). */
  def remove(key: RollupKey): Unit

  /** The rollups of the current version inside the range. */
  def list(accountId: UUID, domain: RollupDomain, range: DateRange): Map[LocalDate, RollupMetrics]

  /** Queues every day of the range for recomputation: the rebuild of INS-04. */
  def markDirty(accountId: UUID, domain: RollupDomain, range: DateRange, now: Instant): Unit
