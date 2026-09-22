package dosecord.core.ports

import dosecord.core.domain.QuietHoursContext
import dosecord.core.domain.ReminderPolicy

/** The decision context the reminder loop needs per claimed row (DESIGN.md section 7.4's
  * `tx.policies.forOccurrence(occ)`): the reminder policy of the occurrence's materialising revision and the account's
  * quiet hours, interpreted in the occurrence's zone.
  */
trait PolicyRepository:

  /** The `(policy, quiet)` pair `Decide.decide` consumes. Manual rows (no schedule/revision, M1.10) get the ADR-012
    * default policy. A scheduled row whose revision no longer exists is corrupt: implementations throw, so the loop's
    * savepoint rolls the row back and quarantines it instead of deciding on a guessed policy.
    */
  def forOccurrence(occ: StoredOccurrence): (ReminderPolicy, QuietHoursContext)
