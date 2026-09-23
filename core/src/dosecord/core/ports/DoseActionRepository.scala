package dosecord.core.ports

import dosecord.core.domain.ActionRowIntent
import dosecord.core.domain.Actor
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.copy.DoseActionKind

import java.time.Instant
import java.util.UUID

/** One `dose_actions` insert. `seq` is assigned by the database (`max(seq) + 1` per occurrence, inside the same
  * transaction). The vendor/platform fields of the schema stay NULL until the one-tap flows of M1.10 fill them.
  */
final case class NewDoseAction(
    id: UUID,
    occurrenceId: UUID,
    accountId: UUID,
    action: DoseActionKind,
    actor: Actor,
    occurredAt: Instant,
    priorStatus: OccurrenceStatus,
    newStatus: OccurrenceStatus,
    correlationId: String,
    effectiveAt: Option[Instant] = None,
    reasonCode: Option[String] = None,
    note: Option[String] = None,
    undoesSeq: Option[Int] = None,
    catchUp: Boolean = false,
    idempotencyKey: Option[String] = None,
    metadata: String = "{}"
)

object NewDoseAction:

  /** The persistence form of an M1.3 [[ActionRowIntent]]; `takenLate` and `collapsedReminders` are carried into
    * `metadata` (there are no columns for them, Transition.scala).
    */
  def from(intent: ActionRowIntent, occurrenceId: UUID, accountId: UUID, correlationId: String): NewDoseAction =
    NewDoseAction(
      id = UUID.randomUUID(),
      occurrenceId = occurrenceId,
      accountId = accountId,
      action = intent.action,
      actor = intent.actor,
      occurredAt = intent.occurredAt,
      priorStatus = intent.priorStatus,
      newStatus = intent.newStatus,
      correlationId = correlationId,
      effectiveAt = intent.effectiveAt,
      reasonCode = intent.reasonCode,
      note = intent.note,
      undoesSeq = intent.undoesSeq,
      catchUp = intent.catchUp,
      metadata = metadataOf(intent)
    )

  private def metadataOf(intent: ActionRowIntent): String =
    val fields =
      (if intent.takenLate then List("\"taken_late\":true") else Nil) ++
        (if intent.collapsedReminders > 0 then List(s"\"collapsed_reminders\":${intent.collapsedReminders}") else Nil)
    if fields.isEmpty then "{}" else fields.mkString("{", ",", "}")

final case class StoredDoseAction(
    id: UUID,
    occurrenceId: UUID,
    accountId: UUID,
    seq: Int,
    action: DoseActionKind,
    actor: Actor,
    occurredAt: Instant,
    recordedAt: Instant,
    priorStatus: OccurrenceStatus,
    newStatus: OccurrenceStatus,
    effectiveAt: Option[Instant],
    reasonCode: Option[String],
    note: Option[String],
    undoesSeq: Option[Int],
    catchUp: Boolean,
    correlationId: String,
    idempotencyKey: Option[String],
    metadata: String
)

/** `dose_actions` (R27): the append-only audit log whose fold is the projection (DESIGN.md section 8). The INSERT-only
  * trigger of V1 rejects UPDATE/DELETE; this port offers no such methods.
  */
trait DoseActionRepository:
  def append(row: NewDoseAction): Unit
  def listForOccurrence(occurrenceId: UUID): List[StoredDoseAction]
