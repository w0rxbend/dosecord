package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.application.medication.MedicationWithdrawal
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.Wake
import dosegram.core.scheduling.Materialiser
import dosegram.core.scheduling.ReminderLoop
import dosegram.core.scheduling.ScheduleLifecycle

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** PRODUCT_SPEC R-IDC-10 on Testcontainers Postgres 18 (ROADMAP G2.8: "withdrawing consent stops reminders in the same
  * transaction"): the withdrawal of the `medication` consent, the pause of every schedule and the cancellation of every
  * open dose commit together or not at all, and afterwards neither the materialiser nor the reminder loop produces
  * anything for the account.
  */
class MedicationWithdrawalPgSuite extends PgSuite:

  private val start = Instant.parse("2026-11-13T05:00:00Z")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val everyDayAtNine = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
  private lazy val fixtures = Fixtures(dataSource)

  private final class Rig:
    val clock = MutableClock(start)
    val uow = PgUnitOfWork(dataSource, clock, DataKeys.development)
    val account: UUID = fixtures.medicationAccount("Europe/Kyiv")
    val schedule: UUID = fixtures.schedule(clock, account, "Medication A", everyDayAtNine, kyiv).scheduleId
    val withdrawal = MedicationWithdrawal(ScheduleLifecycle(uow, clock))

    def strings(query: SqlQuery): List[String] = withConnection: conn =>
      given Connection = conn
      query.query[String]()

    def scheduleStatus: List[String] = strings(sql"SELECT status FROM medication_schedules WHERE id = $schedule")

    def doseStatuses: List[String] =
      strings(sql"SELECT DISTINCT status::text FROM dose_occurrences WHERE account_id = $account ORDER BY 1")

    def withdraw(failAfter: Boolean): Unit =
      uow.transaction: tx =>
        assert(tx.consents.withdraw(account, ConsentPurpose.Medication, clock.now()))
        withdrawal.stop(tx, account, clock.now())
        if failAfter then throw IllegalStateException("the event fails after the withdrawal")

  test("a failed withdrawal changes nothing: the consent, the schedule and the doses stay as they were"):
    val rig = Rig()
    intercept[IllegalStateException](rig.withdraw(failAfter = true))
    assert(rig.uow.transaction(_.consents.isGranted(rig.account, ConsentPurpose.Medication)))
    assertEquals(rig.scheduleStatus, List("active"))
    assertEquals(rig.doseStatuses, List("pending"))

  test("a withdrawal stops the reminders with the consent: schedules paused, open doses cancelled, nothing new"):
    val rig = Rig()
    // 07:05 UTC is 09:05 in Kyiv: one tick makes the 09:00 dose due, so a dose that is past pending exists.
    rig.clock.advance(Duration.ofMinutes(125))
    val loop = ReminderLoop(rig.uow, Materialiser(rig.uow, rig.clock), Wake.polling, rig.clock, "withdrawal-suite")
    loop.tick(rig.clock.now())
    assertEquals(rig.doseStatuses, List("due", "pending"))
    def queued: Int = rig.strings(sql"SELECT id::text FROM outbox_messages WHERE account_id = ${rig.account}").size
    val queuedBefore = queued

    rig.withdraw(failAfter = false)
    assert(!rig.uow.transaction(_.consents.isGranted(rig.account, ConsentPurpose.Medication)))
    assertEquals(rig.scheduleStatus, List("paused"))
    assertEquals(rig.doseStatuses, List("cancelled"))
    val reasons = rig.strings(
      sql"SELECT DISTINCT cancel_reason FROM dose_occurrences WHERE account_id = ${rig.account} ORDER BY 1"
    )
    assertEquals(reasons, List("consent_withdrawn", "paused"))
    val doses = rig.strings(sql"SELECT id::text FROM dose_occurrences WHERE account_id = ${rig.account}").size
    val cancelActions = rig.strings(
      sql"SELECT id::text FROM dose_actions WHERE account_id = ${rig.account} AND action = 'cancelled'"
    )
    assertEquals(cancelActions.size, doses, "every cancelled dose has its action row")

    // R-IDC-9: nothing is scheduled without the consent, however long the account stays without it.
    rig.clock.advance(Duration.ofDays(3))
    Materialiser(rig.uow, rig.clock).runOnce()
    loop.tick(rig.clock.now())
    assertEquals(queued, queuedBefore, "the loop queues no reminder for the account")
    assertEquals(rig.strings(sql"SELECT id::text FROM dose_occurrences WHERE account_id = ${rig.account}").size, doses)
