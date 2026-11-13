package dosegram.core.ports

import dosegram.core.domain.insights.DoseRecord

import java.time.Instant
import java.util.UUID

/** The read model behind the medication statistics: `dose_occurrences` rows in the shape of the adherence maths. */
trait DoseHistoryRepository:

  /** Every occurrence of the account that could count toward a local day inside `[from, until)`: a row is returned when
    * its scheduled, effective, skip or miss instant lies in the interval. The caller attributes rows to days with the
    * pure functions; a row that belongs to a day outside the wanted ones is ignored there.
    */
  def attributableBetween(accountId: UUID, from: Instant, until: Instant): List[DoseRecord]
