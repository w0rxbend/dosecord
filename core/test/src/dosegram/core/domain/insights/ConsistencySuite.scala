package dosegram.core.domain.insights

import dosegram.core.application.insights.ReportComposer
import dosegram.core.domain.copy.ForbiddenPhrases
import dosegram.core.domain.copy.GamificationCopy
import dosegram.core.domain.copy.Lang
import dosegram.core.ports.ConsentPurpose

import java.time.LocalDate

/** PRODUCT_SPEC section 8.14 and ROADMAP G9.4: counts of logging in a window, nothing that resets, nothing on medication.
  */
class ConsistencySuite extends munit.FunSuite:

  private val week = DateRange.endingOn(LocalDate.of(2026, 11, 22), 7)
  private def day(n: Int): LocalDate = week.start.plusDays(n.toLong)

  test("registry: no counted domain is medication, and a medication reading is dropped (R-GAM-2)"):
    assertEquals(LoggingDomain.values.map(_.toString).toList, List("Water", "Mood", "Sleep", "Food"))
    val byPurpose = ConsentPurpose.values.map(_ -> 1).toMap
    assertEquals(ReportComposer.logging(byPurpose).keySet, LoggingDomain.values.toSet)
    assertEquals(ReportComposer.logging(Map(ConsentPurpose.Medication -> 9)), Map.empty)

  test("a week counts its hit days per domain; a domain without a record is left out, not 0 of 7"):
    val recorded = Map(
      LoggingDomain.Water -> Set(day(0), day(1), day(5), day(9)),
      LoggingDomain.Mood -> Set.empty[LocalDate],
      LoggingDomain.Food -> Set(day(-1), day(6))
    )
    assertEquals(
      Consistency.weekly(recorded, week),
      List(WeeklyConsistency(LoggingDomain.Water, 3, 7), WeeklyConsistency(LoggingDomain.Food, 1, 7))
    )

  test("nothing resets: a thinner week is a smaller count, worded the same way"):
    val full = Consistency.weekly(Map(LoggingDomain.Mood -> (0 to 6).map(day).toSet), week)
    val thin = Consistency.weekly(Map(LoggingDomain.Mood -> Set(day(3))), week)
    Lang.within(Lang.En):
      assertEquals(GamificationCopy.consistency(full), Some("Days with records: mood 7 of 7."))
      assertEquals(GamificationCopy.consistency(thin), Some("Days with records: mood 1 of 7."))
      assertEquals(GamificationCopy.consistency(Nil), None)

  test("a milestone is passed once, when the count crosses it within the week"):
    val before = Map(LoggingDomain.Water -> 8, LoggingDomain.Mood -> 49, LoggingDomain.Sleep -> 100)
    val after = Map(LoggingDomain.Water -> 52, LoggingDomain.Mood -> 49, LoggingDomain.Sleep -> 101, LoggingDomain.Food -> 10)
    assertEquals(
      Consistency.milestones(before, after),
      List(
        LoggingMilestone(LoggingDomain.Water, 10),
        LoggingMilestone(LoggingDomain.Water, 50),
        LoggingMilestone(LoggingDomain.Food, 10)
      )
    )
    assertEquals(Consistency.milestones(after, after), Nil)

  test("every count and milestone line is free of the words of loss and runs, in both languages (R-GAM-6)"):
    val lines = for
      lang <- List(Lang.En, Lang.Uk)
      domain <- LoggingDomain.values.toList
      count <- Consistency.MilestoneCounts
    yield Lang.within(lang)(
      GamificationCopy.milestone(LoggingMilestone(domain, count)) ::
        GamificationCopy.consistency(List(WeeklyConsistency(domain, 2, 7))).toList
    )
    lines.flatten.foreach(line => assertEquals(ForbiddenPhrases.violations(line), Nil, line))
    assert(ForbiddenPhrases.violations("Keep your streak going").nonEmpty)
