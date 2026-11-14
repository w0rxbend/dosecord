package dosegram.app

import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.InsightRule as CoreRule
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.MonthStats
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.ports.DoseMark
import dosegram.core.ports.MonthCardView
import dosegram.core.ports.WeekCardView
import dosegram.render.blob.MoodState
import dosegram.render.card.CalendarData
import dosegram.render.card.DayAdherence
import dosegram.render.card.DoseCounts
import dosegram.render.card.InsightData
import dosegram.render.card.InsightGroup
import dosegram.render.card.InsightRule
import dosegram.render.card.MonthData
import dosegram.render.card.MonthDaysData
import dosegram.render.card.MonthNightsData
import dosegram.render.card.MonthWaterDay
import dosegram.render.card.MonthWeek
import dosegram.render.card.MoodCalendarData
import dosegram.render.card.SleepEvidence
import dosegram.render.card.SleepNightBar
import dosegram.render.card.SleepRegularityData
import dosegram.render.card.WaterProgress
import dosegram.render.card.WeekData
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.GoalDays

import java.time.DayOfWeek

/** The view models of the report cards, built from the core's figures (ADR-022: the core never sees the renderer).
  * Every figure here is one the core computed; this object only changes its shape. A domain the core left out (no
  * consent, or below its gate) has no row and no panel.
  */
