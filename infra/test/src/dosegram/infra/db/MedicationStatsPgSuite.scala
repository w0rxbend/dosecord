package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.application.insights.RollupService
import dosegram.core.domain.Adherence
import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.Actor
import dosegram.core.domain.CancelReason
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.DoseActionKind
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseRecord
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.MedicationStats
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewDoseAction
import dosegram.core.ports.OccurrenceOrigin
import dosegram.core.ports.StoredOccurrence
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** ROADMAP G4.4 and G2.20 on Testcontainers Postgres 18: the medication rollups, fed by the occurrence triggers through
  * the engine's own repository writes, equal a rebuild from scratch and the inherited `Adherence` oracle over the same
  * rows, across inserts, transitions, cancellations and a change of the account zone.
  */
class MedicationStatsPgSuite extends PgSuite, munit.ScalaCheckSuite:

  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(15)

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val start = Instant.parse("2026-10-20T06:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)

  private def dailyAt(time: String): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe(time)))))

  private final class Rig:
    val clock = MutableClock(start)
    val account: UUID = fixtures.account("Europe/Kyiv")
    val schedule = fixtures.schedule(clock, account, "Medication A", dailyAt("09:00"), kyiv)
    val uow = TransactionalTracking.everyPurposeGranted(PgUnitOfWork(dataSource, clock, DataKeys.development))
    val tracking = TransactionalTracking(uow, clock, IdSource.uuidV7(java.util.random.RandomGenerator.getDefault))
    export tracking.{rollups, stats}
    val range: DateRange = DateRange.between(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-12-31"))

    def zone: ZoneId = uow.transaction(_.accounts.timezoneOf(account)).map(ZoneId.of).get

    def occurrences: List[StoredOccurrence] =
      fixtures.uow.transaction(_.occurrences.listBySchedule(schedule.scheduleId))

    /** The rows as the engine's own repository reads them, in the shape of the adherence maths. */
    def rows: List[DoseRecord] =
      occurrences.map { occ =>
        DoseRecord(occ.medicationId, AdherenceInput.of(occ.state, occ.origin == OccurrenceOrigin.Manual, corrected = false))
      }

    def settled(): Map[LocalDate, MedicationDay] =
      while rollups.drain(40) > 0 do ()
      uow
        .transaction(_.tracking.rollups.list(account, RollupDomain.Medication, range))
        .collect { case (date, RollupMetrics.Medication(day)) => date -> day }

    def resolve(occ: StoredOccurrence, status: OccurrenceStatus, delaySeconds: Long): Unit =
      val at = occ.scheduledFor.plusSeconds(delaySeconds)
      val cleared = occ.state.copy(status = status, nextActionAt = None, epoch = occ.state.epoch + 1)
      val resolved = status match
        case OccurrenceStatus.Taken   => cleared.copy(takenAt = Some(at), effectiveAt = Some(at))
        case OccurrenceStatus.Skipped => cleared.copy(skippedAt = Some(at))
        case _                        => cleared.copy(status = OccurrenceStatus.Missed, missedAt = Some(at))
      val applied = fixtures.uow.transaction(
        _.occurrences.applyTransition(occ.id, occ.version, occ.state.epoch, resolved, clock.now())
      )
      assert(applied, s"transition of ${occ.id} was fenced")

  private enum Op:
    case Insert(minutesFromStart: Int, status: OccurrenceStatus)
    case Resolve(pick: Int, status: OccurrenceStatus, delaySeconds: Long)
    case Cancel(pick: Int)
    case Drain(limit: Int)
    case Move(zone: String)

  private val genOp: Gen[Op] = Gen.frequency(
    6 -> Gen
      .zip(
        Gen.choose(-20 * 1440, 2 * 1440),
        Gen.oneOf(
          OccurrenceStatus.Pending,
          OccurrenceStatus.Due,
          OccurrenceStatus.Taken,
          OccurrenceStatus.Skipped,
          OccurrenceStatus.Missed,
          OccurrenceStatus.Unknown
        )
      )
      .map(Op.Insert(_, _)),
    4 -> Gen
      .zip(
        Gen.choose(0, 100),
        Gen.oneOf(OccurrenceStatus.Taken, OccurrenceStatus.Skipped, OccurrenceStatus.Missed),
        Gen.choose(-3600L, 2L * 86400L)
      )
      .map(Op.Resolve(_, _, _)),
    1 -> Gen.choose(0, 100).map(Op.Cancel(_)),
    2 -> Gen.choose(1, 6).map(Op.Drain(_)),
    1 -> Gen.oneOf("Asia/Tokyo", "America/New_York", "Pacific/Kiritimati", "Europe/Kyiv").map(Op.Move(_))
  )

  private def run(rig: Rig, op: Op, index: Int): Unit =
    def open = rig.occurrences.filter(_.status.isOpen)
    op match
      case Op.Insert(minutes, status) =>
        fixtures.occurrenceAt(rig.schedule.scheduleId, start.plus(Duration.ofMinutes(minutes.toLong)), s"x$index", status)
      case Op.Resolve(pick, status, delay) =>
        if open.nonEmpty then rig.resolve(open(pick % open.size), status, delay)
      case Op.Cancel(pick) =>
        if open.nonEmpty then
          fixtures.uow.transaction(_.occurrences.cancel(List(open(pick % open.size).id), CancelReason.Paused, rig.clock.now()))
      case Op.Drain(limit) => rig.rollups.drain(limit)
      case Op.Move(zone)   => fixtures.uow.transaction(_.accounts.setTimezone(rig.account, zone, rig.clock.now()))

  property("rollups fed by the occurrence triggers equal a rebuild from scratch and the Adherence oracle"):
    forAll(Gen.listOfN(30, genOp)) { ops =>
      val rig = Rig()
      ops.zipWithIndex.foreach((op, index) => run(rig, op, index))
      val incremental = rig.settled()
      val rows = rig.rows
      val zone = rig.zone
      assertEquals(incremental, MedicationStats.byDay(rows, zone))
      rig.rollups.rebuild(rig.account, RollupDomain.Medication, rig.range)
      assertEquals(rig.settled(), incremental)
      val inputs = rows.map(_.input)
      assertEquals(MedicationStats.total(incremental.values).resolved, Adherence.counts(inputs))
      Adherence.byDay(inputs, zone).foreach { (date, expected) =>
        val day = incremental(date).total
        assertEquals((day.resolved, day.open), (expected.counts, expected.open), clue(date))
      }
    }

  property("the statistics query equals the pure functions over the rows of its window"):
    forAll(Gen.listOfN(25, genOp), Gen.oneOf(StatsPeriod.values.toList)) { (ops, period) =>
      val rig = Rig()
      ops.zipWithIndex.foreach((op, index) => run(rig, op, index))
      val zone = rig.zone
      val window = period.window(LocalDate.ofInstant(start, zone))
      val inWindow = rig.rows.filter(row => MedicationStats.dayOf(row.input, zone).exists(window.contains))
      val view = rig.stats.medication(rig.account, period).toOption.get.period
      val expected = MedicationStats.period(MedicationStats.byDay(inWindow, zone), inWindow.map(_.input), window)
      assertEquals(view, expected)
      assertEquals(view.total.buckets.resolved, Adherence.counts(inWindow.map(_.input)))
    }

  test("a correction keeps its place in adherence and leaves the delay statistics"):
    val rig = Rig()
    val taken = (1 to 6).map { day =>
      fixtures.occurrenceAt(rig.schedule.scheduleId, start.minus(Duration.ofDays(day.toLong)), s"t$day", OccurrenceStatus.Taken)
    }
    fixtures.uow.transaction: tx =>
      tx.doseActions.append(
        NewDoseAction(
          id = UUID.randomUUID(),
          occurrenceId = taken.head,
          accountId = rig.account,
          action = DoseActionKind.ManuallyCorrected,
          actor = Actor.User,
          occurredAt = start,
          priorStatus = OccurrenceStatus.Taken,
          newStatus = OccurrenceStatus.Taken,
          correlationId = "test",
          effectiveAt = Some(start.minus(Duration.ofDays(1)))
        )
      )
    val view = rig.stats.medication(rig.account, StatsPeriod.Last30Days).toOption.get.period
    assertEquals(view.total.buckets.resolved.taken, 6)
    assertEquals(view.delay.shown.map(_.samples), Some(5))

  test("every dose counts on its own local scheduled day, a late-missed one and one across the DST change included"):
    // The verification of 2026-11-28: a late catch-up marked a Monday 20:00 dose missed on Saturday, and the morning
    // summary's text put it into Saturday ("0 of 2 taken · not confirmed 2") while the card counted Saturday's one dose.
    // Kyiv leaves summer time at 04:00 on Sunday 25 October 2026 (+03 to +02).
    val rig = Rig()
    fixtures.uow.transaction(
      _.occurrences.cancel(rig.occurrences.filter(_.status.isOpen).map(_.id), CancelReason.Paused, rig.clock.now())
    )
    def doseAt(local: String, slot: String): StoredOccurrence =
      val id = fixtures.occurrenceAt(rig.schedule.scheduleId, java.time.LocalDateTime.parse(local).atZone(kyiv).toInstant,
        slot, OccurrenceStatus.Due)
      rig.occurrences.find(_.id == id).get
    def resolveAt(occ: StoredOccurrence, status: OccurrenceStatus, local: String): Unit =
      val at = java.time.LocalDateTime.parse(local).atZone(kyiv).toInstant
      rig.resolve(occ, status, Duration.between(occ.scheduledFor, at).getSeconds)
    val monday = doseAt("2026-10-19T20:00", "mon-20")
    val saturday = doseAt("2026-10-24T09:00", "sat-09")
    // 00:30 on Sunday is 21:30 UTC on Saturday, still in summer time.
    val sundayNight = doseAt("2026-10-25T00:30", "sun-0030")
    // 23:30 on Sunday is in winter time; it is taken after midnight, on Monday.
    val sundayLate = doseAt("2026-10-25T23:30", "sun-2330")
    resolveAt(saturday, OccurrenceStatus.Taken, "2026-10-24T09:05")
    resolveAt(monday, OccurrenceStatus.Missed, "2026-10-24T21:00")
    resolveAt(sundayNight, OccurrenceStatus.Missed, "2026-10-26T08:00")
    resolveAt(sundayLate, OccurrenceStatus.Taken, "2026-10-26T00:40")

    val days = rig.settled()
    def counts(date: String) = days.get(LocalDate.parse(date)).map(_.total)
    assertEquals(counts("2026-10-19").map(_.missed), Some(1), "Monday keeps its missed dose")
    assertEquals(counts("2026-10-24").map(b => (b.takenOnTime, b.missed)), Some((1, 0)), "Saturday has its one dose")
    assertEquals(counts("2026-10-25").map(b => (b.missed, b.takenLate)), Some((1, 1)), "Sunday has both of its doses")
    assertEquals(counts("2026-10-26"), None, "Monday 26 had no dose scheduled")
    assertEquals(days, MedicationStats.byDay(rig.rows, kyiv), "the stored rollups equal the pure attribution")

    // The morning summary's medication line reads the same days as its card (DoseMarks, by scheduled day).
    def line(date: String): String =
      rig.uow.transaction(tx => rig.tracking.statsService.dayLines(tx, rig.account, LocalDate.parse(date))).head
    assert(!line("2026-10-24").contains("not confirmed"), line("2026-10-24"))
    assert(line("2026-10-24").contains("1 of 1 taken"), line("2026-10-24"))
    assert(line("2026-10-19").contains("not confirmed 1"), line("2026-10-19"))
    assert(line("2026-10-25").contains("1 of 2 taken"), line("2026-10-25"))

  test("a rollup stored by an earlier version is queued again at start and rebuilt under today's attribution"):
    // The live rollups were written when a late-missed dose counted on the day it was marked: a start queues every day
    // of a row of another version, and every scheduled day of that account's doses, so no stale figure survives.
    val rig = Rig()
    fixtures.uow.transaction(
      _.occurrences.cancel(rig.occurrences.filter(_.status.isOpen).map(_.id), CancelReason.Paused, rig.clock.now())
    )
    val id = fixtures.occurrenceAt(rig.schedule.scheduleId, Instant.parse("2026-10-19T17:00:00Z"), "mon-20",
      OccurrenceStatus.Due)
    rig.resolve(rig.occurrences.find(_.id == id).get, OccurrenceStatus.Missed, 5L * 86400L)
    rig.settled()
    withConnection { conn =>
      given Connection = conn
      // What the earlier release stored: the dose on Saturday, the day it was marked, and nothing on Monday.
      sql"DELETE FROM daily_rollups WHERE account_id = ${rig.account}".execute()
      sql"""INSERT INTO daily_rollups (account_id, local_date, domain, metrics, rollup_version, computed_at)
            VALUES (${rig.account}, ${LocalDate.parse("2026-10-24")}, 'medication',
                    ${Jsonb(s"""{"perMedication":[{"medicationId":"${rig.schedule.medicationId}","takenOnTime":0,"takenLate":0,"skipped":0,"missed":1,"unknown":0,"open":0,"asNeeded":0}]}""")},
                    1, ${start})""".execute()
    }
    assert(rig.uow.transaction(_.tracking.rollups.markStale()) > 0, "the stale row queues days")
    val days = rig.settled()
    assertEquals(days.keySet, Set(LocalDate.parse("2026-10-19")), "the dose is back on its own Monday")
    assertEquals(days.values.head.total.missed, 1)
    assertEquals(rig.uow.transaction(_.tracking.rollups.markStale()), 0, "a rebuilt account has nothing stale")

  test("reminder bookkeeping queues no rollup day; a resolution does"):
    val rig = Rig()
    rig.settled()
    val occ = rig.occurrences.filter(_.status.isOpen).head
    fixtures.uow.transaction(_.occurrences.quarantine(occ.id, start.plusSeconds(300), start))
    val reminded = occ.state.copy(
      status = OccurrenceStatus.Due,
      reminderSeq = 1,
      lastRemindedAt = Some(start),
      dueWindowEnd = occ.state.dueWindowEnd.plusSeconds(600),
      epoch = occ.state.epoch + 1
    )
    assert(fixtures.uow.transaction(_.occurrences.applyTransition(occ.id, occ.version, occ.state.epoch, reminded, start)))
    withConnection { conn =>
      given Connection = conn
      val queued = sql"SELECT count(*) FROM rollup_dirty WHERE account_id = ${rig.account}".queryOne[Long]()
      assertEquals(queued, Some(0L))
    }
    rig.resolve(rig.occurrences.find(_.id == occ.id).get, OccurrenceStatus.Skipped, 60)
    withConnection { conn =>
      given Connection = conn
      val queued = sql"SELECT count(*) FROM rollup_dirty WHERE account_id = ${rig.account}".queryOne[Long]()
      assert(queued.exists(_ >= 1L), s"queued: $queued")
    }

  test("the 30-day text over a seeded fixture with unknown and cancelled rows holds no forbidden phrase (G2.20)"):
    val rig = Rig()
    val statuses = List(
      OccurrenceStatus.Taken,
      OccurrenceStatus.Taken,
      OccurrenceStatus.Skipped,
      OccurrenceStatus.Taken,
      OccurrenceStatus.Missed,
      OccurrenceStatus.Unknown,
      OccurrenceStatus.Taken
    )
    (1 to 28).foreach { day =>
      val status = statuses(day % statuses.size)
      fixtures.occurrenceAt(rig.schedule.scheduleId, start.minus(Duration.ofDays(day.toLong)), s"s$day", status)
    }
    val cancelled = fixtures.occurrenceAt(rig.schedule.scheduleId, start.minus(Duration.ofHours(30)), "c1")
    fixtures.uow.transaction(_.occurrences.cancel(List(cancelled), CancelReason.Paused, start))
    val month = rig.stats.summary(rig.account, StatsPeriod.Last30Days).toOption.get.lines
    assertEquals(
      month,
      List(
        "Last 30 days · Europe/Kyiv",
        "Doses: 16 of 24 taken (67%) · skipped 4 · not confirmed 4",
        "16 of 16 taken doses on time",
        "20 of 24 answered",
        "4 with no record, not counted",
        "1 still to answer, not counted yet"
      )
    )
    assertEquals(month.flatMap(InsightPhrases.violations), Nil)
    val week = rig.stats.summary(rig.account, StatsPeriod.Last7Days).toOption.get.lines
    assertEquals(
      week,
      List(
        "Last 7 days · Europe/Kyiv",
        "Doses: 3 of 5 taken (60%) · skipped 1 · not confirmed 1",
        "3 of 3 taken doses on time",
        "4 of 5 answered",
        "1 with no record, not counted",
        "1 still to answer, not counted yet"
      )
    )
    assertEquals(week.flatMap(InsightPhrases.violations), Nil)

  test("the month, the year and the week's hit days leave out days whose only doses have no record or are open"):
    // Leftover 1 of the verification of 2026-11-28: the month still counted such days, which `/stats year` did not.
    val rig = Rig()
    fixtures.uow.transaction(
      _.occurrences.cancel(rig.occurrences.filter(_.status.isOpen).map(_.id), CancelReason.Paused, rig.clock.now())
    )
    def doseOn(date: String, status: OccurrenceStatus): Unit =
      val at = java.time.LocalDateTime.parse(s"${date}T09:00").atZone(kyiv).toInstant
      fixtures.occurrenceAt(rig.schedule.scheduleId, at, s"d$date", status)
      ()
    doseOn("2026-10-10", OccurrenceStatus.Unknown)
    doseOn("2026-10-11", OccurrenceStatus.Unknown)
    doseOn("2026-10-12", OccurrenceStatus.Taken)
    // Today, 20 October at 09:00 in Kyiv: due and not answered yet.
    doseOn("2026-10-20", OccurrenceStatus.Due)
    rig.settled()
    val (month, year, hitDays) = rig.uow.transaction: tx =>
      (
        rig.tracking.statsService.month(tx, rig.account, java.time.YearMonth.of(2026, 10)).toOption.get.figures,
        rig.tracking.statsService.year(tx, rig.account, 2026).toOption.get.figures,
        rig.tracking.statsService.recordedDays(tx, rig.account, DateRange.between(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-20")))
      )
    assertEquals((month.daysWithRecords, month.outageOnlyDays), (1, 2))
    assertEquals((year.daysWithRecords, year.outageOnlyDays), (1, 2))
    assertEquals(month.medication.map(_.recordedDays), Some(1))
    assertEquals(hitDays(dosegram.core.ports.ConsentPurpose.Medication), Set(LocalDate.parse("2026-10-12")))
    val text = dosegram.core.domain.copy.MonthCopy.lines(month, kyiv)
    assertEquals(text.slice(1, 3), List("1 day with records",
      "Not counted: 2 days whose only doses have no record (a service outage)"))

  test("a period with fewer than five resolved doses prints counts, and one with only unknown rows prints No data"):
    val rig = Rig()
    fixtures.occurrenceAt(rig.schedule.scheduleId, start.minus(Duration.ofDays(1)), "u1", OccurrenceStatus.Unknown)
    val onlyUnknown = rig.stats.summary(rig.account, StatsPeriod.Last7Days).toOption.get.lines
    assertEquals(onlyUnknown.slice(1, 3), List("Doses: No data", "1 with no record, not counted"))
    (2 to 4).foreach { day =>
      fixtures.occurrenceAt(rig.schedule.scheduleId, start.minus(Duration.ofDays(day.toLong)), s"t$day", OccurrenceStatus.Taken)
    }
    val counts = rig.stats.summary(rig.account, StatsPeriod.Last7Days).toOption.get.lines
    assertEquals(counts(1), "Doses: 3 of 3 taken")
    assert(!counts.mkString.contains("%"), counts.mkString("\n"))
end MedicationStatsPgSuite
