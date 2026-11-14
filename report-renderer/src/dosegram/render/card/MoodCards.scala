package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.MoodState
import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle
import dosegram.render.tokens.Tokens

import java.time.DayOfWeek
import java.time.YearMonth

/** IMG-11: one calendar month of daily mood. A day is the quantised daily mean, or `None` without a check-in. */
final case class MoodCalendarData(month: YearMonth, weekStart: DayOfWeek, days: Vector[Option[MoodState]]):
  require(days.size == month.lengthOfMonth, s"${days.size} days given for $month")
  def checkInDays: Int = days.count(_.isDefined)

/** The grid of a calendar month: seven slots a row from the week start, one cell per day. `numberPx` is the room under
  * a cell for its day number, zero where the days carry no numbers.
  */
final private[card] case class MonthGrid(
    month: YearMonth,
    weekStart: DayOfWeek,
    left: Double,
    top: Double,
    slotPx: Double,
    cellPx: Double,
    numberPx: Double,
    rowGapPx: Double
):
  val offset: Int = Math.floorMod(month.atDay(1).getDayOfWeek.getValue - weekStart.getValue, 7)
  val weeks: Int = (offset + month.lengthOfMonth + 6) / 7
  def rowPx: Double = cellPx + numberPx + rowGapPx
  def bottom: Double = top + (weeks - 1) * rowPx + cellPx + numberPx
  def right: Double = left + 7 * slotPx

  /** The cell of day `day` (1-based). */
  def cell(day: Int): Box =
    val slot = offset + day - 1
    Box(left + (slot % 7) * slotPx + (slotPx - cellPx) / 2, top + (slot / 7) * rowPx, cellPx, cellPx)

  def columnCentre(column: Int): Double = left + (column + 0.5) * slotPx

