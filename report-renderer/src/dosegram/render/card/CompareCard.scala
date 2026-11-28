package dosegram.render.card

import dosegram.render.chart.Icons
import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.TextStyle
import dosegram.render.tokens.Tokens

import java.time.LocalDate

/** One period's head: its name as the caller words it, and its days, which the card's catalogue writes. */
final case class CompareColumn(label: String, from: LocalDate, to: LocalDate)

/** One period of an area: the caller's figure and its value on the row's scale, absent below the period's gate, and the
  * caller's recorded days ("5 days").
  */
final case class CompareCell(figure: Option[(String, Double)], days: String)

/** One area: both periods, the caller's difference in words when both show a figure, and how its values are drawn. */
final case class CompareRow(
    domain: Domain,
    earlier: CompareCell,
    later: CompareCell,
    difference: Option[String],
    scale: TrendScale
)

/** The values of IMG-27: the two periods, the earlier first, the rows in domain order and the text equivalent, written
  * by the caller from the same values.
  */
final case class CompareData(earlier: CompareColumn, later: CompareColumn, rows: Vector[CompareRow], text: String):
  require(rows.nonEmpty, "a comparison card has a row")

/** IMG-27, `card.compare.v1` (supervisor decision, 2026-11-28): two periods side by side, the earlier on the left, one
  * row per shown area with its icon and name, each period's figure and recorded days, and a bar from zero on the row's
  * own scale (mood a dot on its 1 to 5 track), so the two lengths compare without the colour (VA-PR-06). A period below
  * its gate has no figure and a dotted track, never a bar of zero (VA-PR-03). The difference is the caller's words of
  * VISUALS section 7.1; nothing ranks an area, colours a change or names a cause.
  */
