package dosegram.render.blob

import dosegram.render.color.Rgb
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

/** How the blob stands in one frame of its animated form (VISUALS section 4.6), in units of the radius: the breathing
  * scale, the lift off the ground, the turn of the wobble in radians and how open the eyes are. The still blob stands
  * at [[BlobPose.Rest]].
  */
final case class BlobPose(scale: Double, lift: Double, phaseShift: Double, eyeOpenness: Double)

object BlobPose:
  val Rest: BlobPose = BlobPose(scale = 1.0, lift = 0.0, phaseShift = 0.0, eyeOpenness = 1.0)

/** One eye in units of the radius: an ellipse, and the glint of light in it when the eye is open. */
final case class BlobEye(center: Point, radiusX: Double, radiusY: Double, glint: Option[BlobGlint])

/** A small circle inside an open eye, in the colour of [[BlobPalette.glint]]. */
final case class BlobGlint(center: Point, radius: Double)

/** A flat oval of warm colour under an eye, on the pleasant states only. */
final case class BlobCheek(center: Point, radiusX: Double, radiusY: Double)

/** The brows above the eyes: what they say and their two strokes, each from its outer to its inner end. */
enum BrowKind:
  /** Inner ends lowered: the low-valence, high-energy corner ("tense"). */
  case Tense

  /** Inner ends raised: the low-valence, low-energy corner ("tired"). */
  case Tired

/** The face of a mood state in units of the radius around the blob centre, x toward the end edge and y downward. It is
  * the one description both the chat image and the Mini App asset draw (VISUALS section 4.4, supervisor decision of
  * 2026-11-15): two eyes with their glints, the cheeks, one quadratic mouth and the brows.
  */
final case class BlobFace(
    ink: Rgb,
    eyes: Vector[BlobEye],
    mouth: Vector[Point],
    strokeWidth: Double,
    brows: Vector[(Point, Point)],
    browKind: Option[BrowKind],
    glintColour: Rgb,
    cheeks: Vector[BlobCheek],
    cheekColour: Rgb
)

/** Draws the blob (VISUALS section 4.4 with its addendum of 2026-11-15): gradient fill, outline, face and ground mark.
  * Its only inputs are the mood state and the card theme, which is what keeps the record truthful (VA-BL-01).
  */
