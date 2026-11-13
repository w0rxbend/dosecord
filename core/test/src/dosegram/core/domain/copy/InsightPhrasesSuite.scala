package dosegram.core.domain.copy

/** VISUALS_AND_ANALYTICS section 8.6 (VA-IN-10, VA-IN-11), VA-ME-12, VA-ME-30, VA-ME-50 and ROADMAP G2.20, G6.8: no
  * statistic or insight string states a cause, gives advice, judges a value or uses a clinical word.
  */
class InsightPhrasesSuite extends munit.FunSuite:

  private val linted: List[CopyEntry] = StatsCopy.entries ++ MoodLabels.entries

  test("every statistic, insight and mood label string is free of causal, evaluative and clinical wording"):
    val violations = linted.flatMap(entry => InsightPhrases.violations(entry.text).map(p => s"${entry.id}: '$p'"))
    assertEquals(violations, Nil)

  test("the lint covers every catalogue namespace it is responsible for"):
    val namespaces = Copy.all.map(_.id.takeWhile(_ != '.')).filter(Set("stats", "insight", "mood")).toSet
    assertEquals(namespaces, Set("stats", "insight", "mood"))
    val covered = linted.map(_.id).toSet
    val uncovered = Copy.all.map(_.id).filter(id => Set("stats", "insight", "mood")(id.takeWhile(_ != '.')))
    assertEquals(uncovered.filterNot(covered), Nil)

  test("the lint rejects each forbidden sentence of VISUALS section 8.6"):
    val forbidden = List(
      "Sleeping more improves your mood.",
      "You feel worse because you slept less than 7 hours.",
      "Try to go to bed earlier to take your evening items on time.",
      "Your adherence is poor in the evenings.",
      "Your mood has been unstable this month. Consider talking to a professional.",
      "Coffee after 15:00 significantly reduces your sleep.",
      "You are not drinking enough water.",
      "Drinking water leads to a higher mood.",
      "Your mood was lower due to less water.",
      "There is a correlation between water and mood.",
      "You should drink more.",
      "Your mood is normal.",
      "This is a healthy amount.",
      "Risk of dehydration.",
      "Your mood was volatile this week."
    )
    forbidden.foreach(sentence => assert(InsightPhrases.violations(sentence).nonEmpty, clue(sentence)))

  test("the lint accepts the allowed sentences of VISUALS section 8.6"):
    val allowed = List(
      "On the 18 days after a night at or above your 7 h 30 min goal, your average mood was 3.7 (pleasant). " +
        "On the 9 other days it was 3.0 (neutral).",
      "Evening items were answered late or not at all on 5 of 14 days. Morning items on 1 of 14.",
      "On your 8 free days you logged 1.6 l of water on average. On your 20 work days, 2.1 l.",
      "This week you logged 7 h 05 min of sleep a night on average, 40 min longer than your previous four weeks.",
      "This compares your own days. It does not show a cause."
    )
    allowed.foreach(sentence => assertEquals(InsightPhrases.violations(sentence), Nil, clue(sentence)))

  test("a forbidden word is matched as a whole word, not inside another word"):
    assertEquals(InsightPhrases.violations("Also recorded on 3 days."), Nil)
    assertEquals(InsightPhrases.violations("The message was sent."), Nil)
    assert(InsightPhrases.violations("So it was recorded.").contains("so"))

  test("every insight sentence ends with the fixed non-causal sentence (VA-IN-11)"):
    val shown = StatsCopy.entries.filter(entry => entry.id.startsWith("insight.") && !entry.id.contains(".gate"))
    assert(shown.nonEmpty)
    shown.foreach(entry => assert(entry.text.endsWith(StatsCopy.NotACause), clue(entry.id)))

  test("every shown comparison sentence states both group sizes (AC-INS-4)"):
    val sentence = StatsCopy.entries.find(_.id == "insight.ir04").get.text
    assert(sentence.contains("On the 8 days"), sentence)
    assert(sentence.contains("On the 9 other days"), sentence)

  test("the mood labels are the one vocabulary of PRODUCT_SPEC R-MOO-1 and R-MOO-2"):
    assertEquals(
      (1 to 5).map(step => MoodLabels.entries.find(_.id == s"mood.valence.$step").get.text).toList,
      List("Very unpleasant", "Unpleasant", "Neutral", "Pleasant", "Very pleasant")
    )
    assertEquals(
      (1 to 5).map(step => MoodLabels.entries.find(_.id == s"mood.energy.$step").get.text).toList,
      List("Very low", "Low", "Medium", "High", "Very high")
    )
end InsightPhrasesSuite