object ReportCards:

  private val WeekStart = DayOfWeek.MONDAY

  /** IMG-13 draws a comparison whose outcome is the daily mood; other outcomes have no card. */
  def insight(shown: Comparison.Shown): Option[InsightData] =
    val rule = shown.rule match
      case CoreRule.WaterGoalAndMood                             => Some(InsightRule.WaterGoalMood)
      case CoreRule.SleepGoalAndMood                             => Some(InsightRule.SleepGoalMood)
      case CoreRule.BedtimeAndMood                               => Some(InsightRule.BedtimeMood)
      case CoreRule.WorkDaysAndMood                              => Some(InsightRule.WorkDayMood)
      case CoreRule.WorkDaysAndSleep | CoreRule.WorkDaysAndWater => None
    rule.map(drawn =>
      InsightData(
        drawn,
        shown.window.start,
        shown.window.end,
        InsightGroup(shown.first.days, shown.first.mean),
        InsightGroup(shown.second.days, shown.second.mean)
      )
    )

  /** Page 1 of IMG-05: a domain has a row only when it met the section gate of R-INS-11. */
  def monthPage(figures: MonthFigures): MonthData =
    val weeks = figures.weeks.toVector.map { week =>
      MonthWeek(
        doses = Option.when(!week.doses.isEmpty)(counts(week.doses)),
        waterMeanMl = week.waterMeanMl.shown,
        mood = week.moodMean.shown.map(mean => moodState(mean, None)),
        sleepMeanMinutes = week.sleepMeanMinutes.shown.map(minutes => Math.round(minutes).toInt)
      )
    }
    MonthData(
      month = figures.month,
      daysWithRecords = figures.daysWithRecords,
      weeks = weeks,
      doses = figures.medication.filter(_.meetsGate).map(domain => counts(domain.figures.total.buckets)),
      water = figures.hydration.filter(_.meetsGate).flatMap(_.figures.goalDays.flatMap(_.shown)).map { ratio =>
        GoalDays(ratio.numerator, ratio.denominator)
      },
      moodCheckInDays = figures.mood.filter(_.meetsGate).map(_.figures.checkInDays),
      sleep = figures.sleep
        .filter(_.meetsGate)
        .flatMap(domain =>
          domain.figures.averageMinutes.shown.map(mean => (Math.round(mean).toInt, domain.figures.nights))
        ),
      previousAdherence = figures.previousAdherence.shown.map(_.percent)
    )

  /** Page 2: the adherence calendar and the mood calendar; each only for a domain that met its gate. */
  def monthDays(figures: MonthFigures, view: MonthCardView): MonthDaysData =
    val month = figures.month
    val dates = MonthStats.window(month).dates
    val calendar = figures.medication.filter(_.meetsGate).map { domain =>
      CalendarData(
        month,
        WeekStart,
        Some(view.today),
        None,
        dates.toVector.map(date => dayAdherence(domain.figures.days.get(date))),
        domain.figures.total.buckets.unknown
      )
    }
    val mood = figures.mood.filter(_.meetsGate).map { _ =>
      MoodCalendarData(month, WeekStart, dates.toVector.map(date => figures.moodDays.get(date).map(dayMood)))
    }
    MonthDaysData(month, calendar, mood)

  /** Page 3: the month's nights and its water, each only for a domain that met its gate. */
  def monthNights(figures: MonthFigures, view: MonthCardView): MonthNightsData =
    val month = figures.month
    val sleep = for
      domain <- figures.sleep if domain.meetsGate
      nights <- view.sleep
    yield regularity(nights)
    val water = figures.hydration.filter(_.meetsGate).map { _ =>
      MonthStats
        .window(month)
        .dates
        .toVector
        .map(date => figures.hydrationDays.get(date).map(day => MonthWaterDay(day.totalMl, day.goalMl)))
    }
    MonthNightsData(month, sleep, water)

  def regularity(view: SleepRegularityFigures): SleepRegularityData =
    SleepRegularityData(
      from = view.window.start,
      to = view.window.end,
      bars = view.bars.map(bar => SleepNightBar(bar.date, bar.fromMinute, bar.toMinute, bar.isNap)).toVector,
      nights = view.nights,
      averageMinutes = view.averageMinutes,
      bedtimeSpreadMinutes = view.bedtimeSpreadMinutes,
      wakeSpreadMinutes = view.wakeSpreadMinutes,
      typicalBedtime = view.typicalBedtime,
      typicalWake = view.typicalWake,
      goalMinutes = view.goalMinutes
    )

  /** IMG-04: seven days, each domain the core showed. The card draws water and sleep against the user's own goal, so a
    * week with a recorded day that had no goal leaves that row to the text; food is not tracked yet.
    */
  def week(view: WeekCardView): WeekData =
    WeekData(
      from = view.from,
      medication = view.doses.map(_.toVector.map(_.toVector.map(statusOf))),
      water = view.water.filter(_.flatten.forall(_.goalMl.isDefined)).map { days =>
        days.toVector.map(_.flatMap(day => day.goalMl.map(WaterProgress(day.totalMl, _))))
      },
      food = None,
      mood = view.mood.map(_.toVector.map(_.map(day => moodState(day.mean, day.energyMean)))),
      sleep = view.sleep.filter(_.flatten.forall(_.goalMinutes.isDefined)).map { nights =>
        nights.toVector.map {
          case Some(night) => night.goalMinutes.fold(SleepEvidence.NoRecord)(SleepEvidence.Recorded(night.minutes, _))
          case None        => SleepEvidence.NoRecord
        }
      }
    )

  /** The quantised state of a daily mean on the stored 1 to 5 scale (VISUALS section 4.2, `v = (m - 3) / 2`), with the
    * energy mean on the same scale when the day has one, else the middle level.
    */
  def moodState(mean: Double, energy: Option[Double]): MoodState =
    MoodState.quantised((mean - 3) / 2, energy.fold(0.5)(value => (value - 1) / 4))

  private def dayMood(day: MoodDay): MoodState = moodState(day.mean, day.energyMean)

  private def counts(buckets: DoseBuckets): DoseCounts =
    val resolved = buckets.resolved
    DoseCounts(resolved.taken, resolved.skipped, resolved.missed, 0, buckets.unknown, buckets.asNeeded)

  private def dayAdherence(day: Option[MedicationDay]): DayAdherence =
    day.map(_.total) match
      case None                                           => DayAdherence.NothingScheduled
      case Some(buckets) if buckets.resolved.resolved > 0 =>
        DayAdherence.Resolved(buckets.resolved.taken, buckets.resolved.resolved, buckets.missed > 0)
      case Some(buckets) if buckets.open > 0    => DayAdherence.Open
      case Some(buckets) if buckets.unknown > 0 => DayAdherence.NotKnown
      case Some(_)                              => DayAdherence.NothingScheduled

  private def statusOf(mark: DoseMark): DoseStatus =
    mark match
      case DoseMark.TakenOnTime  => DoseStatus.TakenOnTime
      case DoseMark.TakenLate    => DoseStatus.TakenLate
      case DoseMark.Skipped      => DoseStatus.Skipped
      case DoseMark.NotConfirmed => DoseStatus.Missed
      case DoseMark.NoRecord     => DoseStatus.Unknown
      case DoseMark.Open         => DoseStatus.Open
end ReportCards
