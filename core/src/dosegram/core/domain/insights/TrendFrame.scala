package dosegram.core.domain.insights

import java.time.LocalDate

/** The domains of a trend, in the order every surface lists them: the chat, the card and the Mini App. */
enum TrendDomain:
  case Medication, Hydration, Mood, Sleep, Nutrition

/** The windows of one trend request: the period, its current window, the previous one of the same length, and the
  * buckets its charts and small lines are drawn in (VISUALS section 9.2): by day for 7 and 30 days, by week for 90.
  */
final case class TrendFrame(period: TrendPeriod, current: DateRange, previous: DateRange):
  val weeks: List[DateRange] = TrendPeriod.weeks(current)
  val buckets: List[DateRange] = if period.byWeek then weeks else current.dates.map(DateRange.single)

  /** Per bucket, the mean of a value over the recorded days in it; a bucket without a record is a gap. */
  def means[A](days: Map[LocalDate, A])(value: A => Double): List[Option[Double]] =
    buckets.map: bucket =>
      val own = bucket.dates.flatMap(days.get).map(value)
      Option.when(own.nonEmpty)(own.sum / own.size)

  /** Per bucket, the sum of a value over the recorded days in it; a bucket without a record is a gap. */
  def sums[A](days: Map[LocalDate, A])(value: A => Double): List[Option[Double]] =
    buckets.map: bucket =>
      val own = bucket.dates.flatMap(days.get).map(value)
      Option.when(own.nonEmpty)(own.sum)

object TrendFrame:

  /** The frame of `period` ending on the account's current local day. */
  def of(period: TrendPeriod, today: LocalDate): TrendFrame =
    TrendFrame(period, period.current(today), period.previous(today))
