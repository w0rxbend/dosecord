package dosecord.core.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One occurrence's contribution to the adherence maths (ROADMAP M1.4b). The persistence layer (M1.5) maps stored rows
  * onto this shape: `manual` is `dose_occurrences.origin = manual` and `corrected` is "the resolving action was
  * `manually_corrected`", both read from the action log.
  *
  * `resolvedAt` is the resolution instant for non-taken resolutions (`skipped_at` / `missed_at`); `effectiveAt` is the
  * taken row's `effective_at` (ADR-012: adherence uses `effective_at`, corrections record it explicitly).
  */
final case class AdherenceInput(
    scheduledFor: Instant,
    status: OccurrenceStatus,
    effectiveAt: Option[Instant],
    resolvedAt: Option[Instant],
    dueWindowEnd: Instant,
    manual: Boolean,
    corrected: Boolean
)

object AdherenceInput:

  def of(occ: Occurrence, manual: Boolean, corrected: Boolean): AdherenceInput =
    AdherenceInput(
      scheduledFor = occ.scheduledFor,
      status = occ.status,
      effectiveAt = occ.effectiveAt,
      resolvedAt = occ.skippedAt.orElse(occ.missedAt),
      dueWindowEnd = occ.dueWindowEnd,
      manual = manual,
      corrected = corrected
    )

/** The four resolved buckets of DESIGN.md section 7.3. `unknown`, `cancelled`, open statuses and `origin = manual`
  * never appear here: they are excluded before counting.
  */
final case class AdherenceCounts(takenOnTime: Int, takenLate: Int, skipped: Int, missed: Int):
  def taken: Int = takenOnTime + takenLate
  def resolved: Int = taken + skipped + missed
  def combine(other: AdherenceCounts): AdherenceCounts =
    AdherenceCounts(
      takenOnTime + other.takenOnTime,
      takenLate + other.takenLate,
      skipped + other.skipped,
      missed + other.missed
    )

object AdherenceCounts:
  val zero: AdherenceCounts = AdherenceCounts(0, 0, 0, 0)

/** Delay statistics over taken doses, corrections excluded (DESIGN.md section 7.3). A delay is
  * `effective_at - scheduled_for` and may be negative (a dose logged before its scheduled time). `p90` is the
  * nearest-rank 90th percentile; `median` averages the two middle samples on an even count.
  */
final case class DelayStats(median: Duration, p90: Duration, samples: Int)

/** One local day's adherence. A day with any `skipped`/`missed` is `failing` and breaks a streak; a day whose doses are
  * all taken with nothing left open is `perfect` and extends it; every other day — no doses at all, or doses still open
  * with nothing failed — is neutral (DESIGN.md section 7.3: "streaks treat no-dose days as neutral").
  */
final case class DayAdherence(counts: AdherenceCounts, open: Int):
  def failing: Boolean = counts.skipped + counts.missed > 0
  def perfect: Boolean = counts.resolved > 0 && counts.taken == counts.resolved && open == 0
  def addCounts(more: AdherenceCounts): DayAdherence = copy(counts = counts.combine(more))
  def addOpen: DayAdherence = copy(open = open + 1)

object DayAdherence:
  val zero: DayAdherence = DayAdherence(AdherenceCounts.zero, 0)

/** The adherence definitions of DESIGN.md section 7.3 and ADR-012, as pure functions (ROADMAP M1.4b; the M3.4 stats
  * queries must agree with these over the same rows).
  */
