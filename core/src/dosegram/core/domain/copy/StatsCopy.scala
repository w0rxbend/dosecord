package dosegram.core.domain.copy

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.Comparisons
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationPeriod
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.InsightRule
import dosegram.core.domain.insights.MedicationFigures
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.MoodPeriod
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.PairedDay
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.SleepPeriod
import dosegram.core.domain.insights.SleepStats
import dosegram.core.domain.insights.Stat
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.mood.MoodLevel

import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Text statistics and insight sentences (BOT_UX section 12.5; VISUALS_AND_ANALYTICS sections 7 and 8.6; ROADMAP G2.20
  * and G6.8). Every line states counts over the user's own records, with the denominator or the coverage next to the
  * figure. A figure below its minimum sample is printed as what is still needed, never as a number; a zero denominator
  * is "No data". Nothing here compares the user with anyone, advises anything or names a cause: `InsightPhrases` lints
  * every entry.
  */
object StatsCopy:

  val NoData: String = "No data"

  /** The fixed sentence that closes every comparison (VISUALS VA-IN-11). */
  val NotACause: String = "This compares your own days. It does not show a cause."

  /** An empty period explains the absence (BOT_UX BUX-STAT-02). */
  val Empty: String = "Nothing was recorded in this period."

  def header(period: StatsPeriod, zone: ZoneId): String =
    val name = period match
      case StatsPeriod.Today      => "Today"
      case StatsPeriod.Last7Days  => "Last 7 days"
      case StatsPeriod.Last30Days => "Last 30 days"
    s"$name · ${zone.getId}"

  /** The dose lines of a period; empty when the period has no dose at all. Status words follow BOT_UX section 22.4: a
    * missed dose is "not confirmed", an unknown one "no record".
    */
  def doses(figures: MedicationFigures): List[String] =
    doseFigures(figures) match
      case first :: rest => s"Doses: $first" :: rest
      case Nil           => Nil

  /** The dose lines without the domain name in front, for a surface that shows the domain as a heading. */
  def doseFigures(figures: MedicationFigures): List[String] =
    if figures.buckets.isEmpty then Nil
    else
      val buckets = figures.buckets
      val resolved = buckets.resolved
      List(
        Some(headline(figures)),
        Option.when(resolved.taken > 0)(s"${resolved.takenOnTime} of ${resolved.taken} taken doses on time"),
        Option.when(resolved.resolved > 0)(
          s"${resolved.taken + resolved.skipped} of ${resolved.resolved} answered"
        ),
        Option.when(buckets.unknown > 0)(s"${buckets.unknown} with no record, not counted"),
        Option.when(buckets.open > 0)(s"${buckets.open} still due, not counted"),
        Option.when(buckets.asNeeded > 0)(s"As-needed logs: ${buckets.asNeeded}")
      ).flatten

  /** One line of the "By medication" view. `label` is the caller's: the name, or "Item 1" in discreet mode. */
  def medication(label: String, figures: MedicationFigures): String = s"$label: ${headline(figures)}"

  private def headline(figures: MedicationFigures): String =
    val resolved = figures.buckets.resolved
    val taken = figures.adherence match
      case Stat.Shown(ratio, _, _, _) => s"${ratio.numerator} of ${ratio.denominator} taken (${ratio.percent}%)"
      case _: Stat.Insufficient[?] | _: Stat.NoVariation[?] =>
        if resolved.resolved == 0 then NoData else s"${resolved.taken} of ${resolved.resolved} taken"
    List(
      Some(taken),
      Option.when(resolved.skipped > 0)(s"skipped ${resolved.skipped}"),
      Option.when(resolved.missed > 0)(s"not confirmed ${resolved.missed}")
    ).flatten.mkString(" · ")

  /** The water line of a 7-day or 30-day period; `None` when the period has no entry. */
  def water(period: HydrationPeriod): Option[String] =
    Option.when(period.recordedDays > 0)(waterFigures(period).mkString("Water: ", " · ", ""))

  /** The figures of the water line one by one; empty when the period has no entry. */
  def waterFigures(period: HydrationPeriod): List[String] =
    if period.recordedDays == 0 then Nil
    else
      val goal = period.goalDays.map:
        case Stat.Shown(ratio, _, _, _) =>
          s"goal reached on ${ratio.numerator} of ${ratio.denominator} recorded days"
        case Stat.Insufficient(n, required, _) =>
          s"${more(required - n, "recorded day")} with a goal for the goal count"
        case Stat.NoVariation(_, _) => NoData
      val average = period.meanMl match
        case Stat.Shown(millilitres, _, _, _)  => s"average ${litres(millilitres, 1)} l a day"
        case Stat.Insufficient(n, required, _) => s"${more(required - n, "recorded day")} for an average"
        case Stat.NoVariation(_, _)            => NoData
      goal.toList ++ List(average, coverage(period.recordedDays, period.window))

  /** The water line of one day: the total, and the progress toward the user's own goal when there is one. */
  def waterDay(day: Option[HydrationDay]): Option[String] =
    day.map: recorded =>
      recorded.progress match
        case Some(progress) =>
          s"Water today: ${dayLitres(progress.totalMl)} of ${dayLitres(progress.goalMl)} l (${progress.percent}%)"
        case None => s"Water today: ${dayLitres(recorded.totalMl)} l"

  /** The mood line of a 7-day or 30-day period; `None` when the period has no check-in. Variability is a number with no
    * label (VISUALS section 7.6).
    */
  def mood(period: MoodPeriod): Option[String] =
    Option.when(period.checkInDays > 0)(moodFigures(period).mkString("Mood: ", " · ", ""))

  /** The figures of the mood line one by one; empty when the period has no check-in. */
  def moodFigures(period: MoodPeriod): List[String] =
    if period.checkInDays == 0 then Nil
    else
      val average = period.average match
        case Stat.Shown(mean, _, _, _)         => s"average ${meanWithLevel(mean)}"
        case Stat.Insufficient(n, required, _) => s"${more(required - n, "day")} with a check-in for an average"
        case Stat.NoVariation(_, _)            => NoData
      val change = period.dayToDayChange.shown.map(value => s"day-to-day change averaged ${oneDecimal(value)} levels")
      List(count(period.checkIns, "check-in"), average) ++ change.toList :+
        coverage(period.checkInDays, period.window)

  /** The mood line of one day: the day value, which is the mean of the day's check-ins. */
  def moodDay(day: Option[MoodDay]): Option[String] =
    day.map(recorded => s"Mood today: ${meanWithLevel(recorded.mean)} · ${count(recorded.n, "check-in")}")

  /** The sleep line of a 7-day or 30-day period; `None` when the period has no night (BOT_UX section 12.5). */
  def sleep(period: SleepPeriod): Option[String] =
    Option.when(period.nights > 0)(sleepFigures(period).mkString("Sleep: ", " · ", ""))

  /** The figures of the sleep line one by one; empty when the period has no night. Every figure names its source
    * (MINI_APP MA-SLEEP-1), and no reference duration is ever given (MA-SLEEP-4).
    */
  def sleepFigures(period: SleepPeriod): List[String] =
    if period.nights == 0 then Nil
    else
      val average = period.averageMinutes match
        case Stat.Shown(minutes, _, _, _)      => s"average ${duration(Math.round(minutes).toInt)}"
        case Stat.Insufficient(n, required, _) => s"${more(required - n, "night")} for an average"
        case Stat.NoVariation(_, _)            => NoData
      List(s"${count(period.nights, "night")} logged", average, SleepSource, coverage(period.nights, period.window))

  /** The sleep line of one day: the night that ended on it. */
  def sleepDay(night: Option[SleepNight]): Option[String] =
    night.map(recorded => s"Sleep last night: ${duration(recorded.minutes)} ($SleepSource)")

  /** Where every sleep figure comes from: what the user entered (MA-SLEEP-1). */
  val SleepSource: String = "your check-ins"

  /** Hours and minutes, "7 h 05 min", as the images write a duration. */
  def duration(minutes: Int): String =
    if minutes < 60 then s"$minutes min" else f"${minutes / 60} h ${minutes % 60}%02d min"

  /** The text of a comparison: the sentence with both group sizes and the fixed closing sentence, or what is still
    * needed. A comparison that is withheld or has no variation yields nothing: it is omitted, not explained away.
    */
  def comparison(result: Comparison): Option[String] =
    result match
      case Comparison.Shown(rule, first, second, _) =>
        Some(s"${InsightCopy.sentence(rule, first, second)} $NotACause")
      case Comparison.TooFewDays(rule, n, required, _) =>
        Some(s"${more(required - n, "day")} with ${InsightCopy.recordsOf(rule)} for this comparison.")
      case Comparison.TooFewInGroup(_, n, required, _) =>
        Some(s"${more(required - n, "day")} in the smaller group of days for this comparison.")
      case _: Comparison.NoVariation | _: Comparison.Withheld => None

  private[copy] def meanWithLevel(mean: Double): String =
    s"${oneDecimal(mean)} (${MoodLabels.level(MoodLevel.ofMean(mean)).toLowerCase(Locale.ROOT)})"

  private def coverage(recorded: Int, window: DateRange): String = s"$recorded of ${window.days} days have data"

  private[copy] def more(missing: Int, noun: String): String = s"$missing more ${plural(missing, noun)}"

  private[copy] def count(n: Int, noun: String): String = s"$n ${plural(n, noun)}"

  private[copy] def plural(n: Int, noun: String): String = if n == 1 then noun else s"${noun}s"

  private[copy] def oneDecimal(value: Double): String =
    java.math.BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString

  /** Litres with at most `decimals` places and at least one: "1.25", "0.5", "2.0". */
  private[copy] def litres(millilitres: Int, decimals: Int): String =
    litresRounded(millilitres, decimals, RoundingMode.HALF_UP)

  /** A day's water against its goal: two places at most, rounded down, so a day short of the goal never reads as the
    * goal ("1.99 of 2.0 l", not "2.0 of 2.0 l") and the litres agree with the floored percentage. The water image uses
    * the same rule.
    */
  private[copy] def dayLitres(millilitres: Int): String = litresRounded(millilitres, 2, RoundingMode.DOWN)

  private def litresRounded(millilitres: Int, decimals: Int, mode: RoundingMode): String =
    val rounded = java.math.BigDecimal.valueOf(millilitres.toLong, 3).setScale(decimals, mode)
    val stripped = rounded.stripTrailingZeros
    (if stripped.scale < 1 then stripped.setScale(1) else stripped).toPlainString

  val entries: List[CopyEntry] = Samples.entries

  /** One rendering of every template, over values built by the same statistic functions the product uses. */
  private object Samples:
    private val end = LocalDate.parse("2026-11-08")
    private val week = DateRange.endingOn(end, 7)
    private val month = DateRange.endingOn(end, 30)
    private val kyiv = ZoneId.of("Europe/Kyiv")

    private def figures(buckets: DoseBuckets): MedicationFigures = MedicationStats.figures(buckets, week)

    private val full = figures(DoseBuckets(22, 4, 1, 1, unknown = 2, open = 1, asNeeded = 3))
    private val counts = figures(DoseBuckets.ofResolved(AdherenceCounts(3, 0, 0, 1)))
    private val unknownOnly = figures(DoseBuckets.zero.copy(unknown = 2))

    private def waterDays(count: Int, goalMl: Option[Int]): Map[LocalDate, HydrationDay] =
      List.tabulate(count)(back => end.minusDays(back.toLong) -> HydrationDay(1500 + 250 * back, 4, goalMl)).toMap

    private def moodDays(count: Int): Map[LocalDate, MoodDay] =
      List
        .tabulate(count)(back =>
          end.minusDays(back.toLong) -> MoodDay(2, 5 + back % 4, 2, 4, energyN = 0, energySum = 0)
        )
        .toMap

    private def sleepDays(count: Int): Map[LocalDate, SleepNight] =
      List.tabulate(count)(back => end.minusDays(back.toLong) -> SleepNight(400 + 10 * back, None)).toMap

    /** The two groups on alternating days, so that both halves of the window see both groups. */
    private def pairs(first: Int, second: Int): List[PairedDay] =
      List.tabulate(first)(index => PairedDay(month.start.plusDays(2L * index), inFirstGroup = true, 4.0)) ++
        List.tabulate(second)(index => PairedDay(month.start.plusDays(2L * index + 1L), inFirstGroup = false, 3.0))

    private def compared(first: Int, second: Int): Comparison =
      Comparisons.compare(InsightRule.WaterGoalAndMood, pairs(first, second), month)

    private def sample(id: String, text: Option[String]): CopyEntry =
      CopyEntry(id, text.getOrElse(throw new IllegalStateException(s"copy sample $id rendered nothing")))

    val entries: List[CopyEntry] =
      List(
        CopyEntry("stats.header.today", header(StatsPeriod.Today, kyiv)),
        CopyEntry("stats.header.7d", header(StatsPeriod.Last7Days, kyiv)),
        CopyEntry("stats.header.30d", header(StatsPeriod.Last30Days, kyiv)),
        CopyEntry("stats.empty", Empty),
        CopyEntry("stats.doses", doses(full).mkString("\n")),
        CopyEntry("stats.doses.counts", doses(counts).mkString("\n")),
        CopyEntry("stats.doses.no_data", doses(unknownOnly).mkString("\n")),
        CopyEntry("stats.medication", medication("Item 1", full)),
        sample("stats.water", water(HydrationStats.period(waterDays(5, Some(2000)), week))),
        sample("stats.water.no_goal", water(HydrationStats.period(waterDays(5, None), week))),
        sample("stats.water.gate", water(HydrationStats.period(waterDays(2, Some(2000)), week))),
        sample("stats.water.day", waterDay(Some(HydrationDay(1250, 3, Some(2000))))),
        sample("stats.water.day.no_goal", waterDay(Some(HydrationDay(1250, 3, None)))),
        sample("stats.mood", mood(MoodStats.period(moodDays(6), week))),
        sample("stats.mood.gate", mood(MoodStats.period(moodDays(1), week))),
        sample("stats.mood.day", moodDay(Some(MoodDay(2, 7, 2, 5, energyN = 0, energySum = 0)))),
        sample("stats.sleep", sleep(SleepStats.period(sleepDays(5), week))),
        sample("stats.sleep.gate", sleep(SleepStats.period(sleepDays(2), week))),
        sample("stats.sleep.day", sleepDay(Some(SleepNight(430, None)))),
        sample("insight.ir04", comparison(compared(8, 9))),
        sample("insight.ir04.gate.days", comparison(compared(5, 4))),
        sample("insight.ir04.gate.group", comparison(compared(12, 3)))
      )
  end Samples
end StatsCopy
