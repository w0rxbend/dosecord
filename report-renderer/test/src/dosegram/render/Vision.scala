package dosegram.render

import dosegram.render.color.Rgb

/** How a colour looks to a reader without colour vision, or without one of the three cone types (VISUALS VA-PR-06, the
  * greyscale and colour-vision review of section 11.1). The dichromat matrices are those of Machado, Oliveira and
  * Fernandes (2009) at severity 1.0, applied to linear sRGB; greyscale is the WCAG relative luminance, so a mark keeps
  * exactly the contrast the scene lint measured.
  */
enum Vision(matrix: Vector[Double]):
  case Normal extends Vision(Vector(1, 0, 0, 0, 1, 0, 0, 0, 1))
  case Greyscale extends Vision(Vector(0.2126, 0.7152, 0.0722, 0.2126, 0.7152, 0.0722, 0.2126, 0.7152, 0.0722))
  case Deuteranopia
      extends Vision(Vector(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881))
  case Protanopia
      extends Vision(Vector(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998))

  /** An opaque `0xRRGGBB` pixel as this reader sees it. */
  def see(rgb: Int): Int =
    val linear = Vector((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff).map(Vision.Linear)
    val seen = (0 until 3).map(row => Vision.encode((0 until 3).map(column => matrix(row * 3 + column) * linear(column)).sum))
    (seen(0) << 16) | (seen(1) << 8) | seen(2)

  def see(colour: Rgb): Rgb =
    val seen = see((colour.red << 16) | (colour.green << 8) | colour.blue)
    Rgb((seen >> 16) & 0xff, (seen >> 8) & 0xff, seen & 0xff)

object Vision:
  private val Linear: Vector[Double] = Vector.tabulate(256) { channel =>
    val unit = channel / 255.0
    if unit <= 0.04045 then unit / 12.92 else StrictMath.pow((unit + 0.055) / 1.055, 2.4)
  }

  private def encode(linear: Double): Int =
    val clamped = StrictMath.max(0.0, StrictMath.min(1.0, linear))
    val unit = if clamped <= 0.0031308 then clamped * 12.92 else 1.055 * StrictMath.pow(clamped, 1 / 2.4) - 0.055
    StrictMath.round(unit * 255).toInt
