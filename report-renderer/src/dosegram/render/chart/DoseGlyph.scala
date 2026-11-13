package dosegram.render.chart

import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.PathData
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.tokens.Tokens

/** The state of one dose occurrence as an image draws it (VISUALS section 3.2). "No dose scheduled" has no value: it is
  * an empty slot.
  */
enum DoseStatus:
  case TakenOnTime, TakenLate, Skipped, Missed, Unknown, Open, AsNeeded

  def isTaken: Boolean = this == TakenOnTime || this == TakenLate

/** The cell glyphs for dose states: shape first, one hue second, no red and no green-versus-red (VISUALS D5). */
object DoseGlyph:
  val MinimumSidePx: Double = 44
  private val CornerShare = 0.28
  private val ChamferShare = 0.30
  private val DashShare = 0.40
  private val OutlinePx = 6.0
  private val HatchInsetShare = 0.12

  /** The glyph of a status inside a square box. */
  def of(status: DoseStatus, box: Box, tokens: Tokens): Node =
    val radius = box.width * CornerShare
    val (channels, nodes) = status match
      case DoseStatus.TakenOnTime =>
        Set(Channel.Colour, Channel.Shape) -> Vector(Node.filledRect(box, radius, tokens.spruce))
      case DoseStatus.TakenLate =>
        Set(Channel.Colour, Channel.Shape) -> Vector(Node.filledPath(chamfered(box, radius), tokens.spruceLate))
      case DoseStatus.Skipped =>
        Set(Channel.Fill, Channel.Shape) -> Vector(hollow(box, radius, tokens))
      case DoseStatus.Missed =>
        Set(Channel.Shape, Channel.Pattern) -> Vector(dottedOutline(box, radius, tokens.grid), dash(box, tokens))
      case DoseStatus.Unknown =>
        Set(Channel.Pattern) -> Hatch.lines(box.inset(box.width * HatchInsetShare), Stroke.butt(tokens.ink3, 4))
      case DoseStatus.Open =>
        Set(Channel.Pattern) -> Vector(dottedOutline(box, radius, tokens.ink3))
      case DoseStatus.AsNeeded =>
        Set(Channel.Colour, Channel.Shape) -> Vector(Node.filledCircle(box.center, box.width / 2, tokens.spruce))
    Node.Mark(MarkMeta(s"dose.$status", channels), nodes)

  /** A hollow cell: the outline stays inside the box. Also the "none taken, all skipped" day of the calendar. */
  def hollow(box: Box, radius: Double, tokens: Tokens): Node =
    Node.outlinedRect(box.inset(OutlinePx / 2), radius - OutlinePx / 2, Stroke.butt(tokens.ink2, OutlinePx))

  private def dottedOutline(box: Box, radius: Double, color: dosegram.render.color.Rgb): Node =
    Node.outlinedRect(box.inset(2), radius - 2, Stroke.dotted(color))

  private def dash(box: Box, tokens: Tokens): Node =
    val half = box.width * DashShare / 2
    Node.Line(box.center.moved(-half, 0), box.center.moved(half, 0), Stroke.solid(tokens.ink3, OutlinePx))

  // A rounded square whose top trailing corner is cut at 45 degrees.
  private def chamfered(box: Box, radius: Double): PathData =
    val cut = box.width * ChamferShare
    PathData
      .from(Point(box.x + radius, box.y))
      .lineTo(Point(box.right - cut, box.y))
      .lineTo(Point(box.right, box.y + cut))
      .lineTo(Point(box.right, box.bottom - radius))
      .arcTo(radius, false, true, Point(box.right - radius, box.bottom))
      .lineTo(Point(box.x + radius, box.bottom))
      .arcTo(radius, false, true, Point(box.x, box.bottom - radius))
      .lineTo(Point(box.x, box.y + radius))
      .arcTo(radius, false, true, Point(box.x + radius, box.y))
      .closed

/** Hatching: parallel strokes at 45 degrees, clipped analytically to a rectangle because patterns and clip paths are
  * outside the SVG profile. Hatch means "uncertain" or "estimate" everywhere (VISUALS section 3.2).
  */
object Hatch:
  private val SpacingPx = 16.0

  def lines(box: Box, stroke: Stroke): Vector[Node] =
    // A line of the family is x + y = offset; neighbours are SpacingPx apart measured across the strokes.
    val step = SpacingPx * StrictMath.sqrt(2.0)
    val first = box.x + box.y + step / 2
    Iterator
      .iterate(first)(_ + step)
      .takeWhile(_ < box.right + box.bottom)
      .flatMap { offset =>
        val startX = StrictMath.max(box.x, offset - box.bottom)
        val endX = StrictMath.min(box.right, offset - box.y)
        Option.when(endX - startX > 1)(Node.Line(Point(startX, offset - startX), Point(endX, offset - endX), stroke))
      }
      .toVector
