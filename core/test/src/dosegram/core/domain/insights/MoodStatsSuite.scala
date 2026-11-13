package dosegram.core.domain.insights

import dosegram.core.domain.mood.MoodLevel
import dosegram.core.domain.mood.Valence
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Instant
import java.time.LocalDate

/** VISUALS_AND_ANALYTICS section 7.6 (VA-ME-51) and PRODUCT_SPEC R-MOO-5: daily mean, period average over check-in days,
  * distribution, range, variability and the energy figures.
  */
class MoodStatsSuite extends munit.ScalaCheckSuite:

  import TrackingGens.*

  private val day = windowEnd

  private def daysBack(valences: Int*): Map[LocalDate, MoodDay] =
    MoodStats.byDay(valences.zipWithIndex.map((valence, back) => moodOn(day.minusDays(back.toLong), valence)))

  test("a day with valences 2 and 5 has the mean 3.5 and the calendar cell 4 (AC-MOO-4)"):
    val today = MoodStats.byDay(List(moodOn(day, 2), moodOn(day, 5)))(day)
    assertEqualsDouble(today.mean, 3.5, 0.0)
    assertEquals(today.calendarStep, 4)
    assertEquals((today.valenceMin, today.valenceMax, today.n), (2, 5, 2))

  test("a day without a check-in is a gap"):
    assertEquals(MoodStats.byDay(List(moodOn(day, 3))).get(day.minusDays(1)), None)

  test("a check-in without energy is excluded from the energy figures (AC-MOO-8)"):
    val today = MoodStats.byDay(List(moodOn(day, 3, Some(5)), moodOn(day, 3, None), moodOn(day, 3, Some(2))))(day)
    assertEquals(today.energyMean, Some(3.5))
    assertEquals(MoodStats.byDay(List(moodOn(day, 3)))(day).energyMean, None)

  test("the period average is the mean of daily means, so a day with many check-ins does not dominate"):
    val busyDay = List.fill(9)(moodOn(day, 5))
    val quietDays = List(moodOn(day.minusDays(1), 1), moodOn(day.minusDays(2), 1))
    val period = MoodStats.period(MoodStats.byDay(busyDay ++ quietDays), week)
    assertEquals(period.checkIns, 11)
    assertEquals(period.checkInDays, 3)
    assertEqualsDouble(period.average.shown.get, 7.0 / 3.0, 1e-9)

  test("a voided check-in leaves every figure"):
    val voided = moodOn(day, 1).copy(voidedAt = Some(Instant.parse("2026-06-30T13:00:00Z")))
    assertEqualsDouble(MoodStats.byDay(List(moodOn(day, 5), voided))(day).mean, 5.0, 0.0)

  test("a check-in marked for yesterday counts on the stored local date"):
    val forYesterday = moodOn(day, 4).copy(localDate = day.minusDays(1))
    assertEquals(MoodStats.byDay(List(forYesterday)).keySet, Set(day.minusDays(1)))

  test("a week needs three check-in days for an average, a month ten"):
    assertEquals(MoodStats.period(daysBack(3, 4), week).average, Stat.Insufficient(2, 3, week))
    assert(MoodStats.period(daysBack(3, 4, 5), week).average.shown.isDefined)
    assertEquals(MoodStats.period(daysBack(1, 2, 3, 4, 5, 1, 2, 3, 4), month).average, Stat.Insufficient(9, 10, month))

  test("the range is the lowest and the highest daily mean"):
    val period = MoodStats.period(daysBack(2, 4, 3), week)
    assertEquals(period.range.shown, Some(MoodRange(low = 2.0, high = 4.0)))
    assertEquals(MoodStats.period(Map.empty, week).range, Stat.Insufficient(0, 1, week))

  test("the distribution counts days at each quantised level"):
    val days = MoodStats.byDay(List(moodOn(day, 2), moodOn(day, 3), moodOn(day.minusDays(1), 5)))
    val period = MoodStats.period(days, week)
    assertEquals(period.distribution, Map(MoodLevel.V3 -> 1, MoodLevel.V7 -> 1))

  test("the spread is the sample standard deviation of daily means and needs five check-in days"):
    assertEquals(MoodStats.period(daysBack(1, 2, 3, 4), week).spread, Stat.Insufficient(4, 5, week))
    assertEqualsDouble(MoodStats.period(daysBack(1, 2, 3, 4, 5), week).spread.shown.get, Math.sqrt(2.5), 1e-9)
    assertEqualsDouble(MoodStats.period(daysBack(3, 3, 3, 3, 3), week).spread.shown.get, 0.0, 0.0)

  test("the day-to-day change uses pairs of consecutive calendar days only and needs four pairs"):
    val consecutive = daysBack(1, 3, 2, 5, 4)
    assertEqualsDouble(MoodStats.period(consecutive, week).dayToDayChange.shown.get, (2 + 1 + 3 + 1) / 4.0, 1e-9)
    val withGap = consecutive - day.minusDays(2)
    assertEquals(MoodStats.period(withGap, week).dayToDayChange, Stat.Insufficient(2, 4, week))

  test("a mean is named by its quantised level"):
    val captured = Valence.steps.map(valence => MoodLevel.ofMean(valence.step.toDouble))
    assertEquals(captured, List(MoodLevel.V1, MoodLevel.V2, MoodLevel.V4, MoodLevel.V6, MoodLevel.V7))
    assertEquals(MoodLevel.ofMean(3.2), MoodLevel.V4)
    assertEquals(MoodLevel.ofMean(3.5), MoodLevel.V5)
    assertEquals(MoodLevel.ofMean(3.7), MoodLevel.V5)
    assertEquals(MoodLevel.ofMean(2.4), MoodLevel.V3)

  property("every daily mean lies between the day's lowest and highest valence, within 1 and 5"):
    forAll(genMoods) { checkins =>
      MoodStats.byDay(checkins).foreach { (date, summary) =>
        assert(summary.valenceMin >= 1 && summary.valenceMax <= 5, clue(date))
        assert(summary.mean >= summary.valenceMin && summary.mean <= summary.valenceMax, clue(date))
        assert(summary.calendarStep >= summary.valenceMin && summary.calendarStep <= summary.valenceMax, clue(date))
        summary.energyMean.foreach(energy => assert(energy >= 1.0 && energy <= 5.0, clue(date)))
      }
    }

  property("every shown period figure is within its bounds"):
    forAll(genMoods, Gen.oneOf(week, month)) { (checkins, window) =>
      val period = MoodStats.period(MoodStats.byDay(checkins), window)
      assert(period.checkInDays <= window.days)
      assertEquals(period.distribution.values.sum, period.checkInDays)
      for
        range <- period.range.shown
        average <- period.average.shown
      do assert(1.0 <= range.low && range.low <= average && average <= range.high && range.high <= 5.0, clue(period))
      period.spread.shown.foreach(spread => assert(spread >= 0.0 && spread <= 4.0, clue(spread)))
      period.dayToDayChange.shown.foreach(change => assert(change >= 0.0 && change <= 4.0, clue(change)))
      period.energyAverage.shown.foreach(energy => assert(energy >= 1.0 && energy <= 5.0, clue(energy)))
    }

  property("no period figure is shown below its minimum"):
    forAll(genMoods, Gen.oneOf(week, month)) { (checkins, window) =>
      val period = MoodStats.period(MoodStats.byDay(checkins), window)
      val meanDays = if window == week then 3 else 10
      assertEquals(period.average.shown.isDefined, period.checkInDays >= meanDays)
      assertEquals(period.spread.shown.isDefined, period.checkInDays >= 5)
      assertEquals(period.range.shown.isDefined, period.checkInDays >= 1)
      if period.checkInDays < 5 then assert(period.dayToDayChange.shown.isEmpty)
    }

  property("raising one check-in never lowers the period average"):
    forAll(genMoods.suchThat(_.nonEmpty), Gen.choose(0, 1000)) { (checkins, pick) =>
      val index = pick % checkins.size
      val chosen = checkins(index)
      val raised = checkins.updated(index, chosen.copy(valence = Valence.of(5).get))
      for
        before <- MoodStats.period(MoodStats.byDay(checkins), month).average.shown
        after <- MoodStats.period(MoodStats.byDay(raised), month).average.shown
      do assert(after >= before - 1e-12, s"$before -> $after")
    }

  property("on a daylight-saving day of 23 or 25 hours every check-in made inside the day counts toward it"):
    forAll(
      InsightGens.genDstDay.flatMap(d => Gen.listOfN(6, InsightGens.genInstantOn(d._2, d._1)).map(d -> _))
    ) { case ((zone, date, _), instants) =>
      val days = MoodStats.byDay(instants.map(mood(_, zone, 4, None)))
      assertEquals(days.keySet, Set(date))
      assertEquals(days(date).n, 6)
    }
end MoodStatsSuite
