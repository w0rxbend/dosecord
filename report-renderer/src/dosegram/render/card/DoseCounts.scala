package dosegram.render.card

import dosegram.render.chart.DoseStatus

/** Dose occurrences counted by status. Unknown doses are excluded from every denominator (VISUALS section 7.2) and
  * as-needed logs are not scheduled doses.
  */
final case class DoseCounts(taken: Int, skipped: Int, missed: Int, open: Int, unknown: Int, asNeeded: Int):
  /** Scheduled doses that count: everything except unknown. */
  def counted: Int = taken + skipped + missed + open

  /** Whole percent of counted doses recorded as taken, rounded to the nearest; 100 only when every counted dose was
    * taken, so rounding never turns "almost all" into "all". `None` when nothing counts (VA-PR-04).
    */
  def takenPercent: Option[Int] =
    Option.when(counted > 0) {
      val rounded = (taken * 200 + counted) / (2 * counted)
      if taken < counted then StrictMath.min(99, rounded) else rounded
    }

object DoseCounts:
  def of(statuses: Iterable[DoseStatus]): DoseCounts =
    val byStatus = statuses.groupMapReduce(identity)(_ => 1)(_ + _).withDefaultValue(0)
    DoseCounts(
      taken = byStatus(DoseStatus.TakenOnTime) + byStatus(DoseStatus.TakenLate),
      skipped = byStatus(DoseStatus.Skipped),
      missed = byStatus(DoseStatus.Missed),
      open = byStatus(DoseStatus.Open),
      unknown = byStatus(DoseStatus.Unknown),
      asNeeded = byStatus(DoseStatus.AsNeeded)
    )
