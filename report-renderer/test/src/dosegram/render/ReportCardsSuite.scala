package dosegram.render

import dosegram.render.card.CardFrame
import dosegram.render.card.CardOutput
import dosegram.render.card.InsightCard
import dosegram.render.card.InsightData
import dosegram.render.card.InsightGroup
import dosegram.render.card.InsightRule
import dosegram.render.card.MonthCard
import dosegram.render.card.Privacy
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.scene.Node
import dosegram.render.tokens.Theme

import java.time.LocalDate

/** The monthly report's first page (IMG-05) and the insight card (IMG-13): what each must say and must not imply. */
class ReportCardsSuite extends munit.FunSuite:
  private val context = Samples.context(Theme.Light, Privacy.Named)

  private def card(name: String, privacy: Privacy = Privacy.Named): CardOutput =
    Samples.all.find(_.name == name).get.render(Samples.context(Theme.Light, privacy)).head

  private def texts(card: CardOutput): Vector[Node.Text] =
    Node.flatten(card.scene.nodes).collect { case text: Node.Text => text }

  private def marks(card: CardOutput, prefix: String): Vector[Node.Mark] =
    Node.flatten(card.scene.nodes).collect { case mark: Node.Mark if mark.meta.role.startsWith(prefix) => mark }

  test("the insight card always carries the fixed non-causal sentence, in the image and in its text (VA-IN-11)"):
    for name <- Vector("insight/water-goal-mood", "insight/sleep-goal-mood"); privacy <- Privacy.values do
      val insight = card(name, privacy)
      // Drawn whole, on one line or two, never cut by an ellipsis.
      val drawn = texts(insight).map(_.content).mkString(" ")
      assert(drawn.contains(EnglishImageCopy.insightNotCause) || drawn.contains("This compares your own days. It does not show a cause."), s"$name at $privacy: $drawn")
      assert(!drawn.contains("…"), drawn)
      assert(insight.text.full.endsWith(EnglishImageCopy.insightNotCause), insight.text.full)
      assertEquals(insight.scene.size, CardFrame.Square)

  test("the two groups sit on one shared scale: the higher value is drawn further along, with its group size"):
    val data = InsightData(InsightRule.WaterGoalMood, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 9), InsightGroup(18, 3.7), InsightGroup(9, 3.2))
    val insight = InsightCard.render(data, context)
    def dotX(index: Int): Double =
      Node.flatten(marks(insight, s"insight.group.$index")).collect { case circle: Node.Circle => circle.center.x }.head
    assert(dotX(1) > dotX(2), s"${dotX(1)} against ${dotX(2)}")
    val drawn = texts(insight).map(_.content)
    Vector("3.7", "3.2", "18 days", "9 days").foreach(figure => assert(drawn.contains(figure), s"$figure is missing"))
    assert(insight.text.full.contains("On the 18 days you reached your water goal, your average mood was 3.7."), insight.text.full)

  test("the insight wording uses no causal, evaluative or advising word (VISUALS section 8.6)"):
    val forbidden = Vector("cause ", "because", "improve", "better", "worse", "should", "try ", "significant", "effect")
    for name <- Vector("insight/water-goal-mood", "insight/sleep-goal-mood") do
      val words = (texts(card(name)).map(_.content) :+ card(name).text.full).mkString(" ").toLowerCase
      forbidden.foreach(word => assert(!words.replace("does not show a cause", "").contains(word), s"$name says '$word'"))

  test("the month hero is the adherence percentage with its counts when medication is tracked"):
    val month = card("month/typical")
    val drawn = texts(month).map(_.content)
    assert(drawn.contains("94%"), drawn)
    assert(drawn.contains("(112 of 119)"), drawn)
    assert(month.text.full.contains("112 of 119 scheduled doses recorded as taken (94%)"), month.text.full)
    assert(drawn.contains("September: 91% of scheduled doses taken."), drawn)

  test("without medication the month hero counts the days with records, and no medication row is drawn"):
    val month = card("month/no-medication")
    val drawn = texts(month).map(_.content)
    assert(drawn.contains("27"), drawn)
    assert(drawn.contains(EnglishImageCopy.monthHeroDays(27).head), drawn)
    assert(!drawn.contains(EnglishImageCopy.domainName(dosegram.render.tokens.Domain.Medication)), drawn)
    assert(!drawn.exists(_.contains("%")), "no percentage without medication")
    assertEquals(marks(month, "month.medication"), Vector.empty)

  test("a week of the month without a record of a domain is a dotted slot, never a zero bar (VA-PR-03)"):
    val month = card("month/typical")
    // Week 3 has no water, week 4 no mood check-in, week 5 no sleep.
    assertEquals(marks(month, "month.none").size, 3)
    assertEquals(marks(month, "month.water").size, 4)
    assertEquals(marks(month, "month.sleep").size, 4)
    val body = texts(month).filterNot(_.content == EnglishImageCopy.footer)
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    assert(body.forall(_.anchor.y <= frame.bodyBottom), body.map(_.anchor.y).max)

  test("the month card is portrait, collapses to the square with few rows, and its discreet form is generic"):
    assertEquals(card("month/typical").scene.size, CardFrame.Portrait)
    assertEquals(card("month/no-medication").scene.size, CardFrame.Square)
    val discreet = card("month/typical", Privacy.Discreet)
    assertEquals(discreet.text.caption, EnglishImageCopy.monthDiscreet)
    assert(discreet.hasSpoiler)
    assertEquals(MonthCard.TemplateId, "card.month.v1")
    assertEquals(InsightCard.TemplateId, "card.insight.v1")
