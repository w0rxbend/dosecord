package dosegram.core.domain.copy

/** VISUALS_AND_ANALYTICS section 8.6 (VA-IN-10, VA-IN-11) and BOT_UX section 12.5 for the insight and month templates:
  * every sentence ends with its fixed closing sentence, states both counts, and the month text shows the gate with its
  * progress instead of a figure when a domain is short of days (R-INS-11).
  */
class InsightCopySuite extends munit.FunSuite:

  private def entry(id: String): String = InsightCopy.entries.find(_.id == id).getOrElse(fail(s"no entry $id")).text

  test("every comparison sentence ends with the fixed non-causal sentence (VA-IN-11)"):
    List("insight.ir01", "insight.ir02", "insight.ir06.sleep", "insight.ir06.water", "insight.ir06.mood").foreach {
      id => assert(entry(id).endsWith(StatsCopy.NotACause), clue(id))
    }

  test("every count that compares no groups of days ends with the count sentence (VA-IN-03)"):
    (List("insight.ir07") ++ InsightCopy.entries.map(_.id).filter(_.startsWith("insight.ir11"))).foreach { id =>
      assert(entry(id).endsWith(InsightCopy.CountNotACause), clue(id))
    }
    assertEquals(InsightCopy.entries.count(_.id.startsWith("insight.ir11")), 3)

  test("a comparison sentence states both group sizes (AC-INS-4)"):
    val sentence = entry("insight.ir01")
    assert(sentence.contains("On the 10 days"), sentence)
    assert(sentence.contains("On the 10 other days"), sentence)

  test("the timing statement is counts per time of day and suggests no change (R-INS-7)"):
    assertEquals(
      entry("insight.ir07"),
      "Evening doses were answered late or not at all 5 times out of 14. Morning doses 1 time out of 14. " +
        InsightCopy.CountNotACause
    )

  test("the baseline is the user's own previous weeks, worded as longer or shorter, higher or lower"):
    assert(entry("insight.ir11.sleepminutes").contains("40 min longer than your previous 4 weeks"))
    assert(entry("insight.ir11.watermillilitres").contains("0.3 l higher than your previous 4 weeks"))
    assert(entry("insight.ir11.moodlevel").contains("0.4 levels higher than your previous 4 weeks"))

  test("no insight or month template has a forbidden phrase"):
    val violations = (InsightCopy.entries ++ MonthCopy.entries).flatMap(entry =>
      InsightPhrases.violations(entry.text).map(phrase => s"${entry.id}: '$phrase'")
    )
    assertEquals(violations, Nil)

  test("the month text shows each domain's figures or its gate with the progress"):
    val reportable = MonthCopy.entries.find(_.id == "stats.month").get.text.linesIterator.toList
    assertEquals(reportable.take(2), List("October 2026 · Europe/Kyiv", "22 days with records"))
    assert(reportable.exists(_.startsWith("Doses: ")), reportable)
    assert(reportable.exists(_.startsWith("Previous month (September 2026): 60 of 60 taken (100%)")), reportable)
    assert(reportable.contains("Mood: 3 more days with records for the month's figures (11 of 14)"), reportable)
    val gate = MonthCopy.entries.find(_.id == "stats.month.gate").get.text
    assert(gate.contains(MonthCopy.gateTitle), gate)
    assert(!gate.contains("%"), gate)

  test("the year text shows each month's figure from 14 days, the days in brackets below, and the year before"):
    val year = YearCopy.entries.find(_.id == "stats.year").get.text.linesIterator.toList
    assertEquals(
      year,
      List(
        "2026 · Europe/Kyiv",
        "Days with records: 245 of 312",
        YearCopy.gateNote,
        "Doses taken of those answered: Mar 87% · Apr 90% · May 90% · Jun 90% · Jul 90% · Aug 90% · Sep 90% · Oct 92% · Nov —",
        "2026: 90% (240 days)",
        "Water, a day with records: Mar 1.7 l · Apr 1.8 l · May 1.9 l · Jun 2.0 l · Jul 2.0 l · Aug 2.1 l · Sep 2.2 l · Oct (13) · Nov —",
        "2026: 2.0 l (120 days) · 2025: 1.8 l (60 days)",
        // Twelve days of mood: the month and the year are under their gates, so no figure and no year line.
        "Mood, average of the days with a check-in: Oct (12) · Nov —",
        "Sleep, a night with records: Mar 7 h 05 min · Apr 7 h 08 min · May 7 h 11 min · Jun 7 h 14 min · Jul 7 h 07 min · Aug 7 h 07 min · Sep 7 h 22 min · Oct — · Nov —",
        "2026: 7 h 10 min (200 nights)"
      )
    )

end InsightCopySuite
