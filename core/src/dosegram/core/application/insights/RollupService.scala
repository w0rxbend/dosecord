package dosegram.core.application.insights

import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupKey
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.ports.Clock
import dosegram.core.ports.TrackingTx
import dosegram.core.ports.TrackingUnitOfWork

import java.time.LocalDate
import java.util.UUID

/** Keeps `daily_rollups` equal to a pure function of the source rows (ARCHITECTURE section 11.1). A rollup is always
  * recomputed in full for its key, never incremented, so every path here is idempotent: the worker step, the inline
  * refresh of a read, and the rebuild all end in the same rows (INS-01, INS-03, INS-04).
  */
final class RollupService(uow: TrackingUnitOfWork, clock: Clock):

  /** One worker step: recomputes up to `limit` dirty keys and returns how many it took. */
  def drain(limit: Int): Int =
    uow.transaction: tx =>
      val keys = tx.rollups.claimDirty(limit)
      keys
        .groupBy(key => (key.accountId, key.domain))
        .foreach { case ((accountId, domain), own) => recompute(tx, accountId, domain, own.map(_.localDate)) }
      keys.size

  /** Recomputes the dirty days of a range inside the caller's transaction, so a read never shows a stale day. */
  def refresh(tx: TrackingTx, accountId: UUID, domain: RollupDomain, range: DateRange): Unit =
    recompute(tx, accountId, domain, tx.rollups.claimDirtyIn(accountId, domain, range))

  /** Recomputes every day of the range from its source rows, whatever is stored. */
  def rebuild(accountId: UUID, domain: RollupDomain, range: DateRange): Unit =
    uow.transaction: tx =>
      tx.rollups.markDirty(accountId, domain, range, clock.now())
      refresh(tx, accountId, domain, range)

  private def recompute(tx: TrackingTx, accountId: UUID, domain: RollupDomain, dates: List[LocalDate]): Unit =
    if dates.nonEmpty then
      val span = DateRange.between(dates.minBy(_.toEpochDay), dates.maxBy(_.toEpochDay))
      val fresh = source(tx, accountId, domain, span)
      val now = clock.now()
      dates.foreach { date =>
        val key = RollupKey(accountId, date, domain)
        fresh.get(date) match
          case Some(metrics) => tx.rollups.put(key, metrics, now)
          case None          => tx.rollups.remove(key)
      }

  /** The rollups of every day of `span`, straight from the source rows. A day without a record has no entry. */
  private def source(
      tx: TrackingTx,
      accountId: UUID,
      domain: RollupDomain,
      span: DateRange
  ): Map[LocalDate, RollupMetrics] =
    domain match
      case RollupDomain.Hydration =>
        val goals = tx.hydration.goals(accountId, span.end)
        HydrationStats
          .byDay(tx.hydration.listLive(accountId, span), goals)
          .view
          .mapValues(RollupMetrics.Hydration(_))
          .toMap
      case RollupDomain.Mood =>
        MoodStats.byDay(tx.mood.listLive(accountId, span)).view.mapValues(RollupMetrics.Mood(_)).toMap
      case RollupDomain.Medication =>
        tx.accountZone(accountId) match
          case None       => Map.empty
          case Some(zone) =>
            val doses = tx.doseHistory.attributableBetween(
              accountId,
              LocalDays.startOf(span.start, zone),
              LocalDays.endOf(span.end, zone)
            )
            MedicationStats.byDay(doses, zone).view.mapValues(RollupMetrics.Medication(_)).toMap
end RollupService
