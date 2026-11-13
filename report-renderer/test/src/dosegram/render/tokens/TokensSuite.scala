package dosegram.render.tokens

import dosegram.render.color.Oklab
import dosegram.render.color.Oklch
import dosegram.render.color.Rgb

/** Palette validation (VA-CO-01, VISUALS section 3.3.2): the five domain hues in all-pairs mode and the heat steps in
  * ordinal mode, for both themes. Colour-vision deficiency is simulated with the matrices of Machado, Oliveira and
  * Fernandes (2009) at severity 1.0, applied to linear RGB, as the palette validator of the specification does.
  */
class TokensSuite extends munit.FunSuite:
  private val Protanopia = Vector(Vector(0.152286, 1.052583, -0.204868), Vector(0.114503, 0.786281, 0.099216), Vector(-0.003882, -0.048116, 1.051998))
  private val Deuteranopia = Vector(Vector(0.367322, 0.860646, -0.227968), Vector(0.280085, 0.672501, 0.047413), Vector(-0.011820, 0.042940, 0.968881))

  private def linear(channel: Int): Double =
    val unit = channel / 255.0
    if unit <= 0.04045 then unit / 12.92 else StrictMath.pow((unit + 0.055) / 1.055, 2.4)

  private def simulated(color: Rgb, matrix: Vector[Vector[Double]]): Oklab =
    val channels = Vector(linear(color.red), linear(color.green), linear(color.blue))
    val seen = matrix.map(row => StrictMath.max(0.0, StrictMath.min(1.0, row.zip(channels).map(_ * _).sum)))
    Oklab.fromLinear(seen(0), seen(1), seen(2))

  private def worstPair(colors: Vector[Rgb], view: Rgb => Oklab): Double =
    colors.combinations(2).map(pair => distance(view(pair(0)), view(pair(1)))).min

  // The validator's distance: Euclidean in OKLab, times 100.
  private def distance(one: Oklab, other: Oklab): Double =
    100 * StrictMath.sqrt(
      StrictMath.pow(one.lightness - other.lightness, 2) + StrictMath.pow(one.a - other.a, 2) + StrictMath.pow(one.b - other.b, 2)
    )

  Vector(Tokens.Light -> (11.7, 18.3), Tokens.Dark -> (9.1, 16.7)).foreach { case (tokens, (deficient, normal)) =>
    test(s"${tokens.theme}: every pair of domain hues stays apart under colour-vision deficiency"):
      val worst = Vector(Protanopia, Deuteranopia).map(matrix => worstPair(tokens.domainHues, simulated(_, matrix))).min
      assert(worst >= 8, s"worst pair is $worst")
      assertEqualsDouble(worst, deficient, 0.15)

    test(s"${tokens.theme}: every pair of domain hues stays apart for normal vision"):
      val worst = worstPair(tokens.domainHues, Oklab.from)
      assert(worst >= 15, s"worst pair is $worst")
      assertEqualsDouble(worst, normal, 0.15)

    test(s"${tokens.theme}: every domain hue has 3:1 contrast against the panel and the surface"):
      tokens.domainHues.foreach { hue =>
        assert(hue.contrast(tokens.panel) >= 3, s"${hue.hex} on panel")
        assert(hue.contrast(tokens.surface) >= 3, s"${hue.hex} on surface")
      }

    test(s"${tokens.theme}: the heat steps are ordinal, one hue with monotone lightness"):
      val lightness = tokens.spruceHeat.map(step => Oklch.from(step).lightness)
      val steps = lightness.sliding(2).map(pair => pair(1) - pair(0)).toVector
      assert(steps.forall(_ <= -0.06) || steps.forall(_ >= 0.06), s"lightness steps $steps")
      assert(tokens.spruceHeat.map(_.contrast(tokens.panel)).min >= 2, "the faintest step is under 2:1 against the panel")

    test(s"${tokens.theme}: text inks meet 4.5:1 on the surface and the panel"):
      for
        ink <- Vector(tokens.ink, tokens.ink2, tokens.ink3)
        background <- Vector(tokens.surface, tokens.panel)
      do assert(ink.contrast(background) >= 4.5, s"${ink.hex} on ${background.hex}")
  }

  test("contrast ratios match the values computed in the specification"):
    assertEqualsDouble(Tokens.Light.ink.contrast(Tokens.Light.surface), 14.4, 0.1)
    assertEqualsDouble(Tokens.Dark.ink.contrast(Tokens.Dark.surface), 14.2, 0.1)
    assertEqualsDouble(Tokens.Light.ink3.contrast(Tokens.Light.surface), 4.8, 0.1)

  test("no image token set contains the critical colour (VA-PR-08)"):
    val critical = Set(Rgb.hex("#b71824"), Rgb.hex("#f66d67"))
    assert(Tokens.Light.palette.intersect(critical).isEmpty && Tokens.Dark.palette.intersect(critical).isEmpty)
    assert(!Tokens.Light.palette(Tokens.Light.attention) && !Tokens.Dark.palette(Tokens.Dark.attention))

  test("OKLCH round-trips an sRGB colour and clips chroma into the gamut"):
    Tokens.Light.domainHues.foreach(hue => assertEquals(Oklch.from(hue).toRgb, hue))
    val vivid = Oklch(0.7, 0.4, 150).toRgb
    assert(Oklch.from(vivid).chroma < 0.4)
    assertEqualsDouble(Oklch.from(vivid).lightness, 0.7, 0.01)

  test("the OKLCH sources of the specification convert to the token hex values"):
    assertEquals(Oklch(0.965, 0.008, 230).toRgb, Tokens.Light.surface)
    assertEquals(Oklch(0.25, 0.030, 235).toRgb, Tokens.Light.ink)
    assertEquals(Oklch(0.24, 0.030, 230).toRgb, Tokens.Dark.surface)

  test("a malformed colour literal is rejected"):
    intercept[IllegalArgumentException](Rgb.hex("#12345"))
    intercept[IllegalArgumentException](Rgb.hex("red"))