object BlobFigure:
  private val EyeOpening = Vector(0.32, 0.55, 0.78, 0.92, 1.00)
  private val MouthWidth = Vector(0.85, 0.92, 1.00, 1.08, 1.15)
  private val MouthCurve = Vector(-0.13, -0.09, -0.045, 0.0, 0.05, 0.10, 0.15)
  private val GroundWidth = Vector(1.5, 1.4, 1.3, 1.15, 1.0)
  private val EyeOffsetX = 0.27
  private val EyeOffsetY = -0.10
  private val EyeRadius = 0.075
  private val GlintRadius = 0.026
  private val GlintOffset = Point(-0.026, -0.03)
  private val GlintFromOpening = 0.5
  private val MouthY = 0.24
  private val MouthHalfWidth = 0.22
  private val MouthStroke = 0.045
  private val BrowY = -0.26
  private val BrowHalfLength = 0.08
  private val BrowTiltDegrees = 14.0
  private val CheekCenter = Point(0.43, 0.14)
  private val CheekRadiusX = 0.085
  private val CheekRadiusY = 0.05
  private val FirstCheekValence = 6
  private val GroundY = 1.18
  private val GroundHalfHeight = 0.03
  private val NoEntryGroundWidth = 1.3
  // The card geometry is specified at a 300 px radius; strokes scale with the radius but never drop under 4 px.
  private val ReferenceRadius = 300.0
  private val OutlineAtReference = 8.0
  private val MinimumStroke = 4.0
  private val MinimumEyeRadius = 3.0

  /** Under this radius (the mini blobs of the strips and calendars) glints and cheeks are left out: they would be
    * smaller than two pixels. The Mini App applies the same radius.
    */
  val DetailFromRadiusPx: Double = 48.0

  val Mark: MarkMeta = MarkMeta("blob", Set(Channel.Colour, Channel.Shape, Channel.Label))

  /** The revision of the drawn geometry. It is part of the Mini App asset and of the cache key of every shared mood
    * image, so a change of the drawing never reuses an old picture (VA-BL-11).
    */
  val Revision: String = "dg-blob-2"

  /** How far under the centre the ground mark ends, in units of the radius. */
  val GroundBottomShare: Double = GroundY + GroundHalfHeight

  def mouthCurve(valence: Int): Double = MouthCurve(valence - 1)
  def eyeOpening(energy: Int): Double = EyeOpening(energy - 1)

  /** Which brows a state wears: the tense corner (V1 and V2 at high energy) and the tired corner (V1 to V3 at low and
    * middle energy); every other state has none.
    */
  def browKind(state: MoodState): Option[BrowKind] =
    if state.valence <= 2 && state.energy >= 4 then Some(BrowKind.Tense)
    else if state.valence <= 3 && state.energy <= 3 then Some(BrowKind.Tired)
    else None

  def hasBrows(state: MoodState): Boolean = browKind(state).isDefined
  def hasCheeks(state: MoodState): Boolean = state.valence >= FirstCheekValence

  /** The face of a state in units of the radius; `eyeOpenness` closes the eyes for a blink of the animated form. */
  def face(state: MoodState, eyeOpenness: Double = 1.0): BlobFace =
    val opening = eyeOpening(state.energy) * eyeOpenness
    val eyes = Vector(-1.0, 1.0).map { side =>
      val center = Point(side * EyeOffsetX, EyeOffsetY)
      val glint = Option.when(opening >= GlintFromOpening)(
        BlobGlint(Point(center.x + GlintOffset.x, center.y + GlintOffset.y * opening), GlintRadius)
      )
      BlobEye(center, EyeRadius, EyeRadius * opening, glint)
    }
    val halfMouth = MouthHalfWidth * MouthWidth(state.energy - 1)
    val mouth =
      Vector(Point(-halfMouth, MouthY), Point(0, MouthY + mouthCurve(state.valence)), Point(halfMouth, MouthY))
    val kind = browKind(state)
    val cheeks =
      if hasCheeks(state) then
        Vector(-1.0, 1.0).map(side => BlobCheek(Point(side * CheekCenter.x, CheekCenter.y), CheekRadiusX, CheekRadiusY))
      else Vector.empty
    BlobFace(
      ink = BlobPalette.faceInk(state),
      eyes = eyes,
      mouth = mouth,
      strokeWidth = MouthStroke,
      brows = kind.toVector.flatMap(brow => Vector(-1.0, 1.0).map(side => browStroke(side, brow))),
      browKind = kind,
      glintColour = BlobPalette.glint(state),
      cheeks = cheeks,
      cheekColour = BlobPalette.cheek(state)
    )

  /** The blob of a mood state with its ground mark. A `pose` other than rest moves the body and the face, never the
    * ground mark, the colours or the outline width (VISUALS section 4.6: no change of lightness over time).
    */
  def of(state: MoodState, frame: BlobFrame, tokens: Tokens, pose: BlobPose = BlobPose.Rest): Node =
    val posed = BlobFrame(Point(frame.center.x, frame.center.y - pose.lift * frame.radius), frame.radius * pose.scale)
    val gradient = Paint.Radial(
      Point(posed.center.x - 0.3 * posed.radius, posed.center.y - 0.4 * posed.radius),
      1.1 * posed.radius,
      BlobPalette.highlight(state),
      BlobPalette.fill(state)
    )
    val body = Node.Path(
      BlobGeometry.outline(state, pose.phaseShift).placed(posed.center, posed.radius),
      Some(gradient),
      Some(Stroke.solid(tokens.ink2, outlineWidth(frame)))
    )
    val faces = faceNodes(face(state, pose.eyeOpenness), posed)
    Node.Mark(Mark, Vector(ground(GroundWidth(state.energy - 1), frame, tokens), body) ++ faces)

  /** The blob of a sticker (VISUALS section 3.3, transparent stickers): no ground mark, and the outline doubled so it
    * holds 3:1 on a light and a dark chat alike: an outer stroke of [[StickerHalo]] under an inner one of
    * [[StickerInk]], 4 px and 6 px at the 512 px canvas, scaled with the radius.
    */
  def sticker(state: MoodState, frame: BlobFrame): Node =
    val placed = BlobGeometry.outline(state, 0.0).placed(frame.center, frame.radius)
    val scale = frame.radius / StickerRadius
    val inner = StickerInnerStroke * scale
    val halo = Node.Path(placed, None, Some(Stroke.solid(StickerHalo, inner + 2 * StickerOuterStroke * scale)))
    val gradient = Paint.Radial(
      Point(frame.center.x - 0.3 * frame.radius, frame.center.y - 0.4 * frame.radius),
      1.1 * frame.radius,
      BlobPalette.highlight(state),
      BlobPalette.fill(state)
    )
    val body = Node.Path(placed, Some(gradient), Some(Stroke.solid(StickerInk, inner)))
    Node.Mark(Mark, Vector(halo, body) ++ faceNodes(face(state), frame))

  /** The inner outline of a sticker: 4.2:1 on white and 4.3:1 on a dark chat background (VISUALS, COMPUTED). */
  val StickerInk: Rgb = Rgb.hex("#6a7e8a")

  /** The outer outline of a sticker, which keeps the edge visible on a dark chat. */
  val StickerHalo: Rgb = Rgb.hex("#f3f6f8")

  /** The radius the sticker strokes of VISUALS are given at: the blob of a 512 px sticker. */
  val StickerRadius: Double = 200.0
  private val StickerInnerStroke = 6.0
  private val StickerOuterStroke = 4.0

  /** The "no entry" blob: flat neutral fill, dotted outline, no face (VA-BL-02). */
  def noEntry(frame: BlobFrame, tokens: Tokens): Node =
    val body = Node.Path(
      BlobGeometry.noEntryOutline.placed(frame.center, frame.radius),
      Some(Paint.Solid(BlobPalette.noEntryFill(tokens.theme))),
      Some(Stroke.dotted(tokens.ink3))
    )
    Node.Mark(Mark, Vector(ground(NoEntryGroundWidth, frame, tokens), body))

  /** Every point of the face that must lie inside the outline (VA-BL-09): the eye centres and their outer edges, the
    * mouth ends and control point, the brow ends and the outer edges of the cheeks.
    */
  def facePoints(state: MoodState): Vector[Point] =
    val drawn = face(state)
    val eyes = drawn.eyes.flatMap(eye =>
      Vector(eye.center, Point(eye.center.x + StrictMath.signum(eye.center.x) * eye.radiusX, eye.center.y))
    )
    val cheeks = drawn.cheeks.map(cheek =>
      Point(cheek.center.x + StrictMath.signum(cheek.center.x) * cheek.radiusX, cheek.center.y)
    )
    eyes ++ drawn.mouth ++ drawn.brows.flatMap((outer, inner) => Vector(outer, inner)) ++ cheeks

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

  // Paint order: cheeks, eyes with their glints, mouth, brows.
  private def faceNodes(drawn: BlobFace, frame: BlobFrame): Vector[Node] =
    val detailed = frame.radius >= DetailFromRadiusPx
    val stroke = Stroke.solid(drawn.ink, StrictMath.max(MinimumStroke, drawn.strokeWidth * frame.radius))
    val cheeks =
      if detailed then
        drawn.cheeks.map(cheek =>
          Node.Ellipse(
            at(cheek.center, frame),
            cheek.radiusX * frame.radius,
            cheek.radiusY * frame.radius,
            Some(Paint.Solid(drawn.cheekColour)),
            None
          )
        )
      else Vector.empty
    val eyes = drawn.eyes.flatMap { eye =>
      val ball = Node.Ellipse(
        at(eye.center, frame),
        StrictMath.max(MinimumEyeRadius, eye.radiusX * frame.radius),
        StrictMath.max(MinimumEyeRadius / 2, eye.radiusY * frame.radius),
        Some(Paint.Solid(drawn.ink)),
        None
      )
      val glint = eye.glint
        .filter(_ => detailed)
        .map(glint =>
          Node.Circle(at(glint.center, frame), glint.radius * frame.radius, Some(Paint.Solid(drawn.glintColour)), None)
        )
      ball +: glint.toVector
    }
    val mouthPoints = drawn.mouth.map(at(_, frame))
    val mouth = Node.strokedPath(PathData.from(mouthPoints(0)).quadTo(mouthPoints(1), mouthPoints(2)), stroke)
    val brows = drawn.brows.map((outer, inner) => Node.Line(at(outer, frame), at(inner, frame), stroke))
    cheeks ++ eyes ++ (mouth +: brows)

  // A straight stroke above one eye from its outer to its inner end. `side` is -1 for the start-side eye. A tense brow
  // lowers its inner end, a tired one raises it.
  private def browStroke(side: Double, kind: BrowKind): (Point, Point) =
    val tilt = StrictMath.toRadians(BrowTiltDegrees)
    val reach = BrowHalfLength * StrictMath.cos(tilt)
    val drop = BrowHalfLength * StrictMath.sin(tilt) * (if kind == BrowKind.Tense then 1 else -1)
    (Point(side * (EyeOffsetX + reach), BrowY - drop), Point(side * (EyeOffsetX - reach), BrowY + drop))

  private def at(unit: Point, frame: BlobFrame): Point =
    Point(frame.center.x + unit.x * frame.radius, frame.center.y + unit.y * frame.radius)
