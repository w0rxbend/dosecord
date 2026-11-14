package dosegram.render

import dosegram.render.blob.BlobPalette
import dosegram.render.card.CardOutput
import dosegram.render.card.MilestoneCard
import dosegram.render.card.MoodCard
import dosegram.render.card.Privacy
import dosegram.render.card.TextEquivalent
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.lint.LintRules
import dosegram.render.lint.SceneLint
import dosegram.render.scene.Node
import dosegram.render.scene.TextKind
import dosegram.render.svg.SvgEmitter
import dosegram.render.svg.SvgProfile
import dosegram.render.text.TableTextMeasurer
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

/** ROADMAP G2.22, VISUALS VA-L10N-02: every card drawn with the Ukrainian catalogue passes the checks the English cards
  * pass: the scene lints (which include text fitting its slot), the SVG profile, the numbers of the image in the text
  * equivalent, the footer, the caption limit, and a glyph for every Ukrainian character.
  */
class UkrainianCardsSuite extends munit.FunSuite:
  private val Numbers = """\d+""".r

  private def cards(theme: Theme, privacy: Privacy, english: Boolean = false): Vector[(String, CardOutput)] =
    val copy = if english then EnglishImageCopy else UkrainianImageCopy
    Samples.all.flatMap(fixture => fixture.render(Samples.context(theme, privacy, copy)).map(fixture.name -> _))

  private val everyVariant: Vector[(String, CardOutput, Theme)] =
    for
      theme <- Theme.values.toVector
      privacy <- Privacy.values.toVector
      (name, card) <- cards(theme, privacy)
    yield (s"$name/$theme/$privacy", card, theme)

  private def texts(card: CardOutput): Vector[String] =
    Node.flatten(card.scene.nodes).collect { case text: Node.Text => text.content }

  test("every Ukrainian scene passes the scene lints in both themes"):
    everyVariant.foreach { (name, card, theme) =>
      val rules = LintRules(Tokens.of(theme).palette ++ BlobPalette.all, Set(card.scene.background), TableTextMeasurer)
      assertEquals(SceneLint.violations(card.scene, rules), Vector.empty, name)
    }

  test("every Ukrainian document stays inside SVG profile 1"):
    everyVariant.foreach((name, card, _) => assertEquals(SvgProfile.violations(SvgEmitter.emit(card.scene)), Vector.empty, name))

  test("every number drawn as a value also appears in the Ukrainian text equivalent (VA-PR-01)"):
    everyVariant.foreach { (name, card, _) =>
      val spoken = Numbers.findAllIn(card.text.full).toSet
      val drawn = Node.flatten(card.scene.nodes).collect { case text: Node.Text if text.kind == TextKind.Value => text.content }
      drawn.flatMap(Numbers.findAllIn).foreach(number => assert(spoken(number), s"$name draws $number, which '${card.text.full}' lacks"))
    }

  test("every personal Ukrainian card carries the Ukrainian non-advice footer (VA-LA-02)"):
    val global = Set(MoodCard.TemplateId, MilestoneCard.TemplateId)
    everyVariant.filterNot((_, card, _) => global(card.scene.templateId)).foreach { (name, card, _) =>
      assert(texts(card).contains(UkrainianImageCopy.footer), name)
    }

  test("a Ukrainian caption never exceeds the photo caption limit (VA-TX-01)"):
    everyVariant.foreach((name, card, _) => assert(card.text.caption.length <= TextEquivalent.CaptionLimit, name))

  test("the fonts draw every Ukrainian character: no card has a replacement character the English one lacks"):
    val replacement = '�'
    val english = cards(Theme.Light, Privacy.Named, english = true)
    val ukrainian = cards(Theme.Light, Privacy.Named)
    assertEquals(ukrainian.map(_._1), english.map(_._1))
    english.zip(ukrainian).foreach { case ((name, en), (_, uk)) =>
      assertEquals(texts(uk).mkString.count(_ == replacement), texts(en).mkString.count(_ == replacement), name)
    }

  test("no Ukrainian card cuts a word or a line that the English card shows whole (VA-L10N-02)"):
    val ellipsis = '…'
    val english = cards(Theme.Light, Privacy.Named, english = true)
    val ukrainian = cards(Theme.Light, Privacy.Named)
    val cutMore = english.zip(ukrainian).collect {
      case ((name, en), (_, uk)) if texts(uk).count(_.contains(ellipsis)) > texts(en).count(_.contains(ellipsis)) =>
        s"$name cuts ${texts(uk).filter(_.contains(ellipsis))}"
    }
    assertEquals(cutMore, Vector.empty)

  test("the Ukrainian cards say the words in Ukrainian and keep the file names and canvases of the English ones"):
    val english = cards(Theme.Dark, Privacy.Named, english = true)
    val ukrainian = cards(Theme.Dark, Privacy.Named)
    english.zip(ukrainian).foreach { case ((name, en), (_, uk)) =>
      assertEquals(uk.fileName, en.fileName, name)
      assertEquals(uk.scene.size, en.scene.size, name)
      assert("\\p{IsCyrillic}".r.findFirstIn(uk.text.full).isDefined, s"$name: ${uk.text.full}")
    }

  test("no canary name reaches an aggregate or discreet Ukrainian render (VA-PV-01)"):
    for
      privacy <- Vector(Privacy.Aggregate, Privacy.Discreet)
      (name, card) <- cards(Theme.Dark, privacy)
      output <- Vector(SvgEmitter.emit(card.scene), card.text.caption, card.text.altText, card.text.full, card.fileName)
      canary <- Vector("Zyqoxanil", "Canary", "Metformin", "Vitamin", "Ibuprofen")
    do assert(!output.contains(canary), s"$name at $privacy leaks '$canary'")
end UkrainianCardsSuite

/** Writes every fixture drawn with the Ukrainian catalogue, light and named, as SVG into `directory`, for the visual
  * review of text fitting (VA-L10N-02): `./mill report-renderer.test.runMain dosegram.render.ukrainianSheetGenerate
  * <directory>`, then rasterise with resvg and the font pack.
  */
@main def ukrainianSheetGenerate(directory: String): Unit =
  val target = java.nio.file.Path.of(directory)
  java.nio.file.Files.createDirectories(target)
  val written =
    for
      fixture <- Samples.all
      (card, page) <- fixture.render(Samples.context(Theme.Light, Privacy.Named, UkrainianImageCopy)).zipWithIndex
    yield
      val file = target.resolve(s"${fixture.name.replace('/', '-')}-p${page + 1}.svg")
      java.nio.file.Files.writeString(file, SvgEmitter.emit(card.scene))
      file
  println(s"wrote ${written.size} Ukrainian cards to $target")
