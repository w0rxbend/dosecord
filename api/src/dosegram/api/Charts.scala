package dosegram.api

import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DayPoint
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MoodDay

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
      goal.map(_ => s" The goal was reached on ${recorded.count(_.isGoalDay)} of ${recorded.size} recorded days.")
    Chart(
      id = "water-by-day",
      title = "Water per day",
      summary = s"Water per day, ${ApiCopy.rangeLabel(window)}. Entries on ${recorded.size} of ${points.size} days." +
        reached.getOrElse(""),
      scene = Scene(
        Width,
        PlotHeight + LabelRoom,
        bars ++ goalLine ++ frame(points, AxisWidth) ++ points.indices.map(columns.hit)
      ),
      table = ChartTable(
        "Water per day",
        List("Day", "Water"),
        points.map(point => List(ApiCopy.dayLabel(point.date), point.value.fold("No entry")(waterOf)))
      ),
      marks = points.map(point => s"${ApiCopy.dayLabel(point.date)}: ${point.value.fold("no entry")(waterOf)}")
    )

  private def waterOf(day: HydrationDay): String = WaterCopy.volume(day.totalMl)

  /** The goal line is drawn only when every recorded day of the period had the same goal. */
  private def sharedGoal(points: List[DayPoint[HydrationDay]]): Option[Int] =
    points.flatMap(_.value).map(_.goalMl).distinct match
      case List(Some(goal)) => Some(goal)
      case _                => None

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
    val levels = List(5 -> "Very pleasant", 3 -> "Neutral", 1 -> "Very unpleasant").flatMap: (step, name) =>
      List(
        line(Point(MoodLabelWidth, yOf(step.toDouble)), Point(Width, yOf(step.toDouble)), SceneTone.Separator),
        label(Point(0, yOf(step.toDouble) + 4), name, SceneAnchor.Start)
      )
    val recorded = points.flatMap(_.value)
    Chart(
      id = "mood-by-day",
      title = "Mood per day",
      summary = s"Daily mood, ${ApiCopy.rangeLabel(window)}. Check-ins on ${recorded.size} of ${points.size} days. " +
        "A day without a check-in is a gap.",
      scene = Scene(
        Width,
        PlotHeight + LabelRoom,
        levels ++ joins ++ dots ++ dateLabels(points, MoodLabelWidth) ++ points.indices.map(columns.hit)
      ),
      table = ChartTable(
        "Mood per day",
        List("Day", "Daily mood", "Check-ins"),
        points.map: point =>
          List(
            ApiCopy.dayLabel(point.date),
            point.value.fold("No check-in")(ApiCopy.moodMean),
            point.value.fold("0")(_.n.toString)
          )
      ),
      marks = points.map: point =>
        val value =
          point.value.fold("no check-in")(day => s"${ApiCopy.moodMean(day)}, ${ApiCopy.count(day.n, "check-in")}")
        s"${ApiCopy.dayLabel(point.date)}: $value"
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
        title = "Dose outcomes",
        summary = s"Of ${ApiCopy.count(total, "scheduled dose")}: ${counts.mkString(", ")}.",
        scene = Scene(Width, OutcomeBarHeight, segments),
        table = ChartTable(
          "Dose outcomes in this period",
          List("Outcome", "Doses"),
          outcomes.map(outcome => List(outcome.name, outcome.doses.toString))
        ),
        marks = counts
      )

  /** One part of the outcome bar. Recorded doses are filled; the others are outlined, and none is red (MA-DS-3). */
  private final case class Outcome(name: String, doses: Int, tone: SceneTone, look: OutcomeLook):
    def opacity: Option[Double] = look.opacity
    def style: Option[SceneStyle] = look.style

  private final case class OutcomeLook(opacity: Option[Double], style: Option[SceneStyle])

  // The status words are those of BOT_UX section 22.4: a missed dose is "not confirmed", an unknown one "no record".
  private def outcomesOf(buckets: DoseBuckets): List[Outcome] =
    val solid = OutcomeLook(None, None)
    val outlined = Some(SceneStyle.Outlined)
    List(
      Outcome("on time", buckets.takenOnTime, SceneTone.Recorded, solid),
      Outcome("late", buckets.takenLate, SceneTone.Recorded, OutcomeLook(Some(0.55), None)),
      Outcome("skipped", buckets.skipped, SceneTone.Muted, solid),
      Outcome("not confirmed", buckets.missed, SceneTone.Ink, OutcomeLook(Some(0.25), outlined)),
      Outcome("no record", buckets.unknown, SceneTone.Separator, OutcomeLook(None, outlined)),
      Outcome("still due", buckets.open, SceneTone.Separator, OutcomeLook(Some(0.4), Some(SceneStyle.Dashed)))
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
      title = "Doses by day",
      summary = s"Scheduled doses by day, ${ApiCopy.rangeLabel(window)}. Every scheduled dose was recorded as taken " +
        s"on $complete of ${withDoses.size} days with doses.",
      scene = Scene(Width, PlotHeight + LabelRoom, bars ++ frame(days, 0) ++ days.indices.map(columns.hit)),
      table = ChartTable(
        "Doses recorded as taken, by day",
        List("Day", "Taken", "Scheduled"),
        days.map: point =>
          val taken = point.value.fold("0")(_.resolved.taken.toString)
          List(ApiCopy.dayLabel(point.date), taken, point.value.fold("0")(scheduledIn(_).toString))
      ),
      marks = days.map(point => s"${ApiCopy.dayLabel(point.date)}: ${point.value.fold("nothing scheduled")(dosesOf)}")
    )

  /** The doses a day was scheduled to have: the answered, the unanswered and the open ones. As-needed logs are not. */
  def scheduledIn(buckets: DoseBuckets): Int = buckets.resolved.resolved + buckets.unknown + buckets.open

  /** The doses of a day in words: "3 of 4 taken, 1 not confirmed". */
  def dosesOf(buckets: DoseBuckets): String =
    val resolved = buckets.resolved
    val rest = List(
      Option.when(resolved.skipped > 0)(s"${resolved.skipped} skipped"),
      Option.when(resolved.missed > 0)(s"${resolved.missed} not confirmed"),
      Option.when(buckets.unknown > 0)(s"${buckets.unknown} no record"),
      Option.when(buckets.open > 0)(s"${buckets.open} still due")
    ).flatten
    (s"${resolved.taken} of ${scheduledIn(buckets)} taken" :: rest).mkString(", ")

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
