package dosegram.render.scene

import dosegram.render.color.Rgb
import dosegram.render.tokens.TextStyle

final case class Point(x: Double, y: Double):
  def moved(dx: Double, dy: Double): Point = Point(x + dx, y + dy)

final case class Size(width: Double, height: Double)

final case class Box(x: Double, y: Double, width: Double, height: Double):
  def right: Double = x + width
  def bottom: Double = y + height
  def center: Point = Point(x + width / 2, y + height / 2)
  def inset(margin: Double): Box = Box(x + margin, y + margin, width - 2 * margin, height - 2 * margin)

/** A fill. Gradients exist only for the blob (VA-ID-02); coordinates are in the user space of the filled node. */
enum Paint:
  case Solid(color: Rgb)
  case Radial(center: Point, radius: Double, inner: Rgb, outer: Rgb)

enum LineCap:
  case Butt, Round

final case class Dash(on: Double, off: Double)

final case class Stroke(color: Rgb, width: Double, cap: LineCap, dash: Option[Dash])

object Stroke:
  def solid(color: Rgb, width: Double): Stroke = Stroke(color, width, LineCap.Round, None)
  def butt(color: Rgb, width: Double): Stroke = Stroke(color, width, LineCap.Butt, None)

  /** The "no record" or "not yet" outline of VISUALS section 3.2: dash 4, gap 12, round caps. */
  def dotted(color: Rgb): Stroke = Stroke(color, 4, LineCap.Round, Some(Dash(4, 12)))

enum Align:
  case Start, Middle, End

/** What a text node says, for the cross-representation lint: a `Value` repeats a number of the text equivalent, an
  * `Axis` label (a day number, a tick) is scaffolding and is exempt.
  */
enum TextKind:
  case Value, Label, Axis

/** A channel that carries the meaning of a mark. A status mark needs one besides `Colour` (VA-PR-06). */
enum Channel:
  case Colour, Shape, Fill, Pattern, Position, Icon, Label

final case class MarkMeta(role: String, channels: Set[Channel])

/** Translate, then scale uniformly: the only transform the profile needs. */
final case class Placement(dx: Double, dy: Double, scale: Double)

object Placement:
  def at(origin: Point): Placement = Placement(origin.x, origin.y, 1)

/** The scene tree (research brief 04 section 7.2). Positions are absolute pixels of the canvas, or of the enclosing
  * group. A `Mark` is a group that records what its children mean, for the scene lints.
  */
enum Node:
  case Group(placement: Placement, children: Vector[Node])
  case Mark(meta: MarkMeta, children: Vector[Node])
  case Rect(box: Box, radius: Double, fill: Option[Paint], stroke: Option[Stroke])
  case Circle(center: Point, radius: Double, fill: Option[Paint], stroke: Option[Stroke])
  case Ellipse(center: Point, radiusX: Double, radiusY: Double, fill: Option[Paint], stroke: Option[Stroke])
  case Line(from: Point, to: Point, stroke: Stroke)
  case Path(data: PathData, fill: Option[Paint], stroke: Option[Stroke])
  case Text(content: String, anchor: Point, align: Align, style: TextStyle, ink: Rgb, kind: TextKind)

object Node:
  def filledRect(box: Box, radius: Double, color: Rgb): Node = Rect(box, radius, Some(Paint.Solid(color)), None)
  def outlinedRect(box: Box, radius: Double, stroke: Stroke): Node = Rect(box, radius, None, Some(stroke))
  def filledCircle(center: Point, radius: Double, color: Rgb): Node =
    Circle(center, radius, Some(Paint.Solid(color)), None)
  def filledPath(data: PathData, color: Rgb): Node = Path(data, Some(Paint.Solid(color)), None)
  def strokedPath(data: PathData, stroke: Stroke): Node = Path(data, None, Some(stroke))
  def label(content: String, anchor: Point, style: TextStyle, ink: Rgb): Node =
    Text(content, anchor, Align.Start, style, ink, TextKind.Label)

  /** Every node of the tree in paint order, groups and marks included. */
  def flatten(nodes: Vector[Node]): Vector[Node] =
    nodes.flatMap {
      case group: Group => group +: flatten(group.children)
      case mark: Mark   => mark +: flatten(mark.children)
      case leaf         => Vector(leaf)
    }

/** One rendered picture before rasterisation: an opaque, full-bleed background and the nodes painted over it. */
final case class Scene(templateId: String, size: Size, background: Rgb, nodes: Vector[Node])
