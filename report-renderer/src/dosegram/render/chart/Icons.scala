package dosegram.render.chart

import dosegram.render.blob.BlobGeometry
import dosegram.render.color.Rgb
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.PathData
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.tokens.Domain

/** The in-repository domain icons (VISUALS section 3.6): outline paths on a 24-unit grid with a 2-unit stroke and round
  * caps. They are never emoji glyphs. Drawn at 56 px or larger, so the stroke stays above 4 px.
  */
object Icons:
  val SizePx: Double = 56
  private val GridUnits = 24.0
  private val StrokeUnits = 2.0

  /** The icon of a domain inside a square whose top-left corner is `origin`. */
  def of(domain: Domain, origin: Point, color: Rgb): Node =
    val scale = SizePx / GridUnits
    val stroke = Stroke.solid(color, StrokeUnits * scale)
    def placed(unit: Point): Point = Point(origin.x + unit.x * scale, origin.y + unit.y * scale)
    def path(data: PathData): Node = Node.strokedPath(data.placed(origin, scale), stroke)
    val nodes = domain match
      case Domain.Medication =>
        Vector(
          Node
            .outlinedRect(Box(origin.x + 4 * scale, origin.y + 4 * scale, 16 * scale, 16 * scale), 4.5 * scale, stroke),
          Node.Line(placed(Point(8, 12)), placed(Point(16, 12)), stroke)
        )
      case Domain.Water => Vector(path(Drop))
      case Domain.Food  =>
        Vector(Node.Circle(placed(Point(12, 12)), 9 * scale, None, Some(stroke)), path(PlateArc))
      case Domain.Mood  => Vector(path(BlobMini))
      case Domain.Sleep => Vector(path(Crescent))
    Node.Mark(MarkMeta(s"icon.$domain", Set(Channel.Shape)), nodes)

  private val Drop = PathData
    .from(Point(12, 3))
    .cubicTo(Point(9.4, 6.6), Point(5.5, 11), Point(5.5, 14.5))
    .arcTo(6.5, false, false, Point(18.5, 14.5))
    .cubicTo(Point(18.5, 11), Point(14.6, 6.6), Point(12, 3))
    .closed

  private val PlateArc = PathData.from(Point(12, 7.5)).arcTo(4.5, false, true, Point(16.5, 12))

  private val Crescent = PathData
    .from(Point(19.5, 14.2))
    .arcTo(8.2, true, true, Point(9.8, 4.5))
    .arcTo(6.6, false, false, Point(19.5, 14.2))
    .closed

  // Three lobes: six samples around the centre, alternating a long and a short radius.
  private val BlobMini =
    val samples = (0 until 6).toVector.map { index =>
      val angle = StrictMath.toRadians(-90.0 + index * 60)
      val radius = if index % 2 == 0 then 9.2 else 6.6
      Point(12 + radius * StrictMath.cos(angle), 12.6 + radius * StrictMath.sin(angle))
    }
    BlobGeometry.closedSpline(samples, 1.0)
