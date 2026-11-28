package dosegram.render

import dosegram.render.card.CardFrame
import dosegram.render.card.CompareCard
import dosegram.render.card.CompareCell
import dosegram.render.card.CompareColumn
import dosegram.render.card.CompareData
import dosegram.render.card.CompareRow
import dosegram.render.card.Privacy
import dosegram.render.card.TrendScale
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.lint.LintRules
import dosegram.render.lint.SceneLint
import dosegram.render.scene.Node
import dosegram.render.svg.SvgEmitter
import dosegram.render.svg.SvgProfile
import dosegram.render.text.TableTextMeasurer
import dosegram.render.tokens.Domain
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

import java.time.LocalDate

/** IMG-27 (supervisor decision, 2026-11-28): two periods side by side, each with the caller's figure, its recorded days
  * and a bar on the row's own scale; a period below its gate is a dotted track and no figure; the generic caption goes
  * behind a spoiler in discreet mode.
  */
class CompareCardSuite extends munit.FunSuite:

  private val earlier = CompareColumn("Last week", LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22))
  private val later = CompareColumn("This week", LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 26))

  private val water = CompareRow(
    Domain.Water,
    CompareCell(Some("1.6 l" -> 1600.0), "7 days"),
    CompareCell(Some("1.8 l" -> 1800.0), "4 days"),
    Some("0.2 l higher"),
    TrendScale.FromZero
  )
  private val sleep = CompareRow(
    Domain.Sleep,
    CompareCell(Some("7 h 05 min" -> 425.0), "6 nights"),
    CompareCell(None, "2 nights"),
    None,
    TrendScale.FromZero
  )
  private val mood = CompareRow(
    Domain.Mood,
    CompareCell(Some("3.2" -> 3.2), "5 days"),
    CompareCell(Some("3.6" -> 3.6), "4 days"),
    Some("0.4 levels higher"),
    TrendScale.Levels
  )

  private def data(rows: CompareRow*): CompareData =
    CompareData(earlier, later, rows.toVector, "Compare two periods · Europe/Kyiv. Water: 0.2 l higher.")

  private def roles(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Mark(meta, _) => meta.role }

  private def texts(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Text(content, _, _, _, _, _) => content }

  test("each period with a figure is a bar on the row's scale; one below its gate is a dotted track, never a bar"):
    val card = CompareCard.render(data(water, sleep), Samples.context(Theme.Light, Privacy.Aggregate))
    val marks = roles(card.scene.nodes)
    assertEquals(marks.count(_ == "compare.water.earlier"), 1)
    assertEquals(marks.count(_ == "compare.water.later"), 1)
    assertEquals(marks.count(_ == "compare.sleep.earlier"), 1)
    assertEquals(marks.count(_ == "compare.sleep.none"), 1)
    assert(marks.contains("legend.compare.none"), marks)

  test("the later bar of a larger figure is longer: the length carries the figure without the colour"):
    val card = CompareCard.render(data(water), Samples.context(Theme.Light, Privacy.Aggregate))
    def length(role: String): Double =
      Node.flatten(card.scene.nodes).collectFirst { case Node.Mark(meta, children) if meta.role == role =>
        children.collect { case rect: Node.Rect => rect.box.width }.min
      }.get
    assert(length("compare.water.later") > length("compare.water.earlier"))

  test("the periods, the figures, the days and the difference are drawn as the caller words them"):
    val words = texts(CompareCard.render(data(water, mood), Samples.context(Theme.Dark, Privacy.Aggregate)).scene.nodes)
    List("Two periods side by side", "Last week", "This week", "16 to 22 Nov", "23 to 26 Nov", "1.6 l", "1.8 l",
      "7 days", "4 days", "0.2 l higher", "Water", "Mood", "0.4 levels higher", EnglishImageCopy.compare.moodNote).foreach: word =>
      assert(words.contains(word), s"'$word' is not on the card: $words")

  test("the text equivalent is the caller's; discreet mode captions the generic line behind a spoiler"):
    val named = CompareCard.render(data(water), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(named.text.caption, "Compare two periods · Europe/Kyiv. Water: 0.2 l higher.")
    assert(!named.hasSpoiler)
    val discreet = CompareCard.render(data(water), Samples.context(Theme.Dark, Privacy.Discreet))
    assertEquals(discreet.text.caption, EnglishImageCopy.compare.discreet)
    assertEquals(discreet.text.full, named.text.full)
    assert(discreet.hasSpoiler)

  test("up to three rows fit the square; five rows take the portrait"):
    val three = CompareCard.render(data(water, sleep, mood), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(three.scene.size, CardFrame.Square)
    val rows = Vector(Domain.Medication, Domain.Water, Domain.Mood, Domain.Sleep, Domain.Food).map(domain => water.copy(domain = domain))
    val five = CompareCard.render(data(rows*), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(five.scene.size, CardFrame.Portrait)

  test("every fixture passes the lints and the SVG profile in both themes and languages, with no word cut"):
    for
      theme <- Theme.values.toVector
      copy <- Vector(EnglishImageCopy, UkrainianImageCopy)
      privacy <- Vector(Privacy.Aggregate, Privacy.Discreet)
      fixture <- Samples.compares
      card <- fixture.render(Samples.context(theme, privacy, copy))
    do
      val rules = LintRules(Tokens.of(theme).palette, Set(card.scene.background), TableTextMeasurer)
      assertEquals(SceneLint.violations(card.scene, rules), Vector.empty, s"${fixture.name} $theme")
      assertEquals(SvgProfile.violations(SvgEmitter.emit(card.scene)), Vector.empty, fixture.name)
      assert(!texts(card.scene.nodes).exists(_.contains("…")), s"a word is cut in ${fixture.name}: ${texts(card.scene.nodes)}")

  test("every row and the legend end above the footer's clearance, in both languages"):
    for
      copy <- Vector(EnglishImageCopy, UkrainianImageCopy)
      fixture <- Samples.compares
      card <- fixture.render(Samples.context(Theme.Dark, Privacy.Aggregate, copy))
    do
      val footer = card.scene.size.height - CardFrame.MarginPx
      val lowest = Node.flatten(card.scene.nodes).collect {
        case Node.Text(content, anchor, _, _, _, _) if content != copy.footer => anchor.y
      }.max
      assert(lowest <= footer - 64, s"${fixture.name}: a line at $lowest reaches the footer")

  test("the Ukrainian catalogue draws every fixed word of the card in Ukrainian (ROADMAP G2.22)"):
    val words = texts(CompareCard.render(data(water, sleep, mood), Samples.context(Theme.Light, Privacy.Aggregate, UkrainianImageCopy)).scene.nodes)
    List(UkrainianImageCopy.compare.title, UkrainianImageCopy.compare.noFigure, UkrainianImageCopy.compare.scaleNote).foreach: word =>
      assert(words.contains(word), s"'$word' is not on the card: $words")
    List("Two periods", "No figure", "Each row", " to ").foreach: english =>
      assert(!words.exists(_.contains(english)), s"'$english' in: $words")
end CompareCardSuite
