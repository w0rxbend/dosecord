package dosegram.core.domain.copy

import dosegram.core.ports.ConsentPurpose

/** The chat copy of water, mood and statistics states what was recorded and nothing else: the wording rules of
  * VISUALS_AND_ANALYTICS section 8.6 apply to it as they apply to the statistics. The one sentence that is allowed to
  * say "does not recommend" is the goal question, which exists to say exactly that (BOT_UX BUX-WATER-02).
  */
class CaptureCopySuite extends munit.FunSuite:

  private val linted: List[CopyEntry] =
    (WaterCopy.entries ++ CheckInCopy.entries ++ StatsCardCopy.entries).filterNot(_.id == "water.goal.ask")

  test("no water, check-in or statistics-card string states a cause, advises or judges a value"):
    val violations = linted.flatMap(entry => InsightPhrases.violations(entry.text).map(p => s"${entry.id}: '$p'"))
    assertEquals(violations, Nil)

  test("the goal question says that no amount is recommended"):
    assert(WaterCopy.goalAsk.contains("does not recommend an amount"))

  test("a check-in caption uses the labels, never a number such as '4 of 5' (BUX-MOOD-02)"):
    CheckInCopy.entries
      .filter(_.id.startsWith("checkin.recorded"))
      .foreach(entry => assert(!entry.text.matches(".*\\d of \\d.*"), entry.text))

  test("every purpose has both consent stages, a receipt and a withdrawal line"):
    ConsentPurpose.values.foreach: purpose =>
      assert(ConsentCopy.statement(purpose).startsWith("I agree that Dosegram "), purpose.id)
      assert(ConsentCopy.confirmation(purpose).startsWith("Please confirm: Dosegram "), purpose.id)
      assert(ConsentCopy.confirmation(purpose).endsWith("You can withdraw at any time with /privacy."), purpose.id)
      assert(ConsentCopy.receipt(purpose, "13 Nov 2026").contains("Privacy notice version 1."), purpose.id)

  test("routine copy stays inside 160 characters and an outcome inside 80 (BUX-MSG-03)"):
    val outcomes = List("water.added", "water.added.goal_reached", "water.undone", "checkin.undone")
    WaterCopy.entries.concat(CheckInCopy.entries).foreach: entry =>
      val limit = if outcomes.contains(entry.id) then 80 else 160
      assert(entry.text.length <= limit, s"${entry.id} has ${entry.text.length} characters")
