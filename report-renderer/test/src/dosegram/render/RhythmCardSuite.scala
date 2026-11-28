package dosegram.render

import dosegram.render.card.CardFrame
import dosegram.render.card.Privacy
import dosegram.render.card.RhythmCard
import dosegram.render.card.RhythmData
import dosegram.render.card.RhythmRow
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

/** IMG-28 (supervisor decision, 2026-11-28): the rhythm of the day, one 24-hour band per area with a bar per hour that
  * has a record; an area below its gate is a dotted track; the generic caption goes behind a spoiler in discreet mode.
  */
class RhythmCardSuite extends munit.FunSuite:

  private def band(counts: (Int, Int)*): Vector[Int] =
    Vector.tabulate(RhythmCard.Hours)(hour => counts.toMap.getOrElse(hour, 0))

  private val water =
    RhythmRow(Domain.Water, Some(band(8 -> 6, 12 -> 3)), "most often 08:00 to 09:00", "15 days", asleep = false)
  private val mood = RhythmRow(Domain.Mood, None, "records on 5 days", "5 days", asleep = false)
  private val sleep =
    RhythmRow(Domain.Sleep, Some(band(0 -> 14, 1 -> 14)), "usually: bedtime 23:40, wake 07:10", "14 nights",
      asleep = true)

  private def data(rows: RhythmRow*): RhythmData =
    RhythmData(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 26), rows.toVector, "Rhythm of the day. Water: 15.")

  private def roles(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Mark(meta, _) => meta.role }

  private def texts(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Text(content, _, _, _, _, _) => content }

  private def bars(card: dosegram.render.card.CardOutput, role: String): Vector[Node.Rect] =
    Node.flatten(card.scene.nodes).collectFirst { case Node.Mark(meta, children) if meta.role == role =>
      children.collect { case rect: Node.Rect => rect }.drop(1)
    }.get

  test("an area with a pattern is one band of bars, one per hour with a record; one below its gate is a dotted track"):
    val card = RhythmCard.render(data(water, mood), Samples.context(Theme.Light, Privacy.Aggregate))
    val marks = roles(card.scene.nodes)
    assertEquals(marks.count(_ == "rhythm.water"), 1)
    assertEquals(marks.count(_ == "rhythm.mood.none"), 1)
    assert(marks.contains("legend.rhythm.none"), marks)
    assertEquals(bars(card, "rhythm.water").size, 2)

  test("the busier hour is the taller bar, and the hours run left to right from midnight"):
    val card = RhythmCard.render(data(water), Samples.context(Theme.Light, Privacy.Aggregate))
    val Vector(eight, noon) = bars(card, "rhythm.water")
    assert(eight.box.height > noon.box.height)
    assert(eight.box.x < noon.box.x)

  test("the title, the window, the axis, the summaries and the days are drawn as the caller words them"):
    val words = texts(RhythmCard.render(data(water, sleep), Samples.context(Theme.Dark, Privacy.Aggregate)).scene.nodes)
    List("Rhythm of the day", "28 Oct to 26 Nov", "00:00", "12:00", "24:00", "Water", "Sleep", "15 days", "14 nights",
      "most often 08:00 to 09:00", "usually: bedtime 23:40, wake 07:10", EnglishImageCopy.rhythm.sleepNote)
      .foreach: word =>
        assert(words.contains(word), s"'$word' is not on the card: $words")

  test("the text equivalent is the caller's; discreet mode captions the generic line behind a spoiler"):
    val named = RhythmCard.render(data(water), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(named.text.caption, "Rhythm of the day. Water: 15.")
    assert(!named.hasSpoiler)
    val discreet = RhythmCard.render(data(water), Samples.context(Theme.Dark, Privacy.Discreet))
    assertEquals(discreet.text.caption, EnglishImageCopy.rhythm.discreet)
    assertEquals(discreet.text.full, named.text.full)
    assert(discreet.hasSpoiler)

  test("up to three rows fit the square; five rows take the portrait"):
    val three = RhythmCard.render(data(water, sleep, mood), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(three.scene.size, CardFrame.Square)
    val rows = Vector(Domain.Medication, Domain.Water, Domain.Mood, Domain.Sleep, Domain.Food)
      .map(domain => water.copy(domain = domain))
    val five = RhythmCard.render(data(rows*), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(five.scene.size, CardFrame.Portrait)

  test("every fixture passes the lints and the SVG profile in both themes and languages, with no word cut"):
    for
      theme <- Theme.values.toVector
      copy <- Vector(EnglishImageCopy, UkrainianImageCopy)
      privacy <- Vector(Privacy.Aggregate, Privacy.Discreet)
      fixture <- Samples.rhythms
      card <- fixture.render(Samples.context(theme, privacy, copy))
    do
      val rules = LintRules(Tokens.of(theme).palette, Set(card.scene.background), TableTextMeasurer)
      assertEquals(SceneLint.violations(card.scene, rules), Vector.empty, s"${fixture.name} $theme")
      assertEquals(SvgProfile.violations(SvgEmitter.emit(card.scene)), Vector.empty, fixture.name)
      assert(!texts(card.scene.nodes).exists(_.contains("…")), s"a word is cut in ${fixture.name}: ${texts(card.scene.nodes)}")

  test("every mark keeps 3:1 in greyscale against what it is drawn on, in both themes (VA-PR-06)"):
    for
      theme <- Theme.values.toVector
      fixture <- Samples.rhythms
      card <- fixture.render(Samples.context(theme, Privacy.Aggregate))
    do assertEquals(GreyscaleContrast.findings(card.scene), Vector.empty, s"${fixture.name} $theme")

  test("every row, the axis and the legend end above the footer's clearance, in both languages"):
    for
      copy <- Vector(EnglishImageCopy, UkrainianImageCopy)
      fixture <- Samples.rhythms
      card <- fixture.render(Samples.context(Theme.Dark, Privacy.Aggregate, copy))
    do
      val footer = card.scene.size.height - CardFrame.MarginPx
      val lowest = Node.flatten(card.scene.nodes).collect {
        case Node.Text(content, anchor, _, _, _, _) if content != copy.footer => anchor.y
      }.max
      assert(lowest <= footer - 64, s"${fixture.name}: a line at $lowest reaches the footer")

  test("the Ukrainian catalogue draws every fixed word of the card in Ukrainian (ROADMAP G2.22)"):
    val words = texts(RhythmCard.render(data(water, mood, sleep), Samples.context(Theme.Light, Privacy.Aggregate, UkrainianImageCopy)).scene.nodes)
    List(UkrainianImageCopy.rhythm.title, UkrainianImageCopy.rhythm.noPattern, UkrainianImageCopy.rhythm.scaleNote,
      UkrainianImageCopy.rhythm.sleepNote).foreach: word =>
      assert(words.contains(word), s"'$word' is not on the card: $words")
    List("Rhythm", "No pattern", "Height:", "Oct to").foreach: english =>
      assert(!words.exists(_.contains(english)), s"'$english' in: $words")
end RhythmCardSuite
