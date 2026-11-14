package dosegram.render.copy

import dosegram.render.blob.MoodReading
import dosegram.render.card.DayAdherence
import dosegram.render.card.DayData
import dosegram.render.card.DoseCounts
import dosegram.render.card.InsightData
import dosegram.render.card.InsightRule
import dosegram.render.card.MilestoneData
import dosegram.render.card.MilestoneKind
import dosegram.render.card.MonthDaysData
import dosegram.render.card.MonthNightsData
import dosegram.render.card.MoodCalendarData
import dosegram.render.card.MoodTimelineData
import dosegram.render.card.SleepEvidence
import dosegram.render.card.SleepRegularityData
import dosegram.render.card.WaterData
import dosegram.render.card.WaterProgress
import dosegram.render.chart.DoseStatus
import dosegram.render.tokens.Domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** The English image catalogue. Dates and numbers are formatted from explicit tables, not from the JDK locale data, so
  * the output does not change with a JDK release. Status words follow BOT_UX section 22.4: taken, skipped, "not
  * confirmed" for a missed dose, "no record" for an unknown one.
  */
object EnglishImageCopy extends ImageCopy:
  private val Weekdays = Vector("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
  private val Months =
    Vector("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
  private val MonthNames = Vector(
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December"
  )
  private val ValenceNames = Vector(
    "Very unpleasant",
    "Unpleasant",
    "Slightly unpleasant",
    "Neutral",
    "Slightly pleasant",
    "Pleasant",
    "Very pleasant"
  )
  private val EnergyNames = Vector("very low", "low", "medium", "high", "very high")

  def footer: String = "Recorded entries only. Not medical advice."
  def detailsBelow: String = "Details below."

  def domainName(domain: Domain): String =
    domain match
      case Domain.Medication => "Medication"
      case Domain.Water      => "Water"
      case Domain.Food       => "Food"
      case Domain.Mood       => "Mood"
      case Domain.Sleep      => "Sleep"

  def itemLabel(position: Int): String = s"Item $position"

  def dayDate(date: LocalDate): String = s"${weekday(date.getDayOfWeek)} ${dayMonth(date)}"

  def dateRange(from: LocalDate, to: LocalDate): String =
    if from.getMonth == to.getMonth && from.getYear == to.getYear then s"${from.getDayOfMonth} to ${dayMonth(to)}"
    else s"${dayMonth(from)} to ${dayMonth(to)}"

  def monthYear(month: YearMonth): String = s"${MonthNames(month.getMonthValue - 1)} ${month.getYear}"
  def weekdayInitial(day: DayOfWeek): String = weekday(day).take(1)
  def weekdayShort(day: DayOfWeek): String = weekday(day).take(2)
  def time(time: LocalTime): String = f"${time.getHour}%02d:${time.getMinute}%02d"
  def percent(value: Int): String = s"$value%"

  def duration(minutes: Int): String =
    if minutes < 60 then s"$minutes min" else f"${minutes / 60} h ${minutes % 60}%02d min"

  def noData: String = "No data"

  def valenceName(valenceLevel: Int): String = ValenceNames(valenceLevel - 1)
  def energyLine(energyLevel: Int): String = s"Energy: ${EnergyNames(energyLevel - 1)}"

  def moodLabel(reading: MoodReading): String =
    val energy = Option.when(reading.isEnergyGiven)(s" ${energyLine(reading.state.energy)}.")
    s"${valenceName(reading.state.valence)}.${energy.getOrElse("")}"

  def noMoodEntry: String = "No mood entry"

  def moodText(mood: Option[MoodReading]): String =
    mood.map(reading => s"Mood: ${moodLabel(reading)}").getOrElse("No mood entry today.")

  def moodLoggedDiscreet: String = "Mood logged."

  def todayTitle: String = "Today"
  def takenOf(taken: Int, counted: Int): String = s"$taken of $counted taken"
  def nothingLoggedYet: String = "Nothing logged yet"
  def mealsOf(logged: Int, planned: Int): String = s"$logged of $planned meals"
  def fromYourCheckIns: String = "from your check-ins"
  def estimateOnly: String = "Estimate only"

  def dayText(day: DayData): String =
    val medication = day.medication.map(DoseCounts.of).map(counts => s"Medication: ${doseSentence(counts)}.")
    val water = day.water.map { progress =>
      if progress.volumeMl == 0 then "Water: nothing logged yet." else s"Water: ${waterFraction(progress)}."
    }
    val food = day.food.map(meals => s"Food: ${meals.logged} of ${meals.planned} meals logged.")
    val sleep = day.sleep.map {
      case SleepEvidence.Recorded(minutes, _) => s"Sleep: ${duration(minutes)}, from your check-ins."
      case SleepEvidence.QuietWindowOnly      => "Sleep: an estimate from bot activity only, not a duration."
      case SleepEvidence.NoRecord             => "Sleep: no record yet."
    }
    (Vector(s"${dayDate(day.date)}.", moodText(day.mood)) ++ medication ++ water ++ food ++ sleep).mkString(" ")

  def dayDiscreet: String = "Your day so far."

  def waterTitle: String = "Water"
  def waterFraction(progress: WaterProgress): String = s"${litres(progress.volumeMl)} of ${litres(progress.goalMl)} l"
  def waterTick(volumeMl: Int): String = litres(volumeMl)
  def waterTopTick(volumeMl: Int): String = s"${litres(volumeMl)} l"
  def entries(count: Int): String = if count == 1 then "1 entry" else s"$count entries"
  def lastEntryAt(time: LocalTime): String = s"last at ${this.time(time)}"
  def goalIsYours: String = "The goal is the one you set."
  def noWaterToday: String = "No water logged today."

  def waterText(water: WaterData): String =
    val progress = water.progress
    val headline = s"Water on ${dayDate(water.date)}: ${waterFraction(progress)} (${percent(progress.percent)})."
    val log =
      if water.entryCount == 0 then noWaterToday
      else (entries(water.entryCount) +: water.lastEntry.map(lastEntryAt).toVector).mkString("", ", ", ".")
    val factors = Option.when(water.usesDrinkFactors)("Counted with your drink factors.")
    (Vector(headline, log, goalIsYours) ++ factors).mkString(" ")

  def waterDiscreet: String = "Water today."

  def calendarTitle: String = "Medication by day"

  def dayClassName(dayClass: DayClass): String =
    dayClass match
      case DayClass.AllTaken     => "All taken"
      case DayClass.Most         => "75 to 99%"
      case DayClass.Some         => "50 to 74%"
      case DayClass.Few          => "Under 50%"
      case DayClass.Skipped      => "Skipped"
      case DayClass.NotConfirmed => "Not confirmed"
      case DayClass.NoRecord     => "No record"
      case DayClass.StillOpen    => "Still open"

  def blankDayNote: String = "None scheduled"

  def resolvedDosesTaken(taken: Int, resolved: Int): String = s"$taken of $resolved resolved doses taken."

  def unknownDosesNotCounted(count: Int): String =
    if count == 1 then "1 dose: no record, not counted." else s"$count doses: no record, not counted."

  def calendarText(lines: CalendarLines): String =
    val scope = lines.medicationLabel.getOrElse("all medications")
    val classes = lines.dayCounts.map((dayClass, count) => s"${dayClassPhrase(dayClass)} on ${days(count)}")
    val byDay = if classes.isEmpty then Vector(s"$noData.") else Vector(classes.mkString("", ", ", ".").capitalize)
    val unscheduled =
      Option.when(lines.nothingScheduledDays > 0)(s"Nothing scheduled on ${days(lines.nothingScheduledDays)}.")
    val unknown = Option.when(lines.unknownDoseCount > 0) {
      if lines.unknownDoseCount == 1 then "1 dose has no record and is not counted."
      else s"${lines.unknownDoseCount} doses have no record and are not counted."
    }
    val total = Option.when(lines.resolved > 0) {
      val share =
        DoseCounts(lines.taken, lines.resolved - lines.taken, 0, 0, 0, 0).takenPercent.map(p => s" (${percent(p)})")
      s"${lines.taken} of ${lines.resolved} resolved doses taken${share.getOrElse("")}."
    }
    (Vector(s"${monthYear(lines.month)}, $scope.") ++ byDay ++ unscheduled ++ unknown ++ total).mkString(" ")

  def calendarDiscreet: String = "Medication by day."

  def stripTitle: String = "By medication"
  def pageOf(page: Int, pageCount: Int): String = s"Page $page of $pageCount"

  def statusName(status: DoseStatus): String =
    status match
      case DoseStatus.TakenOnTime => "Taken"
      case DoseStatus.TakenLate   => "Taken late"
      case DoseStatus.Skipped     => "Skipped"
      case DoseStatus.Missed      => "Not confirmed"
      case DoseStatus.Unknown     => "No record"
      case DoseStatus.Open        => "Open"
      case DoseStatus.AsNeeded    => "As needed"

  def asNeededName(name: String): String = s"$name (as needed)"
  def logsOnDays(logs: Int, days: Int): String = s"${plural(logs, "log")} on ${this.days(days)}"

  def stripEmpty: String = "No medication records for these days."

  def stripText(lines: StripLines): String =
    val rows = lines.rows.map { row =>
      val record = row.asNeededDays
        .map(logDays => logsOnDays(row.counts.asNeeded, logDays))
        .getOrElse(if row.counts.counted == 0 then noData.toLowerCase else doseSentence(row.counts))
      s"${row.label}: $record."
    }
    (s"${dateRange(lines.from, lines.to)}." +: (if rows.isEmpty then Vector(stripEmpty) else rows)).mkString(" ")

  def stripDiscreet: String = "By medication."

  def weekTitle: String = "Your week"

  def weekDoses(counts: DoseCounts): String =
    val share = counts.takenPercent.filter(_ => counts.counted >= MinimumDosesForPercent).map(p => s" · ${percent(p)}")
    s"${takenOf(counts.taken, counts.counted)}${share.getOrElse("")}"

  def goalReachedDays(days: GoalDays): String = s"Goal on ${days.reached} of ${this.days(days.recorded)}"
  def mealsLogged(count: Int): String = if count == 1 then "1 meal logged" else s"$count meals logged"
  def checkInDays(count: Int): String = if count == 1 then "1 check-in day" else s"$count check-in days"
  def sleepAverage(minutes: Int): String = s"${duration(minutes)} average"

  def sleepTitle: String = "Sleep"
  def sleepAxisHour(hour: Int): String = f"$hour%02d"
  def sleepRowDate(date: LocalDate): String = s"${weekday(date.getDayOfWeek)} ${date.getDayOfMonth}"

  def sleepAverageOf(minutes: Int, nights: Int): String =
    s"${duration(minutes)} average (${if nights == 1 then "1 night" else s"$nights nights"})"

  def sleepSpread(bedtimeMinutes: Int, wakeMinutes: Int): String =
    s"Bedtime varied about $bedtimeMinutes min, wake $wakeMinutes min"

  def sleepEntriesMark: String = "Your entries"
  def sleepNapMark: String = "Nap (n)"
  def sleepTypicalMark: String = "Typical times"
  def sleepGoal(minutes: Int): String = s"Your goal: ${duration(minutes)}"
  def sleepNoNights: String = "No night logged in these days."

  def sleepText(sleep: SleepRegularityData): String =
    val period = s"Sleep, ${dateRange(sleep.from, sleep.to)}."
    val days = (sleep.to.toEpochDay - sleep.from.toEpochDay).toInt + 1
    val nights =
      if sleep.nights == 0 then sleepNoNights
      else
        val count = if sleep.nights == 1 then "1 night" else s"${sleep.nights} nights"
        val average = sleep.averageMinutes.fold("")(minutes => s": ${duration(minutes)} on average")
        val spread = (sleep.bedtimeSpreadMinutes, sleep.wakeSpreadMinutes) match
          case (Some(bed), Some(wake)) =>
            s"; bedtime varied by about $bed min and wake time by about $wake min"
          case _ => ""
        s"$count from your check-ins and entries$average$spread."
    val gaps = days - sleep.nights
    val missing = Option.when(sleep.nights > 0 && gaps > 0)(
      if gaps == 1 then "1 night has no record." else s"$gaps nights have no record."
    )
    val naps = sleep.bars.count(_.isNap)
    val napLine =
      Option.when(naps > 0)(if naps == 1 then "1 nap, not in the average." else s"$naps naps, not in the average.")
    val goal = sleep.goalMinutes.map(minutes => s"Your goal is ${duration(minutes)}.")
    (Vector(period, nights) ++ missing ++ napLine ++ goal).mkString(" ")

  def sleepDiscreet: String = "Sleep, recent nights."
  def noRecordsThisWeek: String = "No records this week"
  def yourGoal: String = "Your goal"
  def noRecordMark: String = "No record"
  def estimateMark: String = "Estimate"
  def snackMark: String = "Snack"

  def weekText(week: WeekSummary): String =
    val medication = week.doses.map { counts =>
      if counts.counted == 0 then "Medication: no records this week." else s"Medication: ${doseSentence(counts)}."
    }
    val water = week.water.map { goalDays =>
      if goalDays.recorded == 0 then "Water: no records this week."
      else s"Water: goal reached on ${goalDays.reached} of ${plural(goalDays.recorded, "recorded day")}."
    }
    val food = week.mealsLogged.map(count =>
      if count == 0 then "Food: no records this week." else s"Food: ${mealsLogged(count)}."
    )
    val mood = week.checkInDays.map(count =>
      if count == 0 then "Mood: no records this week." else s"Mood: ${checkInDays(count)}."
    )
    val sleep = week.sleep.map(sleepSentence)
    (Vector(s"Week ${dateRange(week.from, week.to)}.") ++ medication ++ water ++ food ++ mood ++ sleep).mkString(" ")

  def weekDiscreet: String = "Your week."

  def monthTitle: String = "Your month"
  def weekNumber(week: Int): String = s"Week $week"

  def monthHeroDoses(counts: DoseCounts): Vector[String] =
    Vector("of scheduled doses", "recorded as taken", s"(${counts.taken} of ${counts.counted})")

  def monthHeroDays(days: Int): Vector[String] = Vector(if days == 1 then "day with records" else "days with records")

  def monthPrevious(month: YearMonth, percent: Int): String =
    s"${MonthNames(month.getMonthValue - 1)}: ${this.percent(percent)} of scheduled doses taken."

  def monthText(month: MonthSummary): String =
    val medication = month.doses.map { counts =>
      if counts.counted == 0 then "Medication: no records this month." else s"Medication: ${doseSentence(counts)}."
    }
    val water = month.water.map { goalDays =>
      s"Water: goal reached on ${goalDays.reached} of ${plural(goalDays.recorded, "recorded day")}."
    }
    val mood = month.moodCheckInDays.map(count => s"Mood: ${checkInDays(count)}.")
    val sleep = month.sleep.map { (minutes, nights) =>
      s"Sleep: ${duration(minutes)} on average over ${plural(nights, "night")} from your check-ins."
    }
    val days = s"${plural(month.daysWithRecords, "day")} with records."
    val previous = month.previousAdherence
      .filter(_ => month.doses.exists(_.counted >= MinimumDosesForPercent))
      .map(percent => monthPrevious(month.month.minusMonths(1), percent))
    (Vector(s"${monthYear(month.month)}.") ++ medication ++ water ++ mood ++ sleep ++ Vector(days) ++ previous)
      .mkString(" ")

  def monthDiscreet: String = "Your month."

  def insightTitle: String = "A pattern in your records"

  def insightGroup(rule: InsightRule, isFirst: Boolean): String =
    rule match
      case InsightRule.WaterGoalMood => if isFirst then "Water goal reached" else "Water logged, goal not reached"
      case InsightRule.SleepGoalMood =>
        if isFirst then "After nights at or above your sleep goal" else "After shorter nights"
      case InsightRule.BedtimeMood =>
        if isFirst then "After nights begun near your usual bedtime" else "After the other nights"
      case InsightRule.WorkDayMood => if isFirst then "Work days" else "Free days"

  def insightValue(value: Double): String =
    BigDecimal(value).setScale(1, BigDecimal.RoundingMode.HALF_UP).toString

  def insightDays(days: Int): String = plural(days, "day")

  def insightScaleNote(outcome: Domain): String = "Mood is the daily average on the 1 to 5 scale."

  def insightNotCause: String = "This compares your own days. It does not show a cause."

  def insightText(insight: InsightData): String =
    val (first, second) = (insight.first, insight.second)
    val groups = insight.rule match
      case InsightRule.WaterGoalMood =>
        s"On the ${plural(first.days, "day")} you reached your water goal, your average mood was " +
          s"${insightValue(first.value)}. On the ${second.days} other days with water logged it was " +
          s"${insightValue(second.value)}."
      case InsightRule.SleepGoalMood =>
        s"On the ${plural(first.days, "day")} after a night at or above your sleep goal, your average mood was " +
          s"${insightValue(first.value)}. On the ${second.days} other days it was ${insightValue(second.value)}."
      case InsightRule.BedtimeMood =>
        s"On the ${plural(first.days, "day")} after a night that began within 30 minutes of your usual bedtime, " +
          s"your average mood was ${insightValue(first.value)}. On the ${second.days} other days it was " +
          s"${insightValue(second.value)}."
      case InsightRule.WorkDayMood =>
        s"On your ${plural(first.days, "work day")} your average mood was ${insightValue(first.value)}. On your " +
          s"${plural(second.days, "free day")} it was ${insightValue(second.value)}."
    Vector(
      s"$insightTitle, ${dateRange(insight.from, insight.to)}.",
      groups,
      insightScaleNote(insight.outcome),
      insightNotCause
    ).mkString(" ")

  def insightDiscreet: String = "A pattern in your records."

  def milestoneLabel(milestone: MilestoneData): String =
    val isOne = milestone.count == 1
    milestone.kind match
      case MilestoneKind.WaterEntries  => if isOne then "water entry recorded" else "water entries recorded"
      case MilestoneKind.MealsLogged   => if isOne then "meal logged" else "meals logged"
      case MilestoneKind.MoodCheckIns  => if isOne then "mood check-in recorded" else "mood check-ins recorded"
      case MilestoneKind.SleepCheckIns => if isOne then "sleep check-in recorded" else "sleep check-ins recorded"
      case MilestoneKind.FirstYear     => if isOne then "year of records" else "years of records"

  def milestoneText(milestone: MilestoneData): String = s"${milestone.count} ${milestoneLabel(milestone)}."

  def moodCalendarTitle: String = "Mood by day"
  def noCheckIn: String = "No check-in"
  def moodCalendarFact(checkInDays: Int): String = s"${plural(checkInDays, "check-in day")}."

  def moodCalendarText(calendar: MoodCalendarData): String =
    val levels = calendar.days.flatten.map(_.valence)
    val counts = levels.groupMapReduce(identity)(_ => 1)(_ + _)
    def named(level: Int): String = s"${valenceName(level)} (${plural(counts(level), "day")})"
    val spread = Option.when(levels.nonEmpty) {
      val frequent = counts.toVector.maxBy((level, count) => (count, -level))._1
      Vector(s"Most frequent: ${named(frequent)}.", s"Lowest: ${named(levels.min)}.", s"Highest: ${named(levels.max)}.")
    }
    val gaps = calendar.days.count(_.isEmpty)
    val gapLine = Option.when(gaps > 0)(
      if gaps == 1 then "1 day has no check-in." else s"$gaps days have no check-in."
    )
    (Vector(s"${monthYear(calendar.month)}: ${plural(calendar.checkInDays, "check-in day")}.") ++
      spread.getOrElse(Vector.empty) ++ gapLine).mkString(" ")

  def moodCalendarDiscreet: String = "Your mood by day."
  def moodTimelineTitle: String = "Mood"
  def energyLabel: String = "Energy"
  def moodTimelineFact(checkInDays: Int, days: Int): String =
    s"${plural(checkInDays, "check-in day")} of $days. Lines join consecutive days only."

  def moodTimelineText(timeline: MoodTimelineData): String =
    val points = timeline.days.flatten
    def level(mean: Double): Int = dosegram.render.blob.MoodState.quantised((mean - 3) / 2, 0.5).valence
    val range = Option.when(points.nonEmpty) {
      val levels = points.map(point => level(point.mean))
      val frequent = levels.groupMapReduce(identity)(_ => 1)(_ + _).toVector.maxBy((lvl, count) => (count, -lvl))._1
      s"Daily mood ranged from ${valenceName(levels.min)} to ${valenceName(levels.max)}, most often " +
        s"${valenceName(frequent)}."
    }
    val changes =
      timeline.days.sliding(2).collect { case Vector(Some(a), Some(b)) => Math.abs(b.mean - a.mean) }.toVector
    val change = Option.when(changes.size >= 4)(
      s"Day-to-day change averaged ${insightValue(changes.sum / changes.size)} levels."
    )
    val gaps = timeline.days.count(_.isEmpty)
    val gapLine = Option.when(gaps > 0)(
      if gaps == 1 then "1 day has no check-in and is shown as a gap."
      else s"$gaps days have no check-in and are shown as gaps."
    )
    (Vector(
      s"Mood, ${dateRange(timeline.from, timeline.to)}.",
      s"${plural(timeline.checkInDays, "check-in day")} of ${timeline.days.size}."
    ) ++ range ++ change ++ gapLine).mkString(" ")

  def moodTimelineDiscreet: String = "Your mood over these days."
  def monthDaysTitle: String = "Day by day"
  def monthNightsTitle: String = "Nights and water"
  def sleepNights(nights: Int): String = s"${plural(nights, "night")} from your check-ins."

  def monthWaterFact(goalDays: Option[GoalDays], recordedDays: Int): String =
    goalDays match
      case Some(days) => s"Goal reached on ${days.reached} of ${plural(days.recorded, "recorded day")}."
      case None       => s"${plural(recordedDays, "day")} with water recorded."

  def waterLoggedMark: String = "Water logged"
  def napMark: String = "Nap"

  def monthDaysText(page: MonthDaysData): String =
    val medication = page.medication.map { calendar =>
      val resolved = calendar.days.collect { case DayAdherence.Resolved(taken, all, _) => (taken, all) }
      val (taken, all) = (resolved.map(_._1).sum, resolved.map(_._2).sum)
      val unknown = Option.when(calendar.unknownDoseCount > 0)(s" ${unknownDosesNotCounted(calendar.unknownDoseCount)}")
      s"Medication: ${if all == 0 then s"$noData." else resolvedDosesTaken(taken, all)}${unknown.getOrElse("")}"
    }
    (Vector(s"${monthYear(page.month)}, day by day.") ++ medication ++ page.mood.map(moodCalendarText)).mkString(" ")

  def monthNightsText(page: MonthNightsData): String =
    val sleep = page.sleep.filter(_.bars.nonEmpty).map { nights =>
      val average = nights.averageMinutes.fold(sleepNights(nights.nights))(minutes =>
        s"${duration(minutes)} on average over ${plural(nights.nights, "night")} from your check-ins."
      )
      val spread = for
        bedtime <- nights.bedtimeSpreadMinutes
        wake <- nights.wakeSpreadMinutes
      yield s" Bedtime varied by about $bedtime min, wake time by about $wake min."
      s"Sleep: $average${spread.getOrElse("")}"
    }
    val water = page.water.filter(_.exists(_.isDefined)).map { days =>
      val recorded = days.flatten
      val withGoal = recorded.filter(_.goalMl.isDefined)
      val goal = Option.when(withGoal.nonEmpty)(
        GoalDays(withGoal.count(day => day.goalMl.exists(day.volumeMl >= _)), withGoal.size)
      )
      s"Water: ${monthWaterFact(goal, recorded.size)}"
    }
    (Vector(s"${monthYear(page.month)}, nights and water.") ++ sleep ++ water).mkString(" ")

  // The adherence percentage is shown only from five counted doses up (VISUALS section 6.5).
  private val MinimumDosesForPercent = 5

  private def weekday(day: DayOfWeek): String = Weekdays(day.getValue - 1)
  private def dayMonth(date: LocalDate): String = s"${date.getDayOfMonth} ${Months(date.getMonthValue - 1)}"
  private def days(count: Int): String = plural(count, "day")
  private def plural(count: Int, noun: String): String = if count == 1 then s"1 $noun" else s"$count ${noun}s"

  // Two places at most and at least one, rounded down: a day short of its goal never reads as the goal, and the figure
  // is the one the chat caption states for the same day.
  private def litres(volumeMl: Int): String =
    val rounded = (BigDecimal(volumeMl) / 1000).setScale(2, BigDecimal.RoundingMode.DOWN).bigDecimal.stripTrailingZeros
    (if rounded.scale < 1 then rounded.setScale(1) else rounded).toPlainString

  private def dayClassPhrase(dayClass: DayClass): String =
    dayClass match
      case DayClass.AllTaken     => "all scheduled doses recorded as taken"
      case DayClass.Most         => "most"
      case DayClass.Some         => "some"
      case DayClass.Few          => "few"
      case DayClass.Skipped      => "all skipped"
      case DayClass.NotConfirmed => "none taken"
      case DayClass.NoRecord     => "no record"
      case DayClass.StillOpen    => "still open"

  // "26 of 28 scheduled doses recorded as taken (93%), 1 skipped, 1 not confirmed".
  private def doseSentence(counts: DoseCounts): String =
    val share = counts.takenPercent.filter(_ => counts.counted >= MinimumDosesForPercent).map(p => s" (${percent(p)})")
    val rest = Vector(
      Option.when(counts.skipped > 0)(s"${counts.skipped} skipped"),
      Option.when(counts.missed > 0)(s"${counts.missed} not confirmed"),
      Option.when(counts.open > 0)(s"${counts.open} still open"),
      Option.when(counts.unknown > 0)(s"${counts.unknown} with no record, not counted")
    ).flatten
    (s"${counts.taken} of ${counts.counted} scheduled doses recorded as taken${share.getOrElse("")}" +: rest)
      .mkString(", ")

  private def sleepSentence(sleep: SleepWeek): String =
    val average = sleep.averageMinutes
      .map { minutes =>
        s"Sleep: ${duration(minutes)} on average over ${plural(sleep.recordedNights, "night")} from your check-ins."
      }
      .getOrElse("Sleep: no records this week.")
    val estimates = Option.when(sleep.estimateNights > 0) {
      if sleep.estimateNights == 1 then "1 night is an estimate from bot activity and is not included in the average."
      else s"${sleep.estimateNights} nights are estimates from bot activity and are not included in the average."
    }
    (Vector(average) ++ estimates).mkString(" ")
