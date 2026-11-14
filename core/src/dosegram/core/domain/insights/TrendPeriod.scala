package dosegram.core.domain.insights

import java.time.LocalDate

/** The periods of the Mini App dashboard (MINI_APP MA-INS-1, VISUALS_AND_ANALYTICS section 9.2): 7, 30 and 90 local
  * days ending on the account's current local day. Each is compared with the window of the same length just before it
  * (MA-INS-3). 90 days is drawn in week buckets; the shorter periods by day. The chat keeps its own periods
  * ([[StatsPeriod]]), so adding a dashboard period never adds a button to `/stats`.
  */
enum TrendPeriod(val days: Int):
  case Week extends TrendPeriod(7)
  case Month extends TrendPeriod(30)
  case Quarter extends TrendPeriod(90)

  def current(today: LocalDate): DateRange = DateRange.endingOn(today, days)

  /** The window of the same length that ends the day before [[current]] starts. */
  def previous(today: LocalDate): DateRange = DateRange.endingOn(today.minusDays(days.toLong), days)

  def byWeek: Boolean = this == TrendPeriod.Quarter

object TrendPeriod:

  def of(days: Int): Option[TrendPeriod] = values.find(_.days == days)

  /** The weeks of a window, oldest first, cut from its last day backwards: the latest week is whole and the oldest one
    * holds what is left (90 days are 12 whole weeks and 6 days).
    */
  def weeks(window: DateRange): List[DateRange] =
    List
      .unfold(window.end): end =>
        Option.when(!end.isBefore(window.start)):
          val start = Seq(end.minusDays(6), window.start).maxBy(_.toEpochDay)
          DateRange.between(start, end) -> start.minusDays(1)
      .reverse
