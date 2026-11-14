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
        "1 still due, not counted"
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
        "1 still due, not counted"
      )
    )
    assertEquals(week.flatMap(InsightPhrases.violations), Nil)

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
