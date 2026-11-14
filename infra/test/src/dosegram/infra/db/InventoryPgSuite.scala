package dosegram.infra.db

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.inventory.RefillThreshold
import dosegram.core.ports.InventoryRefusal
import dosegram.core.ports.InventorySettings
import dosegram.core.ports.ScheduleStatus

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** The inventory of V8 against Postgres 18 (PRODUCT_SPEC R-REG-11 to R-REG-16; ROADMAP G9.10): the trigger that
  * deducts a taken dose and restores an undone one on every write path, the floor at zero with its mark, idempotent
  * and versioned writes, and the due pauses of R-REG-5.
  */
class InventoryPgSuite extends PgSuite:

  private val monday = Instant.parse("2026-09-21T08:00:00Z")
  private val utc = ZoneId.of("UTC")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE inventory_events, medication_inventory, dose_actions, dose_occurrences, schedule_revisions, " +
            "medication_schedules, medications, domain_events, users CASCADE"
        )
      finally st.close()
    }

  private lazy val fixtures = Fixtures(dataSource)

  private def daily(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private final case class Med(account: UUID, medication: UUID, schedule: UUID)

  private def scheduled(): Med =
    val account = fixtures.account("UTC")
    val outcome = fixtures.schedule(MutableClock(monday), account, "Iron", daily("09:00", "21:00"), utc)
    val medication = fixtures.uow.transaction(_.schedules.get(outcome.scheduleId).get.medicationId)
    Med(account, medication, outcome.scheduleId)

  private def settings(med: Med, onHand: BigDecimal, perIntake: BigDecimal = 1, key: String = UUID.randomUUID().toString) =
    InventorySettings(med.medication, med.account, onHand, perIntake, Some(RefillThreshold.Days(7)), key)

  private def save(med: Med, onHand: BigDecimal, perIntake: BigDecimal = 1, version: Option[Int] = None) =
    fixtures.uow.transaction(_.medicationDepth.inventory.save(settings(med, onHand, perIntake), version, monday))

  private def inventory(med: Med) =
    fixtures.uow.transaction(_.medicationDepth.inventory.get(med.account, med.medication)).get

  private def setStatus(occurrence: UUID, status: OccurrenceStatus, at: Instant): Unit =
    withConnection { conn =>
      given Connection = conn
      status match
        case OccurrenceStatus.Taken =>
          sql"""UPDATE dose_occurrences SET status = 'taken', taken_at = $at, effective_at = $at, next_action_at = NULL,
                  updated_at = $at WHERE id = $occurrence""".execute()
        case OccurrenceStatus.Due =>
          sql"""UPDATE dose_occurrences SET status = 'due', taken_at = NULL, effective_at = NULL, next_action_at = $at,
                  updated_at = $at WHERE id = $occurrence""".execute()
        case other => fail(s"not used here: $other")
      ()
    }

  private def events(med: Med): List[(String, BigDecimal)] =
    withConnection { conn =>
      given Connection = conn
      given RowMapper[(String, BigDecimal)] = rs => (rs.getString("kind"), BigDecimal(rs.getBigDecimal("quantity")))
      sql"""SELECT kind, quantity FROM inventory_events WHERE medication_id = ${med.medication}
            ORDER BY occurred_at, kind DESC""".query[(String, BigDecimal)]()
    }

  test("AC-REG-8: Taken deducts the units of one intake, Undo gives them back, Taken again deducts once more"):
    val med = scheduled()
    assert(save(med, 10, BigDecimal("0.5")).isRight)
    val dose = fixtures.occurrenceAt(med.schedule, monday.plus(Duration.ofDays(3)), "t0900x")
    setStatus(dose, OccurrenceStatus.Taken, monday.plusSeconds(60))
    assertEquals(inventory(med).onHand, BigDecimal("9.5"))
    setStatus(dose, OccurrenceStatus.Due, monday.plusSeconds(120))
    assertEquals(inventory(med).onHand, BigDecimal("10.0"))
    setStatus(dose, OccurrenceStatus.Taken, monday.plusSeconds(180))
    assertEquals(inventory(med).onHand, BigDecimal("9.5"))
    assertEquals(events(med).map(_._1), List("set", "intake", "restore", "intake"))

  test("an as-needed dose inserted as taken deducts; a medication without a count is untouched"):
    val med = scheduled()
    assert(save(med, 3).isRight)
    fixtures.occurrenceAt(med.schedule, monday.plus(Duration.ofDays(4)), "manual:1", OccurrenceStatus.Taken)
    assertEquals(inventory(med).onHand, BigDecimal(2))
    val other = scheduled()
    fixtures.occurrenceAt(other.schedule, monday.plus(Duration.ofDays(4)), "manual:2", OccurrenceStatus.Taken)
    assertEquals(fixtures.uow.transaction(_.medicationDepth.inventory.get(other.account, other.medication)), None)

  test("AC-REG-10: a deduction below zero stores zero and marks the count; Undo gives back only what it took"):
    val med = scheduled()
    assert(save(med, BigDecimal("0.5")).isRight)
    val dose = fixtures.occurrenceAt(med.schedule, monday.plus(Duration.ofDays(3)), "t0900x")
    setStatus(dose, OccurrenceStatus.Taken, monday.plusSeconds(60))
    assertEquals(inventory(med).onHand, BigDecimal("0.000"))
    assert(inventory(med).countOff)
    setStatus(dose, OccurrenceStatus.Due, monday.plusSeconds(120))
    assertEquals(inventory(med).onHand, BigDecimal("0.500"))

  test("a dose taken before the count existed gives nothing back on Undo"):
    val med = scheduled()
    val dose = fixtures.occurrenceAt(med.schedule, monday.plus(Duration.ofDays(3)), "t0900x", OccurrenceStatus.Taken)
    assert(save(med, 5).isRight)
    setStatus(dose, OccurrenceStatus.Due, monday.plusSeconds(120))
    assertEquals(inventory(med).onHand, BigDecimal(5))

  test("writes are versioned and idempotent: a stale version is refused, a repeated key changes nothing"):
    val med = scheduled()
    val created = save(med, 10).toOption.get
    assertEquals(created.version, 1)
    assertEquals(save(med, 12, version = Some(1)).map(_.onHand), Right(BigDecimal(12)))
    save(med, 20, version = Some(1)) match
      case Left(InventoryRefusal.VersionConflict(current)) => assertEquals(current.onHand, BigDecimal(12))
      case other                                           => fail(s"expected a version conflict: $other")
    val key = UUID.randomUUID().toString
    val repo = (tx: dosegram.core.ports.Tx) => tx.medicationDepth.inventory
    fixtures.uow.transaction(tx => repo(tx).refill(med.account, med.medication, 30, key, monday))
    val again = fixtures.uow.transaction(tx => repo(tx).refill(med.account, med.medication, 30, key, monday)).get
    assertEquals(again.onHand, BigDecimal(42))
    assertEquals(again.lastRefill, Some(BigDecimal(30)))
    // Another account cannot write this medication's count.
    val stranger = fixtures.account("UTC")
    val foreign = fixtures.uow.transaction(
      _.medicationDepth.inventory.save(settings(med, 1).copy(accountId = stranger), None, monday)
    )
    assertEquals(foreign, Left(InventoryRefusal.NotFound))

  test("a stopped notice is cleared by a refill; candidates are the counts with a threshold not sent since"):
    val med = scheduled()
    assert(save(med, 10).isRight)
    val repo = (tx: dosegram.core.ports.Tx) => tx.medicationDepth.inventory
    assertEquals(fixtures.uow.transaction(repo(_).noticeCandidates(monday, 10)).map(_.medicationId), List(med.medication))
    fixtures.uow.transaction(repo(_).markNotice(med.medication, monday))
    assertEquals(fixtures.uow.transaction(repo(_).noticeCandidates(monday, 10)), Nil)
    assertEquals(fixtures.uow.transaction(repo(_).noticeCandidates(monday.plusSeconds(1), 10)).size, 1)
    assert(fixtures.uow.transaction(repo(_).stopNotices(med.account, med.medication, monday)))
    assertEquals(fixtures.uow.transaction(repo(_).noticeCandidates(monday.plusSeconds(1), 10)), Nil)
    fixtures.uow.transaction(repo(_).refill(med.account, med.medication, 30, UUID.randomUUID().toString, monday))
    assertEquals(fixtures.uow.transaction(repo(_).noticeCandidates(monday.plusSeconds(1), 10)).size, 1)

  test("AC-REG-7 on stored schedules: every other day with 5 units covers 10 days, and a pause moves it out"):
    val account = fixtures.account("UTC")
    val clock = MutableClock(monday) // 08:00 UTC; today's 09:00 is ahead
    val rule = Rule.EveryNDays(2, List(HhMm.unsafe("09:00")), LocalDate.of(2026, 9, 21))
    val outcome = fixtures.schedule(clock, account, "Alternate", rule, utc)
    val medication = fixtures.uow.transaction(_.schedules.get(outcome.scheduleId).get.medicationId)
    val med = Med(account, medication, outcome.scheduleId)
    assert(save(med, 5).isRight)
    def view() = fixtures.uow.transaction(tx =>
      dosegram.core.application.medication.InventoryService.view(tx, account, medication, monday).get
    )
    assertEquals(view().supply.daysOfSupply, Some(10))
    assert(!view().belowThreshold, "10 days is above the 7-day threshold")
    fixtures.lifecycle(clock).pause(outcome.scheduleId)
    assertEquals(view().supply.daysOfSupply, None, "paused without a date: nothing is taken")
    fixtures.uow.transaction(_.medicationDepth.pauses.setResumeOn(outcome.scheduleId, Some(LocalDate.of(2026, 9, 26)), monday))
    // From the 26th the anchor still decides: the 27th, 29th, 1st, 3rd, 5th are covered; the 7th is not.
    assertEquals(view().supply.daysOfSupply, Some(16))

  test("R-REG-5: a paused schedule with a resume date is due from local midnight of that date in its own zone"):
    val account = fixtures.account("Europe/Kyiv")
    val kyiv = ZoneId.of("Europe/Kyiv")
    val outcome = fixtures.schedule(MutableClock(monday), account, "Iron", daily("09:00"), kyiv)
    fixtures.lifecycle(MutableClock(monday)).pause(outcome.scheduleId)
    val resumeOn = LocalDate.of(2026, 9, 25)
    fixtures.uow.transaction(_.medicationDepth.pauses.setResumeOn(outcome.scheduleId, Some(resumeOn), monday))
    val beforeMidnight = Instant.parse("2026-09-24T20:59:00Z") // 23:59 in Kyiv
    val atMidnight = Instant.parse("2026-09-24T21:00:00Z")
    assertEquals(fixtures.uow.transaction(_.medicationDepth.pauses.dueResumes(beforeMidnight, 10)), Nil)
    val due = fixtures.uow.transaction(_.medicationDepth.pauses.dueResumes(atMidnight, 10))
    assertEquals(due.map(d => (d.scheduleId, d.accountId, d.resumeOn)), List((outcome.scheduleId, account, resumeOn)))
    assertEquals(fixtures.uow.transaction(_.schedules.get(outcome.scheduleId).get.status), ScheduleStatus.Paused)
    // A resume by any path forgets the date: a later pause without one stays paused.
    fixtures.lifecycle(MutableClock(monday)).resume(outcome.scheduleId)
    assertEquals(fixtures.uow.transaction(_.medicationDepth.pauses.resumeOn(outcome.scheduleId)), None)
end InventoryPgSuite
