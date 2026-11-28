package dosegram.render.blob

import dosegram.render.Samples
import dosegram.render.card.MoodStickerCard
import dosegram.render.card.Privacy
import dosegram.render.color.Rgb
import dosegram.render.lint.LintRules
import dosegram.render.lint.SceneLint
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.svg.SvgEmitter
import dosegram.render.svg.SvgProfile
import dosegram.render.text.TableTextMeasurer
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

/** The stickers of "My mood stickers" (supervisor decision of 2026-11-28): the blob of each captured valence step on a
  * transparent 512 px square, with the doubled outline of a transparent sticker (VISUALS section 3.3).
  */
class MoodStickerSuite extends munit.FunSuite:
  private val steps = (1 to 5).toVector.map(step => MoodState.captured(step, None))
  private def sticker(state: MoodState, theme: Theme = Theme.Dark) =
    MoodStickerCard.render(state, Samples.context(theme, Privacy.Aggregate))

  test("each step is a 512 px transparent square with the blob alone: no background, no ground mark, no words"):
    steps.foreach: state =>
      val scene = sticker(state).scene
      assertEquals((scene.size.width, scene.size.height), (512.0, 512.0))
      assert(scene.transparent, state.id)
      val svg = SvgEmitter.emit(scene)
      assert(!svg.contains("<rect"), s"${state.id}: a transparent sticker paints no background")
      val nodes = Node.flatten(scene.nodes)
      assert(nodes.collect { case text: Node.Text => text }.isEmpty, state.id)
      assert(nodes.collect { case ellipse: Node.Ellipse if ellipse.radiusX > 100 => ellipse }.isEmpty, "no ground mark")

  test("the outline is doubled: the halo under the inner stroke, both inside the square"):
    steps.foreach: state =>
      val nodes = Node.flatten(sticker(state).scene.nodes)
      val outlineInks = Set(BlobFigure.StickerHalo, BlobFigure.StickerInk)
      val outlines = nodes.collect { case path: Node.Path if path.stroke.exists(s => outlineInks(s.color)) => path }
      assertEquals(outlines.flatMap(_.stroke.map(s => (s.color, s.width))), Vector(
        (BlobFigure.StickerHalo, 14.0),
        (BlobFigure.StickerInk, 6.0)
      ))
      assert(outlines.last.fill.exists(_.isInstanceOf[Paint.Radial]), "the body keeps its gradient")
      val points = outlines.flatMap(_.data.points)
      assert(points.forall(p => p.x > 7 && p.x < 505 && p.y > 7 && p.y < 505), state.id)

  test("the inner outline keeps 3:1 against a white and a dark chat background (VISUALS A4)"):
    assert(BlobFigure.StickerInk.contrast(Rgb.hex("#ffffff")) >= 3.0)
    assert(BlobFigure.StickerInk.contrast(Rgb.hex("#0e1621")) >= 3.0)

  test("a sticker is the same picture in every theme and passes the scene lints and SVG profile 1"):
    steps.foreach: state =>
      val dark = sticker(state, Theme.Dark)
      assertEquals(SvgEmitter.emit(sticker(state, Theme.Light).scene), SvgEmitter.emit(dark.scene))
      val rules = LintRules(
        Tokens.of(Theme.Dark).palette ++ BlobPalette.all ++ Set(BlobFigure.StickerInk, BlobFigure.StickerHalo),
        Set(dark.scene.background),
        TableTextMeasurer
      )
      assertEquals(SceneLint.violations(dark.scene, rules), Vector.empty, state.id)
      assertEquals(SvgProfile.violations(SvgEmitter.emit(dark.scene)), Vector.empty, state.id)

  test("its text equivalent is the step's level name, and its file name is neutral"):
    val output = sticker(MoodState.captured(4, None))
    assertEquals(output.text.altText, "Pleasant")
    assertEquals(output.fileName, "dosegram-mood-sticker.png")
    assert(!output.hasSpoiler)
