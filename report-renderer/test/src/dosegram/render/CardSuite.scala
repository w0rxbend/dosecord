package dosegram.render

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobPalette
import dosegram.render.card.CardFrame
import dosegram.render.card.CardOutput
import dosegram.render.card.DayCard
import dosegram.render.card.MedicationStripCard
import dosegram.render.card.MilestoneCard
import dosegram.render.card.MoodCard
import dosegram.render.card.Privacy
import dosegram.render.card.TextEquivalent
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.lint.LintRules
import dosegram.render.lint.SceneLint
import dosegram.render.scene.Node
import dosegram.render.scene.TextKind
import dosegram.render.svg.SvgEmitter
import dosegram.render.svg.SvgProfile
import dosegram.render.text.TableTextMeasurer
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

/** Checks that hold for every fixture in every theme and privacy level: the goldens, the scene lints, the SVG profile,
  * the equality of numbers between image and text, privacy, and the card sizes.
  */
class CardSuite extends munit.FunSuite:
  private val Numbers = """\d+(?:[.:]\d+)*""".r

  private def cards(theme: Theme, privacy: Privacy): Vector[(String, CardOutput)] =
    Samples.all.flatMap(fixture => fixture.render(Samples.context(theme, privacy)).map(fixture.name -> _))

  private val everyVariant: Vector[(String, CardOutput, Theme)] =
    for
      theme <- Theme.values.toVector
      privacy <- Privacy.values.toVector
      (name, card) <- cards(theme, privacy)
    yield (s"$name/$theme/$privacy", card, theme)

  RenderGoldens.expected.foreach { (path, content) =>
    test(s"golden $path"):
      assertEquals(content, RenderGoldens.load(path), "golden drifted; regenerate with renderGoldenGenerate and review")
  }

  test("the same view model always yields the same SVG bytes (VA-PR-11)"):
    val first = cards(Theme.Dark, Privacy.Named).map((_, card) => SvgEmitter.emit(card.scene))
    val second = cards(Theme.Dark, Privacy.Named).map((_, card) => SvgEmitter.emit(card.scene))
    assertEquals(first, second)

  test("every scene passes the scene lints in both themes"):
    everyVariant.foreach { (name, card, theme) =>
      val rules = LintRules(Tokens.of(theme).palette ++ BlobPalette.all, Set(card.scene.background), TableTextMeasurer)
      assertEquals(SceneLint.violations(card.scene, rules), Vector.empty, name)
    }

  test("every emitted document stays inside SVG profile 1"):
    everyVariant.foreach((name, card, _) => assertEquals(SvgProfile.violations(SvgEmitter.emit(card.scene)), Vector.empty, name))

  test("every number drawn as a value also appears in the text equivalent (VA-PR-01)"):
    everyVariant.foreach { (name, card, _) =>
      val spoken = Numbers.findAllIn(card.text.full).toSet
      val drawn = Node.flatten(card.scene.nodes).collect { case text: Node.Text if text.kind == TextKind.Value => text.content }
      drawn.flatMap(Numbers.findAllIn).foreach(number => assert(spoken(number), s"$name draws $number, which '${card.text.full}' lacks"))
    }

  test("every personal card carries the non-advice footer (VA-LA-02)"):
    val global = Set(MoodCard.TemplateId, MilestoneCard.TemplateId)
    everyVariant.filterNot((_, card, _) => global(card.scene.templateId)).foreach { (name, card, _) =>
      val texts = Node.flatten(card.scene.nodes).collect { case text: Node.Text => text.content }
      assert(texts.contains(EnglishImageCopy.footer), name)
    }

  test("no canary name reaches an aggregate or discreet render (VA-PV-01)"):
    for
      privacy <- Vector(Privacy.Aggregate, Privacy.Discreet)
      (name, card) <- cards(Theme.Dark, privacy)
      output <- Vector(SvgEmitter.emit(card.scene), card.text.caption, card.text.altText, card.text.full, card.fileName)
      canary <- Vector("Zyqoxanil", "Canary", "script", "mega", "Metformin", "Vitamin", "Ibuprofen", "number")
    do assert(!output.contains(canary), s"$name at $privacy leaks '$canary'")

  test("a named render shows the medication names, so the canary check can fail"):
    val named = cards(Theme.Dark, Privacy.Named).map((_, card) => SvgEmitter.emit(card.scene)).mkString
    assert(named.contains("Zyqoxanil-Canary"))

  test("discreet cards have a generic caption and personal ones a spoiler (VA-PV-03)"):
    cards(Theme.Dark, Privacy.Discreet).foreach { (name, card) =>
      assert(Numbers.findFirstIn(card.text.caption).isEmpty || card.scene.templateId == MilestoneCard.TemplateId, s"$name: ${card.text.caption}")
      val isGlobal = Set(MoodCard.TemplateId, MilestoneCard.TemplateId)(card.scene.templateId)
      assertEquals(card.hasSpoiler, !isGlobal, name)
    }

  test("file names are neutral constants (VA-PV-05)"):
    everyVariant.foreach((name, card, _) => assert(card.fileName.matches("dosegram-[a-z-]+\\.png"), s"$name: ${card.fileName}"))

  test("a caption never exceeds the photo caption limit, and a long text is announced (VA-TX-01)"):
    everyVariant.foreach((name, card, _) => assert(card.text.caption.length <= TextEquivalent.CaptionLimit, name))
    val long = TextEquivalent.of("x" * 2000, "Your week.", Samples.context(Theme.Dark, Privacy.Named))
    assertEquals(long.caption, "Your week. Details below.")
    assertEquals(long.altText.length, TextEquivalent.CaptionLimit)

  test("cards use the square and portrait canvases, both within the 1280 px long side"):
    everyVariant.foreach { (name, card, _) =>
      assert(Set(CardFrame.Square, CardFrame.Portrait)(card.scene.size), s"$name: ${card.scene.size}")
    }

  test("a strip paginates beyond its row limit and drops no medication (VA-LA-03)"):
    val pages = Samples.strips(1).render(Samples.context(Theme.Light, Privacy.Aggregate))
    assert(pages.size > 1)
    val items = pages.flatMap(page => "Item \\d+".r.findAllIn(SvgEmitter.emit(page.scene)))
    assertEquals(items.distinct.size, 9)
    pages.foreach { page =>
      val rows = "Item \\d+".r.findAllIn(SvgEmitter.emit(page.scene)).size
      assert(rows <= MedicationStripCard.MaximumRowsPerPage)
    }

  test("missed doses change the halo and the medication row, never the blob (VA-BL-01)"):
    val context = Samples.context(Theme.Light, Privacy.Named)
    def blobOf(card: CardOutput): Vector[Node] = card.scene.nodes.collect { case mark: Node.Mark if mark.meta == BlobFigure.Mark => mark }
    val recorded = DayCard.render(Samples.partialDay, context)
    val missed = DayCard.render(Samples.partialDay.copy(medication = Some(Vector.fill(4)(DoseStatus.Missed))), context)
    assertEquals(blobOf(missed), blobOf(recorded))
    assertNotEquals(SvgEmitter.emit(missed.scene), SvgEmitter.emit(recorded.scene))

  test("a day without a mood entry shows the no-entry blob, never a substitute (VA-BL-02)"):
    val card = DayCard.render(Samples.partialDay.copy(mood = None), Samples.context(Theme.Light, Privacy.Named))
    assert(SvgEmitter.emit(card.scene).contains(EnglishImageCopy.noMoodEntry))
    assert(!SvgEmitter.emit(card.scene).contains("radialGradient"))
