package dosegram.core.domain.copy

import dosegram.core.domain.insights.BaselineMetric
import dosegram.core.domain.insights.BaselineStatement
import dosegram.core.domain.insights.BaselineStatements
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.Comparisons
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseSlot
import dosegram.core.domain.insights.GroupMean
import dosegram.core.domain.insights.Insight
import dosegram.core.domain.insights.InsightGap
import dosegram.core.domain.insights.InsightList
import dosegram.core.domain.insights.InsightRule
import dosegram.core.domain.insights.PairedDay
import dosegram.core.domain.insights.SlotShare
import dosegram.core.domain.insights.TimingStatement

import java.time.LocalDate

/** The insight sentences of VISUALS_AND_ANALYTICS section 8.6 and the `/insights` card (ROADMAP G6.8). Every sentence
  * is a template with numbered slots over the user's own records, printed with both group sizes; the closing sentence
  * is part of every template (VA-IN-11). `InsightPhrases` lints every entry: no cause, no advice, no judgement.
  */
object InsightCopy:

  import StatsCopy.count
  import StatsCopy.duration
  import StatsCopy.meanWithLevel
  import StatsCopy.more
  import StatsCopy.oneDecimal

  /** The closing sentence of a count that compares no groups of days (VISUALS VA-IN-03). */
  def CountNotACause: String = tr(
    "This is a count over your own records. It cannot show a cause.",
    "Це підрахунок ваших власних записів. Він не може показати причину."
  )

  def title: String = tr("Patterns in your records", "Закономірності у ваших записах")
  def none: String =
    tr(
      "No pattern in the last 28 days passed the checks. A pattern needs at least 14 days with records of both kinds.",
      "За останні 28 днів жодна закономірність не пройшла перевірок. Для неї потрібно щонайменше 14 днів із записами обох видів."
    )
  def closeTitle: String = tr("Close to the minimum data:", "Бракує зовсім трохи даних:")
  def crossDomainOff: String =
    tr(
      "Comparisons between areas are off. One compares your average mood on days you reached your water goal with the other days.",
      "Порівняння між розділами вимкнено. Одне з них зіставляє ваш середній настрій у дні, коли ви досягли цілі з води, з іншими днями."
    )
  def CompareAcrossAreas: String = tr("Compare areas", "Порівнювати розділи")
  def usage: String = tr(
    "Send /insights to see the patterns in your records.",
    "Надішліть /insights, щоб побачити закономірності у ваших записах."
  )
  def imageUnavailable: String = tr(
    "No pattern can be drawn right now. The text above is complete.",
    "Зараз не вдається зобразити закономірність. Текст вище повний."
  )

  def header(window: DateRange): String = tr(
    s"$title · ${window.days} days to ${window.end}",
    s"$title · ${UkNouns.Days.count(window.days)} до ${window.end}"
  )

  /** The group sentence of a rule, without the closing sentence. */
  def sentence(rule: InsightRule, first: GroupMean, second: GroupMean): String =
    rule match
      case InsightRule.WaterGoalAndMood =>
        tr(
          s"On the ${first.days} days you reached your water goal, your average mood was " +
            s"${meanWithLevel(first.mean)}. On the ${second.days} other days it was ${meanWithLevel(second.mean)}.",
          s"У дні, коли ви досягли цілі з води (${UkNouns.Days.count(first.days)}), ваш середній настрій був " +
            s"${meanWithLevel(first.mean)}. В інші дні (${UkNouns.Days.count(second.days)}) — ${meanWithLevel(second.mean)}."
        )
      case InsightRule.SleepGoalAndMood =>
        tr(
          s"On the ${first.days} days after a night at or above your sleep goal, your average mood was " +
            s"${meanWithLevel(first.mean)}. On the ${second.days} other days it was ${meanWithLevel(second.mean)}.",
          s"У дні після ночі, коли сон досяг вашої цілі (${UkNouns.Days.count(first.days)}), ваш середній настрій був " +
            s"${meanWithLevel(first.mean)}. В інші дні (${UkNouns.Days.count(second.days)}) — ${meanWithLevel(second.mean)}."
        )
      case InsightRule.BedtimeAndMood =>
        tr(
          s"On the ${first.days} days after a night that began within 30 minutes of your usual bedtime, your average " +
            s"mood was ${meanWithLevel(first.mean)}. On the ${second.days} other days it was " +
            s"${meanWithLevel(second.mean)}.",
          s"У дні після ночі, що почалася в межах 30 хвилин від вашого звичного часу відходу до сну " +
            s"(${UkNouns.Days.count(first.days)}), ваш середній настрій був ${meanWithLevel(first.mean)}. В інші дні " +
            s"(${UkNouns.Days.count(second.days)}) — ${meanWithLevel(second.mean)}."
        )
      case InsightRule.WorkDaysAndSleep =>
        tr(
          s"After your ${first.days} work-day nights you logged ${duration(minutes(first))} of sleep on average. " +
            s"After your ${second.days} free-day nights, ${duration(minutes(second))}.",
          s"За ночі робочих днів (${UkNouns.Nights.count(first.days)}) ви записали в середньому " +
            s"${duration(minutes(first))} сну. За ночі вихідних (${UkNouns.Nights.count(second.days)}) — " +
            s"${duration(minutes(second))}."
        )
      case InsightRule.WorkDaysAndWater =>
        tr(
          s"On your ${first.days} work days you logged ${StatsCopy.litres(millilitres(first), 1)} l of water on " +
            s"average. On your ${second.days} free days, ${StatsCopy.litres(millilitres(second), 1)} l.",
          s"У робочі дні (${UkNouns.Days.count(first.days)}) ви записали в середньому " +
            s"${StatsCopy.litres(millilitres(first), 1)} л води. У вихідні (${UkNouns.Days.count(second.days)}) — " +
            s"${StatsCopy.litres(millilitres(second), 1)} л."
        )
      case InsightRule.WorkDaysAndMood =>
        tr(
          s"On your ${first.days} work days your average mood was ${meanWithLevel(first.mean)}. On your " +
            s"${second.days} free days it was ${meanWithLevel(second.mean)}.",
          s"У робочі дні (${UkNouns.Days.count(first.days)}) ваш середній настрій був ${meanWithLevel(first.mean)}. " +
            s"У вихідні (${UkNouns.Days.count(second.days)}) — ${meanWithLevel(second.mean)}."
        )

  /** What a rule pairs, for the line that says how many days it still needs. */
  def recordsOf(rule: InsightRule): String =
    rule match
      case InsightRule.WaterGoalAndMood => tr("both water and mood records", "записами і про воду, і про настрій")
      case InsightRule.SleepGoalAndMood =>
        tr(
          "both a night under your sleep goal and a mood check-in",
          "і ніччю, коротшою за вашу ціль сну, і позначкою настрою"
        )
      case InsightRule.BedtimeAndMood =>
        tr("both a night and a mood check-in", "і записаною ніччю, і позначкою настрою")
      case InsightRule.WorkDaysAndSleep => tr("a night recorded", "записаною ніччю")
      case InsightRule.WorkDaysAndWater => tr("water recorded", "записом води")
      case InsightRule.WorkDaysAndMood  => tr("a mood check-in", "позначкою настрою")

  /** The short name of a rule in the list of rules that are close to their data. */
  def topic(rule: InsightRule): String =
    rule match
      case InsightRule.WaterGoalAndMood => tr("Water goal and mood", "Ціль з води й настрій")
      case InsightRule.SleepGoalAndMood => tr("Sleep goal and mood", "Ціль сну й настрій")
      case InsightRule.BedtimeAndMood   => tr("Bedtime and mood", "Час відходу до сну й настрій")
      case InsightRule.WorkDaysAndSleep => tr("Work days and sleep", "Робочі дні й сон")
      case InsightRule.WorkDaysAndWater => tr("Work days and water", "Робочі дні й вода")
      case InsightRule.WorkDaysAndMood  => tr("Work days and mood", "Робочі дні й настрій")

  def slotName(slot: DoseSlot): String =
    slot match
      case DoseSlot.Morning => tr("Morning", "Ранок")
      case DoseSlot.Midday  => tr("Midday", "День")
      case DoseSlot.Evening => tr("Evening", "Вечір")
      case DoseSlot.Night   => tr("Night", "Ніч")

  /** IR-07 as counts: the slot with the highest share first. It never suggests changing a schedule (R-INS-7). */
  def timing(highest: SlotShare, lowest: SlotShare): String =
    tr(
      s"${slotName(highest.slot)} doses were answered late or not at all ${count(highest.lateOrUnanswered, "time")} " +
        s"out of ${highest.resolved}. ${slotName(lowest.slot)} doses ${count(lowest.lateOrUnanswered, "time")} out of " +
        s"${lowest.resolved}.",
      s"${slotName(highest.slot)}: на дози відповіли пізніше або не відповіли ${highest.lateOrUnanswered} з " +
        s"${highest.resolved}. ${slotName(lowest.slot)}: ${lowest.lateOrUnanswered} з ${lowest.resolved}."
    )

  /** IR-11: the last seven days against the user's previous weeks, worded as tr("higher", "більше"), tr("longer",
    * "довше") and their opposites.
    */
  def baseline(metric: BaselineMetric, current: Double, previous: Double, weeks: Int): String =
    val earlier = tr(s"your previous ${count(weeks, "week")}", s"за ваші попередні ${UkNouns.Weeks.count(weeks)}")
    metric match
      case BaselineMetric.SleepMinutes =>
        val gap = Math.round(Math.abs(current - previous)).toInt
        val word = if current > previous then tr("longer", "довше") else tr("shorter", "коротше")
        tr(
          s"In the last 7 days you logged ${duration(Math.round(current).toInt)} of sleep a night on average, " +
            s"${duration(gap)} $word than $earlier.",
          s"За останні 7 днів ви записали в середньому ${duration(Math.round(current).toInt)} сну за ніч, " +
            s"на ${duration(gap)} $word, ніж $earlier."
        )
      case BaselineMetric.WaterMillilitres =>
        val gap = Math.round(Math.abs(current - previous)).toInt
        val word = if current > previous then tr("higher", "більше") else tr("lower", "менше")
        tr(
          s"In the last 7 days you logged ${StatsCopy.litres(Math.round(current).toInt, 1)} l of water a day on " +
            s"average, ${StatsCopy.litres(gap, 1)} l $word than $earlier.",
          s"За останні 7 днів ви записали в середньому ${StatsCopy.litres(Math.round(current).toInt, 1)} л води на " +
            s"день, на ${StatsCopy.litres(gap, 1)} л $word, ніж $earlier."
        )
      case BaselineMetric.MoodLevel =>
        val word = if current > previous then tr("higher", "більше") else tr("lower", "менше")
        tr(
          s"In the last 7 days your average mood was ${meanWithLevel(current)}, " +
            s"${oneDecimal(Math.abs(current - previous))} levels $word than $earlier.",
          s"За останні 7 днів ваш середній настрій був ${meanWithLevel(current)}, на " +
            s"${oneDecimal(Math.abs(current - previous))} рівня $word, ніж $earlier."
        )

  /** One shown insight as the chat and the Mini App print it, with its closing sentence. */
  def text(insight: Insight): String =
    insight match
      case Insight.Pattern(Comparison.Shown(rule, first, second, _), _) =>
        s"${sentence(rule, first, second)} ${StatsCopy.NotACause}"
      case Insight.Timing(TimingStatement.Shown(highest, lowest, _), _) =>
        s"${timing(highest, lowest)} $CountNotACause"
      case Insight.Baseline(BaselineStatement.Shown(metric, current, previous, weeks, _), _) =>
        s"${baseline(metric, current, previous, weeks)} $CountNotACause"

  /** "How was this worked out?" (VISUALS VA-IN-03): the period, the groups and their sizes, the minimum-data rules and
    * the sentence that a count over the user's own records cannot show a cause.
    */
  def howWorkedOut(insight: Insight): List[String] =
    val rules = insight match
      case Insight.Pattern(Comparison.Shown(rule, first, second, window), _) =>
        List(
          tr(
            s"Period: ${window.days} days to ${window.end}.",
            s"Період: ${UkNouns.Days.count(window.days)} до ${window.end}."
          ),
          tr(
            s"Groups: ${topic(rule).toLowerCase(java.util.Locale.ROOT)}; ${first.days} and ${second.days} days.",
            s"Групи: ${topic(rule).toLowerCase(java.util.Locale.ROOT)}; ${first.days} і ${UkNouns.Days.count(second.days)}."
          ),
          tr(
            "Only days with both records are compared: at least 14 such days and 5 in each group, and the " +
              "difference holds in both halves of the period and without any single day.",
            "Порівнюються лише дні з обома записами: щонайменше 14 таких днів і по 5 у кожній групі, а різниця " +
              "зберігається в обох половинах періоду й без будь-якого окремого дня."
          )
        )
      case Insight.Timing(TimingStatement.Shown(highest, lowest, window), _) =>
        List(
          tr(
            s"Period: ${window.days} days to ${window.end}.",
            s"Період: ${UkNouns.Days.count(window.days)} до ${window.end}."
          ),
          tr(
            s"Times of day compared: ${slotName(highest.slot)} (${highest.resolved} answered doses) and " +
              s"${slotName(lowest.slot)} (${lowest.resolved}).",
            s"Порівнювані частини дня: ${slotName(highest.slot).toLowerCase(java.util.Locale.ROOT)} (доз із відповіддю: " +
              s"${highest.resolved}) і ${slotName(lowest.slot).toLowerCase(java.util.Locale.ROOT)} (${lowest.resolved})."
          ),
          tr(
            "Each time of day needs at least 10 answered doses, and the order holds in both halves of the period.",
            "Кожна частина дня потребує щонайменше 10 доз із відповіддю, а порядок зберігається в обох половинах періоду."
          )
        )
      case Insight.Baseline(BaselineStatement.Shown(_, _, _, weeks, window), _) =>
        List(
          tr(
            s"Period: the last 7 days, to ${window.end}, against your previous ${count(weeks, "week")}.",
            s"Період: останні 7 днів, до ${window.end}, порівняно з вашими попередніми ${UkNouns.WeeksWith.count(weeks)}."
          ),
          tr(
            "A week counts when it has records on at least 3 days; at least 2 earlier weeks are needed.",
            "Тиждень враховується, якщо записи є щонайменше за 3 дні; потрібно щонайменше 2 попередні тижні."
          )
        )
    rules :+ CountNotACause

  /** A rule that is close to its minimum data, with the count it still needs (VISUALS section 8.5). */
  def gap(gap: InsightGap): String =
    gap match
      case InsightGap.Pattern(rule, missing, false) =>
        tr(
          s"${topic(rule)}: ${more(missing, "day")} with ${recordsOf(rule)}.",
          s"${topic(rule)}: ще ${UkNouns.Days.count(missing)} з ${recordsOf(rule)}."
        )
      case InsightGap.Pattern(rule, missing, true) =>
        tr(
          s"${topic(rule)}: ${more(missing, "day")} in the smaller group of days.",
          s"${topic(rule)}: ще ${UkNouns.Days.count(missing)} у меншій групі днів."
        )
      case InsightGap.Timing(missing) =>
        tr(
          s"Dose times: ${more(missing, "answered dose")} in a second time of day.",
          s"Час доз: ще ${UkNouns.AnsweredDoses.count(missing)} в іншій частині дня."
        )

  /** The lines of the `/insights` card. */
  def lines(list: InsightList, crossDomain: Boolean): List[String] =
    val shown = if list.shown.isEmpty then List(none) else list.shown.map(text)
    val close = if list.gaps.isEmpty then Nil else closeTitle :: list.gaps.map(gap)
    val consent = if crossDomain then Nil else List(crossDomainOff)
    (header(list.window) :: shown) ++ close ++ consent

  private def minutes(group: GroupMean): Int = Math.round(group.mean).toInt
  private def millilitres(group: GroupMean): Int = Math.round(group.mean).toInt

  def entries: List[CopyEntry] = Samples.entries

  /** One rendering of every template, over values built by the same functions the product uses. */
  private object Samples:
    private val end = LocalDate.parse("2026-11-08")
    private val window = DateRange.endingOn(end, 28)

    private def pairs(first: Double, second: Double): List[PairedDay] =
      List.tabulate(20)(index => PairedDay(window.start.plusDays(index.toLong), index % 2 == 0, 0.0)).map { day =>
        day.copy(outcome = if day.inFirstGroup then first else second)
      }

    private def shown(rule: InsightRule, first: Double, second: Double): String =
      Comparisons.compare(rule, pairs(first, second), window) match
        case comparison: Comparison.Shown => text(Insight.Pattern(comparison, 1.0))
        case other                        => throw IllegalStateException(s"copy sample ${rule.id} is not shown: $other")

    private def timingSample: String =
      val evening = SlotShare(DoseSlot.Evening, 5, 14)
      val morning = SlotShare(DoseSlot.Morning, 1, 14)
      s"${timing(evening, morning)} $CountNotACause"

    private def baselines: List[CopyEntry] =
      val values = (0 until 35).map(back => end.minusDays(back.toLong) -> (if back < 7 then 1.0 else 0.0)).toMap
      def sample(metric: BaselineMetric, now: Double, before: Double): CopyEntry =
        val days = values.view.mapValues(isNow => if isNow > 0 then now else before).toMap
        BaselineStatements.of(metric, days, end) match
          case statement: BaselineStatement.Shown =>
            CopyEntry(
              s"insight.ir11.${metric.toString.toLowerCase(java.util.Locale.ROOT)}",
              text(Insight.Baseline(statement, 1.0))
            )
          case other => throw IllegalStateException(s"copy sample IR-11 $metric is not shown: $other")
      List(
        sample(BaselineMetric.SleepMinutes, 425, 385),
        sample(BaselineMetric.WaterMillilitres, 1900, 1600),
        sample(BaselineMetric.MoodLevel, 3.8, 3.4)
      )

    def entries: List[CopyEntry] =
      List(
        CopyEntry("insight.ir01", shown(InsightRule.SleepGoalAndMood, 3.7, 3.0)),
        CopyEntry("insight.ir02", shown(InsightRule.BedtimeAndMood, 3.9, 3.2)),
        CopyEntry("insight.ir06.sleep", shown(InsightRule.WorkDaysAndSleep, 410, 470)),
        CopyEntry("insight.ir06.water", shown(InsightRule.WorkDaysAndWater, 2100, 1600)),
        CopyEntry("insight.ir06.mood", shown(InsightRule.WorkDaysAndMood, 3.2, 3.9)),
        CopyEntry("insight.ir07", timingSample),
        CopyEntry("insight.gap.days", gap(InsightGap.Pattern(InsightRule.SleepGoalAndMood, 4, inSmallerGroup = false))),
        CopyEntry("insight.gap.group", gap(InsightGap.Pattern(InsightRule.WorkDaysAndMood, 2, inSmallerGroup = true))),
        CopyEntry("insight.gap.timing", gap(InsightGap.Timing(3))),
        CopyEntry("insight.title", header(window)),
        CopyEntry("insight.none", none),
        CopyEntry("insight.close", closeTitle),
        CopyEntry("insight.cross_domain_off", crossDomainOff),
        CopyEntry("insight.compare", CompareAcrossAreas),
        CopyEntry("insight.usage", usage),
        CopyEntry("insight.image_unavailable", imageUnavailable),
        CopyEntry("insight.count_not_a_cause", CountNotACause),
        CopyEntry(
          "insight.how",
          Comparisons.compare(InsightRule.SleepGoalAndMood, pairs(3.7, 3.0), window) match
            case comparison: Comparison.Shown => howWorkedOut(Insight.Pattern(comparison, 1.0)).mkString("\n")
            case other                        => throw IllegalStateException(s"copy sample insight.how: $other")
        )
      ) ++ baselines
  end Samples
end InsightCopy
