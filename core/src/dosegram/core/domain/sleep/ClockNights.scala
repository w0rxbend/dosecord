package dosegram.core.domain.sleep

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Turns clock times the user typed into instants (PRODUCT_SPEC R-SLP-1, R-SLP-21). The user names a clock time; the
  * record is an instant, so a night that crosses a daylight-saving change has the length that actually passed. A clock
  * time that does not exist on the spring-forward day is read as the time the clock showed instead, one gap later; a
  * clock time that exists twice on the autumn day is read as its first occurrence.
  */
object ClockNights:

  /** The latest instant that is not after `limit` and shows `time` on the clock of `zone`. */
  def latestAt(time: LocalTime, limit: Instant, zone: ZoneId): Instant =
    val today = LocalDate.ofInstant(limit, zone)
    // Two days back covers the day on which `time` falls in a gap and is read as a later instant than `limit`.
    List(today, today.minusDays(1), today.minusDays(2))
      .map(date => ZonedDateTime.of(date, time, zone).toInstant)
      .find(candidate => !candidate.isAfter(limit))
      .getOrElse(throw IllegalStateException(s"no occurrence of $time within two days before $limit in $zone"))

  /** The most recent finished period from the clock time `start` to the clock time `end`: `end` is its latest
    * occurrence that is not after `now`, `start` the latest occurrence before that.
    */
  def latest(start: LocalTime, end: LocalTime, now: Instant, zone: ZoneId): (Instant, Instant) =
    val endAt = latestAt(end, now, zone)
    (latestAt(start, endAt.minusSeconds(1), zone), endAt)
