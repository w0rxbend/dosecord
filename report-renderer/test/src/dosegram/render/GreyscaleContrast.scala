package dosegram.render

import dosegram.render.color.Rgb
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.PathCommand
import dosegram.render.scene.Placement
import dosegram.render.scene.Point
import dosegram.render.scene.Scene
import dosegram.render.tokens.Tokens

/** The greyscale check of every card (VISUALS VA-PR-06 and VA-CO-05, ROADMAP G4.7): a mark that carries a meaning stays
  * visible to a reader who sees the card without colour, so at least one of its colours keeps 3:1 against what it is
  * drawn on, measured on the greyscale of [[Vision.Greyscale]]. What a mark is drawn on is the last solid shape
  * painted before it under the centre of its extent, else the card's background. The canvas tones (`surface`, `panel`,
  * `grid`) are scaffolding, an empty slot or the track a fill runs in, and are not judged: a mark drawn in them alone
  * says "nothing here", which reads the same with and without colour. Text has the 4.5:1 lint of
  * [[dosegram.render.lint.SceneLint]]; the blob is judged by its own review (VISUALS section 4).
  */
object GreyscaleContrast:
  val Minimum: Double = 3.0
  private val BlobRole = "blob"
  private val Scaffolding: Set[Rgb] =
    Vector(Tokens.Light, Tokens.Dark).flatMap(tokens => Vector(tokens.surface, tokens.panel, tokens.grid)).toSet

  /** One mark that fails: its role, its best contrast and the colours compared. */
  final case class Finding(role: String, contrast: Double, colours: Vector[Rgb], backdrop: Rgb):
    override def toString: String =
      f"mark '$role' keeps $contrast%.2f:1 in greyscale (${colours.map(_.hex).mkString(", ")} on ${backdrop.hex})"

  def findings(scene: Scene): Vector[Finding] =
    val painted = flatten(scene.nodes, Placement(0, 0, 1), insideBlob = false)
    painted.zipWithIndex.flatMap {
      case (Painted(mark: Node.Mark, placement, false), index) if mark.meta.role != BlobRole =>
        val shapes = flatten(mark.children, placement, insideBlob = false).filter(_.node.isInstanceOf[Shape])
        val colours = shapes.flatMap(item => coloursOf(item.node)).distinct.filterNot(Scaffolding)
        if colours.isEmpty then None
        else
          val centre = extentOf(shapes).center
          val backdrop = painted
            .take(index)
            .reverse
            .collectFirst { case item if solidFill(item.node).isDefined && extentOf(Vector(item)).holds(centre) =>
              solidFill(item.node).get
            }
            .getOrElse(scene.background)
          val best = colours.map(seen(_).contrast(seen(backdrop))).max
          Option.when(best < Minimum)(Finding(mark.meta.role, best, colours, backdrop))
      case _ => None
    }

  private def seen(colour: Rgb): Rgb = Vision.Greyscale.see(colour)

  private type Shape = Node.Rect | Node.Circle | Node.Ellipse | Node.Path | Node.Line

  /** A node with the transform of its enclosing groups, in paint order, and whether a blob encloses it. */
  final private case class Painted(node: Node, placement: Placement, insideBlob: Boolean)

  private def flatten(nodes: Vector[Node], outer: Placement, insideBlob: Boolean): Vector[Painted] =
    nodes.flatMap {
      case Node.Group(inner, children) =>
        val combined =
          Placement(outer.dx + inner.dx * outer.scale, outer.dy + inner.dy * outer.scale, outer.scale * inner.scale)
        flatten(children, combined, insideBlob)
      case mark: Node.Mark =>
        Painted(mark, outer, insideBlob) +: flatten(mark.children, outer, insideBlob || mark.meta.role == BlobRole)
      case leaf => Vector(Painted(leaf, outer, insideBlob))
    }

  private def coloursOf(node: Node): Vector[Rgb] =
    node match
      case rect: Node.Rect       => paints(rect.fill) ++ rect.stroke.map(_.color)
      case circle: Node.Circle   => paints(circle.fill) ++ circle.stroke.map(_.color)
      case ellipse: Node.Ellipse => paints(ellipse.fill) ++ ellipse.stroke.map(_.color)
      case path: Node.Path       => paints(path.fill) ++ path.stroke.map(_.color)
      case line: Node.Line       => Vector(line.stroke.color)
      case _                     => Vector.empty

  private def paints(fill: Option[Paint]): Vector[Rgb] = fill.toVector.collect { case Paint.Solid(colour) => colour }

  private def solidFill(node: Node): Option[Rgb] =
    node match
      case Node.Rect(_, _, Some(Paint.Solid(colour)), _)       => Some(colour)
      case Node.Circle(_, _, Some(Paint.Solid(colour)), _)     => Some(colour)
      case Node.Ellipse(_, _, _, Some(Paint.Solid(colour)), _) => Some(colour)
      case Node.Path(_, Some(Paint.Solid(colour)), _)          => Some(colour)
      case _                                                   => None

  final private case class Box(left: Double, top: Double, right: Double, bottom: Double):
    def center: Point = Point((left + right) / 2, (top + bottom) / 2)
    def holds(point: Point): Boolean = point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

  private def extentOf(items: Vector[Painted]): Box =
    val points = items.flatMap { item =>
      pointsOf(item.node).map(p => Point(item.placement.dx + p.x * item.placement.scale, item.placement.dy + p.y * item.placement.scale))
    }
    if points.isEmpty then Box(0, 0, 0, 0)
    else Box(points.map(_.x).min, points.map(_.y).min, points.map(_.x).max, points.map(_.y).max)

  private def pointsOf(node: Node): Vector[Point] =
    node match
      case rect: Node.Rect     => Vector(Point(rect.box.x, rect.box.y), Point(rect.box.right, rect.box.bottom))
      case circle: Node.Circle =>
        Vector(circle.center.moved(-circle.radius, -circle.radius), circle.center.moved(circle.radius, circle.radius))
      case ellipse: Node.Ellipse =>
        Vector(ellipse.center.moved(-ellipse.radiusX, -ellipse.radiusY), ellipse.center.moved(ellipse.radiusX, ellipse.radiusY))
      case line: Node.Line => Vector(line.from, line.to)
      case path: Node.Path =>
        path.data.commands.flatMap {
          case PathCommand.MoveTo(to)          => Vector(to)
          case PathCommand.LineTo(to)          => Vector(to)
          case PathCommand.QuadTo(control, to) => Vector(control, to)
          case PathCommand.CubicTo(c1, c2, to) => Vector(c1, c2, to)
          case PathCommand.ArcTo(_, _, _, to)  => Vector(to)
          case PathCommand.Close               => Vector.empty
        }
      case _ => Vector.empty
