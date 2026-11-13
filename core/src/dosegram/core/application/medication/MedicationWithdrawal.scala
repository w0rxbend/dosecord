package dosegram.core.application.medication

import dosegram.core.domain.Actor
import dosegram.core.domain.CancelReason
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.copy.DoseActionKind
import dosegram.core.ports.NewDoseAction
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.Tx
import dosegram.core.scheduling.ScheduleLifecycle

import java.time.Instant
import java.util.UUID

/** What withdrawing the `medication` consent does to the reminders (PRODUCT_SPEC R-IDC-10): every active schedule is
  * paused, so nothing new is materialised, and every dose that is still open is cancelled, so the reminder loop has
  * nothing to send and the outbox fences what is already queued by the bumped epoch. It runs on the transaction of the
  * withdrawal: the consent and the reminders stop together or not at all. A schedule stays paused after a new consent
  * until the user resumes it.
  */
final class MedicationWithdrawal(lifecycle: ScheduleLifecycle):

  def stop(tx: Tx, accountId: UUID, now: Instant): Unit =
    val schedules =
      tx.medications.listAllForAccount(accountId).flatMap(medication => tx.schedules.listForMedication(medication.id))
    schedules.filter(_.status == ScheduleStatus.Active).foreach(schedule => lifecycle.pauseInTx(tx, schedule.id, now))
    // The pause cancels the doses that were still ahead; a dose already due or snoozed is cancelled here.
    val open = schedules.flatMap(schedule => tx.occurrences.lockOpenRows(schedule.id))
    if open.nonEmpty then
      tx.occurrences.cancel(open.map(_.id), CancelReason.ConsentWithdrawn, now)
      val correlationId = UUID.randomUUID().toString
      open.foreach(row => tx.doseActions.append(cancelled(row, correlationId, now)))

  private def cancelled(row: StoredOccurrence, correlationId: String, now: Instant): NewDoseAction =
    NewDoseAction(
      id = UUID.randomUUID(),
      occurrenceId = row.id,
      accountId = row.accountId,
      action = DoseActionKind.Cancelled,
      actor = Actor.System,
      occurredAt = now,
      priorStatus = row.status,
      newStatus = OccurrenceStatus.Cancelled,
      correlationId = correlationId,
      metadata = s"""{"cancel_reason":"${CancelReason.ConsentWithdrawn.dbValue}"}"""
    )
