package dosegram.render.blob

import dosegram.render.scene.PathData
import dosegram.render.scene.Point

/** The energy-dependent shape parameters of VISUALS section 4.4. */
final case class EnergyShape(
    amplitude: Double,
    harmonicWeights: Vector[Double],
    sampleCount: Int,
    verticalScale: Double,
    baseWidening: Double
)

/** The parametric blob outline (VISUALS section 4.4), in units of the base radius around the origin, y downward.
  * `r(theta) = 1 + sum over k = 2..5 of a_k * sin(k * theta + phi_k)`; phases come from a seeded generator, so a state
  * has the same outline in every build. All trigonometry uses `StrictMath`.
  */
object BlobGeometry:
  private val FirstHarmonic = 2
  private val PointedTension = 0.55
  private val RoundTension = 1.0

  private val Shapes: Vector[EnergyShape] = Vector(
    EnergyShape(0.030, Vector(0.70, 0.20, 0.10, 0.00), 8, 0.95, 0.06),
    EnergyShape(0.055, Vector(0.55, 0.25, 0.15, 0.05), 8, 0.975, 0.03),
    EnergyShape(0.080, Vector(0.40, 0.30, 0.20, 0.10), 10, 1.00, 0.0),
    EnergyShape(0.110, Vector(0.30, 0.30, 0.25, 0.15), 12, 1.025, 0.0),
    EnergyShape(0.140, Vector(0.20, 0.25, 0.30, 0.25), 12, 1.05, 0.0)
  )

  private val NoEntryEnergy = 3

  def shape(energy: Int): EnergyShape = Shapes(energy - 1)

  /** Curve tension: low-valence, high-energy states get pointed lobes ("tense"); every other state is round. */
  def tension(state: MoodState): Double =
    if state.valence <= 2 && state.energy >= 4 then PointedTension else RoundTension

  /** How far the top is lowered, in units of the radius: low-valence, low-energy states slump ("heavy"). */
  def slump(state: MoodState): Double =
    if state.energy > 3 then 0.0
    else if state.valence == 1 then 0.08
    else if state.valence == 2 then 0.04
    else 0.0

  /** The outline samples of a mood state, unit radius. */
  def samples(state: MoodState): Vector[Point] = sampled(state.id, shape(state.energy), slump(state))

  /** The outline samples of the "no entry" blob: the reference shape, never slumped. */
  def noEntrySamples: Vector[Point] = sampled(MoodState.NoEntryId, shape(NoEntryEnergy), 0.0)

  def outline(state: MoodState): PathData = closedSpline(samples(state), tension(state))

  def noEntryOutline: PathData = closedSpline(noEntrySamples, RoundTension)

  /** The radius of the raw polar curve at an angle, before posture is applied. */
  def polarRadius(stateId: String, energyShape: EnergyShape, theta: Double): Double =
    val harmonics = phases(stateId).zip(energyShape.harmonicWeights).zipWithIndex.map { case ((phase, weight), index) =>
      energyShape.amplitude * weight * StrictMath.sin((index + FirstHarmonic) * theta + phase)
    }
    1 + harmonics.sum

  /** Four phases, one per harmonic, from SplitMix64 seeded with the FNV-1a hash of `dg-blob-1:<state id>`. */
  def phases(stateId: String): Vector[Double] =
    Iterator
      .iterate(SplitMix64.first(Fnv1a.hash64(s"dg-blob-1:$stateId")))(_.next)
      .take(4)
      .map(_.unitDouble * 2 * StrictMath.PI)
      .toVector

  /** A closed cubic Bezier path through the points by Catmull-Rom interpolation; tension 1 is the round curve. */
  def closedSpline(points: Vector[Point], tension: Double): PathData =
    val count = points.size
    def at(index: Int): Point = points(((index % count) + count) % count)
    (0 until count)
      .foldLeft(PathData.from(points.head)) { (path, index) =>
        val (before, from, to, after) = (at(index - 1), at(index), at(index + 1), at(index + 2))
        path.cubicTo(
          Point(from.x + (to.x - before.x) * tension / 6, from.y + (to.y - before.y) * tension / 6),
          Point(to.x - (after.x - from.x) * tension / 6, to.y - (after.y - from.y) * tension / 6),
          to
        )
      }
      .closed

  private def sampled(stateId: String, energyShape: EnergyShape, slumpDepth: Double): Vector[Point] =
    (0 until energyShape.sampleCount).toVector.map { index =>
      val theta = 2 * StrictMath.PI * index / energyShape.sampleCount
      val radius = polarRadius(stateId, energyShape, theta)
      postured(Point(radius * StrictMath.cos(theta), radius * StrictMath.sin(theta)), energyShape, slumpDepth)
    }

  // Posture: the whole shape is scaled vertically; the lower half widens toward the bottom; the upper half is
  // lowered toward the top. Both blends are linear from the equator.
  private def postured(raw: Point, energyShape: EnergyShape, slumpDepth: Double): Point =
    if raw.y >= 0 then
      Point(raw.x * (1 + energyShape.baseWidening * StrictMath.min(1.0, raw.y)), raw.y * energyShape.verticalScale)
    else Point(raw.x, raw.y * energyShape.verticalScale + slumpDepth * StrictMath.min(1.0, -raw.y))

/** 64-bit FNV-1a over the bytes of an ASCII string. */
object Fnv1a:
  private val OffsetBasis = 0xcbf29ce484222325L
  private val Prime = 0x100000001b3L

  def hash64(ascii: String): Long =
    ascii.foldLeft(OffsetBasis)((hash, character) => (hash ^ (character & 0xff)) * Prime)

/** One step of the SplitMix64 generator: the state after the step and the value it produced. */
final case class SplitMix64(state: Long, value: Long):
  def next: SplitMix64 = SplitMix64.first(state)

  /** The value as a double in [0, 1), from its top 53 bits. */
  def unitDouble: Double = (value >>> 11) * (1.0 / (1L << 53))

object SplitMix64:
  private val Gamma = 0x9e3779b97f4a7c15L

  def first(seed: Long): SplitMix64 =
    val state = seed + Gamma
    val mixed1 = (state ^ (state >>> 30)) * 0xbf58476d1ce4e5b9L
    val mixed2 = (mixed1 ^ (mixed1 >>> 27)) * 0x94d049bb133111ebL
    SplitMix64(state, mixed2 ^ (mixed2 >>> 31))
