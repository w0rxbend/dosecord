package dosegram.core.domain.insights

import dosegram.core.application.insights.ReportComposer
import dosegram.core.domain.copy.ForbiddenPhrases
import dosegram.core.domain.copy.GamificationCopy
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.MonthCopy
import dosegram.core.ports.ConsentPurpose

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

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

  test("one year since the first record is a milestone only when that day falls in the reported week"):
    val first = LocalDate.of(2025, 11, 16)
    val inside = DateRange.endingOn(LocalDate.of(2026, 11, 22), 7)
    assertEquals(inside.contains(first.plusYears(1)), true)
    assertEquals(
      Consistency.anniversaries(Map(LoggingDomain.Water -> first, LoggingDomain.Mood -> first.minusDays(1)), inside),
      List(LoggingAnniversary(LoggingDomain.Water, first))
    )
    val outside = DateRange.endingOn(first.plusYears(1).minusDays(1), 7)
    assertEquals(Consistency.anniversaries(Map(LoggingDomain.Water -> first), outside), Nil)

  test("a 29 February record is one year old on 28 February of the next year"):
    val first = LocalDate.of(2024, 2, 29)
    val week = DateRange.endingOn(LocalDate.of(2025, 3, 2), 7)
    assertEquals(
      Consistency.anniversaries(Map(LoggingDomain.Sleep -> first), week),
      List(LoggingAnniversary(LoggingDomain.Sleep, first))
    )
    assertEquals(
      Consistency.anniversaries(Map(LoggingDomain.Sleep -> first), DateRange.endingOn(LocalDate.of(2025, 2, 27), 7)),
      Nil
    )

  test("the one-year line states the fact, in both languages, with none of the words of R-GAM-6"):
    Lang.within(Lang.En):
      assertEquals(
        GamificationCopy.anniversary(LoggingAnniversary(LoggingDomain.Water, LocalDate.of(2025, 11, 16))),
        "One year since the first water entry."
      )
    Lang.within(Lang.Uk):
      assertEquals(
        GamificationCopy.anniversary(LoggingAnniversary(LoggingDomain.Mood, LocalDate.of(2025, 11, 16))),
        "Рік від першої позначки настрою."
      )
    val lines = for
      lang <- List(Lang.En, Lang.Uk)
      domain <- LoggingDomain.values.toList
    yield Lang.within(lang)(GamificationCopy.anniversary(LoggingAnniversary(domain, LocalDate.of(2025, 1, 1))))
    lines.foreach(line => assertEquals(ForbiddenPhrases.violations(line), Nil, line))

  test("a month below its gate keeps the header and drops the progress line when counts are off"):
    val month = YearMonth.of(2026, 10)
    val figures = MonthStats.of(
      month,
      DayOfWeek.MONDAY,
      medication = None,
      hydration = Some(Map(month.atDay(3) -> HydrationDay(500, 1, None))),
      mood = None,
      sleep = None,
      previousMedication = None
    )
    val kyiv = ZoneId.of("Europe/Kyiv")
    assert(MonthCopy.lines(figures, kyiv).exists(_.contains("more")))
    assertEquals(
      MonthCopy.linesOmittingGates(figures, kyiv),
      List("October 2026 · Europe/Kyiv", "1 day with records")
    )

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
