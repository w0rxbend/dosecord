package dosecord.infra.db

import dosecord.contracts.HhMm
import dosecord.contracts.Weekday
import dosecord.core.domain.CancelReason
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.domain.copy.DoseActionKind
import dosecord.core.ports.ScheduleStatus
import dosecord.core.scheduling.CreateSchedule
import dosecord.core.scheduling.Materialiser

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** ROADMAP M1.5, repositories-and-revisions half: the schedule lifecycle against Testcontainers Postgres 18 —
  * revision reconciliation (acceptance 1), pause/resume (2), the `schedule_created.v1` event (5), eastward-move
  * kept rows, `tz_follows_user`, MVP rule validation, and the Fixtures builder itself (7).
  */
class ScheduleLifecycleSuite extends PgSuite:

  private val monday = Instant.parse("2026-09-21T08:00:00Z") // a Monday
  private val utc = ZoneId.of("UTC")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val newYork = ZoneId.of("America/New_York")
  private val lordHowe = ZoneId.of("Australia/Lord_Howe")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "domain_events, users CASCADE"
        )
      finally st.close()
    }

  private lazy val fixtures = Fixtures(dataSource)

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private given RowMapper[(String, String, UUID)] = rs =>
    (rs.getString("type"), rs.getString("source"), rs.uuid("account_id"))

  test("create materialises the 48 h horizon and appends exactly one schedule_created.v1"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account("UTC")
    val outcome = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)

    val rows = fixtures.uow.transaction(_.occurrences.listBySchedule(outcome.scheduleId))
    assertEquals(
      rows.map(_.scheduledFor),
      List(Instant.parse("2026-09-21T09:00:00Z"), Instant.parse("2026-09-22T09:00:00Z"))
    )
    assert(rows.forall(r => r.status == OccurrenceStatus.Pending && r.revision.contains(1)))
    assert(rows.forall(_.state.nextActionAt.isDefined))

    withConnection { conn =>
      given Connection = conn
      val events = sql"SELECT type, source, account_id FROM domain_events".query[(String, String, UUID)]()
      assertEquals(events, List(("dosecord.medication.schedule_created.v1", "dosecord.medication", accountId)))
    }

  test("a new revision cancels exactly the superseded rows and touches nothing else"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    val rows = fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId))
    val monRow = rows.find(_.scheduledFor == Instant.parse("2026-09-21T09:00:00Z")).get
    val tueRow = rows.find(_.scheduledFor == Instant.parse("2026-09-22T09:00:00Z")).get
    // Today's dose was already taken: it must survive the edit untouched.
    withConnection { conn =>
      given Connection = conn
      sql"""UPDATE dose_occurrences
            SET status = 'taken', taken_at = ${monRow.scheduledFor}, next_action_at = NULL
            WHERE id = ${monRow.id}""".execute()
    }

    val outcome = fixtures.lifecycle(clock).edit(created.scheduleId, dailyAt("21:00"), effectiveFrom = Some(monday))

    assertEquals(outcome.cancelled, List(tueRow.id), "only the future live rows of the old revision are cancelled")
    assertEquals(outcome.keptForQuestion, Nil)
    val after = fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId))
    val byId = after.map(r => r.id -> r).toMap
    assertEquals(byId(monRow.id).status, OccurrenceStatus.Taken, "resolved row untouched")
    assertEquals(byId(monRow.id).revision, Some(1))
    assertEquals(byId(tueRow.id).status, OccurrenceStatus.Cancelled)
    assertEquals(byId(tueRow.id).cancelReason, Some(CancelReason.Superseded))
    assertEquals(byId(tueRow.id).state.nextActionAt, None)
    val newRows = after.filter(_.revision.contains(2))
    assertEquals(
      newRows.map(_.scheduledFor),
      List(Instant.parse("2026-09-21T21:00:00Z"), Instant.parse("2026-09-22T21:00:00Z"))
    )
    assert(newRows.forall(_.status == OccurrenceStatus.Pending))

    val actions = fixtures.uow.transaction(_.doseActions.listForOccurrence(tueRow.id))
    assertEquals(actions.size, 1)
    assertEquals(actions.head.action, DoseActionKind.Cancelled)
    assertEquals(actions.head.priorStatus, OccurrenceStatus.Pending)
    assertEquals(actions.head.newStatus, OccurrenceStatus.Cancelled)
    assertEquals(fixtures.uow.transaction(_.doseActions.listForOccurrence(monRow.id)), Nil)

  test("pause, advance one hour, resume fires the next slot"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00", "21:00"), utc)
    // Materialised: Mon 09:00, Mon 21:00, Tue 09:00, Tue 21:00 (the horizon ends Wed 08:00).
    val paused = fixtures.lifecycle(clock).pause(created.scheduleId)
    assertEquals(paused.cancelled.size, 4)
    assertEquals(paused.keptForQuestion, Nil)
    val pausedRows = fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId))
    assert(
      pausedRows.forall(r => r.status == OccurrenceStatus.Cancelled && r.cancelReason.contains(CancelReason.Paused))
    )
    assertEquals(fixtures.uow.transaction(_.schedules.get(created.scheduleId)).get.status, ScheduleStatus.Paused)
    assertEquals(fixtures.uow.transaction(_.revisions.latest(created.scheduleId)).get.rule, Rule.Paused)

    clock.advance(Duration.ofHours(1)) // 09:00
    val resumed = fixtures.lifecycle(clock).resume(created.scheduleId)
    assertEquals(resumed.revision, 3)
    val live = fixtures.uow
      .transaction(_.occurrences.listBySchedule(created.scheduleId))
      .filter(_.status != OccurrenceStatus.Cancelled)
    // The 09:00 slot is due right now; the rest of the horizon is regenerated.
    assertEquals(
      live.map(_.scheduledFor),
      List(
        Instant.parse("2026-09-21T09:00:00Z"),
        Instant.parse("2026-09-21T21:00:00Z"),
        Instant.parse("2026-09-22T09:00:00Z"),
        Instant.parse("2026-09-22T21:00:00Z")
      )
    )
    assert(live.forall(r => r.status == OccurrenceStatus.Pending && r.revision.contains(3)))
    assertEquals(live.head.state.nextActionAt, Some(Instant.parse("2026-09-21T09:00:00Z")), "the next slot fires")

  test("an eastward zone move keeps and flags tonight's row"):
    val clock = MutableClock(Instant.parse("2026-09-21T12:00:00Z")) // Mon 08:00 New York, 15:00 Kyiv
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Melatonin", dailyAt("21:00"), newYork)
    val rows = fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId))
    val tonight = rows.find(_.scheduledFor == Instant.parse("2026-09-22T01:00:00Z")).get // Mon 21:00 in New York
    val tomorrow = rows.find(_.scheduledFor == Instant.parse("2026-09-23T01:00:00Z")).get

    // The new plan starts at the next Kyiv midnight.
    val effective = Instant.parse("2026-09-21T21:00:00Z")
    val outcome = fixtures.lifecycle(clock).changeTimezone(created.scheduleId, kyiv, Some(effective))

    assertEquals(outcome.keptForQuestion.map(_.id), List(tonight.id), "tonight's row kept and flagged for M3.2")
    assertEquals(outcome.cancelled, List(tomorrow.id))
    val after = fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId))
    val byId = after.map(r => r.id -> r).toMap
    assertEquals(byId(tonight.id).status, OccurrenceStatus.Pending)
    assertEquals(byId(tonight.id).revision, Some(1), "the kept row stays under the old revision")
    assertEquals(byId(tomorrow.id).status, OccurrenceStatus.Cancelled)
    assertEquals(byId(tomorrow.id).cancelReason, Some(CancelReason.Superseded))
    val newRows = after.filter(_.revision.contains(2))
    assertEquals(newRows.map(_.scheduledFor), List(Instant.parse("2026-09-22T18:00:00Z"))) // Tue 21:00 Kyiv
    assertEquals(newRows.head.tz, kyiv)
    assertEquals(fixtures.uow.transaction(_.schedules.get(created.scheduleId)).get.tz, kyiv)

  test("a user timezone change re-revisions following schedules only"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account("UTC")
    val following = fixtures.schedule(clock, accountId, "MedA", dailyAt("09:00"), utc, tzFollowsUser = true)
    val other = fixtures.schedule(clock, accountId, "MedB", dailyAt("09:00"), utc, tzFollowsUser = false)

    val outcomes = fixtures.lifecycle(clock).rezoneFollowingSchedules(accountId, kyiv)

    assertEquals(outcomes.map(_.scheduleId), List(following.scheduleId))
    val followingSchedule = fixtures.uow.transaction(_.schedules.get(following.scheduleId)).get
    assertEquals(followingSchedule.tz, kyiv)
    assertEquals(followingSchedule.currentRevision, 2)
    val followingLive = fixtures.uow
      .transaction(_.occurrences.listBySchedule(following.scheduleId))
      .filter(_.status != OccurrenceStatus.Cancelled)
    assert(followingLive.nonEmpty)
    assert(followingLive.forall(r => r.revision.contains(2) && r.tz == kyiv))
    val otherSchedule = fixtures.uow.transaction(_.schedules.get(other.scheduleId)).get
    assertEquals(otherSchedule.tz, utc)
    assertEquals(otherSchedule.currentRevision, 1)
    assertEquals(
      fixtures.uow
        .transaction(_.occurrences.listBySchedule(other.scheduleId))
        .count(_.status != OccurrenceStatus.Cancelled),
      2
    )

  test("create rejects a rule kind that is not MVP-supported"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val result = fixtures
      .lifecycle(clock)
      .create(CreateSchedule(accountId, "Med", Rule.EveryNDays(2, List(HhMm.unsafe("09:00"))), utc))
    assert(result.isLeft)
    withConnection { conn =>
      given Connection = conn
      assertEquals(sql"SELECT count(*) FROM medications".queryOne[Long]().get, 0L)
      assertEquals(sql"SELECT count(*) FROM medication_schedules".queryOne[Long]().get, 0L)
    }

  test("the fixtures builder seeds accounts, medications and occurrences at chosen instants"):
    val clock = MutableClock(monday)
    val accountId = fixtures.account("Europe/Kyiv")
    val medicationId =
      fixtures.medication(accountId, "  Magnesium 400 mg", monday, doseAmount = Some(BigDecimal(400)),
        doseUnit = Some("mg"))
    val stored = fixtures.uow.transaction(_.medications.get(medicationId)).get
    assertEquals(stored.nameNorm, "magnesium 400 mg")
    assertEquals(stored.doseUnit, Some("mg"))
    assertEquals(
      fixtures.uow.transaction(_.medications.findByNameNorm(accountId, "magnesium 400 mg")).map(_.id),
      Some(medicationId)
    )

    val created = fixtures.schedule(clock, accountId, "Vitamin D", dailyAt("09:00"), utc)
    val chosen = monday.plus(Duration.ofHours(7)) // 15:00, a slot the materialiser does not own
    val occurrenceId = fixtures.occurrenceAt(created.scheduleId, chosen, "t1500", OccurrenceStatus.Taken)
    val occurrence = fixtures.uow.transaction(_.occurrences.get(occurrenceId)).get
    assertEquals(occurrence.status, OccurrenceStatus.Taken)
    assertEquals(occurrence.state.takenAt, Some(chosen))
    assertEquals(occurrence.state.nextActionAt, None)
    assertEquals(occurrence.localTime, Some(LocalTime.of(15, 0)))

    // A revision through the fixture path is a normal edit revision.
    val edited = fixtures.revision(clock, created.scheduleId, dailyAt("10:00"), effectiveFrom = Some(monday))
    assertEquals(edited.revision, 2)

  test("a zone move flags the kept row that suppresses the new plan's slot through the live-slot index"):
    // Pinned-seed sequence from LiveSlotPropertySuite: Kyiv -> Lord_Howe, 19 h later Lord_Howe -> UTC.
    val clock = MutableClock(monday)
    val accountId = fixtures.account()
    val created = fixtures.schedule(clock, accountId, "Med", dailyAt("09:00", "21:00"), kyiv)

    // Mon 09:00 Kyiv is past its due-window start, so revision 2 (Lord_Howe) governs from the next LH midnight.
    val toLordHowe = fixtures.lifecycle(clock).changeTimezone(created.scheduleId, lordHowe)
    assertEquals(toLordHowe.revision, 2)
    val afterFirst = fixtures.uow.transaction(_.occurrences.listBySchedule(created.scheduleId))
    val wed0900LordHowe = afterFirst
      .find(row => row.revision.contains(2) && row.localDate.toString == "2026-09-23" && row.slotKey == "t0900")
      .get

    clock.advance(Duration.ofHours(19)) // Tue 03:00Z
    Materialiser(PgUnitOfWork(dataSource, clock), clock).runOnce()
    val toUtc = fixtures.lifecycle(clock).changeTimezone(created.scheduleId, utc)
    Materialiser(PgUnitOfWork(dataSource, clock), clock).runOnce()

    // Revision 3 (UTC) governs from the next UTC midnight; rev2's Wed 09:00 Lord_Howe row fires before the cutover,
    // so it is kept and flagged — and through uq_occ_live_slot it suppresses rev3's (Wed, t0900) candidate.
    assertEquals(toUtc.keptForQuestion.map(_.id), List(wed0900LordHowe.id))
    assertEquals(toUtc.cancelled.size, 2)
    val live = fixtures.uow
      .transaction(_.occurrences.listBySchedule(created.scheduleId))
      .filter(_.status != OccurrenceStatus.Cancelled)
    val wed0900 = live.filter(row => row.localDate.toString == "2026-09-23" && row.slotKey == "t0900")
    assertEquals(wed0900.map(_.id), List(wed0900LordHowe.id), "exactly one live Wed 09:00 row, at the kept instant")
    assertEquals(wed0900.head.scheduledFor, Instant.parse("2026-09-22T22:30:00Z")) // Wed 09:00 Lord_Howe (UTC+11:30)
    val rev3 = live.filter(_.revision.contains(3))
    assertEquals(
      rev3.map(row => (row.slotKey, row.scheduledFor)),
      List(("t2100", Instant.parse("2026-09-23T21:00:00Z"))),
      "rev3's Wed 09:00 candidate is suppressed; Wed 21:00 is materialised at the UTC instant"
    )
