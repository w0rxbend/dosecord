package dosegram.render.scene

enum PathCommand:
  case MoveTo(to: Point)
  case LineTo(to: Point)
  case QuadTo(control: Point, to: Point)
  case CubicTo(control1: Point, control2: Point, to: Point)

  /** A circular arc of `radius` to `to`; `largeArc` and `clockwise` are the SVG flags. */
  case ArcTo(radius: Double, largeArc: Boolean, clockwise: Boolean, to: Point)
  case Close

/** An immutable path. Built with the fluent methods; emitted as an SVG `d` attribute. */
final case class PathData(commands: Vector[PathCommand]):
  def lineTo(to: Point): PathData = PathData(commands :+ PathCommand.LineTo(to))
  def quadTo(control: Point, to: Point): PathData = PathData(commands :+ PathCommand.QuadTo(control, to))
  def cubicTo(control1: Point, control2: Point, to: Point): PathData =
    PathData(commands :+ PathCommand.CubicTo(control1, control2, to))
  def arcTo(radius: Double, largeArc: Boolean, clockwise: Boolean, to: Point): PathData =
    PathData(commands :+ PathCommand.ArcTo(radius, largeArc, clockwise, to))
  def closed: PathData = PathData(commands :+ PathCommand.Close)

  /** The path scaled uniformly about the origin and then moved so the origin lands on `origin`. */
  def placed(origin: Point, scale: Double): PathData =
    def at(unit: Point): Point = Point(origin.x + unit.x * scale, origin.y + unit.y * scale)
    PathData(commands.map {
      case PathCommand.MoveTo(to)                             => PathCommand.MoveTo(at(to))
      case PathCommand.LineTo(to)                             => PathCommand.LineTo(at(to))
      case PathCommand.QuadTo(control, to)                    => PathCommand.QuadTo(at(control), at(to))
      case PathCommand.CubicTo(c1, c2, to)                    => PathCommand.CubicTo(at(c1), at(c2), at(to))
      case PathCommand.ArcTo(radius, largeArc, clockwise, to) =>
        PathCommand.ArcTo(radius * scale, largeArc, clockwise, at(to))
      case PathCommand.Close => PathCommand.Close
    })

  /** Every end point and control point, for bounds and finiteness checks. */
  def points: Vector[Point] =
    commands.flatMap {
      case PathCommand.MoveTo(to)          => Vector(to)
      case PathCommand.LineTo(to)          => Vector(to)
      case PathCommand.QuadTo(control, to) => Vector(control, to)
      case PathCommand.CubicTo(c1, c2, to) => Vector(c1, c2, to)
      case PathCommand.ArcTo(_, _, _, to)  => Vector(to)
      case PathCommand.Close               => Vector.empty
    }

object PathData:
  def from(start: Point): PathData = PathData(Vector(PathCommand.MoveTo(start)))
