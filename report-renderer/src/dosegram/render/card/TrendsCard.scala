package dosegram.render.card

import dosegram.render.chart.Bars
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

/** How the values of one row are drawn: bars from zero on the row's own scale, or a dot per bucket on the 1 to 5 mood
  * scale, where a bar from zero would read as an amount.
  */
enum TrendScale:
  case FromZero
  case Levels

/** One domain of IMG-23: its name comes from the catalogue, its figure from the caller (the same words as the chat and
  * the Mini App), and one value per bucket, oldest first, `None` for a bucket without a record.
  */
final case class TrendRow(domain: Domain, figure: String, buckets: Vector[Option[Double]], scale: TrendScale)

/** The values of IMG-23: the period, whether its buckets are weeks, the rows in domain order and the text equivalent,
  * written by the caller from the same values.
  */
final case class TrendsData(from: LocalDate, to: LocalDate, byWeek: Boolean, rows: Vector[TrendRow], text: String):
  require(rows.nonEmpty, "a trends card has a row")

/** IMG-23, `card.trends.v1` (ROADMAP G9.4, G9.5): the trend of every shown domain over 30 or 90 days as small
  * multiples, one row per domain with its own scale (VISUALS section 3.2): the domain icon and name, the caller's
  * headline figure, and a bar per day or week from zero; mood is a dot on its 1 to 5 scale. A bucket without a record
  * is a dotted slot, never a bar of zero (VA-PR-03). Nothing ranks a row, colours a change or names a cause; the
  * comparison with the previous period is in the text equivalent.
  */
object TrendsCard:
  val TemplateId = "card.trends.v1"
  private val PeriodBaseline = 180.0
  private val RowsTop = 212.0
  private val RowPitchPx = 188.0
  private val NameLeftPx = 140.0
  private val NameBaselinePx = 40.0
  private val FigureBaselinePx = 92.0
  private val PlotTopPx = 112.0
  private val PlotHeightPx = 56.0
  private val BarShare = 0.62
  private val MinimumBarPx = 6.0
  private val DotRadiusPx = 9.0
  private val LegendGapPx = 8.0
  private val LevelSteps = 5
  private val MinimumSlotPx = 24.0

  def render(data: TrendsData, context: CardContext): CardOutput =
    val copy = context.copy
    val legendItems = Vector(
      Option.when(data.rows.exists(_.buckets.contains(None)))(
        LegendItem.of(box => emptySlot("legend.none", box.inset(8), context.tokens), copy.noRecordMark)
      ),
      Some(LegendItem.note(copy.trendsScaleNote))
    ).flatten
    val probe = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val legend = Legend.flow(legendItems, probe.width, context.fitter)
    // Rows close up when a legend of two rows (a longer language) would otherwise reach the footer.
    val room = probe.bodyBottom - RowsTop - LegendGapPx - legend.height
    val pitch = StrictMath.min(RowPitchPx, StrictMath.floor(room / data.rows.size))
    val rowsBottom = RowsTop + data.rows.size * pitch
    val frame = CardFrame(context, CardFrame.collapsing(rowsBottom + LegendGapPx + legend.height), probe.background)
    val periodLook = TextLook(TextStyle.BodyMedium, frame.tokens.ink2, Align.Start, TextKind.Value)
    val nodes = frame.header(copy.trendsTitle, "")
      ++ Vector(
        frame.text(
          copy.trendsPeriod(data.from, data.to, data.byWeek),
          Point(frame.left, PeriodBaseline),
          periodLook,
          frame.width
        )
      )
      ++ data.rows.zipWithIndex.flatMap((row, index) => drawRow(row, RowsTop + index * pitch, frame, context))
      ++ legend.nodes(Point(frame.left, rowsBottom + LegendGapPx), frame, context.fitter)
      :+ frame.footer
    val text = TextEquivalent.of(data.text, copy.trendsDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-trends.png", context.privacy == Privacy.Discreet)

  private def drawRow(row: TrendRow, top: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val tokens = frame.tokens
    val textWidth = frame.right - NameLeftPx
    Vector(
      Icons.of(row.domain, Point(frame.left, top), tokens.hue(row.domain)),
      frame.text(
        context.copy.domainName(row.domain),
        Point(NameLeftPx, top + NameBaselinePx),
        frame.look(TextStyle.Label, tokens.ink2),
        textWidth
      ),
      frame.text(
        row.figure,
        Point(NameLeftPx, top + FigureBaselinePx),
        TextLook(TextStyle.BodyMedium, tokens.ink, Align.Start, TextKind.Value),
        textWidth
      )
    ) ++ plot(row, top + PlotTopPx, frame)

  private def plot(row: TrendRow, top: Double, frame: CardFrame): Vector[Node] =
    val tokens = frame.tokens
    val pitch = frame.width / row.buckets.size
    val slot = StrictMath.floor(pitch * BarShare)
    val bottom = top + PlotHeightPx
    val colour = tokens.hue(row.domain)
    val role = row.domain.toString.toLowerCase(java.util.Locale.ROOT)
    val scale = (row.buckets.flatten :+ 1.0).max
    val baseline = Node.Line(Point(frame.left, bottom), Point(frame.right, bottom), Stroke.butt(tokens.grid, 4))
    baseline +: row.buckets.zipWithIndex.map { (bucket, index) =>
      val centre = frame.left + (index + 0.5) * pitch
      bucket match
        case Some(value) =>
          row.scale match
            case TrendScale.FromZero =>
              val height = StrictMath.max(MinimumBarPx, PlotHeightPx * value / scale)
              val bar = Bars.upright(Box(centre - slot / 2, bottom - height, slot, height), colour)
              Node.Mark(MarkMeta(s"trends.$role.bucket", Set(Channel.Colour, Channel.Position)), Vector(bar))
            case TrendScale.Levels =>
              val share = (StrictMath.min(LevelSteps, StrictMath.max(1.0, value)) - 1) / (LevelSteps - 1)
              val y = bottom - DotRadiusPx - share * (PlotHeightPx - 2 * DotRadiusPx)
              val dot = Node.Circle(Point(centre, y), DotRadiusPx, Some(Paint.Solid(colour)), None)
              Node.Mark(MarkMeta(s"trends.$role.bucket", Set(Channel.Colour, Channel.Position)), Vector(dot))
        case None =>
          // A slot is never narrower than the legend can show it, nor taller than half the plot.
          val side = StrictMath.min(StrictMath.max(slot, MinimumSlotPx), PlotHeightPx / 2)
          emptySlot(s"trends.$role.none", Box(centre - side / 2, bottom - side, side, side), tokens)
    }

  private def emptySlot(role: String, box: Box, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta(role, Set(Channel.Pattern)),
      Vector(Node.outlinedRect(box.inset(2), 4, Stroke.dotted(tokens.ink3)))
    )
