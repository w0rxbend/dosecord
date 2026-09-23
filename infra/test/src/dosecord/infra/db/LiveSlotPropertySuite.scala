package dosecord.infra.db

import dosecord.contracts.HhMm
import dosecord.contracts.Weekday
import dosecord.core.domain.Evaluator
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.scheduling.Materialiser

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import scala.language.implicitConversions

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** ROADMAP M1.5 property (acceptance 3): one live slot per `(schedule, local_date, slot_key)` across any sequence of
  * edits, pauses and zone moves, on Testcontainers Postgres 18. Additionally pins that the live rows of the latest
  * revision never leave the pure evaluator's window, and that every candidate of the latest revision is covered by
  * exactly one live row — the latest revision's own, or an older kept row that owns the slot through the
  * cross-revision live-slot index (ADR-004 arbitration suppresses the duplicate candidate).
  */
class LiveSlotPropertySuite extends PgSuite, munit.ScalaCheckSuite:

  private val start = Instant.parse("2026-09-21T08:00:00Z") // a Monday

  override def scalaCheckTestParameters: org.scalacheck.Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(15)

  private sealed trait Op
  private case object PauseOp extends Op
  private case object ResumeOp extends Op
  private final case class Move(zone: ZoneId) extends Op
  private final case class Wait(hours: Int) extends Op

  private val genZone: Gen[ZoneId] = Gen.oneOf(
    ZoneId.of("Europe/Kyiv"),
    ZoneId.of("America/New_York"),
    ZoneId.of("Australia/Lord_Howe"),
    ZoneId.of("Asia/Kolkata"),
    ZoneId.of("UTC")
  )

  private val genOp: Gen[Op] = Gen.frequency(
    2 -> Gen.const(PauseOp: Op),
    2 -> Gen.const(ResumeOp: Op),
    3 -> genZone.map(zone => Move(zone): Op),
    4 -> Gen.choose(1, 30).map(hours => Wait(hours): Op)
  )

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private def truncateAll(): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "domain_events, users CASCADE"
        )
      finally st.close()
    }

  property("one live slot per (schedule, local_date, slot_key) across edits, pauses and zone moves"):
    forAll(Gen.listOfN(14, genOp)) { ops =>
      truncateAll()
      val clock = MutableClock(start)
      val fixtures = Fixtures(dataSource)
      val lifecycle = fixtures.lifecycle(clock)
      val materialiser = Materialiser(PgUnitOfWork(dataSource, clock), clock)
      val accountId = fixtures.account()
      val created = fixtures.schedule(clock, accountId, "Med", dailyAt("09:00", "21:00"), ZoneId.of("Europe/Kyiv"))

      var paused = false
      var lastRevisionAt = start
      ops.foreach {
        case PauseOp if !paused =>
          lifecycle.pause(created.scheduleId); paused = true; lastRevisionAt = clock.now()
          materialiser.runOnce()
        case ResumeOp if paused =>
          lifecycle.resume(created.scheduleId); paused = false; lastRevisionAt = clock.now()
          materialiser.runOnce()
        case Move(zone) if !paused =>
          lifecycle.changeTimezone(created.scheduleId, zone); lastRevisionAt = clock.now()
          materialiser.runOnce()
        case Wait(hours) =>
          clock.advance(Duration.ofHours(hours.toLong)); materialiser.runOnce()
        case _ => ()
      }
      materialiser.runOnce()

      withConnection { conn =>
        given Connection = conn
        val violations = sql"""SELECT count(*) FROM (
                                 SELECT 1 FROM dose_occurrences
                                 WHERE status <> 'cancelled'
                                 GROUP BY schedule_id, local_date, slot_key
                                 HAVING count(*) > 1
                               ) violations""".queryOne[Long]().get
        assertEquals(violations, 0L, "one live slot per (schedule, local_date, slot_key)")
      }

      val (schedule, latest) = fixtures.uow.transaction { tx =>
        (tx.schedules.get(created.scheduleId).get, tx.revisions.latest(created.scheduleId).get)
      }
      val now = clock.now()
      val expected = Evaluator.occurrences(
        Materialiser.toEvaluator(schedule, latest),
        lastRevisionAt,
        now.plus(Evaluator.MaterialisationHorizon)
      )
      val expectedKeys = expected.map(candidate => (candidate.localDate, candidate.slotKey)).toSet
      val expectedExact = expected.map(candidate => (candidate.localDate, candidate.slotKey, candidate.scheduledFor)).toSet
      val live = fixtures.uow
        .transaction(_.occurrences.listBySchedule(created.scheduleId))
        .filter(_.status != OccurrenceStatus.Cancelled)

      // No live row of the latest revision outside the evaluator's window.
      val latestLive = live
        .filter(_.revision.contains(latest.revision))
        .map(row => (row.localDate, row.slotKey, row.scheduledFor))
        .toSet
      assert(
        latestLive.forall(expectedExact),
        s"live rows of the latest revision outside the evaluator window: ${latestLive.diff(expectedExact)}"
      )

      // Every candidate of the latest revision has exactly one live row (the uniqueness half is the SQL check
      // above). The live row is either the latest revision's own or an older kept row — a pending row before the
      // cutover or a due/snoozed row — that owns the `(local_date, slot_key)` through the cross-revision live-slot
      // index (ADR-004 arbitrates, the candidate is suppressed, the dose fires once at the kept row's instant).
      val liveKeys = live.map(row => (row.localDate, row.slotKey)).toSet
      assert(
        expectedKeys.forall(liveKeys),
        s"candidates with no live row: ${expectedKeys.diff(liveKeys)}"
      )
    }
