package dosegram.core.domain.insights

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The local day of VISUALS_AND_ANALYTICS section 7.1: the half-open interval from 00:00 to the next 00:00 in an IANA
  * zone. A daylight-saving day has 23 or 25 hours, so a day is never computed as "start plus 24 hours".
  */
object LocalDays:

  def dateOf(instant: Instant, zone: ZoneId): LocalDate = LocalDate.ofInstant(instant, zone)

  /** First instant of the local day. Where midnight falls in a gap, the day starts at the first valid local time. */
  def startOf(date: LocalDate, zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant

  /** First instant after the local day: the start of the next one. */
  def endOf(date: LocalDate, zone: ZoneId): Instant = startOf(date.plusDays(1), zone)
