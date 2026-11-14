package dosegram.core.domain.input

import dosegram.contracts.Weekday
import dosegram.contracts.Weekday.*
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The snooze-duration, day-list and dose-amount grammars of BOT_UX section 15.6. */
class DurationAndDaysSuite extends munit.ScalaCheckSuite:

  private def minutes(text: String): Int =
    SnoozeDuration.parse(text).fold(r => fail(s"'$text' rejected: $r"), identity)

  test("snooze: a bare number is minutes; m, min, h and hour spellings"):
    assertEquals(minutes("10"), 10)
    assertEquals(minutes("10m"), 10)
    assertEquals(minutes("10 min"), 10)
    assertEquals(minutes("10 minutes"), 10)
    assertEquals(minutes("1h"), 60)
    assertEquals(minutes("1 hour"), 60)
    assertEquals(minutes("2 hours"), 120)
    assertEquals(minutes("1.5h"), 90)
    assertEquals(minutes("1,5 h"), 90)
    assertEquals(minutes("1h30"), 90)
    assertEquals(minutes("1h 30m"), 90)
    assertEquals(minutes("½h"), 30)
    assertEquals(minutes("half an hour"), 30)
    assertEquals(minutes("for 20 min."), 20)
    assertEquals(minutes("１５"), 15)

  test("snooze: rejects name the part and the bounds"):
    assertEquals(SnoozeDuration.parse("soon"), Left(SnoozeDuration.Rejection.NotADuration("soon")))
    assertEquals(SnoozeDuration.parse("10 parsecs"), Left(SnoozeDuration.Rejection.NotADuration("10 parsecs")))
    assertEquals(SnoozeDuration.parse(""), Left(SnoozeDuration.Rejection.Empty))
    assertEquals(SnoozeDuration.parse("0"), Left(SnoozeDuration.Rejection.OutOfRange(1, 1440)))
    assertEquals(SnoozeDuration.parse("25h"), Left(SnoozeDuration.Rejection.OutOfRange(1, 1440)))

  private def days(text: String): List[Weekday] =
    DayList.parse(text).fold(r => fail(s"'$text' rejected: $r"), identity)

  test("days: names, short names, plurals, numbers, ranges and the common phrases"):
    assertEquals(days("mon wed fri"), List(Mon, Wed, Fri))
    assertEquals(days("Monday, Wednesday and Friday"), List(Mon, Wed, Fri))
    assertEquals(days("fri, mon"), List(Mon, Fri))
    assertEquals(days("tues thurs"), List(Tue, Thu))
    assertEquals(days("on Saturdays"), List(Sat))
    assertEquals(days("1 3 5"), List(Mon, Wed, Fri))
    assertEquals(days("weekdays"), List(Mon, Tue, Wed, Thu, Fri))
    assertEquals(days("weekends"), List(Sat, Sun))
    assertEquals(days("every day"), Weekday.values.toList)
    assertEquals(days("daily"), Weekday.values.toList)
    assertEquals(days("mon-fri"), List(Mon, Tue, Wed, Thu, Fri))
    assertEquals(days("fri-mon"), List(Mon, Fri, Sat, Sun))
    assertEquals(days("1-3"), List(Mon, Tue, Wed))

  test("days: a word that is not a day is named"):
    assertEquals(DayList.parse("mon funday"), Left(DayList.Rejection.NotADay("funday")))
    assertEquals(DayList.parse("8"), Left(DayList.Rejection.NotADay("8")))
    assertEquals(DayList.parse(" "), Left(DayList.Rejection.Empty))

  test("dose: decimal comma, fractions, Unicode digits and an abbreviated unit"):
    assertEquals(DoseText.parse("1 tab"), (Some(BigDecimal(1)), Some("tab")))
    assertEquals(DoseText.parse("0,5"), (Some(BigDecimal("0.5")), None))
    assertEquals(DoseText.parse("½"), (Some(BigDecimal("0.5")), None))
    assertEquals(DoseText.parse("1½ tablets"), (Some(BigDecimal("1.5")), Some("tablets")))
    assertEquals(DoseText.parse("1/2 tablet"), (Some(BigDecimal("0.5")), Some("tablet")))
    assertEquals(DoseText.parse("1 1/2 tablets"), (Some(BigDecimal("1.5")), Some("tablets")))
    assertEquals(DoseText.parse(".5 ml"), (Some(BigDecimal("0.5")), Some("ml")))
    assertEquals(DoseText.parse("1000 IU"), (Some(BigDecimal(1000)), Some("IU")))
    assertEquals(DoseText.parse("２ puffs"), (Some(BigDecimal(2)), Some("puffs")))
    assertEquals(DoseText.parse("a pinch"), (None, Some("a pinch")))
    assertEquals(DoseText.parse(""), (None, None))
    assertEquals(DoseText.parse("1/99999999999999999999 tab"), (None, Some("1/99999999999999999999 tab")))
    assertEquals(DoseText.parse("0 mg"), (None, Some("0 mg")))

  test("snooze: the Ukrainian units and half an hour (ROADMAP G2.22)"):
    assertEquals(minutes("10 хв"), 10)
    assertEquals(minutes("15 хвилин"), 15)
    assertEquals(minutes("1 год"), 60)
    assertEquals(minutes("2 години"), 120)
    assertEquals(minutes("1,5 год"), 90)
    assertEquals(minutes("1 год 30 хв"), 90)
    assertEquals(minutes("пів години"), 30)
    assertEquals(minutes("на 20 хв"), 20)

  test("days: the Ukrainian names, groups and ranges (ROADMAP G2.22)"):
    assertEquals(days("пн ср пт"), List(Mon, Wed, Fri))
    assertEquals(days("понеділок, середа і пʼятниця"), List(Mon, Wed, Fri))
    assertEquals(days("п'ятниця"), List(Fri))
    assertEquals(days("будні"), List(Mon, Tue, Wed, Thu, Fri))
    assertEquals(days("вихідні"), List(Sat, Sun))
    assertEquals(days("щодня"), Weekday.values.toList)
    assertEquals(days("кожен день"), Weekday.values.toList)
    assertEquals(days("пн-пт"), List(Mon, Tue, Wed, Thu, Fri))
    assertEquals(days("сб нд"), List(Sat, Sun))

  property("no snooze, day or dose input throws"):
    forAll(Gen.oneOf(Gen.asciiStr, Gen.listOf(Gen.choose(Char.MinValue, Char.MaxValue)).map(_.mkString))) { text =>
      SnoozeDuration.parse(text)
      DayList.parse(text)
      DoseText.parse(text)
      true
    }
