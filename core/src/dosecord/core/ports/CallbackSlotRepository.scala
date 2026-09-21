package dosecord.core.ports

import java.time.Instant
import java.util.UUID

/** Row of `callback_slots` (DESIGN.md section 4.4): the server-side payload behind a slot-mode CallbackToken. `chat`
  * and `payload` are raw JSON documents; menu/wizard slots expire with their session.
  */
final case class CallbackSlot(
    id: UUID,
    accountId: Option[UUID],
    chat: String,
    payload: String,
    sessionId: Option[UUID],
    stepSeq: Option[Int],
    expiresAt: Option[Instant]
)

trait CallbackSlotRepository:
  def insert(slot: CallbackSlot): Unit

  /** Locks the row until the surrounding transaction ends. */
  def loadForUpdate(id: UUID): Option[CallbackSlot]

  /** Session cleanup on abort/complete (M0.12b): returns the number of slots deleted. */
  def deleteForSession(sessionId: UUID): Int
