package dosegram.api

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.SleepInput
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps
import dosegram.infra.perf.StatementLog
import dosegram.infra.perf.Timings

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** docs/PERFORMANCE.md, API latency under a seeded 90-day account: the module's routes on the real Netty interpreter
  * and Testcontainers Postgres (the [[ApiSuite]] harness), after 90 days of two doses, six water entries, three meals,
  * a mood check-in and a night each day, all written through the production services; the rollup worker drained.
  * Run: `./mill api.test.runMain dosegram.api.apiLatencyProbe [samples]`.
  */
abstract class ApiLatencyRig extends ApiSuite:

  // The suite starts its container only when it has a test. The class is abstract, so the test runner never runs it
  // as a suite; the entry stands for the measurement that `run` makes by hand.
  test("rig")(())

  private val zone = ZoneId.of("UTC")
  private val days = 90

  private def at(date: LocalDate, hour: Int, minute: Int): Unit =
    clock.at = date.atTime(hour, minute).toInstant(ZoneOffset.UTC)

  private def key: String = s"probe:${UUID.randomUUID()}"

  private def seed(): TelegramAccountRef =
    val end = clock.now().atZone(zone).toLocalDate
    val first = end.minusDays(days.toLong)
    at(first, 6, 0)
    val account = accountWithoutConsent(zone.getId)
    val id = account.accountId
    ConsentPurpose.values.foreach: purpose =>
      tracking.consents.grant(id, purpose, ConsentNotice(1, "seeded for the probe", "en"),
        ConsentTaps(clock.now(), clock.now()))
    val rule = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("08:00"), HhMm.unsafe("20:00")))))
    val schedule = fixtures.schedule(clock, id, "Probe medication", rule, zone).scheduleId
    (0 to days).foreach: offset =>
      val date = first.plusDays(offset.toLong)
      val isToday = date == end
      at(date, 7, 30)
      val night = tracking.sleeps.enter(
        EnterSleep(SleepInput(id, EntrySource.MiniApp, key), SleepKind.Main,
          date.minusDays(1).atTime(23, 10).toInstant(ZoneOffset.UTC), date.atTime(7, 5).toInstant(ZoneOffset.UTC))
      )
      require(night.isRight, night.toString)
      List((MealType.Breakfast, 8), (MealType.Lunch, 13), (MealType.Dinner, 19))
        .filterNot((_, hour) => isToday && hour > 8)
        .foreach: (meal, hour) =>
          at(date, hour, 0)
          val words = MealText.of(s"probe words $offset").toOption.flatten
          val logged = uow.transactionAs(id)(tx =>
            tracking.nutritionService.log(tx, LogMeal(id, MealInput(meal, words, None), EntrySource.MiniApp, key)))
          require(logged.isRight, logged.toString)
      (9 to (if isToday then 9 else 14)).foreach: hour =>
        at(date, hour, 15)
        val water = WaterAmount.ofMillilitres(250).toOption.get
        require(tracking.water.log(LogWater(id, water, EntryDay.Today, EntrySource.MiniApp, key)).isRight)
      at(date, if isToday then 9 else 21, 30)
      require(tracking.moods.checkIn(
        CheckIn(id, Valence.of(1 + offset % 5).get, None, Nil, EntryDay.Today, EntrySource.MiniApp, key)).isRight)
      if offset >= 2 then
        List(("am", 8), ("pm", 20)).foreach: (slot, hour) =>
          val status =
            if isToday then OccurrenceStatus.Pending
            else if offset % 7 == 0 && slot == "pm" then OccurrenceStatus.Skipped
            else OccurrenceStatus.Taken
          fixtures.occurrenceAt(schedule, date.atTime(hour, 0).toInstant(ZoneOffset.UTC), s"probe-$offset-$slot", status)
    at(end, 9, 45)
    while tracking.rollups.drain(500) > 0 do ()
    TelegramAccountRef(account, end)

  private def rowsOf(table: String): Long =
    withConnection: conn =>
      val rs = conn.createStatement().executeQuery(s"SELECT count(*) FROM $table") // a table of this probe
      rs.next()
      rs.getLong(1)

  private final case class TelegramAccountRef(account: ApiSuite.TelegramAccount, today: LocalDate)

  def run(samples: Int): Unit =
    beforeAll()
    try
      val started = System.nanoTime()
      val ref = seed()
      println(f"seeded a $days-day account in ${(System.nanoTime() - started) / 1e9}%.1f s")
      val token = login(ref.account)
      val month = ref.today.toString.take(7)
      val counts = List("dose_occurrences", "hydration_entries", "meals", "mood_checkins", "sleep_episodes",
        "daily_rollups").map(table => s"$table ${rowsOf(table)}")
      println(s"rows: ${counts.mkString(", ")}")
      val statements = List.newBuilder[String]
      def probe(label: String, path: String): Timings =
        // A session may read 120 times a minute (RequestLimits): each probe starts a minute later.
        clock.advance(java.time.Duration.ofSeconds(61))
        def call(): Unit =
          val reply = get(path, token)
          require(reply.status == 200, s"$path: ${reply.status} ${reply.body.take(200)}")
        val timed = Timings.of(label, 10, samples)(call())
        val counted = StatementLog.during(pg, dataSource)(call())
        statements += f"$label: ${counted.total} statements, ${counted.serverMillis}%.1f ms in the server\n" +
          counted.top(12)
        timed
      val probes = List(
        probe("GET /today", "/api/v1/today"),
        probe("GET /days/{date}, 30 days ago", s"/api/v1/days/${ref.today.minusDays(30)}"),
        probe("GET /insights/overview?range=7", "/api/v1/insights/overview?range=7"),
        probe("GET /insights/overview?range=30", "/api/v1/insights/overview?range=30"),
        probe("GET /insights/overview?range=90", "/api/v1/insights/overview?range=90"),
        probe("GET /insights/calendar medication", s"/api/v1/insights/calendar?domain=medication&month=$month"),
        probe("GET /insights/calendar hydration", s"/api/v1/insights/calendar?domain=hydration&month=$month"),
        probe("GET /insights/calendar nutrition", s"/api/v1/insights/calendar?domain=nutrition&month=$month"),
        probe("GET /insights/month", s"/api/v1/insights/month?month=$month")
      )
      println(Timings.Header)
      probes.foreach(probe => println(probe.row))
      statements.result().foreach(println)
    finally afterAll()

@main def apiLatencyProbe(samples: Int): Unit = (new ApiLatencyRig {}).run(samples)
