package dosegram.render.card

import dosegram.render.chart.DoseColumn
import dosegram.render.chart.DoseGlyph
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.StripLine
import dosegram.render.copy.StripLines
import dosegram.render.scene.Align
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.TextKind
import dosegram.render.text.FittedText
import dosegram.render.text.TextFitter
import dosegram.render.tokens.TextStyle

/** IMG-07, `card.med.strip.v1`: medications compared over the same 7 or 14 days, one row each, in registry order. At
  * most six rows per image; more medications paginate. Names appear only at the named privacy level; otherwise rows are
  * "Item 1" onward (VA-PV-03). A long name takes the condensed width, then a second line, then an ellipsis (VA-TY-04).
  */
object MedicationStripCard:
  val TemplateId = "card.med.strip.v1"
  val MaximumRowsPerPage: Int = 6
  private val PageMarkerBaseline = 166.0
  private val WeekdayBaseline = 212.0
  private val RowsTop = 240.0
  private val NameLinePx = 52.0
  private val NameToCellsPx = 16.0
  private val RowGapPx = 28.0
  private val MaximumPitchPx = 76.0
  private val CellGapPx = 12.0
  private val SummaryWidthPx = 340.0
  private val LegendToFooterPx = 76.0
  private val WeekdayHeightPx = 32.0
  private val EmptyStatePx = 60.0

  /** A medication row prepared for drawing: its fitted label, its summary and its measured height. */
  final private case class Row(
      label: String,
      name: FittedText,
      summary: String,
      medication: MedicationRow,
      cellPx: Double
  ):
    private def stack: Int = StrictMath.max(1, medication.days.map(day => DoseColumn.rowsFor(day.size)).max)
    def cellsTop: Double = name.lines.size * NameLinePx + NameToCellsPx
    def height: Double = cellsTop + stack * cellPx + (stack - 1) * DoseColumn.GapPx

  /** One image per page; a strip with no medication still yields one page. */
  def render(strip: StripData, context: CardContext): Vector[CardOutput] =
    val probe = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val cellPx = StrictMath.min(MaximumPitchPx, probe.width / strip.dayCount) - CellGapPx
    val legend = Legend.flow(legendItems(strip, probe, context), probe.width, context.fitter)
    val lowestLegendTop = probe.footerBaseline - LegendToFooterPx - legend.height
    val rows = strip.rows.zipWithIndex.map((medication, index) => row(medication, index + 1, cellPx, probe, context))
    val pages = paginate(rows, lowestLegendTop - RowGapPx - RowsTop)
    val period = context.copy.dateRange(strip.from, strip.from.plusDays(strip.dayCount - 1L))
    pages.zipWithIndex.map { (pageRows, pageIndex) =>
      val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
      val rowsBottom = RowsTop + StrictMath.max(EmptyStatePx, pageRows.map(_.height + RowGapPx).sum)
      val legendTop = StrictMath.min(lowestLegendTop, rowsBottom + RowGapPx)
      // A page with few rows leaves room under its legend: the body moves down so the room is shared around it.
      val shift = frame.balance(WeekdayBaseline - WeekdayHeightPx, legendTop + legend.height)
      val nodes = frame.header(context.copy.stripTitle, period)
        ++ weekdays(strip, WeekdayLine(cellPx, WeekdayBaseline + shift), frame, context)
        ++ drawRows(pageRows, RowsTop + shift, frame)
        ++ emptyState(pageRows, RowsTop + shift, frame, context)
        ++ legend.nodes(Point(frame.left, legendTop + shift), frame, context.fitter)
        ++ pageMarker(pageIndex + 1, pages.size, frame, context)
        :+ frame.footer
      val text = TextEquivalent.of(context.copy.stripText(lines(strip, pageRows)), context.copy.stripDiscreet, context)
      CardOutput(
        frame.scene(TemplateId, nodes),
        text,
        "dosegram-medication-days.png",
        context.privacy == Privacy.Discreet
      )
    }

  private def row(
      medication: MedicationRow,
      position: Int,
      cellPx: Double,
      frame: CardFrame,
      context: CardContext
  ): Row =
    val copy = context.copy
    val name = if context.privacy.showsNames then medication.name else copy.itemLabel(position)
    val label = TextFitter.sanitised(if medication.isAsNeeded then copy.asNeededName(name) else name)
    val counts = DoseCounts.of(medication.days.flatten)
    val summary =
      if medication.isAsNeeded then copy.logsOnDays(counts.asNeeded, medication.days.count(_.nonEmpty))
      else if counts.counted == 0 then copy.noData
      else copy.takenOf(counts.taken, counts.counted)
    val fitted = context.fitter.twoLines(label, TextStyle.BodyMedium, frame.width - SummaryWidthPx - 20)
    Row(label, fitted, summary, medication, cellPx)

  // Greedy: a page takes rows while they fit its height and its row limit; every page takes at least one row.
  private def paginate(rows: Vector[Row], availablePx: Double): Vector[Vector[Row]] =
    rows.foldLeft(Vector(Vector.empty[Row])) { (pages, next) =>
      val current = pages.last
      val used = current.map(_.height + RowGapPx).sum
      val fits = current.size < MaximumRowsPerPage && used + next.height <= availablePx
      if current.isEmpty || fits then pages.init :+ (current :+ next) else pages :+ Vector(next)
    }

  private def drawRows(rows: Vector[Row], firstTop: Double, frame: CardFrame): Vector[Node] =
    val tops = rows.scanLeft(firstTop)((top, row) => top + row.height + RowGapPx)
    rows.zip(tops).flatMap { (row, top) =>
      val summaryLook = TextLook(TextStyle.Label, frame.tokens.ink2, Align.End, TextKind.Value)
      val name = row.name.lines.zipWithIndex.map { (line, index) =>
        Node.label(line, Point(frame.left, top + 40 + index * NameLinePx), row.name.style, frame.tokens.ink)
      }
      val columns = row.medication.days.zipWithIndex.flatMap { (statuses, day) =>
        val origin = Point(frame.left + day * (row.cellPx + CellGapPx), top + row.cellsTop)
        DoseColumn.of(statuses, origin, row.cellPx, frame.tokens)
      }
      (name :+ frame.text(row.summary, Point(frame.right, top + 40), summaryLook, SummaryWidthPx)) ++ columns
    }

  private def emptyState(rows: Vector[Row], top: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = frame.look(TextStyle.Body, frame.tokens.ink2)
    Option
      .when(rows.isEmpty)(frame.text(context.copy.stripEmpty, Point(frame.left, top + 44), look, frame.width))
      .toVector

  /** The line of weekday initials over the day columns. */
  final private case class WeekdayLine(cellPx: Double, baseline: Double)

  private def weekdays(strip: StripData, line: WeekdayLine, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    (0 until strip.dayCount).toVector.map { day =>
      val initial = context.copy.weekdayInitial(strip.from.plusDays(day.toLong).getDayOfWeek)
      val x = frame.left + day * (line.cellPx + CellGapPx) + line.cellPx / 2
      frame.text(initial, Point(x, line.baseline), look, line.cellPx + CellGapPx)
    }

  // One entry per status present anywhere in the strip, so every page carries the same legend.
  private def legendItems(strip: StripData, frame: CardFrame, context: CardContext): Vector[LegendItem] =
    val present = strip.rows.flatMap(_.days.flatten).toSet
    DoseStatus.values.toVector.filter(present).map { status =>
      LegendItem.of(box => DoseGlyph.of(status, box, frame.tokens), context.copy.statusName(status))
    }

  private def pageMarker(page: Int, pageCount: Int, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink2, Align.End, TextKind.Axis)
    Option
      .when(pageCount > 1)(
        frame.text(context.copy.pageOf(page, pageCount), Point(frame.right, PageMarkerBaseline), look, 240)
      )
      .toVector

  private def lines(strip: StripData, rows: Vector[Row]): StripLines =
    val stripLines = rows.map { row =>
      val asNeededDays = Option.when(row.medication.isAsNeeded)(row.medication.days.count(_.nonEmpty))
      StripLine(row.label, DoseCounts.of(row.medication.days.flatten), asNeededDays)
    }
    StripLines(strip.from, strip.from.plusDays(strip.dayCount - 1L), stripLines)
