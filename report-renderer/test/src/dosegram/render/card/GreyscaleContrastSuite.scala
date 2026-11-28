package dosegram.render.card

import dosegram.render.GreyscaleContrast
import dosegram.render.Samples
import dosegram.render.color.Rgb
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Scene
import dosegram.render.scene.Size
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

/** The greyscale check of ROADMAP G4.7 over every card of the review set, in light, dark and discreet, in English and
  * Ukrainian: every mark keeps 3:1 against what it is drawn on for a reader who sees no colour (VISUALS VA-PR-06).
  */
class GreyscaleContrastSuite extends munit.FunSuite:

  private val variants = Vector(
    ("light", Theme.Light, Privacy.Named),
    ("dark", Theme.Dark, Privacy.Named),
    ("discreet", Theme.Dark, Privacy.Discreet),
    ("light discreet", Theme.Light, Privacy.Discreet)
  )

  test("every mark of every card keeps 3:1 against its backdrop in greyscale, light, dark and discreet"):
    val cards = for
      fixture <- Samples.review
      (variant, theme, privacy) <- variants
      copy <- Vector(None, Some(UkrainianImageCopy))
      context = copy.fold(Samples.context(theme, privacy))(Samples.context(theme, privacy, _))
      (card, page) <- fixture.render(context).zipWithIndex
    yield (s"${fixture.name} $variant${copy.fold("")(_ => " uk")} p${page + 1}", card.scene)
    assert(cards.size > Samples.review.size, "the check cannot pass with no cards")
    val failures = cards.flatMap((name, scene) => GreyscaleContrast.findings(scene).map(finding => s"$name: $finding"))
    assertEquals(failures.distinct, Vector.empty[String])

  test("a mark whose only colour is close to its backdrop is caught, and one on a darker panel is judged on it"):
    val light = Tokens.Light
    def scene(nodes: Node*) = Scene("probe", Size(200, 200), light.panel, nodes.toVector)
    def mark(colour: Rgb, box: Box) =
      Node.Mark(MarkMeta("probe", Set(Channel.Colour, Channel.Shape)), Vector(Node.filledRect(box, 0, colour)))
    val faint = Rgb.hex("#e6eef2")
    assertEquals(GreyscaleContrast.findings(scene(mark(faint, Box(20, 20, 40, 40)))).map(_.role), Vector("probe"))
    assertEquals(GreyscaleContrast.findings(scene(mark(light.ink, Box(20, 20, 40, 40)))), Vector.empty)
    // An empty slot or a track in the canvas tones says "nothing here" with or without colour, and is not judged.
    assertEquals(GreyscaleContrast.findings(scene(mark(light.grid, Box(20, 20, 40, 40)))), Vector.empty)
    // The same faint mark on an ink panel painted before it is plain to see.
    val onInk = scene(Node.filledRect(Box(0, 0, 200, 200), 0, light.ink), mark(faint, Box(20, 20, 40, 40)))
    assertEquals(GreyscaleContrast.findings(onInk), Vector.empty)
end GreyscaleContrastSuite