private[card] object MonthGrid:
  private val GridUnitPx = 4.0

  /** The largest cell, up to `maximumCellPx`, with which the grid ends at `bottom` at the latest. A slot is `slotPx`
    * wide, or as wide as its cell and the row gap when `slotPx` is zero.
    */
  def fitting(
      month: YearMonth,
      weekStart: DayOfWeek,
      left: Double,
      top: Double,
      bottom: Double,
      maximumCellPx: Double,
      numberPx: Double,
      rowGapPx: Double,
      slotPx: Double = 0
  ): MonthGrid =
    val probe = MonthGrid(month, weekStart, left, top, slotPx, 0, numberPx, rowGapPx)
    val perRow = (bottom - top + rowGapPx) / probe.weeks - numberPx - rowGapPx
    val cell = StrictMath.min(maximumCellPx, StrictMath.floor(perRow / GridUnitPx) * GridUnitPx)
    probe.copy(slotPx = if slotPx > 0 then slotPx else cell + rowGapPx, cellPx = cell)

  /** The weekday names over the columns: short names on wide slots, initials on narrow ones. */
  def weekdays(grid: MonthGrid, baseline: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    (0 until 7).toVector.map { column =>
      val day = grid.weekStart.plus(column.toLong)
      val name = if grid.slotPx >= 96 then context.copy.weekdayShort(day) else context.copy.weekdayInitial(day)
      frame.text(name, Point(grid.columnCentre(column), baseline), look, grid.slotPx)
    }

/** IMG-11, `card.mood.calendar.v1` (VISUALS section 6.12): a mini blob in its state for each day with a check-in and a
  * small grid dot for a day without one, which is a gap and never a low mood (VA-PR-03). The legend shows the seven
  * valence states in order with the two end names, and the dot when a day has none. [[panel]] draws the same calendar
  * into page 2 of the monthly report.
  */
object MoodCalendarCard:
  val TemplateId = "card.mood.calendar.v1"
  private val PanelTop = 148.0
  private val PanelPadPx = 28.0
  private val PanelRadius = 40.0
  private val WeekdayBaseline = 204.0
  private val GridTop = 228.0
  private val NumberPx = 40.0
  private val RowGapPx = 20.0
  private val SlotPx = 128.0
  private val MaximumCellPx = 96.0
  private val GapDotPx = 6.0
  private val LegendRowPx = 60.0
  private val GridToLegendPx = 28.0
  private val FactsGapPx = 56.0

  /** How large the legend's blobs are and how far apart. */
  final private case class LegendScale(radius: Double, step: Double)

  private val CardLegend = LegendScale(24, 72)
  private val PanelLegend = LegendScale(18, 48)

  def render(calendar: MoodCalendarData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val copy = context.copy
    val legendRows = this.legendRows(calendar, CardLegend, frame.width - 2 * PanelPadPx, context)
    val gridLimit = frame.bodyBottom - FactsGapPx - PanelPadPx - legendRows * LegendRowPx - GridToLegendPx
    val grid = MonthGrid.fitting(
      calendar.month,
      calendar.weekStart,
      frame.left + (frame.width - 7 * SlotPx) / 2,
      GridTop,
      gridLimit,
      MaximumCellPx,
      NumberPx,
      RowGapPx,
      SlotPx
    )
    val legendTop = grid.bottom + GridToLegendPx
    val panelBottom = legendTop + legendRows * LegendRowPx + PanelPadPx - 20
    val panel =
      Node.filledRect(Box(frame.left, PanelTop, frame.width, panelBottom - PanelTop), PanelRadius, frame.tokens.panel)
    val numberLook = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    val numbers = (1 to calendar.month.lengthOfMonth).toVector.map { day =>
      val box = grid.cell(day)
      frame.text(day.toString, Point(box.center.x, box.bottom + NumberPx), numberLook, SlotPx)
    }
    val factLook = TextLook(TextStyle.Body, frame.tokens.ink, Align.Start, TextKind.Value)
    val nodes = frame.header(copy.moodCalendarTitle, copy.monthYear(calendar.month))
      ++ Vector(panel)
      ++ MonthGrid.weekdays(grid, WeekdayBaseline, frame, context)
      ++ days(calendar, grid, frame.tokens)
      ++ numbers
      ++ legend(
        calendar,
        Point(frame.left + PanelPadPx, legendTop),
        CardLegend,
        frame.right - PanelPadPx,
        frame,
        context
      )
      :+ frame.text(
        copy.moodCalendarFact(calendar.checkInDays),
        Point(frame.left, panelBottom + FactsGapPx),
        factLook,
        frame.width
      )
      :+ frame.footer
    val text = TextEquivalent.of(copy.moodCalendarText(calendar), copy.moodCalendarDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-mood-month.png", context.privacy == Privacy.Discreet)

  /** The calendar's days drawn into a grid laid out by another card, with the legend and the fact line starting at
    * `legendOrigin`, to the end side of the grid.
    */
  private[card] def panelDays(
      calendar: MoodCalendarData,
      grid: MonthGrid,
      legendOrigin: Point,
      right: Double,
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    val factLook = TextLook(TextStyle.Label, frame.tokens.ink, Align.Start, TextKind.Value)
    val legendNodes = legend(calendar, legendOrigin, PanelLegend, right, frame, context)
    days(calendar, grid, frame.tokens) ++ legendNodes :+ frame.text(
      context.copy.moodCalendarFact(calendar.checkInDays),
      Point(
        legendOrigin.x,
        legendOrigin.y + legendRows(calendar, PanelLegend, right - legendOrigin.x, context) * LegendRowPx + 36
      ),
      factLook,
      right - legendOrigin.x
    )

  /** The most height the panel form of the legend and its fact line need: the names on rows of their own. */
  private[card] def panelLegendPx(calendar: MoodCalendarData): Double =
    (if calendar.days.contains(None) then 4 else 3) * LegendRowPx + 56

  private def legendRows(calendar: MoodCalendarData, scale: LegendScale, width: Double, context: CardContext): Int =
    (if namesSideBySide(scale, width, context) then 2 else 3) + (if calendar.days.contains(None) then 1 else 0)

  private def blobRowWidth(scale: LegendScale): Double = (MoodState.ValenceLevels - 1) * scale.step + 2 * scale.radius

  private def namesWidth(context: CardContext): Double =
    Vector(1, MoodState.ValenceLevels)
      .map(level => context.fitter.width(context.copy.valenceName(level), TextStyle.Label))
      .sum

  /** The two end names fit side by side when the room the legend has holds both with a gap between them. */
  private def namesSideBySide(scale: LegendScale, width: Double, context: CardContext): Boolean =
    namesWidth(context) + 32 <= width && scale == CardLegend

  private def days(calendar: MoodCalendarData, grid: MonthGrid, tokens: Tokens): Vector[Node] =
    calendar.days.zipWithIndex.map { (state, index) =>
      val box = grid.cell(index + 1)
      state match
        case Some(mood) => miniBlob(mood, box, tokens)
        case None       => gapDot(box.center, tokens)
    }

  /** A blob fitted to a cell, its ground mark inside the cell. */
  private[card] def miniBlob(state: MoodState, box: Box, tokens: Tokens): Node =
    val radius = box.height / (1 + BlobFigure.GroundBottomShare)
    BlobFigure.of(state, BlobFrame(Point(box.center.x, box.y + radius), radius), tokens)

  private def gapDot(centre: Point, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta("mood.calendar.none", Set(Channel.Shape, Channel.Label)),
      Vector(Node.filledCircle(centre, GapDotPx, tokens.ink3))
    )

  /** Row 1: the seven valence states in order. Then the two end names: side by side under the two ends when they fit,
    * else one row each, the first under the start and the last ending under the end. Last, when a day has no check-in,
    * the dot and its name.
    */
  private def legend(
      calendar: MoodCalendarData,
      origin: Point,
      scale: LegendScale,
      right: Double,
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    val tokens = frame.tokens
    val copy = context.copy
    val sideBySide = namesSideBySide(scale, right - origin.x, context)
    // Side by side, the row of blobs spans the two names, so each end name sits under its own end of the scale.
    val span = if sideBySide then StrictMath.max(blobRowWidth(scale), namesWidth(context) + 32) else blobRowWidth(scale)
    val step = (span - 2 * scale.radius) / (MoodState.ValenceLevels - 1)
    val blobs = (1 to MoodState.ValenceLevels).toVector.map { valence =>
      val centre = Point(origin.x + scale.radius + (valence - 1) * step, origin.y + scale.radius)
      BlobFigure.of(MoodState(valence, 3), BlobFrame(centre, scale.radius), tokens)
    }
    val endRight = origin.x + span
    val firstBaseline = origin.y + LegendRowPx + 28
    val lastBaseline = if sideBySide then firstBaseline else firstBaseline + LegendRowPx
    val nameLook = frame.look(TextStyle.Label, tokens.ink2)
    val ends = Vector(
      frame.text(copy.valenceName(1), Point(origin.x, firstBaseline), nameLook, right - origin.x),
      frame.text(
        copy.valenceName(MoodState.ValenceLevels),
        Point(endRight, lastBaseline),
        TextLook(TextStyle.Label, tokens.ink2, Align.End, TextKind.Label),
        right - origin.x
      )
    )
    val gap = Option.when(calendar.days.contains(None)) {
      val rowTop = lastBaseline + LegendRowPx - 28
      Vector(
        gapDot(Point(origin.x + scale.radius, rowTop + 14), tokens),
        frame.text(
          copy.noCheckIn,
          Point(origin.x + 2 * scale.radius + 12, rowTop + 28),
          nameLook,
          right - origin.x - 2 * scale.radius - 12
        )
      )
    }
    blobs ++ ends ++ gap.getOrElse(Vector.empty)
end MoodCalendarCard
