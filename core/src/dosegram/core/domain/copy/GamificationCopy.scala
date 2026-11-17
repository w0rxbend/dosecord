package dosegram.core.domain.copy

import dosegram.core.domain.insights.LoggingAnniversary
import dosegram.core.domain.insights.LoggingDomain
import dosegram.core.domain.insights.LoggingMilestone
import dosegram.core.domain.insights.WeeklyConsistency

import java.time.LocalDate

/** The lines of PRODUCT_SPEC section 8.14 in the weekly report: on how many days each domain has a record, and the
  * logging milestones the week passed. They count what was logged, never what was not, and use none of the words of
  * R-GAM-6.
  */
object GamificationCopy:
  import UkNouns.CheckIns

  private val WaterEntries = UkForms("запис води", "записи води", "записів води", "запису води")
  private val SleepEntries = UkForms("запис сну", "записи сну", "записів сну", "запису сну")
  private val FoodEntries = UkForms("запис їжі", "записи їжі", "записів їжі", "запису їжі")

  def CountsOn = tr("Counts in reports: on", "Підрахунки у звітах: увімк.")
  def CountsOff = tr("Counts in reports: off", "Підрахунки у звітах: вимк.")
  def countsTurnedOn = tr(
    "The weekly report shows on how many days you logged, and round counts of entries.",
    "Тижневий звіт показує, за скільки днів ви робили записи, і круглі числа записів."
  )
  def countsTurnedOff = tr(
    "The weekly report shows no day counts and no round counts of entries.",
    "Тижневий звіт не показує ні кількості днів, ні круглих чисел записів."
  )

  /** "Days with records: water 5 of 7, mood 6 of 7." `None` for a week without a counted domain. */
  def consistency(counts: List[WeeklyConsistency]): Option[String] =
    Option.when(counts.nonEmpty):
      val parts = counts.map(count => s"${name(count.domain)} ${count.hitDays} ${tr("of", "з")} ${count.days}")
      tr(s"Days with records: ${parts.mkString(", ")}.", s"Дні із записами: ${parts.mkString(", ")}.")

  /** "50 mood check-ins recorded so far." */
  def milestone(milestone: LoggingMilestone): String =
    val n = milestone.count
    milestone.domain match
      case LoggingDomain.Water =>
        tr(s"$n water entries recorded so far.", s"Уже ${WaterEntries.count(n)}.")
      case LoggingDomain.Mood =>
        tr(s"$n mood check-ins recorded so far.", s"Уже ${CheckIns.count(n)} настрою.")
      case LoggingDomain.Sleep =>
        tr(s"$n sleep entries recorded so far.", s"Уже ${SleepEntries.count(n)}.")
      case LoggingDomain.Food =>
        tr(s"$n food entries recorded so far.", s"Уже ${FoodEntries.count(n)}.")

  /** "One year since the first water entry." The fact of the date, with no measured value and no celebration. */
  def anniversary(anniversary: LoggingAnniversary): String = anniversary.domain match
    case LoggingDomain.Water =>
      tr("One year since the first water entry.", "Рік від першого запису води.")
    case LoggingDomain.Mood =>
      tr("One year since the first mood check-in.", "Рік від першої позначки настрою.")
    case LoggingDomain.Sleep =>
      tr("One year since the first sleep entry.", "Рік від першого запису сну.")
    case LoggingDomain.Food =>
      tr("One year since the first food entry.", "Рік від першого запису їжі.")

  private def name(domain: LoggingDomain): String = domain match
    case LoggingDomain.Water => tr("water", "вода")
    case LoggingDomain.Mood  => tr("mood", "настрій")
    case LoggingDomain.Sleep => tr("sleep", "сон")
    case LoggingDomain.Food  => tr("food", "їжа")

  def entries: List[CopyEntry] =
    val sampleDay = LocalDate.of(2025, 6, 1)
    List(
      CopyEntry(
        "report.counts",
        consistency(List(WeeklyConsistency(LoggingDomain.Water, 5, 7), WeeklyConsistency(LoggingDomain.Mood, 6, 7))).get
      ),
      CopyEntry("report.milestone.water", milestone(LoggingMilestone(LoggingDomain.Water, 50))),
      CopyEntry("report.milestone.mood", milestone(LoggingMilestone(LoggingDomain.Mood, 50))),
      CopyEntry("report.milestone.sleep", milestone(LoggingMilestone(LoggingDomain.Sleep, 10))),
      CopyEntry("report.milestone.food", milestone(LoggingMilestone(LoggingDomain.Food, 100))),
      CopyEntry("report.anniversary.water", anniversary(LoggingAnniversary(LoggingDomain.Water, sampleDay))),
      CopyEntry("report.anniversary.mood", anniversary(LoggingAnniversary(LoggingDomain.Mood, sampleDay))),
      CopyEntry("report.anniversary.sleep", anniversary(LoggingAnniversary(LoggingDomain.Sleep, sampleDay))),
      CopyEntry("report.anniversary.food", anniversary(LoggingAnniversary(LoggingDomain.Food, sampleDay))),
      CopyEntry("reminders.counts.on", CountsOn),
      CopyEntry("reminders.counts.off", CountsOff),
      CopyEntry("reminders.counts.turned_on", countsTurnedOn),
      CopyEntry("reminders.counts.turned_off", countsTurnedOff)
    )
end GamificationCopy
