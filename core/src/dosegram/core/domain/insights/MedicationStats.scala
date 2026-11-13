package dosegram.core.domain.insights

import dosegram.core.domain.Adherence
import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.DelayStats
import dosegram.core.domain.OccurrenceStatus

import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One dose occurrence with the medication it belongs to: the input of the per-medication figures. */
final case class DoseRecord(medicationId: UUID, input: AdherenceInput)

/** The buckets of VISUALS_AND_ANALYTICS section 7.2 for a set of dose occurrences. The four resolved buckets are the
  * inherited [[AdherenceCounts]]; `unknown` and `open` are the exclusion counts that are printed next to every rate;
  * `asNeeded` counts manual logs, which never enter a rate. Cancelled occurrences are in no bucket.
  */
final case class DoseBuckets(
    takenOnTime: Int,
    takenLate: Int,
    skipped: Int,
    missed: Int,
    unknown: Int,
    open: Int,
    asNeeded: Int
):
  def resolved: AdherenceCounts = AdherenceCounts(takenOnTime, takenLate, skipped, missed)
  def isEmpty: Boolean = this == DoseBuckets.zero

  def combine(other: DoseBuckets): DoseBuckets =
    DoseBuckets(
      takenOnTime + other.takenOnTime,
      takenLate + other.takenLate,
      skipped + other.skipped,
      missed + other.missed,
      unknown + other.unknown,
      open + other.open,
      asNeeded + other.asNeeded
    )

object DoseBuckets:
  val zero: DoseBuckets = DoseBuckets(0, 0, 0, 0, 0, 0, 0)

  def ofResolved(counts: AdherenceCounts): DoseBuckets =
    zero.copy(
      takenOnTime = counts.takenOnTime,
      takenLate = counts.takenLate,
      skipped = counts.skipped,
      missed = counts.missed
    )

/** The dose occurrences attributed to one local day, by medication. Totals pool the bucket counts; they are never the
  * mean of per-medication percentages (VISUALS section 7.2 "Aggregation").
  */
final case class MedicationDay(perMedication: Map[UUID, DoseBuckets]):
  def total: DoseBuckets = perMedication.values.foldLeft(DoseBuckets.zero)(_.combine(_))

/** The rates of one set of buckets, each behind its minimum sample. */
final case class MedicationFigures(
    buckets: DoseBuckets,
    adherence: Stat[Ratio],
    onTimeRate: Stat[Ratio],
    recordedRate: Stat[Ratio]
)

/** The medication view of a period: the pooled figures, the same figures per medication, the per-day rollups for the
  * calendar, and the delay statistics. As-needed logs appear as counts only.
  */
final case class MedicationPeriod(
    window: DateRange,
    days: Map[LocalDate, MedicationDay],
    total: MedicationFigures,
    perMedication: Map[UUID, MedicationFigures],
    delay: Stat[DelayStats],
    daysWithAsNeededLog: Int
)

/** The medication metrics of VISUALS_AND_ANALYTICS section 7.2. The inherited `Adherence` functions stay the definition
  * of the four resolved buckets, of lateness and of day attribution (VA-ME-10); this module adds the exclusion counts,
  * the recorded rate, the day fraction and the minimum-sample gates.
  */
