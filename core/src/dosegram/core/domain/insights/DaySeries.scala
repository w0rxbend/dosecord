package dosegram.core.domain.insights

import java.time.LocalDate

/** One local day of a chart of days. `value` is absent on a day without a record, which every chart draws as a gap and
  * never as a zero (VISUALS_AND_ANALYTICS section 7.1, "Average over days").
  */
final case class DayPoint[+A](date: LocalDate, value: Option[A])

/** The days of a period as a chart series. */
object DaySeries:

  /** Every day of the window in order, behind the gate of the statistics: the series is shown only when at least one
    * day of the window has a record, so no surface draws a chart from an empty sample (VA-ME-03).
    */
  def of[A](recorded: Map[LocalDate, A], window: DateRange): Stat[List[DayPoint[A]]] =
    val inWindow = recorded.view.filterKeys(window.contains).toMap
    Stat.gated(inWindow.size, MinimumSample.AnyRecord, window, Basis.Recorded)(
      window.dates.map(date => DayPoint(date, inWindow.get(date)))
    )
