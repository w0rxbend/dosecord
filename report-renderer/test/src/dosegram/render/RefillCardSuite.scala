package dosegram.render

import dosegram.render.card.Privacy
import dosegram.render.card.RefillCard
import dosegram.render.card.RefillData
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.lint.LintRules
import dosegram.render.lint.SceneLint
import dosegram.render.scene.Node
import dosegram.render.text.TableTextMeasurer
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

import java.time.LocalDate

/** IMG-15 (VISUALS section 6.16, ROADMAP G9.10): a cell per unit left, the user's units threshold marked in the
  * attention colour, the source of the count, no instruction, and the generic line in discreet mode.
  */
class RefillCardSuite extends munit.FunSuite:

  private val data = RefillData(
    label = "Metformin 500 mg",
    onHand = 6,
    unit = "tablets",
    thresholdUnits = Some(10),
    threshold = "10 tablets",
    coversUntil = Some(LocalDate.of(2026, 11, 15)),
    text = "Metformin 500 mg: 6 tablets left by your count, at or below your threshold of 10 tablets."
  )

  private def roles(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Mark(meta, _) => meta.role }

  private def texts(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Text(content, _, _, _, _, _) => content }

  test("six units left are six solid cells of thirty, with the threshold marked and the date it covers until"):
    val card = RefillCard.render(data, Samples.context(Theme.Light, Privacy.Aggregate))
    val marks = roles(card.scene.nodes)
    assertEquals(marks.count(_ == "refill.cell.left"), 6)
    assertEquals(marks.count(_ == "refill.cell.used"), RefillCard.Cells - 6)
    assertEquals(marks.count(_ == "refill.threshold"), 1)
    val words = texts(card.scene.nodes)
    assert(words.contains("Metformin 500 mg"), words.toString)
    assert(words.contains("Covers your schedule until about Sun 15 Nov."), words.toString)
    assert(words.contains(RefillCard.Source))
    assertEquals(card.text.full, data.text)

  test("VA-IMG-15a: no word on the card tells the user to obtain, buy or take anything"):
    val words = texts(RefillCard.render(data, Samples.context(Theme.Dark, Privacy.Aggregate)).scene.nodes).mkString(" ")
    List("buy", "order", "take", "pharmacy", "get more", "should").foreach: word =>
      assert(!words.toLowerCase(java.util.Locale.ROOT).contains(word), s"'$word' in: $words")

  test("discreet mode draws no name, captions the generic line and goes behind a spoiler"):
    val card = RefillCard.render(data, Samples.context(Theme.Dark, Privacy.Discreet))
    assert(!texts(card.scene.nodes).exists(_.contains("Metformin")))
    assertEquals(card.text.caption, RefillCard.DiscreetLabel)
    assert(card.hasSpoiler)

  test("the scene passes the lints in both themes, and a days threshold draws no marker"):
    Theme.values.foreach: theme =>
      val card = RefillCard.render(data.copy(thresholdUnits = None), Samples.context(theme, Privacy.Aggregate))
      assertEquals(roles(card.scene.nodes).count(_ == "refill.threshold"), 0)
      val rules = LintRules(Tokens.of(theme).palette, Set(card.scene.background), TableTextMeasurer)
      assertEquals(SceneLint.violations(card.scene, rules), Vector.empty, theme.toString)
      assertEquals(dosegram.render.svg.SvgProfile.violations(dosegram.render.svg.SvgEmitter.emit(card.scene)), Vector.empty)

  test("the Ukrainian catalogue draws every fixed word of the card in Ukrainian (ROADMAP G2.22)"):
    val ukrainian = data.copy(
      unit = "таблеток",
      threshold = "10 таблеток",
      text = "Metformin 500 mg: за вашим підрахунком лишилося 6 таблеток."
    )
    Theme.values.foreach: theme =>
      val card = RefillCard.render(ukrainian, Samples.context(theme, Privacy.Aggregate, UkrainianImageCopy))
      val words = texts(card.scene.nodes)
      assert(words.contains(UkrainianImageCopy.refillTitle), words.toString)
      assert(words.contains("Вистачить приблизно до Нд 15 листопада."), words.toString)
      assert(words.contains(UkrainianImageCopy.refillSource), words.toString)
      assert(!words.exists(_.contains("…")), s"a word is cut: $words")
      List("Running low", "Your threshold", " left", "Covers", "Based on", "tablet").foreach: english =>
        assert(!words.exists(_.contains(english)), s"'$english' in: $words")
      // As the English lint above: a days threshold, since the units marker's attention colour is outside the palette.
      val linted = RefillCard.render(
        ukrainian.copy(thresholdUnits = None),
        Samples.context(theme, Privacy.Aggregate, UkrainianImageCopy)
      )
      val rules = LintRules(Tokens.of(theme).palette, Set(linted.scene.background), TableTextMeasurer)
      assertEquals(SceneLint.violations(linted.scene, rules), Vector.empty, theme.toString)
    val discreet = RefillCard.render(ukrainian, Samples.context(Theme.Dark, Privacy.Discreet, UkrainianImageCopy))
    assertEquals(discreet.text.caption, UkrainianImageCopy.refillDiscreetLabel)