object MedicationStats:

  /** The one-hot bucket of an occurrence; all zero for a cancelled row and for a manual row that is not taken. */
  def bucket(input: AdherenceInput): DoseBuckets =
    if input.manual then
      if input.status == OccurrenceStatus.Taken then DoseBuckets.zero.copy(asNeeded = 1) else DoseBuckets.zero
    else
      Adherence.classify(input) match
        case Some(resolved) => DoseBuckets.ofResolved(resolved)
        case None           => excluded(input.status)

  private def excluded(status: OccurrenceStatus): DoseBuckets =
    if status == OccurrenceStatus.Unknown then DoseBuckets.zero.copy(unknown = 1)
    else if status.isOpen then DoseBuckets.zero.copy(open = 1)
    else DoseBuckets.zero

  /** The local day an occurrence counts toward: the inherited rule for resolved doses (effective day for taken,
    * resolution day for skipped and missed), the effective day for an as-needed log, the scheduled day for an open or
    * unknown dose. `None` for an occurrence that is in no bucket.
    */
  def dayOf(input: AdherenceInput, zone: ZoneId): Option[LocalDate] =
    if bucket(input).isEmpty then None
    else if input.manual then Some(LocalDays.dateOf(input.effectiveAt.getOrElse(input.scheduledFor), zone))
    else Adherence.dayOf(input, zone).orElse(Some(LocalDays.dateOf(input.scheduledFor, zone)))

  def byDay(records: Iterable[DoseRecord], zone: ZoneId): Map[LocalDate, MedicationDay] =
    records
      .flatMap(record => dayOf(record.input, zone).map(day => (day, record.medicationId, bucket(record.input))))
      .groupMap(_._1)(attributed => (attributed._2, attributed._3))
      .view
      .mapValues(doses => MedicationDay(doses.groupMapReduce(_._1)(_._2)(_.combine(_))))
      .toMap

  def total(days: Iterable[MedicationDay]): DoseBuckets =
    days.foldLeft(DoseBuckets.zero)((sum, day) => sum.combine(day.total))

  def perMedication(days: Iterable[MedicationDay]): Map[UUID, DoseBuckets] =
    days.flatMap(_.perMedication).groupMapReduce(_._1)(_._2)(_.combine(_))

  /** `(Ton + Tlate) / (Ton + Tlate + S + M)`; a percentage needs a denominator of at least 5. */
  def adherence(counts: AdherenceCounts, window: DateRange): Stat[Ratio] =
    share(counts.taken, counts.resolved, window)

  /** `Ton / (Ton + Tlate)`: of the doses taken, the share taken on time. */
  def onTimeRate(counts: AdherenceCounts, window: DateRange): Stat[Ratio] =
    share(counts.takenOnTime, counts.taken, window)

  /** `(Ton + Tlate + S) / (Ton + Tlate + S + M)`: the share of resolved doses the user answered. */
  def recordedRate(counts: AdherenceCounts, window: DateRange): Stat[Ratio] =
    share(counts.taken + counts.skipped, counts.resolved, window)

  /** Adherence over the occurrences attributed to one local day, for the heat step of the adherence calendar. It is
    * defined from one resolved dose: a day rarely has five, and the step is drawn, never printed as a percentage.
    */
  def dayFraction(day: DoseBuckets, date: LocalDate): Stat[Ratio] =
    val counts = day.resolved
    Stat.gated(counts.resolved, MinimumSample.AnyRecord, DateRange.single(date), Basis.Recorded)(
      Ratio(counts.taken, counts.resolved)
    )

  /** Median and 90th percentile of the delay of taken doses, corrections excluded; at least 5 samples. */
  def delay(entries: Iterable[AdherenceInput], window: DateRange): Stat[DelayStats] =
    Adherence.delayStats(entries) match
      case Some(stats) => Stat.gated(stats.samples, MinimumSample.DelaySamples, window, Basis.Recorded)(stats)
      case None        => Stat.Insufficient(0, MinimumSample.DelaySamples.required, window)

  /** The number of local days in `days` with at least one as-needed log (descriptive count, never a rate). */
  def daysWithAsNeededLog(days: Map[LocalDate, MedicationDay]): Int =
    days.values.count(_.total.asNeeded > 0)

  def figures(buckets: DoseBuckets, window: DateRange): MedicationFigures =
    val resolved = buckets.resolved
    MedicationFigures(
      buckets,
      adherence(resolved, window),
      onTimeRate(resolved, window),
      recordedRate(resolved, window)
    )

  /** The period view over daily rollups. `doses` are the occurrences of the same days, for the delay statistics, which
    * need single samples and cannot come from daily counts.
    */
  def period(
      days: Map[LocalDate, MedicationDay],
      doses: Iterable[AdherenceInput],
      window: DateRange
  ): MedicationPeriod =
    val inWindow = days.view.filterKeys(window.contains).toMap
    MedicationPeriod(
      window = window,
      days = inWindow,
      total = figures(total(inWindow.values), window),
      perMedication = perMedication(inWindow.values).view.mapValues(figures(_, window)).toMap,
      delay = delay(doses, window),
      daysWithAsNeededLog = daysWithAsNeededLog(inWindow)
    )

  private def share(numerator: Int, denominator: Int, window: DateRange): Stat[Ratio] =
    Stat.gated(denominator, MinimumSample.PercentageDenominator, window, Basis.Recorded)(
      Ratio(numerator, denominator)
    )
end MedicationStats
