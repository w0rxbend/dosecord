package dosegram.core.application.insights

import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.TrendCopy
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationPeriod
import dosegram.core.domain.insights.MealDay
import dosegram.core.domain.insights.MealPeriod
import dosegram.core.domain.insights.MedicationPeriod
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.MoodPeriod
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.SleepPeriod
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.TrendFrame
import dosegram.core.ports.TrendCardRow
import dosegram.core.ports.TrendsCardView

import java.time.LocalDate
import java.time.ZoneId

/** The records behind one trend block, for a surface that draws more than the block's words (the Mini App's charts). */
enum TrendSource(val domain: TrendDomain):
  case Medication(period: MedicationPeriod) extends TrendSource(TrendDomain.Medication)
  case Hydration(days: Map[LocalDate, HydrationDay]) extends TrendSource(TrendDomain.Hydration)
  case Mood(days: Map[LocalDate, MoodDay]) extends TrendSource(TrendDomain.Mood)
  case Sleep(days: Map[LocalDate, SleepNight], regularity: SleepRegularityFigures)
      extends TrendSource(TrendDomain.Sleep)
  case Nutrition(days: Map[LocalDate, MealDay]) extends TrendSource(TrendDomain.Nutrition)

/** One domain of a trend period in the core's words: its figures (empty when the period has no record of it), the
  * user's own previous period of the same length (MINI_APP MA-INS-3), and one value per bucket of the frame, a gap
  * where the bucket has no record. The chat, the trends card and the Mini App all print `lines` and `comparison` as
  * they are, so the same figure reads the same on every surface (ROADMAP G9.5).
  */
final case class TrendBlock(
    source: TrendSource,
    lines: List[String],
    comparison: String,
    buckets: List[Option[Double]]
):
  def domain: TrendDomain = source.domain
  def hasData: Boolean = lines.nonEmpty

/** The trends of a period (ROADMAP G9.4, G9.5): one block per domain whose consent is active, in [[TrendDomain]] order,
  * and, with the counts switch on, the hit days of the Monday week that contains today (PRODUCT_SPEC R-GAM-1).
  */
final case class TrendsView(zone: ZoneId, frame: TrendFrame, blocks: List[TrendBlock], weekCounts: Option[String]):
  def hasData: Boolean = blocks.exists(_.hasData)

  /** The text of the trends in chat: the header, then each domain with a record, its figures and the previous period;
    * then the week's hit days. A period with nothing at all says so (BOT_UX BUX-STAT-02).
    */
  def lines: List[String] =
    val shown = blocks.filter(_.hasData)
    val body =
      if shown.isEmpty then List(StatsCopy.Empty)
      else shown.flatMap(block => List(TrendCopy.domainLine(block.domain, block.lines), block.comparison))
    TrendCopy.header(frame.period, zone) :: body ++ weekCounts.toList

  /** IMG-23: the domains with a record, each with its headline as the text prints it; `None` with nothing to draw. */
  def card: Option[TrendsCardView] =
    val shown = blocks.filter(_.hasData)
    Option.when(shown.nonEmpty):
      val sentences = shown.map(block => TrendCopy.cardSentence(block.domain, block.lines.head, block.comparison))
      TrendsCardView(
        frame.current,
        frame.period.byWeek,
        shown.map(block => TrendCardRow(block.domain, block.lines.head, block.buckets)),
        (TrendCopy.cardHeader(frame.period, zone, frame.current) :: sentences).mkString("\n")
      )

/** The blocks of [[TrendsView]], pure over the records of the current and the previous window. A figure below its
  * minimum sample is printed as what it still needs with the counts switch on, and left out with it off (R-GAM-5).
  */
object TrendBlocks:

  def medication(frame: TrendFrame, current: MedicationPeriod, previous: MedicationPeriod): TrendBlock =
    val shares = frame.buckets.map: bucket =>
      val pooled = bucket.dates.flatMap(current.days.get).map(_.total)
      val scheduled = pooled.map(_.scheduled).sum
      Option.when(scheduled > 0)(pooled.map(_.resolved.taken).sum.toDouble / scheduled)
    TrendBlock(
      TrendSource.Medication(current),
      StatsCopy.doseFigures(current.total),
      TrendCopy.doses(frame.period.days, previous.total),
      shares
    )

  def hydration(
      frame: TrendFrame,
      current: HydrationPeriod,
      days: Map[LocalDate, HydrationDay],
      previous: HydrationPeriod,
      countsOn: Boolean
  ): TrendBlock =
    TrendBlock(
      TrendSource.Hydration(days),
      if countsOn then StatsCopy.waterFigures(current) else StatsCopy.waterFiguresOmittingGates(current),
      TrendCopy.water(frame.period.days, previous),
      frame.means(days)(_.totalMl.toDouble)
    )

  def mood(
      frame: TrendFrame,
      current: MoodPeriod,
      days: Map[LocalDate, MoodDay],
      previous: MoodPeriod,
      countsOn: Boolean
  ): TrendBlock =
    TrendBlock(
      TrendSource.Mood(days),
      if countsOn then StatsCopy.moodFigures(current) else StatsCopy.moodFiguresOmittingGates(current),
      TrendCopy.mood(frame.period.days, previous),
      frame.means(days)(_.mean)
    )

  def sleep(
      frame: TrendFrame,
      current: SleepPeriod,
      days: Map[LocalDate, SleepNight],
      regularity: SleepRegularityFigures,
      previous: SleepPeriod,
      countsOn: Boolean
  ): TrendBlock =
    TrendBlock(
      TrendSource.Sleep(days, regularity),
      if countsOn then StatsCopy.sleepFigures(current) else StatsCopy.sleepFiguresOmittingGates(current),
      TrendCopy.sleep(frame.period.days, previous),
      frame.means(days)(_.minutes.toDouble)
    )

  /** Meals logged per bucket: the distinct main meal types of each day, summed (VISUALS section 7.5). */
  def nutrition(
      frame: TrendFrame,
      current: MealPeriod,
      days: Map[LocalDate, MealDay],
      previous: MealPeriod
  ): TrendBlock =
    TrendBlock(
      TrendSource.Nutrition(days),
      TrendCopy.foodFigures(current),
      TrendCopy.food(frame.period.days, previous),
      frame.sums(days)(_.mainMeals.toDouble)
    )
end TrendBlocks
