package dosegram.core.application.capture

import dosegram.core.domain.OccurrenceStatus
import dosegram.core.ports.DoseMark
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.Tx

import java.time.LocalDate
import java.util.UUID

/** The doses of one local day as the daily summary draws them, in schedule order. A cancelled dose is not a dose of the
  * day; a dose taken after its window is late by the engine's own rule (`Decide`: past `due_window_end`).
  */
private[application] object DoseMarks:

  def of(tx: Tx, accountId: UUID, date: LocalDate): List[DoseMark] =
    tx.occurrences.listForAccountBetween(accountId, date, date).sortBy(_.scheduledFor).flatMap(markOf)

  private def markOf(occurrence: StoredOccurrence): Option[DoseMark] =
    occurrence.status match
      case OccurrenceStatus.Taken =>
        val late = occurrence.state.effectiveAt.exists(_.isAfter(occurrence.state.dueWindowEnd))
        Some(if late then DoseMark.TakenLate else DoseMark.TakenOnTime)
      case OccurrenceStatus.Skipped                                                   => Some(DoseMark.Skipped)
      case OccurrenceStatus.Missed                                                    => Some(DoseMark.NotConfirmed)
      case OccurrenceStatus.Unknown                                                   => Some(DoseMark.NoRecord)
      case OccurrenceStatus.Pending | OccurrenceStatus.Due | OccurrenceStatus.Snoozed => Some(DoseMark.Open)
      case OccurrenceStatus.Cancelled                                                 => None
