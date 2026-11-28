package dosegram.api

import dosegram.api.Charts.Box
import dosegram.api.Charts.Point
import dosegram.core.domain.copy.Formats
import dosegram.core.domain.copy.YearCopy
import dosegram.core.domain.copy.tr
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.TrendDomain

import java.time.LocalDate
import java.time.YearMonth

/** The year as a calendar heat strip (MINI_APP section 7.11, the Year tab; VISUALS IMG-30 as a scene): one cell per
  * week, a track that fills from the bottom in the area's hue to the share of the week's days with a record, at least 8
  * px when it has one, so the height carries the count without the colour (VA-PR-06); a week without a record is a thin
  * outlined ring, never a fill of zero (VA-PR-03). The cells record logging only: no value, level or goal enters them,
  * nothing ranks a week and nothing joins weeks into a run.
  */
object YearCharts:
  private val Width = Charts.Width
  private val LabelRoom = 18.0
  private val TrackHeight = 36.0
  private val MinimumFill = 8.0
  private val RingHeight = 4.0
  private val CellShare = 0.7

  /** The weeks of `window` for one area whose recorded days are `recorded`. The area is never medication. */
  def byWeek(domain: TrendDomain, recorded: Set[LocalDate], weeks: List[DateRange], window: DateRange): Chart =
    require(domain != TrendDomain.Medication, "medication is never drawn by day or week (R-GAM-2)")
    val pitch = Width.toDouble / weeks.size
    val counts = weeks.map(week => week.dates.count(recorded))
    val cells = weeks.zip(counts).zipWithIndex.flatMap { case ((week, count), index) =>
      val column = Box(index * pitch, LabelRoom, pitch, TrackHeight)
      val hit = Charts.rect(column, SceneTone.Separator).copy(shape = SceneShape.Hit, mark = Some(index))
      cell(column, count, week.days, toneOf(domain)) :+ hit
    }
    val texts = weeks.zip(counts).map((week, count) => s"${ApiCopy.rangeLabel(week)}: ${daysText(count, week.days)}")
    val total = recorded.count(window.contains)
    Chart(
      id = s"year-weeks-${InsightYears.domainOf(domain).wire}",
      title = title,
      summary = tr(
        s"${YearCopy.areaName(domain)}, ${window.start.getYear}: days with a record week by week, $total of " +
          s"${window.days} days. A taller cell is a week with more days with a record; a ring is a week without one.",
        s"${YearCopy.areaName(domain)}, ${window.start.getYear}: дні із записом тиждень за тижнем, $total з " +
          s"${Charts.UkDaysOf.count(window.days)}. Вища клітинка — тиждень, де більше днів із записом; кільце — " +
          "тиждень без записів."
      ),
      scene = Scene(Width, (LabelRoom + TrackHeight).toInt + 2, monthInitials(weeks, pitch) ++ cells),
      table = ChartTable(
        title,
        tr(List("Week", "Days with a record"), List("Тиждень", "Дні із записом")),
        weeks.zip(counts).map((week, count) => List(ApiCopy.rangeLabel(week), daysText(count, week.days).capitalize))
      ),
      marks = texts
    )

  /** One week in its column: a track filled to the share of its days with a record, or a ring without one. */
  private def cell(column: Box, count: Int, days: Int, tone: SceneTone): List[SceneNode] =
    val width = Math.max(2.0, column.width * CellShare)
    val x = column.x + (column.width - width) / 2
    val bottom = column.y + column.height
    if count == 0 then
      List(
        Charts
          .rect(Box(x, bottom - RingHeight, width, RingHeight), SceneTone.Muted)
          .copy(style = Some(SceneStyle.Outlined))
      )
    else
      val height = Math.max(MinimumFill, column.height * count / days)
      List(
        Charts.rect(Box(x, column.y, width, column.height), SceneTone.Separator),
        Charts.rect(Box(x, bottom - height, width, height), tone)
      )

  private def title: String = tr("Days with a record, week by week", "Дні із записом, тиждень за тижнем")

  private def daysText(count: Int, of: Int): String =
    if count == 0 then tr("no day with a record", "жодного дня із записом")
    else tr(s"$count of $of days with a record", s"$count з ${Charts.UkDaysOf.count(of)} із записом")

  /** A month's initial over the week that holds its first day. */
  private def monthInitials(weeks: List[DateRange], pitch: Double): List[SceneNode] =
    weeks.zipWithIndex.flatMap: (week, index) =>
      week.dates
        .find(_.getDayOfMonth == 1)
        .map: first =>
          val initial = YearCopy.monthName(YearMonth.from(first)).take(1).toUpperCase(Formats.locale)
          Charts.label(Point(index * pitch + pitch / 2, LabelRoom - 6), initial, SceneAnchor.Middle)

  private def toneOf(domain: TrendDomain): SceneTone =
    domain match
      case TrendDomain.Hydration  => SceneTone.Hydration
      case TrendDomain.Mood       => SceneTone.Mood
      case TrendDomain.Sleep      => SceneTone.Sleep
      case TrendDomain.Nutrition  => SceneTone.Nutrition
      case TrendDomain.Medication => SceneTone.Medication
end YearCharts
