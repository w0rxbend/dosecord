package dosegram.infra.db

import dosegram.core.application.hydration.HydrationRefusal
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.insights.ComparisonWindow
import dosegram.core.application.insights.RollupService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.mood.MoodService
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.hydration.EnteredAmount
import dosegram.core.domain.hydration.HydrationUnit
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupKey
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.ConsentCheck
import dosegram.core.ports.IdSource
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** ROADMAP G5.3, G5.3b, G5.3c, G5.6 (rollup), G6.1 (domain part) and G6.8 on Testcontainers Postgres 18: the hydration
  * and mood use cases on the real tables, the rollup queue fed by the schema triggers, and figures that equal the pure
  * functions over the same rows.
  */
class TrackingPgSuite extends PgSuite, munit.ScalaCheckSuite:

  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(15)

  private val now = Instant.parse("2026-06-30T15:00:00Z")
  private val today = LocalDate.parse("2026-06-30")
  private val everyone: ConsentCheck = (_, _) => true
  private lazy val fixtures = Fixtures(dataSource)

  private final class Rig(zone: String = "Europe/Kyiv"):
    val account: UUID = fixtures.account(zone)
    val clock = MutableClock(now)
    val uow = PgTrackingUnitOfWork(dataSource)
    val ids: IdSource = IdSource.uuidV7(RandomGenerator.getDefault)
    val water = HydrationService(uow, everyone, clock, ids)
    val moods = MoodService(uow, everyone, clock, ids)
    val rollups = RollupService(uow, clock)
    val stats = StatsQueryService(uow, rollups, everyone, clock)

    def on(daysAgo: Int)(record: => Unit): Unit =
      clock.at = now.minus(Duration.ofDays(daysAgo.toLong)).minus(Duration.ofHours(3))
      record
      clock.at = now

    def drink(millilitres: Int, key: String, day: EntryDay = EntryDay.Today): UUID =
      val amount = WaterAmount.ofMillilitres(millilitres).toOption.get
      water.log(LogWater(account, amount, day, EntrySource.Bot, key)).fold(r => fail(s"refused: $r"), _.entry.id)

    def feel(valence: Int, key: String, energy: Option[Int] = None): UUID =
      val command =
        CheckIn(account, Valence.of(valence).get, energy.flatMap(Energy.of), Nil, EntryDay.Today, EntrySource.Bot, key)
      moods.checkIn(command).fold(r => fail(s"refused: $r"), _.checkin.id)

    def dirty(domain: RollupDomain): Set[LocalDate] =
      withConnection { conn =>
        given Connection = conn
        sql"SELECT local_date FROM rollup_dirty WHERE account_id = $account AND domain = ${domain.dbValue}"
          .query[LocalDate]()
          .toSet
      }

    def stored(domain: RollupDomain): Map[LocalDate, RollupMetrics] =
      uow.transaction(_.rollups.list(account, domain, DateRange.endingOn(today, 60)))

  test("an entry is stored with the value and unit as entered, and read back the same (AC-HYD-3)"):
    val rig = Rig()
    val entered = EnteredAmount(BigDecimal(12), HydrationUnit.FluidOunce)
    val amount = WaterAmount.of(entered, glassMl = 250).toOption.get
    val logged = rig.water.log(LogWater(rig.account, amount, EntryDay.Today, EntrySource.MiniApp, "k1")).toOption.get
    val read = rig.uow.transaction(_.hydration.get(rig.account, logged.entry.id)).get
    assertEquals((read.amount.millilitres, read.amount.entered), (355, entered))
    assertEquals((read.localDate, read.zone.getId, read.source), (today, "Europe/Kyiv", EntrySource.MiniApp))
    assertEquals(read.id.version(), 7)

  test("eight concurrent deliveries of one tap record exactly one entry (AC-HYD-2)"):
    val rig = Rig()
    val amount = WaterAmount.ofMillilitres(250).toOption.get
    val start = CountDownLatch(1)
    val outcomes = ox.supervised:
      val forks = List.fill(8)(ox.fork:
        start.await()
        rig.water.log(LogWater(rig.account, amount, EntryDay.Today, EntrySource.Bot, "one-tap")))
      start.countDown()
      forks.map(_.join())
    assertEquals(outcomes.count(_.exists(!_.replayed)), 1)
    assertEquals(outcomes.flatMap(_.toOption).map(_.entry.id).distinct.size, 1)
    assertEquals(rig.water.day(rig.account, EntryDay.Today).map(_.recorded), Right(Some(HydrationDay(250, 1, None))))

  test("Undo voids the entry, the total follows at once, and a second Undo changes nothing"):
    val rig = Rig()
    rig.drink(300, "k1")
    val second = rig.drink(500, "k2")
    assertEquals(rig.water.undo(rig.account, second).map(r => (r.changed, r.day.recorded.map(_.totalMl))), Right((true, Some(300))))
    assertEquals(rig.water.undo(rig.account, second).map(r => (r.changed, r.day.recorded.map(_.totalMl))), Right((false, Some(300))))
    rig.clock.advance(Duration.ofMinutes(16))
    val first = rig.uow.transaction(_.hydration.findByClientKey(rig.account, "k1")).get.id
    assertEquals(rig.water.undo(rig.account, first), Left(HydrationRefusal.UndoWindowPassed))

  test("an entry of one account is invisible to another"):
    val owner = Rig()
    val other = Rig()
    val entry = owner.drink(300, "k1")
    assertEquals(other.water.undo(other.account, entry), Left(HydrationRefusal.UnknownEntry))
    assertEquals(other.water.day(other.account, EntryDay.Today).map(_.recorded), Right(None))
    assertEquals(other.stats.hydration(other.account, StatsPeriod.Last7Days).map(_.days), Right(Map.empty))

  test("every write queues its day in the same transaction, and a read answers from the refreshed rollup"):
    val rig = Rig()
    rig.on(20)(rig.water.setGoal(rig.account, Some(2000)))
    rig.drink(1200, "k1")
    rig.drink(900, "y1", EntryDay.Yesterday)
    assertEquals(rig.dirty(RollupDomain.Hydration), Set(today, today.minusDays(1)))
    val view = rig.stats.hydration(rig.account, StatsPeriod.Last7Days).toOption.get
    assertEquals(view.days, Map(today -> HydrationDay(1200, 1, Some(2000)), today.minusDays(1) -> HydrationDay(900, 1, Some(2000))))
    assertEquals(rig.dirty(RollupDomain.Hydration), Set.empty[LocalDate])
    assertEquals(rig.stored(RollupDomain.Hydration).keySet, Set(today, today.minusDays(1)))

  test("a goal change queues every recorded day from its first day on, and past days keep their goal"):
    val rig = Rig()
    rig.on(3)(rig.drink(1000, "k1"))
    rig.on(1)(rig.drink(1000, "k2"))
    rig.stats.hydration(rig.account, StatsPeriod.Last7Days)
    rig.on(2)(rig.water.setGoal(rig.account, Some(800)))
    assertEquals(rig.dirty(RollupDomain.Hydration), Set(today.minusDays(1)))
    val days = rig.stats.hydration(rig.account, StatsPeriod.Last7Days).toOption.get.days
    assertEquals(days(today.minusDays(3)).goalMl, None)
    assertEquals(days(today.minusDays(1)).goalMl, Some(800))

  test("a rollup transaction that fails leaves the key queued (INS-02)"):
    val rig = Rig()
    rig.drink(400, "k1")
    intercept[IllegalStateException]:
      rig.uow.transaction: tx =>
        assertEquals(tx.rollups.claimDirtyIn(rig.account, RollupDomain.Hydration, DateRange.single(today)), List(today))
        throw IllegalStateException("crash between claim and recompute")
    assertEquals(rig.dirty(RollupDomain.Hydration), Set(today))
    assertEquals(rig.stored(RollupDomain.Hydration), Map.empty)

  test("a claim skips a key whose source write has not committed, and finds it afterwards"):
    val rig = Rig()
    val written = CountDownLatch(1)
    val release = CountDownLatch(1)
    ox.supervised:
      val writer = ox.fork:
        rig.uow.transaction: tx =>
          val amount = WaterAmount.ofMillilitres(700).toOption.get
          val entry = dosegram.core.domain.hydration.HydrationEntry(
            rig.ids.next(now), rig.account, now, today, java.time.ZoneId.of("Europe/Kyiv"), amount, EntrySource.Bot,
            Some("in-flight"), now, None)
          assert(tx.hydration.insert(entry))
          written.countDown()
          release.await()
      written.await()
      val key = RollupKey(rig.account, today, RollupDomain.Hydration)
      val claimedEarly = rig.uow.transaction(_.rollups.claimDirty(1000)).filter(_ == key)
      assertEquals(claimedEarly, Nil)
      release.countDown()
      writer.join()
    assertEquals(rig.stats.hydration(rig.account, StatsPeriod.Today).map(_.days.get(today).map(_.totalMl)), Right(Some(700)))

  test("the worker step drains the queue and leaves the rollups a rebuild would leave"):
    val rig = Rig()
    rig.on(2) { rig.drink(700, "w1"); rig.feel(2, "m1", Some(4)) }
    rig.drink(300, "w2")
    rig.feel(5, "m2")
    while rig.rollups.drain(1) > 0 do ()
    val drained = (rig.stored(RollupDomain.Hydration), rig.stored(RollupDomain.Mood))
    val month = DateRange.endingOn(today, 30)
    rig.rollups.rebuild(rig.account, RollupDomain.Hydration, month)
    rig.rollups.rebuild(rig.account, RollupDomain.Mood, month)
    assertEquals((rig.stored(RollupDomain.Hydration), rig.stored(RollupDomain.Mood)), drained)
    assertEquals(drained._2.keySet, Set(today, today.minusDays(2)))

  test("a mood check-in round-trips with its tags, and the energy step supersedes it"):
    val rig = Rig()
    val tags = List(UUID.randomUUID(), UUID.randomUUID())
    val command = CheckIn(rig.account, Valence.of(4).get, None, tags, EntryDay.Today, EntrySource.Bot, "k1")
    val recorded = rig.moods.checkIn(command).toOption.get
    val read = rig.uow.transaction(_.mood.get(rig.account, recorded.checkin.id)).get
    assertEquals((read.valence.step, read.energy, read.tagIds, read.supersedesId), (4, None, tags, None))
    val amended = rig.moods.setEnergy(rig.account, read.id, Energy.of(2).get, "k1-energy").toOption.get
    assertEquals(amended.checkin.supersedesId, Some(read.id))
    val view = rig.stats.mood(rig.account, StatsPeriod.Today).toOption.get
    assertEquals(view.days(today).n, 1)
    assertEquals(view.days(today).energyMean, Some(2.0))

  test("eight concurrent deliveries of one mood tap record exactly one check-in"):
    val rig = Rig()
    val command = CheckIn(rig.account, Valence.of(3).get, None, Nil, EntryDay.Today, EntrySource.Bot, "one-tap")
    val outcomes = ox.supervised:
      List.fill(8)(ox.fork(rig.moods.checkIn(command))).map(_.join())
    assertEquals(outcomes.count(_.exists(!_.replayed)), 1)
    assertEquals(rig.moods.day(rig.account, EntryDay.Today).map(_.recorded.map(_.n)), Right(Some(1)))

  test("a note is never written or read by the repository"):
    val rig = Rig()
    val id = rig.feel(3, "k1")
    withConnection { conn =>
      given Connection = conn
      given RowMapper[Boolean] = _.getBoolean(1)
      assertEquals(sql"SELECT note_enc IS NULL FROM mood_checkins WHERE id = $id".queryOne[Boolean](), Some(true))
    }

  test("the 7-day and 30-day text of water and mood prints figures with coverage, or the data gate (G6.8)"):
    val rig = Rig()
    rig.on(25)(rig.water.setGoal(rig.account, Some(2000)))
    (1 to 12).foreach { daysAgo =>
      rig.on(daysAgo):
        rig.drink(if daysAgo % 3 == 0 then 2500 else 1500, s"w$daysAgo")
        if daysAgo <= 2 then rig.feel(3 + daysAgo, s"m$daysAgo")
    }
    assertEquals(
      rig.stats.summary(rig.account, StatsPeriod.Last7Days).toOption.get.lines,
      List(
        "Last 7 days · Europe/Kyiv",
        "Water: goal reached on 2 of 6 recorded days · average 1.8 l a day · 6 of 7 days have data",
        "Mood: 2 check-ins · 1 more day with a check-in for an average · 2 of 7 days have data"
      )
    )
    assertEquals(
      rig.stats.summary(rig.account, StatsPeriod.Last30Days).toOption.get.lines,
      List(
        "Last 30 days · Europe/Kyiv",
        "Water: goal reached on 4 of 12 recorded days · average 1.8 l a day · 12 of 30 days have data",
        "Mood: 2 check-ins · 8 more days with a check-in for an average · 2 of 30 days have data"
      )
    )

  test("the comparison is not shown before 14 paired days, and is honest about what is missing"):
    val rig = Rig()
    rig.on(27)(rig.water.setGoal(rig.account, Some(2000)))
    (1 to 6).foreach(daysAgo => rig.on(daysAgo) { rig.drink(2100, s"w$daysAgo"); rig.feel(4, s"m$daysAgo") })
    val window = DateRange.endingOn(today, 28)
    assertEquals(
      rig.stats.waterGoalAndMood(rig.account, ComparisonWindow.Days28),
      Right(Comparison.TooFewDays(dosegram.core.domain.insights.InsightRule.WaterGoalAndMood, 6, 14, window))
    )

  private enum Step:
    case Drink(daysAgo: Int, millilitres: Int)
    case Feel(daysAgo: Int, valence: Int, energy: Option[Int])
    case Remove(pick: Int)
    case Goal(daysAgo: Int, target: Option[Int])
    case Drain(limit: Int)
    case Read

  private val genStep: Gen[Step] = Gen.frequency(
    5 -> Gen.zip(Gen.choose(0, 9), Gen.choose(1, 5000)).map(Step.Drink(_, _)),
    5 -> Gen.zip(Gen.choose(0, 9), Gen.choose(1, 5), Gen.option(Gen.choose(1, 5))).map(Step.Feel(_, _, _)),
    2 -> Gen.choose(0, 100).map(Step.Remove(_)),
    1 -> Gen.zip(Gen.choose(0, 9), Gen.option(Gen.choose(100, 10000))).map(Step.Goal(_, _)),
    2 -> Gen.choose(1, 4).map(Step.Drain(_)),
    1 -> Gen.const(Step.Read)
  )

  property("rollups maintained through any sequence of writes equal the pure functions over the stored rows"):
    forAll(Gen.listOfN(25, genStep)) { steps =>
      val rig = Rig()
      val range = DateRange.endingOn(today, 30)
      steps.zipWithIndex.foreach { (step, index) =>
        step match
          case Step.Drink(daysAgo, millilitres) => rig.on(daysAgo)(rig.drink(millilitres, s"w$index"))
          case Step.Feel(daysAgo, valence, energy) => rig.on(daysAgo)(rig.feel(valence, s"m$index", energy))
          case Step.Remove(pick) =>
            val entries = rig.uow.transaction(_.hydration.listLive(rig.account, range))
            if entries.nonEmpty then rig.water.delete(rig.account, entries(pick % entries.size).id)
          case Step.Goal(daysAgo, target) => rig.on(daysAgo)(rig.water.setGoal(rig.account, target))
          case Step.Drain(limit)          => rig.rollups.drain(limit)
          case Step.Read                  => rig.stats.summary(rig.account, StatsPeriod.Last7Days)
      }
      val water = rig.stats.hydration(rig.account, StatsPeriod.Last30Days).toOption.get
      val mood = rig.stats.mood(rig.account, StatsPeriod.Last30Days).toOption.get
      val (entries, goals, checkins) = rig.uow.transaction: tx =>
        (tx.hydration.listLive(rig.account, range), tx.hydration.goals(rig.account, today), tx.mood.listLive(rig.account, range))
      assertEquals(water.days, HydrationStats.byDay(entries, goals))
      assertEquals(water.period, HydrationStats.period(HydrationStats.byDay(entries, goals), range))
      assertEquals(mood.days, MoodStats.byDay(checkins))
      assertEquals(mood.period, MoodStats.period(MoodStats.byDay(checkins), range))
      val incremental = (rig.stored(RollupDomain.Hydration), rig.stored(RollupDomain.Mood))
      rig.rollups.rebuild(rig.account, RollupDomain.Hydration, DateRange.endingOn(today, 60))
      rig.rollups.rebuild(rig.account, RollupDomain.Mood, DateRange.endingOn(today, 60))
      assertEquals((rig.stored(RollupDomain.Hydration), rig.stored(RollupDomain.Mood)), incremental)
    }
end TrackingPgSuite
