package dosegram.core.ports

import dosegram.core.domain.VoidReason
import dosegram.core.domain.hydration.GoalChange
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.insights.DateRange

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `hydration_entries`, `hydration_goals` and `hydration_presets` (ARCHITECTURE section 10.6). Entries are immutable:
  * the only change to a stored entry is its void. Every method is scoped to one account.
  */
trait HydrationRepository:

  /** Inserts the entry unless the account already has one with the same client key; returns whether a row was written.
    * The client key makes one tap, or one retried request, record exactly once.
    */
  def insert(entry: HydrationEntry): Boolean

  def get(accountId: UUID, id: UUID): Option[HydrationEntry]
  def findByClientKey(accountId: UUID, clientKey: String): Option[HydrationEntry]

  /** Voids a live entry; returns false when the entry is unknown or already voided, so a second void changes nothing.
    */
  def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean

  /** The entries that count toward totals, with a local date inside the range, oldest first. */
  def listLive(accountId: UUID, range: DateRange): List[HydrationEntry]

  /** Records the goal in force from `change.effectiveFrom`; a second change on the same day replaces the first. */
  def setGoal(accountId: UUID, change: GoalChange, now: Instant): Unit

  /** Every goal change that took effect on or before `upTo`, oldest first. */
  def goals(accountId: UUID, upTo: LocalDate): List[GoalChange]

  /** The quick-add amounts in slot order; empty when the account has never set them. */
  def presets(accountId: UUID): List[Int]

  def replacePresets(accountId: UUID, amountsMl: List[Int]): Unit