object CompareCard:
  val TemplateId = "card.compare.v1"
  private val ColumnLeftPx = 140.0
  private val ColumnGapPx = 32.0
  private val LabelBaseline = 188.0
  private val DatesBaseline = 232.0
  private val RowsTop = 260.0
  private val RowPitchPx = 168.0
  private val NameBaselinePx = 40.0
  private val FigureBaselinePx = 96.0
  private val TrackTopPx = 112.0
  private val FigureGapPx = 16.0
  private val TrackPx = 28.0
  private val TrackRadius = 8.0
  private val MinimumBarPx = 8.0
  private val DotRadiusPx = 14.0
  private val LegendGapPx = 8.0
  private val LevelSteps = 5

  def render(data: CompareData, context: CardContext): CardOutput =
    val copy = context.copy.compare
    val probe = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val legendItems = Vector(
      Option.when(data.rows.exists(row => row.earlier.figure.isEmpty || row.later.figure.isEmpty))(
        LegendItem.of(box => emptyTrack("legend.compare.none", box.inset(8), context.tokens), copy.noFigure)
      ),
      Some(LegendItem.note(copy.scaleNote)),
      Option.when(data.rows.exists(_.scale == TrendScale.Levels))(LegendItem.note(copy.moodNote))
    ).flatten
    val legend = Legend.flow(legendItems, probe.width, context.fitter)
    // Rows close up when a legend of more lines (a longer language) would otherwise reach the footer.
    val room = probe.bodyBottom - RowsTop - LegendGapPx - legend.height
    val pitch = StrictMath.min(RowPitchPx, StrictMath.floor(room / data.rows.size))
    val rowsBottom = RowsTop + data.rows.size * pitch
    val frame = CardFrame(context, CardFrame.collapsing(rowsBottom + LegendGapPx + legend.height), probe.background)
    val columns = Columns(frame)
    val nodes = frame.header(copy.title, "")
      ++ heads(data.earlier, columns.earlier, columns.width, frame, context)
      ++ heads(data.later, columns.later, columns.width, frame, context)
      ++ data.rows.zipWithIndex.flatMap((row, index) => drawRow(row, RowsTop + index * pitch, columns, frame, context))
      ++ legend.nodes(Point(frame.left, rowsBottom + LegendGapPx), frame, context.fitter)
      :+ frame.footer
    val text = TextEquivalent.of(data.text, copy.discreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-compare.png", context.privacy == Privacy.Discreet)

  /** The left edges and the width of the two period columns. */
  private final case class Columns(earlier: Double, later: Double, width: Double)

  private object Columns:
    def apply(frame: CardFrame): Columns =
      val width = StrictMath.floor((frame.right - ColumnLeftPx - ColumnGapPx) / 2)
      Columns(ColumnLeftPx, ColumnLeftPx + width + ColumnGapPx, width)

  private def heads(
      column: CompareColumn,
      left: Double,
      width: Double,
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    Vector(
      frame.text(
        column.label,
        Point(left, LabelBaseline),
        TextLook(TextStyle.BodyMedium, frame.tokens.ink, Align.Start, TextKind.Label),
        width
      ),
      frame.text(
        context.copy.compare.dates(column.from, column.to),
        Point(left, DatesBaseline),
        TextLook(TextStyle.Label, frame.tokens.ink2, Align.Start, TextKind.Value),
        width
      )
    )

  private def drawRow(row: CompareRow, top: Double, columns: Columns, frame: CardFrame, context: CardContext) =
    val tokens = frame.tokens
    val domainName = context.copy.domainName(row.domain)
    val name = frame.text(
      domainName,
      Point(ColumnLeftPx, top + NameBaselinePx),
      frame.look(TextStyle.Label, tokens.ink2),
      columns.width
    )
    // The difference has the line's room after the name.
    val nameWidth = StrictMath.min(columns.width, context.fitter.width(domainName, TextStyle.Label))
    val difference = row.difference.toVector.map(words =>
      frame.text(
        words,
        Point(frame.right, top + NameBaselinePx),
        TextLook(TextStyle.Label, tokens.ink, Align.End, TextKind.Value),
        frame.right - ColumnLeftPx - nameWidth - ColumnGapPx
      )
    )
    val scale = (row.earlier.figure.map(_._2).toVector ++ row.later.figure.map(_._2).toVector :+ 1.0).max
    Vector(Icons.of(row.domain, Point(frame.left, top), tokens.hue(row.domain)), name) ++ difference ++
      cell(row, row.earlier, "earlier", columns.earlier, top, columns.width, scale, frame, context) ++
      cell(row, row.later, "later", columns.later, top, columns.width, scale, frame, context)

  private def cell(
      row: CompareRow,
      cell: CompareCell,
      side: String,
      left: Double,
      top: Double,
      width: Double,
      scale: Double,
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    val tokens = frame.tokens
    val role = row.domain.toString.toLowerCase(java.util.Locale.ROOT)
    // The days keep their whole width at the column's end; the figure has the rest.
    val daysWidth = StrictMath.min(width / 2, context.fitter.width(cell.days, TextStyle.Label))
    val days = frame.text(
      cell.days,
      Point(left + width, top + FigureBaselinePx),
      TextLook(TextStyle.Label, tokens.ink2, Align.End, TextKind.Value),
      daysWidth
    )
    val track = Box(left, top + TrackTopPx, width, TrackPx)
    cell.figure match
      case Some((figure, value)) =>
        val text = frame.text(
          figure,
          Point(left, top + FigureBaselinePx),
          TextLook(TextStyle.BodyStrong, tokens.ink, Align.Start, TextKind.Value),
          width - daysWidth - FigureGapPx
        )
        Vector(text, days, mark(s"compare.$role.$side", row, value, scale, track, tokens))
      case None => Vector(days, emptyTrack(s"compare.$role.none", track, tokens))

  /** A bar from zero on the row's scale, or for mood a dot on the 1 to 5 track; the track is the mark's own ground. */
  private def mark(role: String, row: CompareRow, value: Double, scale: Double, track: Box, tokens: Tokens): Node =
    val colour = tokens.hue(row.domain)
    val ground = Node.filledRect(track, TrackRadius, tokens.grid)
    val figure = row.scale match
      case TrendScale.FromZero =>
        val length = StrictMath.max(MinimumBarPx, track.width * value / scale)
        Node.filledRect(Box(track.x, track.y, length, track.height), TrackRadius, colour)
      case TrendScale.Levels =>
        val share = (StrictMath.min(LevelSteps, StrictMath.max(1.0, value)) - 1) / (LevelSteps - 1)
        val x = track.x + DotRadiusPx + share * (track.width - 2 * DotRadiusPx)
        Node.Circle(Point(x, track.y + track.height / 2), DotRadiusPx, Some(Paint.Solid(colour)), None)
    Node.Mark(MarkMeta(role, Set(Channel.Colour, Channel.Position)), Vector(ground, figure))

  private def emptyTrack(role: String, box: Box, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta(role, Set(Channel.Pattern)),
      Vector(Node.outlinedRect(box.inset(2), 4, Stroke.dotted(tokens.ink3)))
    )
end CompareCard
