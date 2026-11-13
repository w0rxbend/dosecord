package dosegram.core.domain.insights

import java.time.LocalDate

/** A closed range of local days: the "period" of VISUALS_AND_ANALYTICS section 7.1. Both ends count. */
final case class DateRange private (start: LocalDate, end: LocalDate):
  def days: Int = (end.toEpochDay - start.toEpochDay).toInt + 1
  def dates: List[LocalDate] = List.tabulate(days)(offset => start.plusDays(offset.toLong))
  def contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

object DateRange:

  def between(start: LocalDate, end: LocalDate): DateRange =
    require(!end.isBefore(start), s"range end $end is before its start $start")
    new DateRange(start, end)

  /** The last `days` local days up to and including `end` ("the last 7 days" ends today). */
  def endingOn(end: LocalDate, days: Int): DateRange =
    require(days >= 1, s"a range has at least one day, got $days")
    new DateRange(end.minusDays(days.toLong - 1L), end)

  def single(date: LocalDate): DateRange = new DateRange(date, date)
