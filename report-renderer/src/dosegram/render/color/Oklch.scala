package dosegram.render.color

/** A colour in OKLCH (Ottosson 2020): perceptual lightness 0 to 1, chroma, hue in degrees. Tokens are specified in this
  * space (VISUALS section 3.3); `StrictMath` keeps the conversion identical on every machine.
  */
final case class Oklch(lightness: Double, chroma: Double, hueDegrees: Double):
  require(lightness >= 0 && lightness <= 1 && chroma >= 0, s"not an OKLCH colour: $this")

  /** The nearest sRGB colour of the same lightness and hue: chroma is reduced until the colour is inside the gamut. The
    * search ends at chroma zero, a grey, which is inside the gamut for every valid lightness.
    */
  def toRgb: Rgb =
    Iterator
      .iterate(chroma)(reduced => StrictMath.max(0.0, reduced - Oklch.ChromaStep))
      .map(reduced => copy(chroma = reduced).linearChannels)
      .find(_.forall(Oklch.isInGamut))
      .map(channels => Rgb(Rgb.encode(channels(0)), Rgb.encode(channels(1)), Rgb.encode(channels(2))))
      .getOrElse(throw IllegalStateException(s"no sRGB colour for $this"))

  /** The same hue, lighter by `lightnessGain` and with chroma scaled by `chromaScale`. */
  def shifted(lightnessGain: Double, chromaScale: Double): Oklch =
    copy(lightness = StrictMath.min(1.0, lightness + lightnessGain), chroma = chroma * chromaScale)

  private def linearChannels: Vector[Double] =
    val hue = StrictMath.toRadians(hueDegrees)
    val a = chroma * StrictMath.cos(hue)
    val b = chroma * StrictMath.sin(hue)
    val l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b)
    val m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b)
    val s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b)
    Vector(
      4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
      -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
      -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    )

  private def cube(value: Double): Double = value * value * value

object Oklch:
  private val ChromaStep = 0.002
  private val GamutSlack = 1e-4

  def from(rgb: Rgb): Oklch =
    val lab = Oklab.from(rgb)
    val hue = StrictMath.toDegrees(StrictMath.atan2(lab.b, lab.a))
    Oklch(lab.lightness, StrictMath.hypot(lab.a, lab.b), if hue < 0 then hue + 360 else hue)

  private def isInGamut(channel: Double): Boolean = channel >= -GamutSlack && channel <= 1 + GamutSlack

/** OKLab coordinates (Ottosson 2020). */
final case class Oklab(lightness: Double, a: Double, b: Double)

object Oklab:
  def from(rgb: Rgb): Oklab = fromLinear(Rgb.linear(rgb.red), Rgb.linear(rgb.green), Rgb.linear(rgb.blue))

  def fromLinear(red: Double, green: Double, blue: Double): Oklab =
    val l = StrictMath.cbrt(0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue)
    val m = StrictMath.cbrt(0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue)
    val s = StrictMath.cbrt(0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue)
    Oklab(
      0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
      1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
      0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
    )
