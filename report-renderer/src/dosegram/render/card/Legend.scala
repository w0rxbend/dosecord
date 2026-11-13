package dosegram.render.card

import dosegram.render.scene.Box
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.text.TextFitter
import dosegram.render.tokens.TextStyle

/** One legend entry: how to draw its glyph in a given square, and its name. An entry without a glyph explains what an
  * empty place means, which no glyph can show.
  */
final case class LegendItem(glyph: Option[Box => Node], name: String)

object LegendItem:
  def of(glyph: Box => Node, name: String): LegendItem = LegendItem(Some(glyph), name)
  def note(text: String): LegendItem = LegendItem(None, text)

/** A legend laid out, ready to be placed: its rows and its height. */
final case class Legend(rows: Vector[Vector[LegendItem]]):
  def height: Double = rows.size * Legend.RowPx

  /** The legend drawn with its top-left corner at `origin`. */
  def nodes(origin: Point, frame: CardFrame, fitter: TextFitter): Vector[Node] =
    rows.zipWithIndex.flatMap { (row, rowIndex) =>
      val top = origin.y + rowIndex * Legend.RowPx
      val starts = row.scanLeft(origin.x)((x, item) => x + Legend.itemWidth(item, fitter) + Legend.ItemGapPx)
      row.zip(starts).flatMap { (item, x) =>
        val name = frame.text(
          item.name,
          Point(x + Legend.glyphWidth(item), top + Legend.BaselinePx),
          frame.look(TextStyle.Label, frame.tokens.ink2),
          frame.width
        )
        item.glyph.map(draw => draw(Box(x, top, Legend.GlyphPx, Legend.GlyphPx))).toVector :+ name
      }
    }

/** A legend that names every glyph present (VA-ID-07), wrapped by measured width. */
object Legend:
  val RowPx: Double = 56
  private val GlyphPx = 44.0
  private val GlyphGapPx = 12.0
  private val ItemGapPx = 28.0
  private val BaselinePx = 36.0

  def flow(items: Vector[LegendItem], maxWidth: Double, fitter: TextFitter): Legend =
    val rows = items.foldLeft(Vector.empty[Vector[LegendItem]]) { (rows, item) =>
      rows.lastOption match
        case Some(last) if rowWidth(last :+ item, fitter) <= maxWidth => rows.init :+ (last :+ item)
        case _                                                        => rows :+ Vector(item)
    }
    Legend(rows)

  private def glyphWidth(item: LegendItem): Double = if item.glyph.isDefined then GlyphPx + GlyphGapPx else 0.0

  private def itemWidth(item: LegendItem, fitter: TextFitter): Double =
    glyphWidth(item) + fitter.width(TextFitter.drawable(item.name, TextStyle.Label), TextStyle.Label)

  private def rowWidth(row: Vector[LegendItem], fitter: TextFitter): Double =
    row.map(itemWidth(_, fitter)).sum + (row.size - 1) * ItemGapPx
