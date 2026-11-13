package dosegram.infra.db

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.DoseSnapshot
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.ports.MedicationEdit
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.StoredMedication
import dosegram.core.scheduling.AddSchedule

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The registry writes the Mini App API needs (ROADMAP G3.5, G3.6): a versioned medication edit, the list that
  * includes archived rows, a schedule for a medication that already exists, and a dose change as a revision.
  */
final class MedicationRegistrySuite extends PgSuite:

  private val Utc = ZoneId.of("UTC")
  private val T0 = Instant.parse("2026-11-13T08:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)

  private def edit(row: StoredMedication, accountId: UUID, name: String): MedicationEdit =
    MedicationEdit(
      row.id,
      accountId,
      row.updatedAt,
      name,
      row.doseAmount,
      row.doseUnit,
      row.instructions,
      MedicationStatus.Active
    )

  private def stored(id: UUID): StoredMedication = fixtures.uow.transaction(_.medications.get(id)).get

  private val everyDayAtNine = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))

  test("replace applies an edit that carries the current version and returns the row as written"):
    val account = fixtures.account()
    val id = fixtures.medication(account, "Aspirin", T0, Some(BigDecimal(100)), Some("mg"))
    val before = stored(id)
    val written = fixtures.uow.transaction(_.medications.replace(edit(before, account, "Aspirin forte"), T0.plusSeconds(60)))
    assertEquals(written.map(_.name), Some("Aspirin forte"))
    assertEquals(written.map(_.nameNorm), Some("aspirin forte"))
    assertEquals(written.map(_.updatedAt), Some(T0.plusSeconds(60)))
    assertEquals(stored(id).name, "Aspirin forte")

  test("replace refuses a stale version and changes nothing"):
    val account = fixtures.account()
    val id = fixtures.medication(account, "Ibuprofen", T0)
    val before = stored(id)
    fixtures.uow.transaction(_.medications.replace(edit(before, account, "Ibuprofen 200"), T0.plusSeconds(60)))
    val stale = fixtures.uow.transaction(_.medications.replace(edit(before, account, "Lost update"), T0.plusSeconds(120)))
    assertEquals(stale, None)
    assertEquals(stored(id).name, "Ibuprofen 200")

  test("replace refuses the row of another account"):
    val owner = fixtures.account()
    val stranger = fixtures.account()
    val id = fixtures.medication(owner, "Private", T0)
    val foreign = fixtures.uow.transaction(_.medications.replace(edit(stored(id), stranger, "Taken over"), T0.plusSeconds(60)))
    assertEquals(foreign, None)
    assertEquals(stored(id).name, "Private")

  test("two edits at the same clock instant carry different versions"):
    val account = fixtures.account()
    val id = fixtures.medication(account, "Zinc", T0)
    val first = fixtures.uow.transaction(_.medications.replace(edit(stored(id), account, "Zinc 10"), T0)).get
    val second = fixtures.uow.transaction(_.medications.replace(edit(first, account, "Zinc 20"), T0)).get
    assert(first.updatedAt.isAfter(T0), s"first edit kept ${first.updatedAt}")
    assert(second.updatedAt.isAfter(first.updatedAt), s"second edit kept ${second.updatedAt}")

  test("listAllForAccount includes archived medications and no row of another account"):
    val account = fixtures.account()
    val other = fixtures.account()
    val kept = fixtures.medication(account, "Kept", T0)
    val archived = fixtures.medication(account, "Archived", T0.plusSeconds(1))
    fixtures.medication(other, "Foreign", T0)
    fixtures.uow.transaction: tx =>
      tx.medications.replace(edit(stored(archived), account, "Archived").copy(status = MedicationStatus.Archived), T0)
    val listed = fixtures.uow.transaction(_.medications.listAllForAccount(account))
    assertEquals(listed.map(_.id), List(kept, archived))
    assertEquals(listed.map(_.status), List(MedicationStatus.Active, MedicationStatus.Archived))
    assertEquals(fixtures.uow.transaction(_.medications.listForAccount(account)).map(_.id), List(kept))

  test("addInTx starts a schedule for an existing medication with its dose snapshot and its dates"):
    val clock = MutableClock(T0)
    val account = fixtures.account()
    val id = fixtures.medication(account, "Magnesium", T0, Some(BigDecimal(250)), Some("mg"))
    val plan = AddSchedule(id, everyDayAtNine, Utc, endDate = Some(LocalDate.parse("2026-11-13")))
    val outcome = fixtures.uow.transaction(tx => fixtures.lifecycle(clock).addInTx(tx, account, plan, T0)).toOption.get
    val (schedule, revision, rows) = fixtures.uow.transaction: tx =>
      (tx.schedules.get(outcome.scheduleId).get, tx.revisions.latest(outcome.scheduleId).get,
        tx.occurrences.listBySchedule(outcome.scheduleId))
    assertEquals(schedule.medicationId, id)
    assertEquals(schedule.endDate, Some(LocalDate.parse("2026-11-13")))
    assertEquals(revision.doseSnapshot, DoseSnapshot("Magnesium", Some(BigDecimal(250)), Some("mg"), None))
    // The end date is inclusive: 09:00 on the 13th exists, 09:00 on the 14th does not.
    assertEquals(rows.map(_.scheduledFor), List(Instant.parse("2026-11-13T09:00:00Z")))

  test("addInTx treats the medication of another account as unknown and writes nothing"):
    val clock = MutableClock(T0)
    val owner = fixtures.account()
    val stranger = fixtures.account()
    val id = fixtures.medication(owner, "Owned", T0)
    intercept[NoSuchElementException]:
      fixtures.uow.transaction(tx => fixtures.lifecycle(clock).addInTx(tx, stranger, AddSchedule(id, everyDayAtNine, Utc), T0))
    assertEquals(fixtures.uow.transaction(_.schedules.listForMedication(id)), Nil)

  test("changeDoseInTx re-materialises pending rows with the new snapshot and keeps the rule"):
    val clock = MutableClock(T0)
    val account = fixtures.account()
    val created = fixtures.schedule(clock, account, "Vitamin D", everyDayAtNine, Utc, doseAmount = Some(BigDecimal(1000)), doseUnit = Some("IU"))
    val renamed = DoseSnapshot("Vitamin D3", Some(BigDecimal(2000)), Some("IU"), None)
    val later = T0.plusSeconds(60)
    val outcome = fixtures.uow.transaction(tx => fixtures.lifecycle(clock).changeDoseInTx(tx, created.scheduleId, renamed, later))
    val (revision, live) = fixtures.uow.transaction: tx =>
      (tx.revisions.latest(created.scheduleId).get,
        tx.occurrences.listBySchedule(created.scheduleId).filter(_.status == OccurrenceStatus.Pending))
    assertEquals(outcome.revision, 2)
    assertEquals(revision.rule, everyDayAtNine)
    assertEquals(revision.reason, Some("dose_change"))
    assertEquals(live.map(_.scheduledFor), List(Instant.parse("2026-11-13T09:00:00Z"), Instant.parse("2026-11-14T09:00:00Z")))
    assertEquals(live.flatMap(_.doseSnapshot).distinct, List(renamed))
    assertEquals(live.flatMap(_.revision).distinct, List(2))
