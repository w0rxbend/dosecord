package dosegram.core.domain.insights

import java.time.LocalDate

/** The periods of the text statistics (PRODUCT_SPEC R-INS-2, MVP part): today, the last 7 and the last 30 local days,
  * each ending on the account's current local day.
  */
enum StatsPeriod(val days: Int):
  case Today extends StatsPeriod(1)
  case Last7Days extends StatsPeriod(7)
  case Last30Days extends StatsPeriod(30)

  def window(today: LocalDate): DateRange = DateRange.endingOn(today, days)
