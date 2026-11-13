package dosegram.core.domain

import dosegram.core.domain.insights.LocalDays

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The local day a new tracking entry is recorded for: today, or "add for yesterday" (PRODUCT_SPEC section 8.8). */
enum EntryDay:
  case Today, Yesterday

  /** The local date of the entry and the instant stored as its time. An entry for yesterday has no time of its own, so
    * it is placed at the last second of that local day: its instant then agrees with its local date and sorts after the
    * entries made on the day itself.
    */
  def resolve(now: Instant, zone: ZoneId): (LocalDate, Instant) =
    val today = LocalDays.dateOf(now, zone)
    this match
      case Today     => (today, now)
      case Yesterday => (today.minusDays(1), LocalDays.startOf(today, zone).minusSeconds(1))
