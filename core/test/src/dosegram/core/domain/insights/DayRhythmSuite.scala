package dosegram.core.domain.insights

import dosegram.core.domain.AdherenceCounts
import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.EntrySource
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.RhythmCopy
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.sleep.Confidence
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepOrigin
import dosegram.core.domain.sleep.SleepTier
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** The rhythm of the day (supervisor decision, 2026-11-28): records counted by the hour of the local day they were
  * recorded in, per area behind the 14-day gate; the typical bedtime and wake time with their circular spread; the
  * timing of taken doses against their scheduled time; meals by type; and the one rule of days with records.
  */
class DayRhythmSuite extends munit.ScalaCheckSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val today = LocalDate.of(2026, 11, 26)
  private val window = DayRhythm.window(today)
  private val medication = UUID.fromString("00000000-0000-7000-8000-000000000001")
  private val none = YearSources(None, None, None, None, None)

  private def at(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(hour, minute).atZone(kyiv).toInstant
  private def daysBack(n: Int): List[LocalDate] = (0 until n).toList.map(back => today.minusDays(back.toLong))

  private def figures(sources: RhythmSources): RhythmFigures = DayRhythm.of(window, sources)

  private def waterDays(dates: List[LocalDate]): Map[LocalDate, HydrationDay] =
    dates.map(_ -> HydrationDay(500, 1, None)).toMap

  private def takenDose(date: LocalDate, delayMinutes: Int, corrected: Boolean = false): AdherenceInput =
    val scheduled = at(date, 8)
    AdherenceInput(
      scheduled,
      OccurrenceStatus.Taken,
      Some(scheduled.plusSeconds(60L * delayMinutes)),
      Some(scheduled),
      scheduled.plusSeconds(3600),
      manual = false,
      corrected = corrected
    )

  private def night(wakeDay: LocalDate, bed: LocalTime, wake: LocalTime, kind: SleepKind = SleepKind.Main) =
    val wakeAt = wakeDay.atTime(wake).atZone(kyiv).toInstant
    val bedDay = if bed.isAfter(wake) then wakeDay.minusDays(1) else wakeDay
    val onsetAt = bedDay.atTime(bed).atZone(kyiv).toInstant
    SleepEpisode(
      UUID.randomUUID(),
      TrackingGens.account,
      kind,
      SleepInterval(SleepEdge(onsetAt, kyiv, EdgeSource.CheckIn), SleepEdge(wakeAt, kyiv, EdgeSource.CheckIn)),
      SleepOrigin.CheckIn,
      SleepTier.Explicit,
      Confidence.BothExplicit,
      None,
      EntrySource.Bot,
      None,
      None,
      wakeAt,
      None
    )

  private def meal(date: LocalDate, mealType: MealType, hour: Int, minute: Int = 0): MealEntry =
    val when = at(date, hour, minute)
    MealEntry(UUID.randomUUID(), TrackingGens.account, when, date, kyiv, mealType, None, EntrySource.Bot, None, None,
      when, None)

  test("water is counted by the local hour of each entry, the busiest hour the earliest of a tie, and by quarter"):
    val dates = daysBack(14)
    val water = dates.flatMap(date => List(TrackingGens.water(at(date, 8, 10), kyiv, 250), TrackingGens.water(at(date, 19, 40), kyiv, 250)))
    val result = figures(RhythmSources(none.copy(hydration = Some(waterDays(dates))), kyiv, water = water))
    val pattern = result.rows.head.pattern.shown.get
    assertEquals(result.rows.map(_.domain), List(TrendDomain.Hydration))
    assertEquals(pattern.band.counts(8), 14)
    assertEquals(pattern.band.counts(19), 14)
    assertEquals(pattern.band.peak, Some(8))
    assertEquals(pattern.band.byPart, List(DayPart.Morning -> 14, DayPart.Afternoon -> 0, DayPart.Evening -> 14, DayPart.Night -> 0))
    assertEquals(pattern.detail, RhythmDetail.Entries)

  test("an entry's hour is read in the zone it was recorded in, not the account's zone of today"):
    val dates = daysBack(14)
    val tokyo = ZoneId.of("Asia/Tokyo")
    val water = dates.map(date => TrackingGens.water(date.atTime(7, 0).atZone(tokyo).toInstant, tokyo, 250))
    val band = figures(RhythmSources(none.copy(hydration = Some(waterDays(dates))), kyiv, water = water)).rows.head.pattern.shown.get.band
    assertEquals(band.peak, Some(7))

  test("below 14 days with records an area has no pattern, only its days; 14 days show it"):
    val thirteen = daysBack(13)
    val below = figures(RhythmSources(none.copy(hydration = Some(waterDays(thirteen))), kyiv))
    assertEquals(below.rows.head.recordedDays, 13)
    assertEquals(below.rows.head.pattern, Stat.Insufficient(13, 14, window))
    assert(!below.hasPattern)
    val at14 = figures(RhythmSources(none.copy(hydration = Some(waterDays(daysBack(14)))), kyiv))
    assert(at14.hasPattern)

  property("no area shows a pattern below the gate, and every band counts each live record once (VA-ME-03)"):
    forAll(Gen.choose(0, 30), Gen.listOfN(40, Gen.choose(0, 23))): (days, hours) =>
      val dates = daysBack(days)
      val water = hours.zipWithIndex.map((hour, index) => TrackingGens.water(at(today.minusDays((index % 30).toLong), hour), kyiv, 100))
      val row = figures(RhythmSources(none.copy(hydration = Some(waterDays(dates))), kyiv, water = water)).rows.head
      row.pattern.shown match
        case Some(pattern) => days >= 14 && pattern.band.total == water.size
        case None          => days < 14

  test("doses: taken ones by the hour they were recorded; the median and 9 in 10 of their timing, corrections left out"):
    val dates = daysBack(14)
    val doses = dates.zipWithIndex.map((date, index) => takenDose(date, if index < 7 then 10 else 30)) :+
      takenDose(today, 300, corrected = true) :+
      takenDose(today, 0).copy(manual = true)
    val days = dates.map(_ -> MedicationDay(Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(1, 0, 0, 0))))).toMap
    val pattern = figures(RhythmSources(none.copy(medication = Some(days)), kyiv, doses = doses)).rows.head.pattern.shown.get
    assertEquals(pattern.band.total, 15, "the corrected dose has a recorded hour; the as-needed log has no schedule")
    assertEquals(pattern.band.peak, Some(8))
    assertEquals(pattern.band.counts(13), 1)
    assertEquals(pattern.detail, RhythmDetail.Doses(Stat.gated(14, MinimumSample.DelaySamples, window, Basis.Recorded)(DoseTiming(20, 30))))

  test("fewer than 5 timed doses show no timing; a dose taken before its time counts as a negative delay"):
    val dates = daysBack(14)
    val days = dates.map(_ -> MedicationDay(Map(medication -> DoseBuckets.ofResolved(AdherenceCounts(1, 0, 0, 0))))).toMap
    val four = figures(RhythmSources(none.copy(medication = Some(days)), kyiv, doses = dates.take(4).map(takenDose(_, -5))))
    assertEquals(four.rows.head.pattern.shown.map(_.detail), Some(RhythmDetail.Doses(Stat.Insufficient(4, 5, window))))
    val five = figures(RhythmSources(none.copy(medication = Some(days)), kyiv, doses = dates.take(5).map(takenDose(_, -5))))
    assertEquals(five.rows.head.pattern.shown.map(_.detail).collect { case RhythmDetail.Doses(t) => t.shown }.flatten, Some(DoseTiming(-5, 5)))

  test("days whose only doses have no record are no days with records, for the area and for the window"):
    val outage = daysBack(3).map(_ -> MedicationDay(Map(medication -> DoseBuckets.zero.copy(unknown = 1)))).toMap
    val result = figures(RhythmSources(none.copy(medication = Some(outage)), kyiv))
    assertEquals(result.rows.head.recordedDays, 0)
    assertEquals(result.records, RecordedDays(0, 3))
    assert(!result.hasRecords)

  test("sleep: the hours a night covers for at least half an hour, and the usual bedtime and wake time across midnight"):
    val dates = daysBack(14)
    val nights = dates.zipWithIndex.map((date, index) =>
      night(date, if index < 8 then LocalTime.of(23, 40) else LocalTime.of(0, 20), LocalTime.of(7, 10))
    ) :+ night(today, LocalTime.of(14, 0), LocalTime.of(15, 0), SleepKind.Nap)
    val days = SleepStats.byDay(nights)
    val pattern = figures(RhythmSources(none.copy(sleep = Some(days)), kyiv, sleep = nights)).rows.head.pattern.shown.get
    assertEquals(pattern.band.counts(23), 0, "a night from 23:40 covers only 20 minutes of the hour from 23:00")
    assertEquals(pattern.band.counts(0), 14, "a night from 00:20 covers 40 minutes of the hour from 00:00")
    assertEquals(pattern.band.counts(6), 14)
    assertEquals(pattern.band.counts(7), 0, "a wake at 07:10 covers 10 minutes of the hour from 07:00")
    assertEquals(pattern.band.counts(14), 0, "a nap is not a night")
    pattern.detail match
      case RhythmDetail.Nights(bedtime, wake) =>
        assertEquals(bedtime.typicalMinute, 23 * 60 + 40)
        assertEqualsDouble(bedtime.spreadMinutes.toDouble, 20.0, 1.0)
        assertEquals(wake, ClockSpread(7 * 60 + 10, 0))
      case other => fail(s"not a sleep detail: $other")

  test("meals: every meal and snack by hour, and each type with its count and usual time"):
    val dates = daysBack(14)
    val meals = dates.flatMap(date => List(meal(date, MealType.Breakfast, 8, 30), meal(date, MealType.Dinner, 19, 15))) :+
      meal(today, MealType.Snack, 16)
    val days = dates.map(_ -> MealDay(1, 0, 1, 0)).toMap
    val pattern = figures(RhythmSources(none.copy(food = Some(days)), kyiv, meals = meals)).rows.head.pattern.shown.get
    assertEquals(pattern.band.total, 29)
    assertEquals(
      pattern.detail,
      RhythmDetail.Meals(List(MealTime(MealType.Breakfast, 14, 510), MealTime(MealType.Dinner, 14, 1155), MealTime(MealType.Snack, 1, 960)))
    )

  test("the lines name times and counts only, in English and Ukrainian, with no forbidden word"):
    val dates = daysBack(20)
    val water = dates.map(date => TrackingGens.water(at(date, 9), kyiv, 250))
    val result = figures(RhythmSources(none.copy(hydration = Some(waterDays(dates))), kyiv, water = water))
    val english = RhythmCopy.lines(result, kyiv, keepGates = true)
    assert(english.contains("Water (20 days): 20 entries, most often 09:00 to 10:00 · morning 20, afternoon 0, evening 0, night 0"), english)
    val ukrainian = Lang.within(Lang.Uk)(RhythmCopy.lines(result, kyiv, keepGates = true))
    assert(ukrainian.contains("Вода (20 днів): 20 записів, найчастіше 09:00–10:00 · ранок 20, день 0, вечір 0, ніч 0"), ukrainian)
    assertEquals((english ++ ukrainian).flatMap(InsightPhrases.violations), Nil)
end DayRhythmSuite
