package dosegram.api

import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.TrendCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.copy.tr
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DayPoint
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.MealDay
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.SleepBar
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.WeekdayMood

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The chart layouts of the Mini App (MINI_APP section 7.11, "Chart choice per metric"): water as bars from zero with
  * the user's own goal as a dashed line, mood as the line of the daily mean with gaps, dose outcomes as one stacked bar
  * and as columns per day. Each chart carries its sentence, its table and the exact value of every mark, written from
  * the same data as the drawing. A day without a record is a gap, never a zero (MA-DS-3). The scenes are laid out here,
  * on the server; the client only paints them (ARCHITECTURE section 9.5.1).
  */
object Charts:
  private val Width = 320
  private val PlotHeight = 96
  private val LabelRoom = 20
  private val AxisWidth = 44
  private val BarShare = 0.7
  private val OutcomeBarHeight = 20
  private val MoodSteps = 5
  // Room for the level names, so that no dot is drawn over a word.
  private val MoodLabelWidth = 96
  private val MoodPlotTop = 16.0

  private final case class Point(x: Double, y: Double)
  private final case class Box(x: Double, y: Double, width: Double, height: Double)

  /** The day columns of a plot: equal widths from `left` to the right edge of the scene. */
  private final case class Columns(left: Double, count: Int):
    val pitch: Double = (Width - left) / count
    def start(index: Int): Double = left + index * pitch
    def centre(index: Int): Double = start(index) + pitch / 2
    def barStart(index: Int): Double = start(index) + pitch * (1 - BarShare) / 2
    def barWidth: Double = pitch * BarShare
    def hit(index: Int): SceneNode =
      rect(Box(start(index), 0, pitch, PlotHeight), SceneTone.Separator)
        .copy(shape = SceneShape.Hit, mark = Some(index))

  def waterByDay(points: List[DayPoint[HydrationDay]], window: DateRange): Chart =
    val goal = sharedGoal(points)
    val top = (goal.toList ++ points.flatMap(_.value.map(_.totalMl))).maxOption.getOrElse(1) * 1.1
    val columns = Columns(AxisWidth, points.size)
    def yOf(millilitres: Int): Double = PlotHeight - millilitres / top * PlotHeight
    val bars = points.zipWithIndex.collect { case (DayPoint(_, Some(day)), index) =>
      val box = Box(columns.barStart(index), yOf(day.totalMl), columns.barWidth, PlotHeight - yOf(day.totalMl))
      rect(box, SceneTone.Hydration)
        .copy(radius = Some(rounded(Math.min(2.0, columns.barWidth / 4))), mark = Some(index))
    }
    val goalLine = goal.toList.flatMap: millilitres =>
      List(
        line(Point(AxisWidth, yOf(millilitres)), Point(Width, yOf(millilitres)), SceneTone.Muted)
          .copy(style = Some(SceneStyle.Dashed)),
        label(Point(0, yOf(millilitres) + 4), WaterCopy.volume(millilitres), SceneAnchor.Start)
      )
    val recorded = points.flatMap(_.value)
    val reached =
      goal.map(_ =>
        tr(
          s" The goal was reached on ${recorded.count(_.isGoalDay)} of ${recorded.size} recorded days.",
          s" Ціль досягнуто в ${recorded.count(_.isGoalDay)} з ${recorded.size} днів із записами."
        )
      )
    Chart(
      id = "water-by-day",
      title = tr("Water per day", "Вода за день"),
      summary = tr(
        s"Water per day, ${ApiCopy.rangeLabel(window)}. Entries on ${recorded.size} of ${points.size} days.",
        s"Вода за день, ${ApiCopy.rangeLabel(window)}. Записи є за ${recorded.size} з ${points.size} днів."
      ) +
        reached.getOrElse(""),
      scene = Scene(
        Width,
        PlotHeight + LabelRoom,
        bars ++ goalLine ++ frame(points, AxisWidth) ++ points.indices.map(columns.hit)
      ),
      table = ChartTable(
        tr("Water per day", "Вода за день"),
        tr(List("Day", "Water"), List("День", "Вода")),
        points.map(point =>
          List(ApiCopy.dayLabel(point.date), point.value.fold(tr("No entry", "Немає запису"))(waterOf))
        )
      ),
      marks = points.map(point =>
        s"${ApiCopy.dayLabel(point.date)}: ${point.value.fold(tr("no entry", "немає запису"))(waterOf)}"
      ),
      markDates = datesOf(points)
    )

  private def waterOf(day: HydrationDay): String = WaterCopy.volume(day.totalMl)

  /** The goal line is drawn only when every recorded day of the period had the same goal. */
  private def sharedGoal(points: List[DayPoint[HydrationDay]]): Option[Int] =
    points.flatMap(_.value).map(_.goalMl).distinct match
      case List(Some(goal)) => Some(goal)
      case _                => None

  /** Sleep as bars of duration from zero per sleep day, with the user's own goal as a dashed line when there is one. No
    * reference duration is drawn (MINI_APP MA-SLEEP-4); every figure names its source (MA-SLEEP-1).
    */
  def sleepByDay(points: List[DayPoint[SleepNight]], window: DateRange, goalMinutes: Option[Int]): Chart =
    val top = (goalMinutes.toList ++ points.flatMap(_.value.map(_.minutes))).maxOption.getOrElse(1) * 1.1
    val columns = Columns(AxisWidth, points.size)
    def yOf(minutes: Int): Double = PlotHeight - minutes / top * PlotHeight
    val bars = points.zipWithIndex.collect { case (DayPoint(_, Some(night)), index) =>
      val box = Box(columns.barStart(index), yOf(night.minutes), columns.barWidth, PlotHeight - yOf(night.minutes))
      rect(box, SceneTone.Sleep)
        .copy(radius = Some(rounded(Math.min(2.0, columns.barWidth / 4))), mark = Some(index))
    }
    val goalLine = goalMinutes.toList.flatMap: minutes =>
      List(
        line(Point(AxisWidth, yOf(minutes)), Point(Width, yOf(minutes)), SceneTone.Muted)
          .copy(style = Some(SceneStyle.Dashed)),
        label(
          Point(0, yOf(minutes) + 4),
          tr(f"${minutes / 60} h ${minutes % 60}%02d", f"${minutes / 60} год ${minutes % 60}%02d"),
          SceneAnchor.Start
        )
      )
    val recorded = points.flatMap(_.value)
    val goal = goalMinutes.fold("")(minutes => s" Your goal: ${StatsCopy.duration(minutes)}.")
    def nightOf(point: DayPoint[SleepNight]): String =
      point.value.fold(tr("no night logged", "ніч не записано"))(night => StatsCopy.duration(night.minutes))
    Chart(
      id = "sleep-by-day",
      title = tr("Sleep per night", "Сон за ніч"),
      summary = tr(
        s"Sleep per night, ${ApiCopy.rangeLabel(window)}, from your check-ins. ",
        s"Сон за ніч, ${ApiCopy.rangeLabel(window)}, з ваших позначок. "
      ) +
        tr(
          s"Nights logged on ${recorded.size} of ${points.size} days.$goal",
          s"Ночі записано за ${recorded.size} з ${points.size} днів.$goal"
        ),
      scene = Scene(
        Width,
        PlotHeight + LabelRoom,
        bars ++ goalLine ++ frame(points, AxisWidth) ++ points.indices.map(columns.hit)
      ),
      table = ChartTable(
        tr("Sleep per night", "Сон за ніч"),
        tr(List("Day", "Sleep"), List("День", "Сон")),
        points.map(point => List(ApiCopy.dayLabel(point.date), nightOf(point).capitalize))
      ),
      marks = points.map(point => s"${ApiCopy.dayLabel(point.date)}: ${nightOf(point)}"),
      markDates = datesOf(points)
    )

  def moodByDay(points: List[DayPoint[MoodDay]], window: DateRange): Chart =
    val columns = Columns(MoodLabelWidth, points.size)
    def yOf(mean: Double): Double = MoodPlotTop + (MoodSteps - mean) / (MoodSteps - 1) * (PlotHeight - MoodPlotTop)
    val indexed = points.zipWithIndex
    // A line joins two consecutive days only: a gap in the records is a gap in the line.
    val joins = indexed
      .zip(indexed.drop(1))
      .collect { case ((DayPoint(_, Some(from)), first), (DayPoint(_, Some(to)), second)) =>
        val start = Point(columns.centre(first), yOf(from.mean))
        line(start, Point(columns.centre(second), yOf(to.mean)), SceneTone.Mood)
      }
    val dots = indexed.collect { case (DayPoint(_, Some(day)), index) =>
      SceneNode(SceneShape.Circle, rounded(columns.centre(index)), rounded(yOf(day.mean)), SceneTone.Mood)
        .copy(radius = Some(3.5), mark = Some(index))
    }
    val levels = tr(
      List(5 -> "Very pleasant", 3 -> "Neutral", 1 -> "Very unpleasant"),
      List(5 -> "Дуже приємно", 3 -> "Нейтрально", 1 -> "Дуже неприємно")
    ).flatMap: (step, name) =>
      List(
        line(Point(MoodLabelWidth, yOf(step.toDouble)), Point(Width, yOf(step.toDouble)), SceneTone.Separator),
        label(Point(0, yOf(step.toDouble) + 4), name, SceneAnchor.Start)
      )
    val recorded = points.flatMap(_.value)
    Chart(
      id = "mood-by-day",
      title = tr("Mood per day", "Настрій за день"),
      summary = tr(
        s"Daily mood, ${ApiCopy.rangeLabel(window)}. Check-ins on ${recorded.size} of ${points.size} days. ",
        s"Денний настрій, ${ApiCopy.rangeLabel(window)}. Позначки є за ${recorded.size} з ${points.size} днів. "
      ) +
        tr("A day without a check-in is a gap.", "День без позначки показано як пропуск."),
      scene = Scene(
        Width,
        PlotHeight + LabelRoom,
        levels ++ joins ++ dots ++ dateLabels(points, MoodLabelWidth) ++ points.indices.map(columns.hit)
      ),
      table = ChartTable(
        tr("Mood per day", "Настрій за день"),
        tr(List("Day", "Daily mood", "Check-ins"), List("День", "Денний настрій", "Позначки")),
        points.map: point =>
          List(
            ApiCopy.dayLabel(point.date),
            point.value.fold(tr("No check-in", "Немає позначки"))(ApiCopy.moodMean),
            point.value.fold("0")(_.n.toString)
          )
      ),
      marks = points.map: point =>
        val value =
          point.value.fold(tr("no check-in", "немає позначки"))(day =>
            s"${ApiCopy.moodMean(day)}, ${ApiCopy.count(day.n, "check-in")}"
          )
        s"${ApiCopy.dayLabel(point.date)}: $value"
      ,
      markDates = datesOf(points)
    )

  /** One horizontal bar of the outcomes of a period; `None` when the period has no scheduled dose. */
  def doseOutcomes(buckets: DoseBuckets): Option[Chart] =
    val outcomes = outcomesOf(buckets)
    val total = outcomes.map(_.doses).sum
    Option.when(total > 0):
      val shown = outcomes.filter(_.doses > 0)
      val starts = shown.scanLeft(0.0)((x, outcome) => x + outcome.doses.toDouble / total * Width)
      val segments = shown.zip(starts).zipWithIndex.map { case ((outcome, x), index) =>
        rect(Box(x, 0, outcome.doses.toDouble / total * Width, OutcomeBarHeight), outcome.tone)
          .copy(opacity = outcome.opacity, style = outcome.style, mark = Some(index))
      }
      val counts = shown.map(outcome => s"${outcome.doses} ${outcome.name}")
      Chart(
        id = "dose-outcomes",
        title = tr("Dose outcomes", "Результати доз"),
        summary = tr(
          s"Of ${ApiCopy.count(total, "scheduled dose")}: ${counts.mkString(", ")}.",
          s"Заплановано доз: $total. ${counts.mkString(", ").capitalize}."
        ),
        scene = Scene(Width, OutcomeBarHeight, segments),
        table = ChartTable(
          tr("Dose outcomes in this period", "Результати доз за цей період"),
          tr(List("Outcome", "Doses"), List("Результат", "Дози")),
          outcomes.map(outcome => List(outcome.name, outcome.doses.toString))
        ),
        marks = counts
      )

  /** One part of the outcome bar. Recorded doses are filled; the others are outlined, and none is red (MA-DS-3). */
  private final case class Outcome(name: String, doses: Int, tone: SceneTone, look: OutcomeLook):
    def opacity: Option[Double] = look.opacity
    def style: Option[SceneStyle] = look.style

  private final case class OutcomeLook(opacity: Option[Double], style: Option[SceneStyle])

  // The status words are those of BOT_UX section 22.4: a missed dose is tr("not confirmed", "не підтверджено"), an unknown one tr("no record", "немає запису").
  private def outcomesOf(buckets: DoseBuckets): List[Outcome] =
    val solid = OutcomeLook(None, None)
    val outlined = Some(SceneStyle.Outlined)
    List(
      Outcome(tr("on time", "вчасно"), buckets.takenOnTime, SceneTone.Recorded, solid),
      Outcome(tr("late", "із запізненням"), buckets.takenLate, SceneTone.Recorded, OutcomeLook(Some(0.55), None)),
      Outcome(tr("skipped", "пропущено"), buckets.skipped, SceneTone.Muted, solid),
      Outcome(tr("not confirmed", "не підтверджено"), buckets.missed, SceneTone.Ink, OutcomeLook(Some(0.25), outlined)),
      Outcome(tr("no record", "немає запису"), buckets.unknown, SceneTone.Separator, OutcomeLook(None, outlined)),
      Outcome(
        tr("still due", "ще очікують"),
        buckets.open,
        SceneTone.Separator,
        OutcomeLook(Some(0.4), Some(SceneStyle.Dashed))
      )
    )

  def dosesByDay(points: List[DayPoint[MedicationDay]], window: DateRange): Chart =
    val days = points.map(point => DayPoint(point.date, point.value.map(_.total).filter(scheduledIn(_) > 0)))
    val top = days.flatMap(_.value.map(scheduledIn)).maxOption.getOrElse(1).toDouble
    val columns = Columns(0, days.size)
    def bar(index: Int, doses: Int, tone: SceneTone): SceneNode =
      val height = doses / top * PlotHeight
      rect(Box(columns.barStart(index), PlotHeight - height, columns.barWidth, height), tone)
    val bars = days.zipWithIndex.flatMap:
      case (DayPoint(_, Some(buckets)), index) =>
        val scheduled = bar(index, scheduledIn(buckets), SceneTone.Separator)
          .copy(style = Some(SceneStyle.Outlined), mark = Some(index))
        val taken = Option.when(buckets.resolved.taken > 0)(bar(index, buckets.resolved.taken, SceneTone.Recorded))
        scheduled :: taken.toList
      case (DayPoint(_, None), _) => Nil
    val withDoses = days.flatMap(_.value)
    val complete = withDoses.count(buckets => buckets.resolved.taken == scheduledIn(buckets))
    Chart(
      id = "doses-by-day",
      title = tr("Doses by day", "Дози за днями"),
      summary = tr(
        s"Scheduled doses by day, ${ApiCopy.rangeLabel(window)}. Every scheduled dose was recorded as taken " +
          s"on $complete of ${withDoses.size} days with doses.",
        s"Заплановані дози за днями, ${ApiCopy.rangeLabel(window)}. Усі заплановані дози записано як прийняті " +
          s"за $complete з ${withDoses.size} днів із дозами."
      ),
      scene = Scene(Width, PlotHeight + LabelRoom, bars ++ frame(days, 0) ++ days.indices.map(columns.hit)),
      table = ChartTable(
        tr("Doses recorded as taken, by day", "Дози, записані як прийняті, за днями"),
        tr(List("Day", "Taken", "Scheduled"), List("День", "Прийнято", "Заплановано")),
        days.map: point =>
          val taken = point.value.fold("0")(_.resolved.taken.toString)
          List(ApiCopy.dayLabel(point.date), taken, point.value.fold("0")(scheduledIn(_).toString))
      ),
      marks = days.map(point =>
        s"${ApiCopy.dayLabel(point.date)}: ${point.value.fold(tr("nothing scheduled", "нічого не заплановано"))(dosesOf)}"
      ),
      markDates = datesOf(days)
    )

  /** The doses a day was scheduled to have: the answered, the unanswered and the open ones. As-needed logs are not. */
  def scheduledIn(buckets: DoseBuckets): Int = buckets.resolved.resolved + buckets.unknown + buckets.open

  /** The doses of a day in words: "3 of 4 taken, 1 not confirmed". */
  def dosesOf(buckets: DoseBuckets): String =
    val resolved = buckets.resolved
    val rest = List(
      Option.when(resolved.skipped > 0)(tr(s"${resolved.skipped} skipped", s"пропущено ${resolved.skipped}")),
      Option.when(resolved.missed > 0)(tr(s"${resolved.missed} not confirmed", s"не підтверджено ${resolved.missed}")),
      Option.when(buckets.unknown > 0)(tr(s"${buckets.unknown} no record", s"без запису ${buckets.unknown}")),
      Option.when(buckets.open > 0)(tr(s"${buckets.open} still due", s"ще очікують ${buckets.open}"))
    ).flatten
    (tr(
      s"${resolved.taken} of ${scheduledIn(buckets)} taken",
      s"прийнято ${resolved.taken} з ${scheduledIn(buckets)}"
    ) :: rest).mkString(", ")

  /** The local day of every mark of a chart of days, so that a picked mark opens that day (S-13). */
  private def datesOf[A](points: List[DayPoint[A]]): Option[List[String]] = Some(points.map(_.date.toString))

  // ---------- The dashboard of MINI_APP section 7.11 and VISUALS section 9.2 ----------

  private val SparkWidth = 120
  private val SparkHeight = 32
  private val SparkInset = 3.0

  /** A small line of a period by bucket, for the block's summary tile. It is decorative: its text is the block's lines.
    * A bucket without a record is a gap. At least two buckets with a value are needed for a line.
    */
  def spark(values: List[Option[Double]], tone: SceneTone): Option[Scene] =
    val present = values.flatten
    Option.when(present.size >= 2):
      val low = present.min
      val span = present.max - low
      val step = (SparkWidth - 2 * SparkInset) / Math.max(1, values.size - 1)
      def at(index: Int, value: Double): Point =
        val share = if span <= 1e-9 then 0.5 else (value - low) / span
        Point(SparkInset + index * step, SparkHeight - SparkInset - share * (SparkHeight - 2 * SparkInset))
      val indexed = values.zipWithIndex
      val joins = indexed.zip(indexed.drop(1)).collect { case ((Some(from), first), (Some(to), second)) =>
        line(at(first, from), at(second, to), tone)
      }
      val (lastValue, lastIndex) = indexed.collect { case (Some(value), index) => value -> index }.last
      val end = at(lastIndex, lastValue)
      val dot = SceneNode(SceneShape.Circle, rounded(end.x), rounded(end.y), tone).copy(radius = Some(2.5))
      Scene(SparkWidth, SparkHeight, joins :+ dot)

  /** One week of a 90-day chart (VISUALS section 9.2): the days it covers, the value it pools, an optional outlined
    * total behind the value (the scheduled doses of the week), and the week in words.
    */
  final case class WeekColumn(range: DateRange, value: Option[Double], outline: Option[Double], text: String)

  private def weekLabel(range: DateRange): String = ApiCopy.rangeLabel(range)

  private def weekBars(
      id: String,
      title: String,
      summary: String,
      tone: SceneTone,
      weeks: List[WeekColumn],
      reference: Option[(Double, String)]
  ): Chart =
    val top = (reference.map(_._1).toList ++ weeks.flatMap(week => week.value.toList ++ week.outline.toList)).maxOption
      .filter(_ > 0)
      .getOrElse(1.0) * 1.1
    val left = if reference.isDefined then AxisWidth.toDouble else 0.0
    val columns = Columns(left, weeks.size)
    def yOf(value: Double): Double = PlotHeight - value / top * PlotHeight
    def bar(index: Int, value: Double, shade: SceneTone): SceneNode =
      rect(Box(columns.barStart(index), yOf(value), columns.barWidth, PlotHeight - yOf(value)), shade)
    val bars = weeks.zipWithIndex.flatMap: (week, index) =>
      val outline = week.outline
        .filter(_ > 0)
        .map(total =>
          bar(index, total, SceneTone.Separator).copy(style = Some(SceneStyle.Outlined), mark = Some(index))
        )
      val filled = week.value
        .filter(_ > 0)
        .map(value =>
          bar(index, value, tone).copy(radius = Some(rounded(Math.min(2.0, columns.barWidth / 4))), mark = Some(index))
        )
      outline.toList ++ filled.toList
    val referenceLine = reference.toList.flatMap: (value, text) =>
      List(
        line(Point(left, yOf(value)), Point(Width, yOf(value)), SceneTone.Muted).copy(style = Some(SceneStyle.Dashed)),
        label(Point(0, yOf(value) + 4), text, SceneAnchor.Start)
      )
    val frame = line(Point(left, PlotHeight), Point(Width, PlotHeight), SceneTone.Separator) ::
      weekEnds(weeks.map(_.range), left)
    Chart(
      id = id,
      title = title,
      summary = summary,
      scene = Scene(Width, PlotHeight + LabelRoom, bars ++ referenceLine ++ frame ++ weeks.indices.map(columns.hit)),
      table = ChartTable(title, List("Week", "Value"), weeks.map(week => List(weekLabel(week.range), week.text))),
      marks = weeks.map(week => s"${weekLabel(week.range)}: ${week.text}")
    )

  private def weekEnds(weeks: List[DateRange], left: Double): List[SceneNode] =
    val baseline = PlotHeight + 16.0
    val first =
      weeks.headOption.map(week => label(Point(left, baseline), ApiCopy.dayMonth(week.start), SceneAnchor.Start))
    val last = weeks.lastOption.map(week => label(Point(Width, baseline), ApiCopy.dayMonth(week.end), SceneAnchor.End))
    first.toList ++ last.toList

  private def recordedIn[A](days: Map[LocalDate, A], range: DateRange): List[A] = range.dates.flatMap(days.get)

  private def meanOf(values: List[Int]): Option[Double] =
    Option.when(values.nonEmpty)(values.sum.toDouble / values.size.toDouble)

  def waterByWeek(days: Map[LocalDate, HydrationDay], weeks: List[DateRange], window: DateRange): Chart =
    val recorded = recordedIn(days, window)
    val goal = recorded.map(_.goalMl).distinct match
      case List(Some(goal)) => Some(goal.toDouble -> WaterCopy.volume(goal))
      case _                => None
    val columns = weeks.map: week =>
      val own = recordedIn(days, week)
      val average = meanOf(own.map(_.totalMl))
      val text = average.fold("no entry")(ml =>
        s"average ${WaterCopy.volume(Math.round(ml).toInt)} a day on ${ApiCopy.count(own.size, "day")}"
      )
      WeekColumn(week, average, None, text)
    weekBars(
      "water-by-week",
      "Water per week",
      s"Water per week, ${ApiCopy.rangeLabel(window)}: the average of the days with an entry. " +
        s"Entries on ${recorded.size} of ${window.days} days.",
      SceneTone.Hydration,
      columns,
      goal
    )

  def sleepByWeek(
      days: Map[LocalDate, SleepNight],
      weeks: List[DateRange],
      window: DateRange,
      goalMinutes: Option[Int]
  ): Chart =
    val columns = weeks.map: week =>
      val own = recordedIn(days, week)
      val average = meanOf(own.map(_.minutes))
      val text = average.fold("no night logged")(minutes =>
        s"average ${StatsCopy.duration(Math.round(minutes).toInt)} over ${ApiCopy.count(own.size, "night")}"
      )
      WeekColumn(week, average, None, text)
    weekBars(
      "sleep-by-week",
      "Sleep per week",
      s"Sleep per week, ${ApiCopy.rangeLabel(window)}, from your check-ins: the average of the nights logged. " +
        s"Nights logged on ${recordedIn(days, window).size} of ${window.days} days.",
      SceneTone.Sleep,
      columns,
      goalMinutes.map(minutes => minutes.toDouble -> f"${minutes / 60} h ${minutes % 60}%02d")
    )

  def dosesByWeek(days: Map[LocalDate, MedicationDay], weeks: List[DateRange], window: DateRange): Chart =
    def pooled(range: DateRange): DoseBuckets =
      recordedIn(days, range).map(_.total).foldLeft(DoseBuckets.zero)(_.combine(_))
    val columns = weeks.map: week =>
      val buckets = pooled(week)
      val scheduled = scheduledIn(buckets)
      if scheduled == 0 then WeekColumn(week, None, None, "nothing scheduled")
      else WeekColumn(week, Some(buckets.resolved.taken.toDouble), Some(scheduled.toDouble), dosesOf(buckets))
    val total = pooled(window)
    weekBars(
      "doses-by-week",
      "Doses by week",
      s"Scheduled doses by week, ${ApiCopy.rangeLabel(window)}. ${total.resolved.taken} of " +
        s"${ApiCopy.count(scheduledIn(total), "scheduled dose")} were recorded as taken.",
      SceneTone.Recorded,
      columns,
      None
    )

  def mealsByWeek(days: Map[LocalDate, MealDay], weeks: List[DateRange], window: DateRange): Chart =
    val columns = weeks.map: week =>
      val own = recordedIn(days, week).filter(_.meals > 0)
      val meals = own.map(_.meals).sum
      if meals == 0 then WeekColumn(week, None, None, "no meal logged")
      else
        WeekColumn(
          week,
          Some(meals.toDouble),
          None,
          s"${ApiCopy.count(meals, "meal")} on ${ApiCopy.count(own.size, "day")}"
        )
    weekBars(
      "meals-by-week",
      "Meals per week",
      s"Meals logged per week, ${ApiCopy.rangeLabel(window)}. A count of entries, with no amount.",
      SceneTone.Nutrition,
      columns,
      None
    )

  private val MealRows = List("Breakfast", "Lunch", "Dinner")
  private val MealLabelWidth = 76

  /** The main meals of each day as dots in three rows (MINI_APP section 7.11: presence, not magnitude). */
  def mealsByDay(points: List[DayPoint[MealDay]], window: DateRange): Chart =
    val columns = Columns(MealLabelWidth, points.size)
    val rowGap = PlotHeight / (MealRows.size + 1).toDouble
    def yOf(row: Int): Double = rowGap * (row + 1)
    val rows = MealRows.zipWithIndex.flatMap: (name, row) =>
      List(
        line(Point(MealLabelWidth, yOf(row)), Point(Width, yOf(row)), SceneTone.Separator),
        label(Point(0, yOf(row) + 4), name, SceneAnchor.Start)
      )
    val dots = points.zipWithIndex.flatMap:
      case (DayPoint(_, Some(day)), index) =>
        List(day.breakfast, day.lunch, day.dinner).zipWithIndex.collect {
          case (count, row) if count > 0 =>
            SceneNode(SceneShape.Circle, rounded(columns.centre(index)), rounded(yOf(row)), SceneTone.Nutrition)
              .copy(radius = Some(rounded(Math.min(4.0, columns.pitch / 2.5))), mark = Some(index))
        }
      case (DayPoint(_, None), _) => Nil
    def dayText(point: DayPoint[MealDay]): String =
      point.value
        .filter(_.meals > 0)
        .fold("no meal logged"): day =>
          val main = MealRows.zip(List(day.breakfast, day.lunch, day.dinner)).collect {
            case (name, n) if n > 0 =>
              name.toLowerCase(Locale.ROOT)
          }
          val snacks = Option.when(day.snacks > 0)(ApiCopy.count(day.snacks, "snack"))
          (main ++ snacks.toList).mkString(", ")
    val logged = points.count(_.value.exists(_.meals > 0))
    Chart(
      id = "meals-by-day",
      title = "Meals per day",
      summary = s"Main meals logged per day, ${ApiCopy.rangeLabel(window)}. Meals on $logged of ${points.size} days.",
      scene = Scene(
        Width,
        PlotHeight + LabelRoom,
        rows ++ dots ++ dateLabels(points, MealLabelWidth) ++ points.indices.map(columns.hit)
      ),
      table = ChartTable(
        "Meals per day",
        List("Day", "Meals"),
        points.map(point => List(ApiCopy.dayLabel(point.date), dayText(point).capitalize))
      ),
      marks = points.map(point => s"${ApiCopy.dayLabel(point.date)}: ${dayText(point)}"),
      markDates = datesOf(points)
    )

  private def moodY(mean: Double): Double =
    MoodPlotTop + (MoodSteps - mean) / (MoodSteps - 1) * (PlotHeight - MoodPlotTop)

  private def moodLevels: List[SceneNode] =
    List(5 -> "Very pleasant", 3 -> "Neutral", 1 -> "Very unpleasant").flatMap: (step, name) =>
      List(
        line(Point(MoodLabelWidth, moodY(step.toDouble)), Point(Width, moodY(step.toDouble)), SceneTone.Separator),
        label(Point(0, moodY(step.toDouble) + 4), name, SceneAnchor.Start)
      )

  /** The mean of the daily means per week, drawn as the daily mood line is: a week without a check-in is a gap. */
  def moodByWeek(days: Map[LocalDate, MoodDay], weeks: List[DateRange], window: DateRange): Chart =
    val columns = Columns(MoodLabelWidth, weeks.size)
    val means = weeks.map: week =>
      val own = recordedIn(days, week).map(_.mean)
      Option.when(own.nonEmpty)(own.sum / own.size) -> own.size
    val indexed = means.map(_._1).zipWithIndex
    val joins = indexed.zip(indexed.drop(1)).collect { case ((Some(from), first), (Some(to), second)) =>
      line(Point(columns.centre(first), moodY(from)), Point(columns.centre(second), moodY(to)), SceneTone.Mood)
    }
    val dots = indexed.collect { case (Some(mean), index) =>
      SceneNode(SceneShape.Circle, rounded(columns.centre(index)), rounded(moodY(mean)), SceneTone.Mood)
        .copy(radius = Some(3.5), mark = Some(index))
    }
    val texts = means.map:
      case (Some(mean), n) => s"${TrendCopy.moodMean(mean)}, ${ApiCopy.count(n, "day")} with a check-in"
      case (None, _)       => "no check-in"
    Chart(
      id = "mood-by-week",
      title = "Mood per week",
      summary = s"Mood per week, ${ApiCopy.rangeLabel(window)}: the average of the days with a check-in. " +
        "A week without a check-in is a gap.",
      scene = Scene(
        Width,
        PlotHeight + LabelRoom,
        moodLevels ++ joins ++ dots ++ weekEnds(weeks, MoodLabelWidth) ++ weeks.indices.map(columns.hit)
      ),
      table = ChartTable(
        "Mood per week",
        List("Week", "Mood"),
        weeks.zip(texts).map((week, text) => List(weekLabel(week), text.capitalize))
      ),
      marks = weeks.zip(texts).map((week, text) => s"${weekLabel(week)}: $text")
    )

  /** Mood by day of the week (VISUALS section 9.2, supervisor decision of 2026-11-14): the mean of each weekday's
    * check-in days, shown from 3 such days. It describes the user's own days and draws no comparison between them.
    */
  def moodByWeekday(weekdays: List[WeekdayMood], window: DateRange): Chart =
    val columns = Columns(MoodLabelWidth, weekdays.size)
    val dots = weekdays.zipWithIndex.flatMap: (row, index) =>
      row.mean.shown.map: mean =>
        SceneNode(SceneShape.Circle, rounded(columns.centre(index)), rounded(moodY(mean)), SceneTone.Mood)
          .copy(radius = Some(4.0), mark = Some(index))
    val names = weekdays.zipWithIndex.map: (row, index) =>
      val short = row.day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
      label(Point(columns.centre(index), PlotHeight + 16.0), short, SceneAnchor.Middle)
    def text(row: WeekdayMood): String =
      row.mean.shown match
        case Some(mean)            => s"${TrendCopy.moodMean(mean)}, ${ApiCopy.count(row.days, "day")} with a check-in"
        case None if row.days == 0 => "no check-in"
        case None                  => s"${ApiCopy.count(row.days, "day")} with a check-in, an average needs 3"
    def name(row: WeekdayMood): String = row.day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    Chart(
      id = "mood-by-weekday",
      title = "Mood by day of the week",
      summary = s"Average mood by day of the week, ${ApiCopy.rangeLabel(window)}, from the days with a check-in. " +
        "A day of the week needs 3 such days for an average.",
      scene = Scene(Width, PlotHeight + LabelRoom, moodLevels ++ dots ++ names ++ weekdays.indices.map(columns.hit)),
      table = ChartTable(
        "Mood by day of the week",
        List("Day of the week", "Mood"),
        weekdays.map(row => List(name(row), text(row).capitalize))
      ),
      marks = weekdays.map(row => s"${name(row)}: ${text(row)}")
    )

  /** When each night began and ended (VISUALS IMG-12 as a scene): one bar per sleep episode on a clock from evening to
    * morning, naps outlined. `None` without an episode. Every time is the user's own check-in.
    */
  def sleepTimes(bars: List[SleepBar], window: DateRange): Option[Chart] =
    Option.when(bars.nonEmpty):
      val dates = window.dates
      val low = (bars.map(_.fromMinute).min / 60) * 60
      val high = Math.min(1440, ((bars.map(_.toMinute).max + 59) / 60) * 60)
      val span = Math.max(60, high - low).toDouble
      val columns = Columns(AxisWidth, dates.size)
      def yOf(minute: Int): Double = (minute - low) / span * PlotHeight
      val nodes = bars.flatMap: bar =>
        val index = dates.indexOf(bar.date)
        Option.when(index >= 0):
          val box =
            Box(columns.barStart(index), yOf(bar.fromMinute), columns.barWidth, yOf(bar.toMinute) - yOf(bar.fromMinute))
          val node = rect(box, SceneTone.Sleep)
            .copy(radius = Some(rounded(Math.min(2.0, columns.barWidth / 4))), mark = Some(index))
          if bar.isNap then node.copy(opacity = Some(0.45), style = Some(SceneStyle.Outlined)) else node
      def clockOf(axisMinute: Int): String =
        val minute = Math.floorMod(18 * 60 + axisMinute, 1440)
        f"${minute / 60}%02d:${minute % 60}%02d"
      val axis = List(low, high).flatMap: minute =>
        List(
          line(Point(AxisWidth, yOf(minute)), Point(Width, yOf(minute)), SceneTone.Separator),
          label(Point(0, yOf(minute) + 4), clockOf(minute), SceneAnchor.Start)
        )
      val points = dates.map(date => DayPoint(date, Option.empty[Unit]))
      def dayText(date: LocalDate): String =
        val own = bars.filter(_.date == date)
        if own.isEmpty then "no night logged"
        else
          own
            .map(bar => s"${if bar.isNap then "nap " else ""}${clockOf(bar.fromMinute)} to ${clockOf(bar.toMinute)}")
            .mkString(", ")
      val nights = bars.filterNot(_.isNap).map(_.date).distinct.size
      Chart(
        id = "sleep-times",
        title = "Sleep times",
        summary =
          s"Sleep times, ${ApiCopy.rangeLabel(window)}, from your check-ins: when each night began and ended, " +
            s"on a clock from evening to morning. Nights logged on $nights of ${dates.size} days.",
        scene = Scene(
          Width,
          PlotHeight + LabelRoom,
          axis ++ nodes ++ dateLabels(points, AxisWidth) ++ dates.indices.map(columns.hit)
        ),
        table = ChartTable(
          "Sleep times",
          List("Day", "Slept"),
          dates.map(date => List(ApiCopy.dayLabel(date), dayText(date).capitalize))
        ),
        marks = dates.map(date => s"${ApiCopy.dayLabel(date)}: ${dayText(date)}"),
        markDates = Some(dates.map(_.toString))
      )

  /** The baseline and the first and last date under it. */
  private def frame[A](points: List[DayPoint[A]], left: Double): List[SceneNode] =
    line(Point(left, PlotHeight), Point(Width, PlotHeight), SceneTone.Separator) :: dateLabels(points, left)

  private def dateLabels[A](points: List[DayPoint[A]], left: Double): List[SceneNode] =
    val baseline = PlotHeight + 16.0
    val first =
      points.headOption.map(point => label(Point(left, baseline), ApiCopy.dayMonth(point.date), SceneAnchor.Start))
    val last =
      points.lastOption.map(point => label(Point(Width, baseline), ApiCopy.dayMonth(point.date), SceneAnchor.End))
    first.toList ++ last.toList

  private def rect(box: Box, tone: SceneTone): SceneNode =
    SceneNode(
      SceneShape.Rect,
      rounded(box.x),
      rounded(box.y),
      tone,
      Some(rounded(box.width)),
      Some(rounded(box.height))
    )

  private def line(from: Point, to: Point, tone: SceneTone): SceneNode =
    SceneNode(
      SceneShape.Line,
      rounded(from.x),
      rounded(from.y),
      tone,
      x2 = Some(rounded(to.x)),
      y2 = Some(rounded(to.y))
    )

  private def label(at: Point, content: String, anchor: SceneAnchor): SceneNode =
    SceneNode(
      SceneShape.Text,
      rounded(at.x),
      rounded(at.y),
      SceneTone.Muted,
      text = Some(content),
      anchor = Some(anchor)
    )

  /** One decimal is finer than a device pixel at any width the scene is drawn at. */
  private def rounded(value: Double): Double = Math.round(value * 10.0) / 10.0
end Charts
