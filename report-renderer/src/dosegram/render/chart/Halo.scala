package dosegram.render.chart

import dosegram.render.color.Rgb
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.PathData
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.tokens.Domain
import dosegram.render.tokens.Tokens

/** What one halo segment shows for its domain today (VISUALS section 4.7). */
enum HaloState:
  /** Progress toward the user's own goal; `hollowShare` is the skipped share of medication, drawn as a thin line. */
  case Progress(filledShare: Double, hollowShare: Double)

  /** Nothing recorded yet: a dotted track, identical at 09:00 and at 23:59 (VA-BL-03). */
  case NothingRecorded

  /** Only an estimate exists (a passive quiet window): a hatched outline, because a bound is not a duration. */
  case Estimate

final case class HaloSegment(domain: Domain, state: HaloState)

/** Where the halo is drawn and the colour behind it, which fills the inside of hollow and hatched parts. */
final case class HaloFrame(center: Point, radius: Double, background: Rgb)

/** The segmented halo of the day blob: one arc per tracked domain, clockwise from 12 o'clock, equal spans separated by
  * gaps. A fill never exceeds its span (VA-BL-07) and nothing in it uses an attention colour.
  */
object Halo:
  val StrokePx: Double = 28
  private val GapDegrees = 8.0
  private val SkippedLinePx = 8.0
  private val HatchOutlinePx = 4.0
  private val HatchStepDegrees = 3.4
  private val IconClearancePx = 54.0
  private val MinimumSweepDegrees = 0.5
  private val SkippedGapPx = 10.0

  def of(segments: Vector[HaloSegment], frame: HaloFrame, tokens: Tokens): Vector[Node] =
    spans(segments.size).zip(segments).map { (span, segment) =>
      val channels = Set(Channel.Colour, Channel.Position, Channel.Icon)
      Node.Mark(MarkMeta(s"halo.${segment.domain}", channels), arcs(segment, span, frame, tokens))
    }

  /** The centre of the icon of each segment: outside the ring at the middle of the segment's span. */
  def iconCenters(segmentCount: Int, frame: HaloFrame): Vector[Point] =
    spans(segmentCount).map(span => pointAt(frame, frame.radius + IconClearancePx, (span.from + span.to) / 2))

  final private case class Span(from: Double, to: Double):
    def length: Double = to - from
    def until(share: Double): Double = from + length * StrictMath.max(0.0, StrictMath.min(1.0, share))

  // Round caps reach half a stroke width past the arc ends, so each span is shortened by that angle and the visible
  // gap between two neighbours stays at GapDegrees.
  private def spans(segmentCount: Int): Vector[Span] =
    val each = 360.0 / segmentCount
    (0 until segmentCount).toVector.map { index =>
      Span(index * each + GapDegrees / 2, (index + 1) * each - GapDegrees / 2)
    }

  private def arcs(segment: HaloSegment, nominal: Span, frame: HaloFrame, tokens: Tokens): Vector[Node] =
    val capDegrees = StrictMath.toDegrees(StrictMath.asin(StrokePx / 2 / frame.radius))
    val span = Span(nominal.from + capDegrees, nominal.to - capDegrees)
    segment.state match
      case HaloState.NothingRecorded => Vector(arc(frame, span.from, span.to, Stroke.dotted(tokens.ink3)))
      case HaloState.Estimate        => estimate(frame, span, tokens)
      case HaloState.Progress(filledShare, hollowShare) =>
        val filledEnd = span.until(filledShare)
        val track = arc(frame, span.from, span.to, Stroke.solid(tokens.grid, StrokePx))
        val filled =
          if filledShare <= 0 then Vector.empty
          else
            val end = StrictMath.max(filledEnd, span.from + MinimumSweepDegrees)
            Vector(arc(frame, span.from, end, Stroke.solid(tokens.hue(segment.domain), StrokePx)))
        val skipped =
          if span.until(filledShare + hollowShare) - filledEnd < MinimumSweepDegrees then Vector.empty
          else skippedLine(frame, span, Shares(filledShare, hollowShare), tokens)
        (track +: filled) ++ skipped

  final private case class Shares(filled: Double, hollow: Double)

  // The skipped share is a thin `ink2` line along the middle of the track: answered, so it is marked, but not taken,
  // so it is not filled. It starts one gap after the round end of the fill, and its own round ends stay inside the
  // share, so the visible line ends where the share ends, as the visible end of the fill does.
  private def skippedLine(frame: HaloFrame, span: Span, shares: Shares, tokens: Tokens): Vector[Node] =
    def degrees(px: Double): Double = StrictMath.toDegrees(px / frame.radius)
    val fillCap = StrictMath.toDegrees(StrictMath.asin(StrokePx / 2 / frame.radius))
    val lineCap = degrees(SkippedLinePx / 2)
    val afterFill = span.until(shares.filled) + fillCap + degrees(SkippedGapPx) + lineCap
    val from = StrictMath.min(span.to - MinimumSweepDegrees, if shares.filled <= 0 then span.from else afterFill)
    val to = StrictMath.max(from + MinimumSweepDegrees, span.until(shares.filled + shares.hollow) - lineCap)
    Vector(arc(frame, from, to, Stroke.solid(tokens.ink2, SkippedLinePx)))

  // An outlined band: the full stroke, then the background over its middle.
  private def outlined(frame: HaloFrame, from: Double, to: Double, outer: Stroke, outlinePx: Double): Vector[Node] =
    Vector(
      arc(frame, from, to, outer),
      arc(frame, from, to, Stroke.solid(frame.background, outer.width - 2 * outlinePx))
    )

  private def estimate(frame: HaloFrame, span: Span, tokens: Tokens): Vector[Node] =
    val band = outlined(frame, span.from, span.to, Stroke.solid(tokens.ink3, StrokePx), HatchOutlinePx)
    val reach = StrokePx / 2 - HatchOutlinePx
    val ticks = Iterator
      .iterate(span.from + HatchStepDegrees / 2)(_ + HatchStepDegrees)
      .takeWhile(_ + HatchStepDegrees / 2 <= span.to)
      .map { degrees =>
        Node.Line(
          pointAt(frame, frame.radius - reach, degrees - HatchStepDegrees / 4),
          pointAt(frame, frame.radius + reach, degrees + HatchStepDegrees / 4),
          Stroke.butt(tokens.ink3, HatchOutlinePx)
        )
      }
    band ++ ticks

  private def arc(frame: HaloFrame, from: Double, to: Double, stroke: Stroke): Node =
    val path = PathData
      .from(pointAt(frame, frame.radius, from))
      .arcTo(frame.radius, to - from > 180, true, pointAt(frame, frame.radius, to))
    Node.strokedPath(path, stroke)

  // Degrees run clockwise from 12 o'clock.
  private def pointAt(frame: HaloFrame, radius: Double, degrees: Double): Point =
    val radians = StrictMath.toRadians(degrees)
    Point(frame.center.x + radius * StrictMath.sin(radians), frame.center.y - radius * StrictMath.cos(radians))
