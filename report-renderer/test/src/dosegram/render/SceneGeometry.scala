package dosegram.render

import dosegram.render.blob.BlobFigure
import dosegram.render.scene.Align
import dosegram.render.scene.Node
import dosegram.render.scene.PathCommand
import dosegram.render.scene.Point
import dosegram.render.text.TextFitter

/** An axis-aligned extent of the canvas, for the layout checks that a golden only pins. */
final case class Extent(left: Double, top: Double, right: Double, bottom: Double)

/** Where the drawn things of a scene are: a text line by its measured width and cap height, a blob by its outline. */
object SceneGeometry:
  // The cap height of the product's text face, as a share of the font size; descenders are left out.
  private val CapShare = 0.72

  def text(text: Node.Text, fitter: TextFitter): Extent =
    val width = fitter.width(text.content, text.style)
    val left = text.align match
      case Align.Start  => text.anchor.x
      case Align.Middle => text.anchor.x - width / 2
      case Align.End    => text.anchor.x - width
    Extent(left, text.anchor.y - text.style.sizePx * CapShare, left + width, text.anchor.y)

  /** Each blob of the scene by the points of its outline, control points included: a bound, never smaller. */
  def blobs(nodes: Vector[Node]): Vector[Extent] =
    Node.flatten(nodes).collect { case mark: Node.Mark if mark.meta == BlobFigure.Mark => outline(mark) }

  /** Each mark of `role` by the rectangles, circles and paths directly under it. */
  def marks(nodes: Vector[Node], role: String): Vector[Extent] =
    Node.flatten(nodes).collect { case mark: Node.Mark if mark.meta.role == role => outline(mark) }

  private def outline(mark: Node.Mark): Extent =
    val points = mark.children.flatMap {
      case path: Node.Path => path.data.commands.flatMap(pointsOf)
      case rect: Node.Rect => Vector(Point(rect.box.x, rect.box.y), Point(rect.box.right, rect.box.bottom))
      case circle: Node.Circle =>
        Vector(circle.center.moved(-circle.radius, -circle.radius), circle.center.moved(circle.radius, circle.radius))
      case _               => Vector.empty
    }
    Extent(points.map(_.x).min, points.map(_.y).min, points.map(_.x).max, points.map(_.y).max)

  private def pointsOf(command: PathCommand): Vector[Point] = command match
    case PathCommand.MoveTo(to)            => Vector(to)
    case PathCommand.LineTo(to)            => Vector(to)
    case PathCommand.QuadTo(control, to)   => Vector(control, to)
    case PathCommand.CubicTo(c1, c2, to)   => Vector(c1, c2, to)
    case PathCommand.ArcTo(_, _, _, to)    => Vector(to)
    case PathCommand.Close                 => Vector.empty
