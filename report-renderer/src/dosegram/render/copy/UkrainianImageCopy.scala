package dosegram.render.copy

import dosegram.render.blob.MoodReading
import dosegram.render.card.DayAdherence
import dosegram.render.card.DayData
import dosegram.render.card.DoseCounts
import dosegram.render.card.FoodDayData
import dosegram.render.card.InsightData
import dosegram.render.card.InsightRule
import dosegram.render.card.MainMeal
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

/** The Ukrainian image catalogue (ROADMAP G2.22). Like the English one it formats dates and numbers from explicit
  * tables, with the decimal comma and the 24-hour clock, and chooses the noun form after a number by the CLDR rules of
  * the language (one, few, many). Status words follow BOT_UX section 22.4: "не підтверджено" for a missed dose, "немає
  * запису" for an unknown one. Copy records what the user did; it never advises, judges or implies a cause (VA-PR-02).
  */
object UkrainianImageCopy extends ImageCopy:
  private val Weekdays = Vector("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")

  /** The genitive month names that follow a day number: "14 листопада". */
  private val MonthsOfDay = Vector(
    "січня",
    "лютого",
    "березня",
    "квітня",
    "травня",
    "червня",
    "липня",
    "серпня",
    "вересня",
    "жовтня",
    "листопада",
    "грудня"
  )

  /** The nominative month names of a heading: "Листопад 2026". */
  private val MonthNames = Vector(
    "Січень",
    "Лютий",
    "Березень",
    "Квітень",
    "Травень",
    "Червень",
    "Липень",
    "Серпень",
    "Вересень",
    "Жовтень",
    "Листопад",
    "Грудень"
  )
  private val ValenceNames = Vector(
    "Дуже неприємно",
    "Неприємно",
    "Трохи неприємно",
    "Нейтрально",
    "Трохи приємно",
    "Приємно",
    "Дуже приємно"
  )
  private val EnergyNames = Vector("дуже низька", "низька", "середня", "висока", "дуже висока")

  def footer: String = "Лише записані дані. Не є медичною порадою."
  def detailsBelow: String = "Подробиці нижче."

  def domainName(domain: Domain): String =
    domain match
      case Domain.Medication => "Ліки"
      case Domain.Water      => "Вода"
      case Domain.Food       => "Їжа"
      case Domain.Mood       => "Настрій"
      case Domain.Sleep      => "Сон"

  def itemLabel(position: Int): String = s"Позиція $position"

  def dayDate(date: LocalDate): String = s"${weekday(date.getDayOfWeek)} ${dayMonth(date)}"

  def dateRange(from: LocalDate, to: LocalDate): String =
    if from.getMonth == to.getMonth && from.getYear == to.getYear then s"${from.getDayOfMonth}–${dayMonth(to)}"
    else s"${dayMonth(from)} – ${dayMonth(to)}"

  def monthYear(month: YearMonth): String = s"${MonthNames(month.getMonthValue - 1)} ${month.getYear}"
  def weekdayInitial(day: DayOfWeek): String = weekday(day).take(1)
  def weekdayShort(day: DayOfWeek): String = weekday(day).take(2)
  def time(time: LocalTime): String = f"${time.getHour}%02d:${time.getMinute}%02d"
  def percent(value: Int): String = s"$value%"

  def duration(minutes: Int): String =
    if minutes < 60 then s"$minutes хв" else f"${minutes / 60} год ${minutes % 60}%02d хв"

  def noData: String = "Немає даних"

  def valenceName(valenceLevel: Int): String = ValenceNames(valenceLevel - 1)
  def energyLine(energyLevel: Int): String = s"Енергія: ${EnergyNames(energyLevel - 1)}"

  def moodLabel(reading: MoodReading): String =
    val energy = Option.when(reading.isEnergyGiven)(s" ${energyLine(reading.state.energy)}.")
    s"${valenceName(reading.state.valence)}.${energy.getOrElse("")}"

  def noMoodEntry: String = "Настрій не записано"

  def moodText(mood: Option[MoodReading]): String =
    mood.map(reading => s"Настрій: ${moodLabel(reading)}").getOrElse("Сьогодні настрій не записано.")

  def moodLoggedDiscreet: String = "Настрій записано."

  def todayTitle: String = "Сьогодні"
  def takenOf(taken: Int, counted: Int): String = s"прийнято $taken з $counted"
  def nothingLoggedYet: String = "Поки нічого не записано"
  def mealsOf(logged: Int, planned: Int): String = s"$logged з ${count(planned, "прийому їжі", "прийомів їжі")}"
  def fromYourCheckIns: String = "з ваших позначок"
  def estimateOnly: String = "Лише оцінка"

  def dayText(day: DayData): String =
    val medication = day.medication.map(DoseCounts.of).map(counts => s"Ліки: ${doseSentence(counts)}.")
    val water = day.water.map { progress =>
      if progress.volumeMl == 0 then "Вода: поки нічого не записано." else s"Вода: ${waterFraction(progress)}."
    }
    val food = day.food.map: meals =>
      if meals.planned <= 0 then s"Їжа: ${mealsLogged(meals.logged)}."
      else s"Їжа: записано ${meals.logged} з ${count(meals.planned, "прийому їжі", "прийомів їжі")}."
    val sleep = day.sleep.map {
      case SleepEvidence.Recorded(minutes, _) => s"Сон: ${duration(minutes)}, з ваших позначок."
      case SleepEvidence.QuietWindowOnly      => "Сон: лише оцінка за активністю в боті, не тривалість."
      case SleepEvidence.NoRecord             => "Сон: запису поки немає."
    }
    (Vector(s"${dayDate(day.date)}.", moodText(day.mood)) ++ medication ++ water ++ food ++ sleep).mkString(" ")

  def dayDiscreet: String = "Ваш день на цю мить."

  def foodTitle: String = "Їжа"

  def mainMeal(meal: MainMeal): String =
    meal match
      case MainMeal.Breakfast => "Сніданок"
      case MainMeal.Lunch     => "Обід"
      case MainMeal.Dinner    => "Вечеря"

  def snacksLabel: String = "Перекуси"
  def snackCount(count: Int): String = counted(count, "перекус", "перекуси", "перекусів")

  /** Under the label "Перекуси" the number alone: the noun would not fit the slot. */
  def snackSlot(count: Int): String = count.toString

  /** "2 з 3 прийомів їжі, 1 перекус"; with no planned meal a count: "записано 2 прийоми їжі". */
  def foodSummary(food: FoodDayData): String =
    val meals =
      if food.progress.planned > 0 then mealsOf(food.progress.logged, food.progress.planned)
      else if food.mains.isEmpty then "Прийомів їжі не записано"
      else mealsLogged(food.mains.size)
    if food.snacks == 0 then meals else s"$meals, ${snackCount(food.snacks)}"

  def foodText(food: FoodDayData): String =
    val logged = MainMeal.values.toVector.flatMap(meal =>
      food.mains.get(meal).map(at => s"${mainMeal(meal).toLowerCase(java.util.Locale.ROOT)} ${time(at)}")
    )
    val snacks = Option.when(food.snacks > 0)(snackCount(food.snacks)).toVector
    val listed = logged ++ snacks
    val meals = if listed.isEmpty then "поки не записано жодного прийому їжі" else listed.mkString(", ")
    s"Їжа, ${dayDate(food.date)}: $meals. ${foodSummary(food)}."

  def foodDiscreet: String = "Їжа сьогодні."

  def waterTitle: String = "Вода"
  def waterFraction(progress: WaterProgress): String = s"${litres(progress.volumeMl)} з ${litres(progress.goalMl)} л"
  def waterTick(volumeMl: Int): String = litres(volumeMl)
  def waterTopTick(volumeMl: Int): String = s"${litres(volumeMl)} л"
  def entries(count: Int): String = counted(count, "запис", "записи", "записів")
  def lastEntryAt(time: LocalTime): String = s"останній о ${this.time(time)}"
  def goalIsYours: String = "Ціль — та, яку задали ви."
  def noWaterToday: String = "Сьогодні воду не записано."

  def waterText(water: WaterData): String =
    val progress = water.progress
    val headline = s"Вода, ${dayDate(water.date)}: ${waterFraction(progress)} (${percent(progress.percent)})."
    val log =
      if water.entryCount == 0 then noWaterToday
      else (entries(water.entryCount) +: water.lastEntry.map(lastEntryAt).toVector).mkString("", ", ", ".")
    val factors = Option.when(water.usesDrinkFactors)("Пораховано з вашими коефіцієнтами напоїв.")
    (Vector(headline, log, goalIsYours) ++ factors).mkString(" ")

  def waterDiscreet: String = "Вода сьогодні."

  def calendarTitle: String = "Ліки по днях"

  def dayClassName(dayClass: DayClass): String =
    dayClass match
      case DayClass.AllTaken     => "Усі прийнято"
      case DayClass.Most         => "75–99%"
      case DayClass.Some         => "50–74%"
      case DayClass.Few          => "Менше 50%"
      case DayClass.Skipped      => "Пропущено"
      case DayClass.NotConfirmed => "Не підтверджено"
      case DayClass.NoRecord     => "Немає запису"
      case DayClass.StillOpen    => "Ще відкрито"

  def blankDayNote: String = "Нічого не заплановано"

  def resolvedDosesTaken(taken: Int, resolved: Int): String =
    s"Прийнято $taken з ${count(resolved, "дози з відповіддю", "доз із відповіддю")}."

  def unknownDosesNotCounted(count: Int): String =
    s"${counted(count, "доза", "дози", "доз")}: немає запису, не враховано."

  def calendarText(lines: CalendarLines): String =
    val scope = lines.medicationLabel.getOrElse("усі ліки")
    val classes = lines.dayCounts.map((dayClass, count) => s"${dayClassPhrase(dayClass)}: ${days(count)}")
    val byDay = if classes.isEmpty then Vector(s"$noData.") else Vector(classes.mkString("", "; ", ".").capitalize)
    val unscheduled =
      Option.when(lines.nothingScheduledDays > 0)(s"Нічого не заплановано: ${days(lines.nothingScheduledDays)}.")
    val unknown = Option.when(lines.unknownDoseCount > 0)(
      s"Без запису й не враховано: ${counted(lines.unknownDoseCount, "доза", "дози", "доз")}."
    )
    val total = Option.when(lines.resolved > 0) {
      val share =
        DoseCounts(lines.taken, lines.resolved - lines.taken, 0, 0, 0, 0).takenPercent.map(p => s" (${percent(p)})")
      s"Прийнято ${lines.taken} з ${count(lines.resolved, "дози з відповіддю", "доз із відповіддю")}" +
        s"${share.getOrElse("")}."
    }
    (Vector(s"${monthYear(lines.month)}, $scope.") ++ byDay ++ unscheduled ++ unknown ++ total).mkString(" ")

  def calendarDiscreet: String = "Ліки по днях."

  def stripTitle: String = "За ліками"
  def pageOf(page: Int, pageCount: Int): String = s"Стор. $page з $pageCount"

  def statusName(status: DoseStatus): String =
    status match
      case DoseStatus.TakenOnTime => "Прийнято"
      case DoseStatus.TakenLate   => "Прийнято пізніше"
      case DoseStatus.Skipped     => "Пропущено"
      case DoseStatus.Missed      => "Не підтверджено"
      case DoseStatus.Unknown     => "Немає запису"
      case DoseStatus.Open        => "Відкрито"
      case DoseStatus.AsNeeded    => "За потреби"

  def asNeededName(name: String): String = s"$name (за потреби)"
  def logsOnDays(logs: Int, days: Int): String =
    s"${counted(logs, "запис", "записи", "записів")} за ${this.days(days)}"

  def stripEmpty: String = "За ці дні записів про ліки немає."

  def stripText(lines: StripLines): String =
    val rows = lines.rows.map { row =>
      val record = row.asNeededDays
        .map(logDays => logsOnDays(row.counts.asNeeded, logDays))
        .getOrElse(if row.counts.counted == 0 then noData.toLowerCase else doseSentence(row.counts))
      s"${row.label}: $record."
    }
    (s"${dateRange(lines.from, lines.to)}." +: (if rows.isEmpty then Vector(stripEmpty) else rows)).mkString(" ")

  def stripDiscreet: String = "За ліками."

  def weekTitle: String = "Ваш тиждень"

  def weekDoses(counts: DoseCounts): String =
    val share = counts.takenPercent.filter(_ => counts.counted >= MinimumDosesForPercent).map(p => s" · ${percent(p)}")
    s"${takenOf(counts.taken, counts.counted)}${share.getOrElse("")}"

  def goalReachedDays(days: GoalDays): String = s"Ціль: ${days.reached} з ${daysOf(days.recorded)}"
  def mealsLogged(count: Int): String = s"записано ${counted(count, "прийом їжі", "прийоми їжі", "прийомів їжі")}"
  def checkInDays(count: Int): String = counted(count, "день із позначкою", "дні з позначками", "днів із позначками")
  def sleepAverage(minutes: Int): String = s"${duration(minutes)} у середньому"

  def sleepTitle: String = "Сон"
  def sleepAxisHour(hour: Int): String = f"$hour%02d"
  def sleepRowDate(date: LocalDate): String = s"${weekday(date.getDayOfWeek)} ${date.getDayOfMonth}"

  def sleepAverageOf(minutes: Int, nights: Int): String =
    s"${duration(minutes)} у середньому (${counted(nights, "ніч", "ночі", "ночей")})"

  def sleepSpread(bedtimeMinutes: Int, wakeMinutes: Int): String =
    s"Розкид: відбій ~$bedtimeMinutes хв, підйом ~$wakeMinutes хв"

  def sleepEntriesMark: String = "Записи"
  def sleepNapMark: String = "Денний сон (д)"
  def sleepNapLetter: String = "д"
  def sleepTypicalMark: String = "Звичний час"
  def sleepGoal(minutes: Int): String = s"Ваша ціль: ${duration(minutes)}"
  def sleepNoNights: String = "За ці дні не записано жодної ночі."

  def sleepText(sleep: SleepRegularityData): String =
    val period = s"Сон, ${dateRange(sleep.from, sleep.to)}."
    val days = (sleep.to.toEpochDay - sleep.from.toEpochDay).toInt + 1
    val nights =
      if sleep.nights == 0 then sleepNoNights
      else
        val nightCount = counted(sleep.nights, "ніч", "ночі", "ночей")
        val average = sleep.averageMinutes.fold("")(minutes => s": у середньому ${duration(minutes)}")
        val spread = (sleep.bedtimeSpreadMinutes, sleep.wakeSpreadMinutes) match
          case (Some(bed), Some(wake)) =>
            s"; час відбою різнився приблизно на $bed хв, час підйому — приблизно на $wake хв"
          case _ => ""
        s"$nightCount з ваших позначок і записів$average$spread."
    val gaps = days - sleep.nights
    val missing = Option.when(sleep.nights > 0 && gaps > 0)(
      s"Без запису: ${counted(gaps, "ніч", "ночі", "ночей")}."
    )
    val naps = sleep.bars.count(_.isNap)
    val napLine = Option.when(naps > 0)(
      s"Денний сон: ${counted(naps, "раз", "рази", "разів")}, не входить у середнє."
    )
    val goal = sleep.goalMinutes.map(minutes => s"Ваша ціль — ${duration(minutes)}.")
    (Vector(period, nights) ++ missing ++ napLine ++ goal).mkString(" ")

  def sleepDiscreet: String = "Сон, останні ночі."
  def noRecordsThisWeek: String = "Цього тижня записів немає"
  def yourGoal: String = "Ваша ціль"
  def noRecordMark: String = "Немає запису"
  def estimateMark: String = "Оцінка"
  def snackMark: String = "Перекус"

  def weekText(week: WeekSummary): String =
    val medication = week.doses.map { counts =>
      if counts.counted == 0 then "Ліки: цього тижня записів немає." else s"Ліки: ${doseSentence(counts)}."
    }
    val water = week.water.map { goalDays =>
      if goalDays.recorded == 0 then "Вода: цього тижня записів немає."
      else s"Вода: ціль досягнуто в ${goalDays.reached} з ${daysOf(goalDays.recorded)} із записами."
    }
    val food = week.mealsLogged.map(count =>
      if count == 0 then "Їжа: цього тижня записів немає." else s"Їжа: ${mealsLogged(count)}."
    )
    val mood = week.checkInDays.map(count =>
      if count == 0 then "Настрій: цього тижня записів немає." else s"Настрій: ${checkInDays(count)}."
    )
    val sleep = week.sleep.map(sleepSentence)
    (Vector(s"Тиждень ${dateRange(week.from, week.to)}.") ++ medication ++ water ++ food ++ mood ++ sleep)
      .mkString(" ")

  def weekDiscreet: String = "Ваш тиждень."

  def monthTitle: String = "Ваш місяць"
  def weekNumber(week: Int): String = s"Тиж. $week"

  def monthHeroDoses(counts: DoseCounts): Vector[String] =
    Vector("запланованих доз", "записано як прийняті", s"(${counts.taken} з ${counts.counted})")

  def monthHeroDays(days: Int): Vector[String] = Vector(
    form(days, "день із записами", "дні з записами", "днів із записами")
  )

  def monthPrevious(month: YearMonth, percent: Int): String =
    s"${MonthNames(month.getMonthValue - 1)}: прийнято ${this.percent(percent)} запланованих доз."

  def monthText(month: MonthSummary): String =
    val medication = month.doses.map { counts =>
      if counts.counted == 0 then "Ліки: цього місяця записів немає." else s"Ліки: ${doseSentence(counts)}."
    }
    val water = month.water.map { goalDays =>
      s"Вода: ціль досягнуто в ${goalDays.reached} з ${daysOf(goalDays.recorded)} із записами."
    }
    val mood = month.moodCheckInDays.map(count => s"Настрій: ${checkInDays(count)}.")
    val sleep = month.sleep.map { (minutes, nights) =>
      s"Сон: у середньому ${duration(minutes)} за ${counted(nights, "ніч", "ночі", "ночей")} з ваших позначок."
    }
    val days = s"Днів із записами: ${month.daysWithRecords}."
    val previous = month.previousAdherence
      .filter(_ => month.doses.exists(_.counted >= MinimumDosesForPercent))
      .map(percent => monthPrevious(month.month.minusMonths(1), percent))
    (Vector(s"${monthYear(month.month)}.") ++ medication ++ water ++ mood ++ sleep ++ Vector(days) ++ previous)
      .mkString(" ")

  def monthDiscreet: String = "Ваш місяць."

  def insightTitle: String = "Закономірність у ваших записах"

  def insightGroup(rule: InsightRule, isFirst: Boolean): String =
    rule match
      case InsightRule.WaterGoalMood =>
        if isFirst then "Ціль з води досягнуто" else "Воду записано, ціль не досягнуто"
      case InsightRule.SleepGoalMood =>
        if isFirst then "Після ночей, коли сон досяг цілі" else "Після коротших ночей"
      case InsightRule.BedtimeMood =>
        if isFirst then "Після ночей зі звичним часом відбою" else "Після інших ночей"
      case InsightRule.WorkDayMood => if isFirst then "Робочі дні" else "Вихідні"

  def insightValue(value: Double): String =
    BigDecimal(value).setScale(1, BigDecimal.RoundingMode.HALF_UP).toString.replace('.', ',')

  def insightDays(days: Int): String = this.days(days)

  def insightScaleNote(outcome: Domain): String = "Настрій — середнє за день за шкалою від 1 до 5."

  def insightNotCause: String = "Це порівняння ваших власних днів. Воно не показує причину."

  def insightText(insight: InsightData): String =
    val (first, second) = (insight.first, insight.second)
    val groups = insight.rule match
      case InsightRule.WaterGoalMood =>
        s"У дні, коли ви досягли цілі з води (${days(first.days)}), ваш середній настрій був " +
          s"${insightValue(first.value)}. В інші дні із записаною водою (${days(second.days)}) — " +
          s"${insightValue(second.value)}."
      case InsightRule.SleepGoalMood =>
        s"У дні після ночі, коли сон досяг вашої цілі (${days(first.days)}), ваш середній настрій був " +
          s"${insightValue(first.value)}. В інші дні (${days(second.days)}) — ${insightValue(second.value)}."
      case InsightRule.BedtimeMood =>
        s"У дні після ночі, що почалася в межах 30 хвилин від вашого звичного часу відбою " +
          s"(${days(first.days)}), ваш середній настрій був ${insightValue(first.value)}. В інші дні " +
          s"(${days(second.days)}) — ${insightValue(second.value)}."
      case InsightRule.WorkDayMood =>
        s"У робочі дні (${days(first.days)}) ваш середній настрій був ${insightValue(first.value)}. " +
          s"У вихідні (${days(second.days)}) — ${insightValue(second.value)}."
    Vector(
      s"$insightTitle, ${dateRange(insight.from, insight.to)}.",
      groups,
      insightScaleNote(insight.outcome),
      insightNotCause
    ).mkString(" ")

  def insightDiscreet: String = "Закономірність у ваших записах."

  def milestoneLabel(milestone: MilestoneData): String =
    val n = milestone.count
    milestone.kind match
      case MilestoneKind.WaterEntries => form(n, "запис води", "записи води", "записів води")
      case MilestoneKind.MealsLogged  => form(n, "прийом їжі записано", "прийоми їжі записано", "прийомів їжі записано")
      case MilestoneKind.MoodCheckIns => form(n, "позначка настрою", "позначки настрою", "позначок настрою")
      case MilestoneKind.SleepCheckIns => form(n, "позначка сну", "позначки сну", "позначок сну")
      case MilestoneKind.FirstYear     => form(n, "рік записів", "роки записів", "років записів")

  def milestoneText(milestone: MilestoneData): String = s"${milestone.count} ${milestoneLabel(milestone)}."

  def moodCalendarTitle: String = "Настрій по днях"
  def noCheckIn: String = "Без позначки"
  def moodCalendarFact(checkInDays: Int): String = s"${this.checkInDays(checkInDays).capitalize}."

  def moodCalendarText(calendar: MoodCalendarData): String =
    val levels = calendar.days.flatten.map(_.valence)
    val counts = levels.groupMapReduce(identity)(_ => 1)(_ + _)
    def named(level: Int): String = s"${valenceName(level)} (${days(counts(level))})"
    val spread = Option.when(levels.nonEmpty) {
      val frequent = counts.toVector.maxBy((level, count) => (count, -level))._1
      Vector(
        s"Найчастіше: ${named(frequent)}.",
        s"Найнижчий: ${named(levels.min)}.",
        s"Найвищий: ${named(levels.max)}."
      )
    }
    val gaps = calendar.days.count(_.isEmpty)
    val gapLine = Option.when(gaps > 0)(s"Без позначки: ${days(gaps)}.")
    (Vector(s"${monthYear(calendar.month)}: ${checkInDays(calendar.checkInDays)}.") ++
      spread.getOrElse(Vector.empty) ++ gapLine).mkString(" ")

  def moodCalendarDiscreet: String = "Ваш настрій по днях."
  def moodTimelineTitle: String = "Настрій"
  def energyLabel: String = "Енергія"
  def moodTimelineFact(checkInDays: Int, days: Int): String =
    s"${this.checkInDays(checkInDays).capitalize} з $days. Лінії з’єднують лише суміжні дні."

  def moodTimelineText(timeline: MoodTimelineData): String =
    val points = timeline.days.flatten
    def level(mean: Double): Int = dosegram.render.blob.MoodState.quantised((mean - 3) / 2, 0.5).valence
    val range = Option.when(points.nonEmpty) {
      val levels = points.map(point => level(point.mean))
      val frequent = levels.groupMapReduce(identity)(_ => 1)(_ + _).toVector.maxBy((lvl, count) => (count, -lvl))._1
      s"Денний настрій був від «${valenceName(levels.min)}» до «${valenceName(levels.max)}», найчастіше — " +
        s"«${valenceName(frequent)}»."
    }
    val changes =
      timeline.days.sliding(2).collect { case Vector(Some(a), Some(b)) => Math.abs(b.mean - a.mean) }.toVector
    val change = Option.when(changes.size >= 4)(
      s"Зміна від дня до дня в середньому ${insightValue(changes.sum / changes.size)} рівня."
    )
    val gaps = timeline.days.count(_.isEmpty)
    val gapLine = Option.when(gaps > 0)(s"Без позначки (показано як пропуск): ${days(gaps)}.")
    (Vector(
      s"Настрій, ${dateRange(timeline.from, timeline.to)}.",
      s"${checkInDays(timeline.checkInDays).capitalize} з ${timeline.days.size}."
    ) ++ range ++ change ++ gapLine).mkString(" ")

  def moodTimelineDiscreet: String = "Ваш настрій за ці дні."
  def monthDaysTitle: String = "День за днем"
  def monthNightsTitle: String = "Ночі й вода"
  def sleepNights(nights: Int): String = s"${counted(nights, "ніч", "ночі", "ночей").capitalize} з ваших позначок."

  def monthWaterFact(goalDays: Option[GoalDays], recordedDays: Int): String =
    goalDays match
      case Some(days) => s"Ціль досягнуто в ${days.reached} з ${daysOf(days.recorded)} із записами."
      case None       => s"Днів із записаною водою: $recordedDays."

  def waterLoggedMark: String = "Воду записано"
  def napMark: String = "Денний сон"

  def monthDaysText(page: MonthDaysData): String =
    val medication = page.medication.map { calendar =>
      val resolved = calendar.days.collect { case DayAdherence.Resolved(taken, all, _) => (taken, all) }
      val (taken, all) = (resolved.map(_._1).sum, resolved.map(_._2).sum)
      val unknown = Option.when(calendar.unknownDoseCount > 0)(s" ${unknownDosesNotCounted(calendar.unknownDoseCount)}")
      s"Ліки: ${if all == 0 then s"$noData." else resolvedDosesTaken(taken, all)}${unknown.getOrElse("")}"
    }
    (Vector(s"${monthYear(page.month)}, день за днем.") ++ medication ++ page.mood.map(moodCalendarText)).mkString(" ")

  def monthNightsText(page: MonthNightsData): String =
    val sleep = page.sleep.filter(_.bars.nonEmpty).map { nights =>
      val average = nights.averageMinutes.fold(sleepNights(nights.nights))(minutes =>
        s"у середньому ${duration(minutes)} за ${counted(nights.nights, "ніч", "ночі", "ночей")} з ваших позначок."
      )
      val spread = for
        bedtime <- nights.bedtimeSpreadMinutes
        wake <- nights.wakeSpreadMinutes
      yield s" Час відбою різнився приблизно на $bedtime хв, час підйому — приблизно на $wake хв."
      s"Сон: $average${spread.getOrElse("")}"
    }
    val water = page.water.filter(_.exists(_.isDefined)).map { days =>
      val recorded = days.flatten
      val withGoal = recorded.filter(_.goalMl.isDefined)
      val goal = Option.when(withGoal.nonEmpty)(
        GoalDays(withGoal.count(day => day.goalMl.exists(day.volumeMl >= _)), withGoal.size)
      )
      s"Вода: ${monthWaterFact(goal, recorded.size)}"
    }
    (Vector(s"${monthYear(page.month)}, ночі й вода.") ++ sleep ++ water).mkString(" ")

  // The adherence percentage is shown only from five counted doses up (VISUALS section 6.5).
  private val MinimumDosesForPercent = 5

  private def weekday(day: DayOfWeek): String = Weekdays(day.getValue - 1)
  private def dayMonth(date: LocalDate): String = s"${date.getDayOfMonth} ${MonthsOfDay(date.getMonthValue - 1)}"

  /** A number of days in the nominative: "1 день", "3 дні", "5 днів". */
  private def days(count: Int): String = counted(count, "день", "дні", "днів")

  /** After "з": "з 1 дня", "з 7 днів". */
  private def daysOf(n: Int): String = count(n, "дня", "днів")

  /** The CLDR plural form of Ukrainian whole numbers. */
  private def form(n: Int, one: String, few: String, many: String): String =
    val (last, lastTwo) = (Math.abs(n) % 10, Math.abs(n) % 100)
    if last == 1 && lastTwo != 11 then one
    else if last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) then few
    else many

  private def counted(n: Int, one: String, few: String, many: String): String = s"$n ${form(n, one, few, many)}"

  /** After "з": the genitive singular after a number ending in 1 (except 11), the genitive plural otherwise. */
  private def count(n: Int, oneOf: String, manyOf: String): String =
    s"$n ${form(n, oneOf, manyOf, manyOf)}"

  // Two places at most and at least one, rounded down, as the English catalogue; with the decimal comma.
  private def litres(volumeMl: Int): String =
    val rounded = (BigDecimal(volumeMl) / 1000).setScale(2, BigDecimal.RoundingMode.DOWN).bigDecimal.stripTrailingZeros
    (if rounded.scale < 1 then rounded.setScale(1) else rounded).toPlainString.replace('.', ',')

  private def dayClassPhrase(dayClass: DayClass): String =
    dayClass match
      case DayClass.AllTaken     => "усі заплановані дози записано як прийняті"
      case DayClass.Most         => "більшість"
      case DayClass.Some         => "частина"
      case DayClass.Few          => "небагато"
      case DayClass.Skipped      => "усі пропущено"
      case DayClass.NotConfirmed => "жодної не прийнято"
      case DayClass.NoRecord     => "немає запису"
      case DayClass.StillOpen    => "ще відкрито"

  // "26 з 28 запланованих доз записано як прийняті (93%), 1 пропущено, 1 не підтверджено".
  private def doseSentence(counts: DoseCounts): String =
    val share = counts.takenPercent.filter(_ => counts.counted >= MinimumDosesForPercent).map(p => s" (${percent(p)})")
    val rest = Vector(
      Option.when(counts.skipped > 0)(s"пропущено ${counts.skipped}"),
      Option.when(counts.missed > 0)(s"не підтверджено ${counts.missed}"),
      Option.when(counts.open > 0)(s"ще відкрито ${counts.open}"),
      Option.when(counts.unknown > 0)(s"без запису ${counts.unknown}, не враховано")
    ).flatten
    val planned = count(counts.counted, "запланованої дози", "запланованих доз")
    (s"${counts.taken} з $planned записано як прийняті${share.getOrElse("")}" +: rest).mkString(", ")

  private def sleepSentence(sleep: SleepWeek): String =
    val average = sleep.averageMinutes
      .map { minutes =>
        s"Сон: у середньому ${duration(minutes)} за ${counted(sleep.recordedNights, "ніч", "ночі", "ночей")} " +
          "з ваших позначок."
      }
      .getOrElse("Сон: цього тижня записів немає.")
    val estimates = Option.when(sleep.estimateNights > 0)(
      s"Оцінка за активністю в боті, не входить у середнє: ${counted(sleep.estimateNights, "ніч", "ночі", "ночей")}."
    )
    (Vector(average) ++ estimates).mkString(" ")
end UkrainianImageCopy
