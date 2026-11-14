package dosegram.core.application.insights

import dosegram.core.application.FakeConsent
import dosegram.core.application.InMemoryTracking
import dosegram.core.application.ManualClock
import dosegram.core.application.SequentialIds
import dosegram.core.application.TrackingTxDouble
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.mood.MoodService
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.application.nutrition.NutritionService
import dosegram.core.domain.Adherence
import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.copy.MonthCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseRecord
import dosegram.core.domain.insights.InsightGens
import dosegram.core.domain.insights.Insight
import dosegram.core.domain.insights.InsightRule
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.Ratio
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.Stat
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.WeekDayFood
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** ROADMAP G2.20, G4.4, G5.6 (rollup) and G6.8 on the in-memory tables: the read API answers from rollups that equal
  * the pure functions over the same rows, prints the data gate below a minimum, and shows no domain without consent.
  */
class StatsQueryServiceSuite extends munit.ScalaCheckSuite:

  private val account = UUID.fromString("00000000-0000-7000-8000-0000000000c1")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val now = Instant.parse("2026-06-30T15:00:00Z")
  private val today = LocalDate.parse("2026-06-30")
  private val medication = InsightGens.medications.head

  private final class Rig(consent: FakeConsent = FakeConsent.all(account)):
    val clock = ManualClock(now)
    val tables = InMemoryTracking(Map(account -> kyiv))
    val tx = TrackingTxDouble(tables, consent, Some(dosegram.core.chat.MediatorFakes.NoPromptPlans))
    val rollups = RollupService(tx, clock)
    val stats = StatsQueryService(rollups, clock)
    val water = HydrationService(clock, SequentialIds())
    val moods = MoodService(clock, SequentialIds())
    val nutrition = NutritionService(clock, SequentialIds())

    /** Moves the clock to 12:00 UTC of `daysAgo` days before today, records, and returns to now. */
    def on(daysAgo: Int)(record: => Unit): Unit =
      clock.set(now.minus(Duration.ofDays(daysAgo.toLong)).minus(Duration.ofHours(3)))
      record
      clock.set(now)

    def drink(millilitres: Int, key: String): Unit =
      val amount = WaterAmount.ofMillilitres(millilitres).toOption.get
      assert(water.log(tx, LogWater(account, amount, EntryDay.Today, EntrySource.Bot, key)).isRight)

    def eat(mealType: MealType, key: String): Unit =
      assert(nutrition.log(tx, LogMeal(account, MealInput(mealType, None, None), EntrySource.Bot, key)).isRight)

    def feel(valence: Int, key: String): Unit =
      val command = CheckIn(account, Valence.of(valence).get, None, Nil, EntryDay.Today, EntrySource.Bot, key)
      assert(moods.checkIn(tx, command).isRight)

    def dose(daysAgo: Int, status: OccurrenceStatus): Unit =
      val scheduledFor = now.minus(Duration.ofDays(daysAgo.toLong)).minus(Duration.ofHours(8))
      val input = AdherenceInput(
        scheduledFor = scheduledFor,
        status = status,
        effectiveAt = Option.when(status == OccurrenceStatus.Taken)(scheduledFor.plusSeconds(300)),
        resolvedAt = Option.when(status == OccurrenceStatus.Skipped || status == OccurrenceStatus.Missed)(
          scheduledFor.plusSeconds(7200)
        ),
        dueWindowEnd = scheduledFor.plusSeconds(3600),
        manual = false,
        corrected = false
      )
      tables.addDose(account, DoseRecord(medication, input))

  test("8 taken, 1 skipped, 1 missed and 2 unknown show 80 percent with the exclusions beside it (AC-INT-8)"):
    val rig = Rig()
    (0 until 8).foreach(_ => rig.dose(1, OccurrenceStatus.Taken))
    rig.dose(2, OccurrenceStatus.Skipped)
    rig.dose(2, OccurrenceStatus.Missed)
    rig.dose(3, OccurrenceStatus.Unknown)
    rig.dose(3, OccurrenceStatus.Unknown)
    rig.dose(3, OccurrenceStatus.Cancelled)
    val view = rig.stats.medication(rig.tx, account, StatsPeriod.Last7Days).toOption.get
    assertEquals(view.period.total.adherence.shown, Some(Ratio(8, 10)))
    assertEquals(view.period.total.buckets.unknown, 2)
    assertEquals(view.period.perMedication.keySet, Set(medication))
    val text = rig.stats.summary(rig.tx, account, StatsPeriod.Last7Days).toOption.get.lines
    assertEquals(
      text,
      List(
        "Last 7 days · Europe/Kyiv",
        "Doses: 8 of 10 taken (80%) · skipped 1 · not confirmed 1",
        "8 of 8 taken doses on time",
        "9 of 10 answered",
        "2 with no record, not counted"
      )
    )

  test("only unknown rows print No data, never a percentage"):
    val rig = Rig()
    rig.dose(1, OccurrenceStatus.Unknown)
    val summary = rig.stats.summary(rig.tx, account, StatsPeriod.Last30Days).toOption.get
    assertEquals(summary.lines.tail, List("Doses: No data", "1 with no record, not counted"))
    assertEquals(summary.window, DateRange.endingOn(today, 30))

  test("a period with nothing recorded says so"):
    val rig = Rig()
    val summary = rig.stats.summary(rig.tx, account, StatsPeriod.Last7Days).toOption.get
    assertEquals(summary.lines, List("Last 7 days · Europe/Kyiv", StatsCopy.Empty))

  test("a dose outside the window is not counted"):
    val rig = Rig()
    rig.dose(9, OccurrenceStatus.Taken)
    assertEquals(rig.stats.medication(rig.tx, account, StatsPeriod.Last7Days).toOption.get.period.total.buckets.isEmpty, true)
    assertEquals(rig.stats.medication(rig.tx, account, StatsPeriod.Last30Days).toOption.get.period.total.buckets.takenOnTime, 1)

  test("water and mood print their figures with coverage from the minimum on, and the data gate below it"):
    val rig = Rig()
    rig.on(20)(rig.water.setGoal(rig.tx, account, Some(2000)))
    rig.on(1) { rig.drink(2100, "w1"); rig.feel(4, "m1") }
    rig.on(2) { rig.drink(900, "w2"); rig.feel(3, "m2") }
    val gated = rig.stats.summary(rig.tx, account, StatsPeriod.Last7Days).toOption.get.lines.tail
    assertEquals(
      gated,
      List(
        "Water: 1 more recorded day with a goal for the goal count · 1 more recorded day for an average · " +
          "2 of 7 days have data",
        "Mood: 2 check-ins · 1 more day with a check-in for an average · 2 of 7 days have data"
      )
    )
    rig.on(3) { rig.drink(3000, "w3"); rig.feel(5, "m3") }
    val shown = rig.stats.summary(rig.tx, account, StatsPeriod.Last7Days).toOption.get.lines.tail
    assertEquals(
      shown,
      List(
        "Water: goal reached on 2 of 3 recorded days · average 2.0 l a day · 3 of 7 days have data",
        "Mood: 3 check-ins · average 4.0 (pleasant) · 3 of 7 days have data"
      )
    )

  test("today's summary shows the day's water against the user's own goal and the day's mood"):
    val rig = Rig()
    rig.water.setGoal(rig.tx, account, Some(2000))
    rig.drink(1250, "w1")
    rig.feel(2, "m1")
    rig.feel(5, "m2")
    val lines = rig.stats.summary(rig.tx, account, StatsPeriod.Today).toOption.get.lines
    assertEquals(
      lines,
      List(
        "Today · Europe/Kyiv",
        "Water today: 1.25 of 2.0 l (62%)",
        "Mood today: 3.5 (slightly pleasant) · 2 check-ins"
      )
    )

  test("a domain without consent is refused on its own and left out of the summary"):
    val consent = FakeConsent.all(account)
    val rig = Rig(consent)
    rig.drink(500, "w1")
    rig.feel(3, "m1")
    consent.withdraw(account, ConsentPurpose.Mood)
    assertEquals(rig.stats.mood(rig.tx, account, StatsPeriod.Last7Days), Left(StatsRefusal.ConsentMissing(ConsentPurpose.Mood)))
    assert(rig.stats.hydration(rig.tx, account, StatsPeriod.Last7Days).isRight)
    val lines = rig.stats.summary(rig.tx, account, StatsPeriod.Today).toOption.get.lines
    assertEquals(lines, List("Today · Europe/Kyiv", "Water today: 0.5 l"))

  test("the calendar reads answer the recorded days of any range, and nothing without the consent"):
    val rig = Rig()
    rig.on(40)(rig.drink(700, "w-old"))
    rig.on(1)(rig.drink(500, "w1"))
    rig.on(1)(rig.feel(4, "m1"))
    rig.dose(2, OccurrenceStatus.Taken)
    val range = DateRange.endingOn(today, 45)
    val water = rig.stats.hydrationCalendar(rig.tx, account, range).toOption.get
    assertEquals(water.view.mapValues(_.totalMl).toMap, Map(today.minusDays(40) -> 700, today.minusDays(1) -> 500))
    assertEquals(rig.stats.moodCalendar(rig.tx, account, range).map(_.keySet), Right(Set(today.minusDays(1))))
    assertEquals(rig.stats.medicationCalendar(rig.tx, account, range).map(_.keySet), Right(Set(today.minusDays(2))))

    val waterOnly = Rig(FakeConsent(Set(account -> ConsentPurpose.Hydration)))
    assertEquals(
      waterOnly.stats.moodCalendar(waterOnly.tx, account, range),
      Left(StatsRefusal.ConsentMissing(ConsentPurpose.Mood))
    )
    assertEquals(
      waterOnly.stats.medicationCalendar(waterOnly.tx, account, range),
      Left(StatsRefusal.ConsentMissing(ConsentPurpose.Medication))
    )

  test("an unknown account is refused"):
    val unknown = UUID.fromString("00000000-0000-7000-8000-0000000000c9")
    val rig = Rig(FakeConsent.all(unknown))
    assertEquals(rig.stats.summary(rig.tx, unknown, StatsPeriod.Today), Left(StatsRefusal.UnknownAccount))
    assertEquals(rig.stats.medication(rig.tx, unknown, StatsPeriod.Today), Left(StatsRefusal.UnknownAccount))

  test("a read refreshes the day that a write changed, and a void changes the figure back"):
    val rig = Rig()
    rig.drink(500, "w1")
    assertEquals(rig.stats.hydration(rig.tx, account, StatsPeriod.Today).toOption.get.days(today).totalMl, 500)
    assert(rig.tables.dirty.isEmpty)
    val entry = rig.tables.entries.keys.head
    rig.water.delete(rig.tx, account, entry)
    assertEquals(rig.stats.hydration(rig.tx, account, StatsPeriod.Today).toOption.get.days.get(today), None)
    assert(rig.tables.stored.isEmpty)

  test("a goal change is seen by the next read of the same day"):
    val rig = Rig()
    rig.drink(1500, "w1")
    assertEquals(rig.stats.hydration(rig.tx, account, StatsPeriod.Today).toOption.get.days(today).goalMl, None)
    rig.water.setGoal(rig.tx, account, Some(1000))
    val day = rig.stats.hydration(rig.tx, account, StatsPeriod.Today).toOption.get.days(today)
    assertEquals((day.goalMl, day.isGoalDay), (Some(1000), true))

  test("the worker step takes dirty keys off the queue and leaves the same rollups as a read"):
    val rig = Rig()
    rig.on(1)(rig.drink(700, "w1"))
    rig.feel(3, "m1")
    rig.dose(1, OccurrenceStatus.Taken)
    assert(rig.tables.dirty.nonEmpty)
    while rig.rollups.drain(2) > 0 do ()
    assert(rig.tables.dirty.isEmpty)
    val drained = rig.tables.stored.toMap
    RollupDomain.values.foreach(rig.rollups.rebuild(account, _, DateRange.endingOn(today, 30)))
    assertEquals(rig.tables.stored.toMap, drained)

  test("the comparison needs the cross-domain consent on top of both domain consents (VA-IN-01)"):
    val consent = FakeConsent.all(account)
    val rig = Rig(consent)
    consent.withdraw(account, ConsentPurpose.CrossDomainPatterns)
    assertEquals(
      rig.stats.waterGoalAndMood(rig.tx, account, ComparisonWindow.Days28),
      Left(StatsRefusal.ConsentMissing(ConsentPurpose.CrossDomainPatterns))
    )

  test("the comparison says how many days are still needed, then shows both groups with their sizes"):
    val rig = Rig()
    rig.on(27)(rig.water.setGoal(rig.tx, account, Some(2000)))
    def day(daysAgo: Int): Unit =
      val reached = daysAgo % 2 == 0
      rig.on(daysAgo):
        rig.drink(if reached then 2200 else 800, s"w$daysAgo")
        rig.feel(if reached then 4 else 3, s"m$daysAgo")
    (1 to 13).foreach(day)
    val window = DateRange.endingOn(today, 28)
    val early = rig.stats.waterGoalAndMood(rig.tx, account, ComparisonWindow.Days28).toOption.get
    assertEquals(early, Comparison.TooFewDays(InsightRule.WaterGoalAndMood, 13, 14, window))
    assertEquals(StatsCopy.comparison(early), Some("1 more day with both water and mood records for this comparison."))
    (14 to 16).foreach(day)
    val shown = rig.stats.waterGoalAndMood(rig.tx, account, ComparisonWindow.Days28).toOption.get
    val sentence = StatsCopy.comparison(shown).get
    assertEquals(
      sentence,
      "On the 8 days you reached your water goal, your average mood was 4.0 (pleasant). " +
        "On the 8 other days it was 3.0 (neutral). This compares your own days. It does not show a cause."
    )
    assertEquals(InsightPhrases.violations(sentence), Nil)

  private val genHistory: Gen[List[DoseRecord]] =
    Gen
      .choose(0, 40)
      .flatMap(Gen.listOfN(_, Gen.choose(0L, 40L * 86400L).flatMap { secondsAgo =>
        InsightGens.genInputAt(now.minusSeconds(secondsAgo)).map(DoseRecord(medication, _))
      }))

  property("adherence from rollups equals the inherited Adherence oracle over the same rows"):
    forAll(genHistory, Gen.oneOf(StatsPeriod.values.toList)) { (history, period) =>
      val rig = Rig()
      history.foreach(rig.tables.addDose(account, _))
      val window = period.window(today)
      val inWindow = history.map(_.input).filter(MedicationStats.dayOf(_, kyiv).exists(window.contains))
      val view = rig.stats.medication(rig.tx, account, period).toOption.get.period
      assertEquals(view.total.buckets.resolved, Adherence.counts(inWindow))
      assertEquals(view.total.adherence.shown.map(_.fraction), Adherence.adherence(inWindow).filter(_ => inWindow.nonEmpty && Adherence.counts(inWindow).resolved >= 5))
      assertEquals(view.delay.shown, Adherence.delayStats(inWindow).filter(_.samples >= 5))
    }

  property("a rollup maintained write by write equals one rebuilt from scratch, in the old zone and after a zone change"):
    forAll(genHistory, Gen.listOf(Gen.choose(1, 5)), Gen.oneOf("Asia/Tokyo", "America/New_York", "UTC")) {
      (history, drains, otherZone) =>
        val rig = Rig()
        val month = DateRange.endingOn(today, 45)
        history.zipAll(drains, null, 0).foreach { (record, drain) =>
          Option(record).foreach(rig.tables.addDose(account, _))
          rig.rollups.drain(drain)
        }
        def settled(): Map[LocalDate, Any] =
          while rig.rollups.drain(50) > 0 do ()
          rig.tables.rollups.list(account, RollupDomain.Medication, month)
        val incremental = settled()
        assertEquals(incremental.view.mapValues(_.toString).toMap, fromScratch(history, kyiv, month))
        rig.tables.setZone(account, ZoneId.of(otherZone))
        assertEquals(settled().view.mapValues(_.toString).toMap, fromScratch(history, ZoneId.of(otherZone), month))
    }

  private def fromScratch(history: List[DoseRecord], zone: ZoneId, range: DateRange): Map[LocalDate, String] =
    MedicationStats
      .byDay(history, zone)
      .view
      .filterKeys(range.contains)
      .mapValues(day => dosegram.core.domain.insights.RollupMetrics.Medication(day).toString)
      .toMap

  test("no figure of a statistic is a bare number: a period below five doses is Insufficient"):
    val rig = Rig()
    (0 until 4).foreach(_ => rig.dose(1, OccurrenceStatus.Taken))
    val total = rig.stats.medication(rig.tx, account, StatsPeriod.Last7Days).toOption.get.period.total
    assertEquals(total.adherence, Stat.Insufficient(4, 5, DateRange.endingOn(today, 7)))
  test("a month answers one block per consented domain, the gate, and the previous month as its baseline"):
    val consent = FakeConsent.all(account)
    val rig = Rig(consent)
    consent.withdraw(account, ConsentPurpose.Mood)
    (1 to 15).foreach(daysAgo => rig.on(daysAgo)(rig.drink(1500, s"w$daysAgo")))
    (31 to 40).foreach(daysAgo => rig.dose(daysAgo, OccurrenceStatus.Taken))
    val view = rig.stats.month(rig.tx, account, java.time.YearMonth.of(2026, 6)).toOption.get
    assertEquals(view.figures.hydration.map(_.recordedDays), Some(15))
    assert(view.figures.isReportable)
    assertEquals(view.figures.mood, None)
    assertEquals(view.figures.previousAdherence.shown, Some(Ratio(10, 10)))
    assertEquals(view.figures.medication.map(_.recordedDays), Some(0))

  test("the weekly card counts each day's logged main meals and its snacks, and has no food row without the consent"):
    val consent = FakeConsent.all(account)
    // The dose marks read the occurrences, which this double does not hold.
    consent.withdraw(account, ConsentPurpose.Medication)
    val rig = Rig(consent)
    rig.on(1) {
      rig.eat(MealType.Breakfast, "b1")
      rig.eat(MealType.Lunch, "l1")
      rig.eat(MealType.Lunch, "l2")
      rig.eat(MealType.Snack, "s1")
    }
    rig.on(3)(rig.eat(MealType.Dinner, "d3"))
    val window = DateRange.endingOn(today, 7)
    val food = ReportViews(rig.stats).week(rig.tx, account, window).food
    val expected = window.dates.map {
      case date if date == today.minusDays(1) => Some(WeekDayFood(mainMeals = 2, snacks = 1))
      case date if date == today.minusDays(3) => Some(WeekDayFood(mainMeals = 1, snacks = 0))
      case _                                  => None
    }
    assertEquals(food, Some(expected))
    consent.withdraw(account, ConsentPurpose.Nutrition)
    assertEquals(ReportViews(rig.stats).week(rig.tx, account, window).food, None)

  test("a month has a food block with the nutrition consent and none without it"):
    val consent = FakeConsent.all(account)
    consent.withdraw(account, ConsentPurpose.Medication)
    val rig = Rig(consent)
    (1 to 14).foreach(daysAgo => rig.on(daysAgo)(rig.eat(MealType.Lunch, s"l$daysAgo")))
    val june = java.time.YearMonth.of(2026, 6)
    val view = rig.stats.month(rig.tx, account, june).toOption.get
    assertEquals(view.figures.food.map(_.recordedDays), Some(14))
    assert(view.figures.isReportable)
    assert(MonthCopy.lines(view.figures, kyiv).exists(_.startsWith("Food: 14 meals logged")), MonthCopy.lines(view.figures, kyiv))
    consent.withdraw(account, ConsentPurpose.Nutrition)
    assertEquals(rig.stats.month(rig.tx, account, june).toOption.get.figures.food, None)

  test("the insights list runs no cross-domain rule without its consent, and the single-domain ones without it"):
    val consent = FakeConsent.all(account)
    val rig = Rig(consent)
    rig.on(27)(rig.water.setGoal(rig.tx, account, Some(2000)))
    (1 to 20).foreach { daysAgo =>
      val reached = daysAgo % 2 == 0
      rig.on(daysAgo):
        rig.drink(if reached then 2200 else 800, s"w$daysAgo")
        rig.feel(if reached then 5 else 3, s"m$daysAgo")
    }
    def patterns(view: InsightsView): List[InsightRule] =
      view.list.shown.collect { case Insight.Pattern(comparison, _) => comparison.rule }
    val on = rig.stats.insights(rig.tx, account, ComparisonWindow.Days28).toOption.get
    assert(on.crossDomain)
    assert(patterns(on).contains(InsightRule.WaterGoalAndMood), clue(on.list))
    consent.withdraw(account, ConsentPurpose.CrossDomainPatterns)
    val off = rig.stats.insights(rig.tx, account, ComparisonWindow.Days28).toOption.get
    assert(!off.crossDomain)
    assertEquals(patterns(off), Nil)
    assertEquals(off.list.window, DateRange.endingOn(today, 28))
end StatsQueryServiceSuite
