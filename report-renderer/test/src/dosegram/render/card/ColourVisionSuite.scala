package dosegram.render.card

import dosegram.render.Vision
import dosegram.render.chart.DoseGlyph
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.DayClass
import dosegram.render.scene.Box
import dosegram.render.scene.Node
import dosegram.render.scene.Scene
import dosegram.render.scene.Size
import dosegram.render.svg.SvgEmitter
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

/** The greyscale and colour-vision check of VISUALS VA-PR-06 and VA-ID-05 (round 4 of the images, 2026-11-27): a
  * meaning that a colour carries is carried by shape, pattern, position or text as well, so a reader who sees the card
  * in greyscale, or with deuteranopia or protanopia, reads the same thing.
  */
class ColourVisionSuite extends munit.FunSuite:
  private val Cell = Box(0, 0, 96, 96)
  private val Heat = Vector(DayClass.AllTaken, DayClass.Most, DayClass.Some, DayClass.Few)

  // A glyph drawn alone, with every colour taken out: what is left is its shape.
  private def shapeOf(node: Node): String =
    SvgEmitter.emit(Scene("shape", Size(96, 96), Tokens.Light.panel, Vector(node))).replaceAll("#[0-9a-f]{6}", "#")

  test("the heat classes of the adherence calendar differ by their fill as well as by their colour"):
    Theme.values.foreach { theme =>
      val shapes = Heat.map(dayClass => dayClass -> shapeOf(AdherenceCalendarCard.cell(dayClass, Cell, Tokens.of(theme))))
      shapes.combinations(2).foreach { pair =>
        assertNotEquals(pair(0)._2, pair(1)._2, s"$theme: ${pair(0)._1} and ${pair(1)._1} differ by colour alone")
      }
    }

  test("every class of the calendar has a glyph of its own without its colour"):
    val shapes = DayClass.values.toVector.map(dayClass => shapeOf(AdherenceCalendarCard.cell(dayClass, Cell, Tokens.Dark)))
    assertEquals(shapes.distinct.size, shapes.size)

  test("the dose glyphs of VA-ID-05 stay apart without their colour"):
    val shapes = DoseStatus.values.toVector.map(status => shapeOf(DoseGlyph.of(status, Cell, Tokens.Dark)))
    assertEquals(shapes.distinct.size, shapes.size)

  // Finding R5 of the second-reviewer pass (2026-11-27): Beet and Rye fell to 2.5:1 on the dark surface under
  // simulated protanopia. Every domain hue keeps the 3:1 of a meaningful graphic (VA-CO-05) for every reader.
  test("every domain hue keeps 3:1 against the surface and the panel for every reader, in both themes"):
    for
      theme <- Theme.values
      tokens = Tokens.of(theme)
      vision <- Vision.values
      hue <- tokens.domainHues
      background <- Vector(tokens.surface, tokens.panel)
    do
      val ratio = vision.see(hue).contrast(vision.see(background))
      assert(ratio >= 3, s"$theme, $vision: ${hue.hex} on ${background.hex} is $ratio")

  test("the heat steps keep their order of lightness in greyscale and for both red-green dichromacies"):
    Theme.values.foreach { theme =>
      val steps = Tokens.of(theme).spruceHeat
      Vision.values.foreach { vision =>
        val seen = steps.map(vision.see(_).luminance)
        val ordered = if theme == Theme.Light then seen.reverse else seen
        assertEquals(ordered, ordered.sorted, s"$theme, $vision: ${steps.map(_.hex)}")
        assertEquals(ordered.distinct.size, ordered.size, s"$theme, $vision")
      }
    }
