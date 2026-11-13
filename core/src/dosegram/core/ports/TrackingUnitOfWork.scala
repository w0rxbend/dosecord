package dosegram.core.ports

import java.time.ZoneId
import java.util.UUID

/** One database transaction over the tracking domains and their derived data. It is separate from [[Tx]] so that the
  * in-memory doubles of the chat suites do not have to grow with every new domain; ARCHITECTURE DEP-10 (ROADMAP G2.1)
  * folds both into repository groups on one transaction handle.
  */
trait TrackingTx:
  def hydration: HydrationRepository
  def mood: MoodRepository
  def rollups: RollupRepository
  def doseHistory: DoseHistoryRepository

  /** The account's IANA zone in force now; `None` for an unknown account. */
  def accountZone(accountId: UUID): Option[ZoneId]

trait TrackingUnitOfWork:
  def transaction[A](f: TrackingTx => A): A