object Adherence:

  /** The one-hot bucket of a single entry, or `None` when the entry is excluded from the adherence denominator:
    * `unknown`, `cancelled`, open statuses and `origin = manual`. A taken dose is late when its `effective_at` is
    * strictly past `due_window_end` (the same boundary `Decide` uses for `taken_late`, snooze-shifted windows
    * included); a taken row without `effective_at` is defensively treated as on time at `scheduled_for` — `Decide`
    * always sets it.
    */
  def classify(input: AdherenceInput): Option[AdherenceCounts] =
    if input.manual then None
    else
      input.status match
        case OccurrenceStatus.Taken =>
          val effective = input.effectiveAt.getOrElse(input.scheduledFor)
          if effective.isAfter(input.dueWindowEnd) then Some(AdherenceCounts(0, 1, 0, 0))
          else Some(AdherenceCounts(1, 0, 0, 0))
        case OccurrenceStatus.Skipped => Some(AdherenceCounts(0, 0, 1, 0))
        case OccurrenceStatus.Missed  => Some(AdherenceCounts(0, 0, 0, 1))
        case _                        => None

  def counts(entries: Iterable[AdherenceInput]): AdherenceCounts =
    entries.foldLeft(AdherenceCounts.zero) { (acc, entry) =>
      classify(entry) match
        case Some(bucket) => acc.combine(bucket)
        case None         => acc
    }

  /** `(taken_on_time + taken_late) / (taken_on_time + taken_late + skipped + missed)`, undefined when the denominator
    * is 0 (DESIGN.md section 7.3).
    */
  def adherence(counts: AdherenceCounts): Option[Double] =
    if counts.resolved == 0 then None
    else Some(counts.taken.toDouble / counts.resolved.toDouble)

  def adherence(entries: Iterable[AdherenceInput]): Option[Double] =
    adherence(counts(entries))

  /** Of the doses taken, the fraction taken on time; undefined when nothing was taken. */
  def onTimeRate(counts: AdherenceCounts): Option[Double] =
    if counts.taken == 0 then None
    else Some(counts.takenOnTime.toDouble / counts.taken.toDouble)

  def onTimeRate(entries: Iterable[AdherenceInput]): Option[Double] =
    onTimeRate(counts(entries))

  /** The local day an entry counts toward (ADR-012: adherence uses `effective_at`): the effective day for a taken dose,
    * the resolution day for a skip or a miss; `None` for excluded entries.
    */
  def dayOf(input: AdherenceInput, zone: ZoneId): Option[LocalDate] =
    classify(input).map { _ =>
      val anchor =
        input.status match
          case OccurrenceStatus.Taken => input.effectiveAt.getOrElse(input.scheduledFor)
          case _                      => input.resolvedAt.getOrElse(input.scheduledFor)
      LocalDate.ofInstant(anchor, zone)
    }

  /** Per-day adherence in the account's zone: resolved entries on their `dayOf`, open entries on their scheduled day
    * (they are the day's remaining work); manual entries are excluded everywhere.
    */
  def byDay(entries: Iterable[AdherenceInput], zone: ZoneId): Map[LocalDate, DayAdherence] =
    entries.foldLeft(Map.empty[LocalDate, DayAdherence]) { (acc, entry) =>
      classify(entry) match
        case Some(bucket) =>
          val day = dayOf(entry, zone).getOrElse(LocalDate.ofInstant(entry.scheduledFor, zone))
          acc.updated(day, acc.getOrElse(day, DayAdherence.zero).addCounts(bucket))
        case None if !entry.manual && entry.status.isOpen =>
          val day = LocalDate.ofInstant(entry.scheduledFor, zone)
          acc.updated(day, acc.getOrElse(day, DayAdherence.zero).addOpen)
        case None => acc
    }

  /** Today's counts in the account's zone: the resolved buckets assigned to the local day of `now` plus the doses still
    * open that were scheduled for today.
    */
  def today(entries: Iterable[AdherenceInput], now: Instant, zone: ZoneId): DayAdherence =
    byDay(entries, zone).getOrElse(LocalDate.ofInstant(now, zone), DayAdherence.zero)

  /** Median and p90 of `effective_at - scheduled_for` over taken doses, corrections excluded (ADR-012: a correction's
    * explicit `effective_at` records when the dose was really taken, not how late the response was). Excluded entries
    * contribute no sample.
    */
  def delayStats(entries: Iterable[AdherenceInput]): Option[DelayStats] =
    val delays = entries.iterator
      .filter(entry => !entry.manual && !entry.corrected && entry.status == OccurrenceStatus.Taken)
      .flatMap(entry => entry.effectiveAt.map(effective => Duration.between(entry.scheduledFor, effective)))
      .toList
      .sortWith((a, b) => a.compareTo(b) < 0)
    delays match
      case Nil    => None
      case sorted =>
        val n = sorted.size
        val median =
          if n % 2 == 1 then sorted(n / 2)
          else sorted(n / 2 - 1).plus(sorted(n / 2).minus(sorted(n / 2 - 1)).dividedBy(2))
        val p90 = sorted(Math.max(0, Math.ceil(0.9 * n).toInt - 1))
        Some(DelayStats(median, p90, n))

  /** The current streak in whole days, walking back from `today`: `perfect` days count, neutral days (no doses, or
    * still-open doses with nothing failed) are skipped, the first `failing` day ends the streak. Days absent from
    * `days` are no-dose days and therefore neutral.
    */
  def streak(days: Map[LocalDate, DayAdherence], today: LocalDate): Int =
    val dates = days.keys.filterNot(_.isAfter(today)).toList.sorted(using Ordering[LocalDate].reverse)
    @annotation.tailrec
    def loop(remaining: List[LocalDate], acc: Int): Int =
      remaining match
        case Nil          => acc
        case date :: rest =>
          val day = days(date)
          if day.failing then acc
          else loop(rest, if day.perfect then acc + 1 else acc)
    loop(dates, 0)
end Adherence
