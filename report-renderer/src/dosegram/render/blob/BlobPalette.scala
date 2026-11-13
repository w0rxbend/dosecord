package dosegram.render.blob

import dosegram.render.color.Oklch
import dosegram.render.color.Rgb
import dosegram.render.tokens.Theme

/** The valence colour ramp of the blob (VISUALS section 4.4): deep indigo through slate blue and grey-teal to sage,
  * straw and warm gold. Lightness rises with valence, so the order survives greyscale; the face and the written level
  * name are the mandatory second encoding (VA-BL-04).
  */
object BlobPalette:
  // Rows are valence V1 to V7, columns are energy E1 to E5.
  private val Fills: Vector[Vector[Rgb]] = Vector(
    Vector("#3f3d64", "#3e3a70", "#3e367a", "#3e3383", "#3f2e8d"),
    Vector("#48577b", "#415689", "#3c5593", "#37539d", "#3251a7"),
    Vector("#597288", "#4f7391", "#477398", "#3d74a0", "#3374a7"),
    Vector("#7d8c8e", "#798e90", "#758f92", "#719093", "#6d9195"),
    Vector("#93a98e", "#8dac86", "#88ae80", "#83b07a", "#7eb273"),
    Vector("#bcbe92", "#bdc080", "#bec171", "#bfc161", "#c0c24e"),
    Vector("#ead6a5", "#f1d48e", "#f7d37b", "#fdd265", "#fed25d")
  ).map(_.map(Rgb.hex))

  private val LightBackgrounds =
    Vector("#eeeffc", "#eaf0fd", "#e7f2fc", "#e3f4f6", "#eaf3e8", "#f0f1e4", "#f5f0e3").map(Rgb.hex)
  private val DarkBackgrounds =
    Vector("#222234", "#1c2435", "#152634", "#0b292c", "#1b2818", "#252611", "#2b230f").map(Rgb.hex)

  private val LightFaceInk = Rgb.hex("#e8f0f4")
  private val DarkFaceInk = Rgb.hex("#13242e")
  private val LastLightInkValence = 3
  private val HighlightLightnessGain = 0.10
  private val HighlightChromaScale = 0.75

  def fill(state: MoodState): Rgb = Fills(state.valence - 1)(state.energy - 1)

  /** The inner stop of the fill gradient: the same hue, lighter and less saturated. */
  def highlight(state: MoodState): Rgb =
    Oklch.from(fill(state)).shifted(HighlightLightnessGain, HighlightChromaScale).toRgb

  def faceInk(state: MoodState): Rgb = if state.valence <= LastLightInkValence then LightFaceInk else DarkFaceInk

  /** The flat card tint behind a mood state; it depends on valence only. */
  def background(state: MoodState, theme: Theme): Rgb =
    theme match
      case Theme.Light => LightBackgrounds(state.valence - 1)
      case Theme.Dark  => DarkBackgrounds(state.valence - 1)

  def noEntryFill(theme: Theme): Rgb =
    theme match
      case Theme.Light => Rgb.hex("#d8dfe3")
      case Theme.Dark  => Rgb.hex("#23363f")

  /** Every colour the blob system may put into a scene, for the palette whitelist lint. */
  val all: Set[Rgb] =
    (MoodState.all.flatMap(state => Vector(fill(state), highlight(state), faceInk(state)))
      ++ LightBackgrounds ++ DarkBackgrounds ++ Theme.values.map(noEntryFill)).toSet
