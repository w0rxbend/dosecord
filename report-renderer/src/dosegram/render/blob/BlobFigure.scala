package dosegram.render.blob

import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.PathData
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.tokens.Tokens

/** Where and how large a blob is drawn. */
final case class BlobFrame(center: Point, radius: Double)

/** Draws the blob (VISUALS section 4.4): gradient fill, outline, face and ground mark. Its only inputs are the mood
  * state and the card theme, which is what keeps the record truthful (VA-BL-01).
  */
object BlobFigure:
  private val EyeOpening = Vector(0.35, 0.55, 0.75, 0.90, 1.00)
  private val MouthWidth = Vector(0.85, 0.92, 1.00, 1.08, 1.15)
  private val MouthCurve = Vector(-0.10, -0.07, -0.035, 0.0, 0.04, 0.08, 0.12)
  private val GroundWidth = Vector(1.5, 1.4, 1.3, 1.15, 1.0)
  private val EyeOffsetX = 0.28
  private val EyeOffsetY = -0.12
  private val EyeRadius = 0.055
  private val MouthY = 0.22
  private val MouthHalfWidth = 0.20
  private val BrowY = -0.24
  private val BrowHalfLength = 0.07
  private val BrowTiltDegrees = 12.0
  private val GroundY = 1.18
  private val GroundHalfHeight = 0.03
  private val NoEntryGroundWidth = 1.3
  // The card geometry is specified at a 300 px radius; strokes scale with the radius but never drop under 4 px.
  private val ReferenceRadius = 300.0
  private val OutlineAtReference = 8.0
  private val MinimumStroke = 4.0
  private val MinimumEyeRadius = 3.0

  val Mark: MarkMeta = MarkMeta("blob", Set(Channel.Colour, Channel.Shape, Channel.Label))

  /** How far under the centre the ground mark ends, in units of the radius. */
  val GroundBottomShare: Double = GroundY + GroundHalfHeight

  def mouthCurve(valence: Int): Double = MouthCurve(valence - 1)
  def eyeOpening(energy: Int): Double = EyeOpening(energy - 1)
  def hasBrows(state: MoodState): Boolean = state.valence <= 2 && state.energy >= 4

  /** The blob of a mood state with its ground mark. */
  def of(state: MoodState, frame: BlobFrame, tokens: Tokens): Node =
    val gradient = Paint.Radial(
      Point(frame.center.x - 0.3 * frame.radius, frame.center.y - 0.4 * frame.radius),
      1.1 * frame.radius,
      BlobPalette.highlight(state),
      BlobPalette.fill(state)
    )
    val body = Node.Path(
      BlobGeometry.outline(state).placed(frame.center, frame.radius),
      Some(gradient),
      Some(Stroke.solid(tokens.ink2, outlineWidth(frame)))
    )
    Node.Mark(Mark, Vector(ground(GroundWidth(state.energy - 1), frame, tokens), body) ++ face(state, frame))

  /** The "no entry" blob: flat neutral fill, dotted outline, no face (VA-BL-02). */
  def noEntry(frame: BlobFrame, tokens: Tokens): Node =
    val body = Node.Path(
      BlobGeometry.noEntryOutline.placed(frame.center, frame.radius),
      Some(Paint.Solid(BlobPalette.noEntryFill(tokens.theme))),
      Some(Stroke.dotted(tokens.ink3))
    )
    Node.Mark(Mark, Vector(ground(NoEntryGroundWidth, frame, tokens), body))

  /** Eye centres, mouth ends and mouth control point in unit coordinates, for the "face inside the outline" check. */
  def facePoints(state: MoodState): Vector[Point] =
    val halfMouth = MouthHalfWidth * MouthWidth(state.energy - 1)
    Vector(
      Point(-EyeOffsetX, EyeOffsetY),
      Point(EyeOffsetX, EyeOffsetY),
      Point(-halfMouth, MouthY),
      Point(halfMouth, MouthY),
      Point(0, MouthY + mouthCurve(state.valence))
    )

  private def outlineWidth(frame: BlobFrame): Double =
    StrictMath.max(MinimumStroke, StrictMath.rint(OutlineAtReference * frame.radius / ReferenceRadius))

  private def ground(widthFactor: Double, frame: BlobFrame, tokens: Tokens): Node =
    Node.Ellipse(
      at(Point(0, GroundY), frame),
      widthFactor * frame.radius / 2,
      StrictMath.max(2.0, GroundHalfHeight * frame.radius),
      Some(Paint.Solid(tokens.grid)),
      None
    )

  private def face(state: MoodState, frame: BlobFrame): Vector[Node] =
    val ink = BlobPalette.faceInk(state)
    val points = facePoints(state).map(at(_, frame))
    val eyeRadiusX = StrictMath.max(MinimumEyeRadius, EyeRadius * frame.radius)
    val eyeRadiusY = StrictMath.max(MinimumEyeRadius / 2, EyeRadius * frame.radius * eyeOpening(state.energy))
    val eyes = points.take(2).map(center => Node.Ellipse(center, eyeRadiusX, eyeRadiusY, Some(Paint.Solid(ink)), None))
    val mouth = Node.strokedPath(
      PathData.from(points(2)).quadTo(points(4), points(3)),
      Stroke.solid(ink, StrictMath.max(MinimumStroke, 0.035 * frame.radius))
    )
    val brows =
      if hasBrows(state) then Vector(-1.0, 1.0).map(side => brow(side, frame, Stroke.solid(ink, browWidth(frame))))
      else Vector.empty
    (eyes :+ mouth) ++ brows

  private def browWidth(frame: BlobFrame): Double = StrictMath.max(MinimumStroke, 0.03 * frame.radius)

  // A straight stroke above one eye, its inner end lowered by the tilt. `side` is -1 for the start-side eye.
  private def brow(side: Double, frame: BlobFrame, stroke: Stroke): Node =
    val tilt = StrictMath.toRadians(BrowTiltDegrees)
    val reach = BrowHalfLength * StrictMath.cos(tilt)
    val drop = BrowHalfLength * StrictMath.sin(tilt)
    val outer = Point(side * (EyeOffsetX + reach), BrowY - drop)
    val inner = Point(side * (EyeOffsetX - reach), BrowY + drop)
    Node.Line(at(outer, frame), at(inner, frame), stroke)

  private def at(unit: Point, frame: BlobFrame): Point =
    Point(frame.center.x + unit.x * frame.radius, frame.center.y + unit.y * frame.radius)
