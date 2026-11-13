package dosegram.render.color

/** An opaque sRGB colour with 8-bit channels. Every colour in a scene is one of these; there is no alpha, because chat
  * images are opaque (VISUALS section 3.7).
  */
final case class Rgb(red: Int, green: Int, blue: Int):
  require(Rgb.isChannel(red) && Rgb.isChannel(green) && Rgb.isChannel(blue), s"channel out of range: $this")

  def hex: String = f"#$red%02x$green%02x$blue%02x"

  /** WCAG 2 relative luminance. */
  def luminance: Double =
    0.2126 * Rgb.linear(red) + 0.7152 * Rgb.linear(green) + 0.0722 * Rgb.linear(blue)

  /** WCAG 2 contrast ratio against another colour, always at least 1. */
  def contrast(other: Rgb): Double =
    val lighter = StrictMath.max(luminance, other.luminance)
    val darker = StrictMath.min(luminance, other.luminance)
    (lighter + 0.05) / (darker + 0.05)

object Rgb:
  private val HexPattern = "#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})".r

  /** Parses a lower-case `#rrggbb` literal. Token tables are constants, so a malformed literal is a defect. */
  def hex(literal: String): Rgb =
    literal match
      case HexPattern(r, g, b) => Rgb(Integer.parseInt(r, 16), Integer.parseInt(g, 16), Integer.parseInt(b, 16))
      case _                   => throw IllegalArgumentException(s"not a #rrggbb colour: $literal")

  private def isChannel(value: Int): Boolean = value >= 0 && value <= 255

  private[color] def linear(channel: Int): Double =
    val unit = channel / 255.0
    if unit <= 0.04045 then unit / 12.92 else StrictMath.pow((unit + 0.055) / 1.055, 2.4)

  private[color] def encode(linearValue: Double): Int =
    val clamped = StrictMath.max(0.0, StrictMath.min(1.0, linearValue))
    val unit = if clamped <= 0.0031308 then clamped * 12.92 else 1.055 * StrictMath.pow(clamped, 1 / 2.4) - 0.055
    StrictMath.round(unit * 255).toInt
