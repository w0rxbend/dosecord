package dosegram.core.ports

import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.mood.MoodCheckin

import java.time.Instant
import java.util.UUID

/** `mood_checkins` (ARCHITECTURE section 10.8). Check-ins are immutable: a correction is a new row that supersedes the
  * old one, and the only change to a stored row is its void. Every method is scoped to one account.
  */
trait MoodRepository:

  /** Inserts the check-in unless the account already has one with the same client key; returns whether a row was
    * written.
    */
  def insert(checkin: MoodCheckin): Boolean

  def get(accountId: UUID, id: UUID): Option[MoodCheckin]
  def findByClientKey(accountId: UUID, clientKey: String): Option[MoodCheckin]

  /** Voids a live check-in; returns false when it is unknown or already voided. */
  def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean

  /** The check-ins that count toward figures, with a local date inside the range, oldest first. */
  def listLive(accountId: UUID, range: DateRange): List[MoodCheckin]
