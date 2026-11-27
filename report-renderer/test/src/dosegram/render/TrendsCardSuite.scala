package dosegram.render

import dosegram.render.card.CardFrame
import dosegram.render.card.Privacy
import dosegram.render.card.TrendRow
import dosegram.render.card.TrendScale
import dosegram.render.card.TrendsCard
import dosegram.render.card.TrendsData
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

/** IMG-23 (ROADMAP G9.4, G9.5): one row per domain on its own scale, the caller's figure drawn as given, a dotted slot
  * for a bucket without a record, mood as dots, and the generic caption behind a spoiler in discreet mode.
  */
class TrendsCardSuite extends munit.FunSuite:

  private val from = LocalDate.of(2026, 10, 10)
  private val to = LocalDate.of(2026, 11, 8)

  private def data(rows: TrendRow*): TrendsData =
    TrendsData(from, to, byWeek = false, rows.toVector, "Trends, last 30 days. Water: average 1.6 l a day.")

  private val water = TrendRow(
    Domain.Water,
    "average 1.6 l a day",
    Vector.tabulate(30)(day => Option.when(day % 3 != 0)(1000.0 + day * 20)),
    TrendScale.FromZero
  )
  private val mood = TrendRow(Domain.Mood, "12 check-ins", Vector.tabulate(30)(day => Some(1.0 + day % 5)), TrendScale.Levels)

  private def roles(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Mark(meta, _) => meta.role }

  private def texts(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Text(content, _, _, _, _, _) => content }

  test("a bucket with a record is a bar, one without is a dotted slot, never a bar of zero"):
    val card = TrendsCard.render(data(water), Samples.context(Theme.Light, Privacy.Aggregate))
    val marks = roles(card.scene.nodes)
    assertEquals(marks.count(_ == "trends.water.bucket"), 20)
    assertEquals(marks.count(_ == "trends.water.none"), 10)

  test("the caller's figure and the domain name are drawn as given, with the period and its buckets"):
    val words = texts(TrendsCard.render(data(water, mood), Samples.context(Theme.Dark, Privacy.Aggregate)).scene.nodes)
    List("Trends", "average 1.6 l a day", "12 check-ins", "Water", "Mood", "10 Oct to 8 Nov · by day").foreach: word =>
      assert(words.contains(word), s"'$word' is not on the card: $words")

  test("the text equivalent is the caller's; discreet mode captions the generic line behind a spoiler"):
    val named = TrendsCard.render(data(water), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(named.text.caption, "Trends, last 30 days. Water: average 1.6 l a day.")
    assert(!named.hasSpoiler)
    val discreet = TrendsCard.render(data(water), Samples.context(Theme.Dark, Privacy.Discreet))
    assertEquals(discreet.text.caption, EnglishImageCopy.trendsDiscreet)
    assertEquals(discreet.text.full, named.text.full)
    assert(discreet.hasSpoiler)

  test("up to three rows fit the square; five rows take the portrait"):
    val three = TrendsCard.render(data(water, mood, water.copy(domain = Domain.Sleep)), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(three.scene.size, CardFrame.Square)
    val rows = Vector(Domain.Medication, Domain.Water, Domain.Mood, Domain.Sleep, Domain.Food).map(domain => water.copy(domain = domain))
    val five = TrendsCard.render(data(rows*), Samples.context(Theme.Dark, Privacy.Aggregate))
    assertEquals(five.scene.size, CardFrame.Portrait)

  test("ninety days of weeks and every fixture pass the lints and the SVG profile in both themes and languages"):
    for
      theme <- Theme.values.toVector
      copy <- Vector(EnglishImageCopy, UkrainianImageCopy)
      fixture <- Samples.trends
      card <- fixture.render(Samples.context(theme, Privacy.Aggregate, copy))
    do
      val rules = LintRules(Tokens.of(theme).palette, Set(card.scene.background), TableTextMeasurer)
      assertEquals(SceneLint.violations(card.scene, rules), Vector.empty, s"${fixture.name} $theme")
      assertEquals(SvgProfile.violations(SvgEmitter.emit(card.scene)), Vector.empty, fixture.name)
      assert(!texts(card.scene.nodes).exists(_.contains("…")), s"a word is cut in ${fixture.name}")

  test("every row and the legend end above the footer's clearance, in both languages"):
    for
      copy <- Vector(EnglishImageCopy, UkrainianImageCopy)
      fixture <- Samples.trends
      card <- fixture.render(Samples.context(Theme.Dark, Privacy.Aggregate, copy))
    do
      val footer = card.scene.size.height - CardFrame.MarginPx
      val lowest = Node.flatten(card.scene.nodes).collect {
        case Node.Text(content, anchor, _, _, _, _) if content != copy.footer => anchor.y
      }.max
      assert(lowest <= footer - 64, s"${fixture.name} ${copy.trendsTitle}: a line at $lowest reaches the footer")

  test("the Ukrainian catalogue draws every fixed word of the card in Ukrainian (ROADMAP G2.22)"):
    val words = texts(TrendsCard.render(data(water), Samples.context(Theme.Light, Privacy.Aggregate, UkrainianImageCopy)).scene.nodes)
    assert(words.contains(UkrainianImageCopy.trendsTitle), words.toString)
    assert(words.contains(UkrainianImageCopy.trendsScaleNote), words.toString)
    List("Trends", "by day", "Each row", "No record").foreach: english =>
      assert(!words.exists(_.contains(english)), s"'$english' in: $words")
