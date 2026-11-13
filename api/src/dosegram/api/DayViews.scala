package dosegram.api

import dosegram.core.domain.OccurrenceStatus
import dosegram.core.ports.Clock
import dosegram.core.ports.UnitOfWork

import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** `GET /today` and `GET /days/{date}` (ARCHITECTURE section 9.5, daily-overview): the dose occurrences of one local
  * day of the session's account. The account id is part of the query, so a day never holds a row of another account.
  */
final class DayViews(uow: UnitOfWork, clock: Clock):

  def today(accountId: UUID): DayView = day(accountId, None)

  def on(accountId: UUID, date: LocalDate): DayView = day(accountId, Some(date))

  private def day(accountId: UUID, requested: Option[LocalDate]): DayView =
    uow.transaction: tx =>
      val zone = ZoneId.of(tx.accounts.timezoneOf(accountId).getOrElse("UTC"))
      val now = clock.now().atZone(zone)
      val today = now.toLocalDate
      val date = requested.getOrElse(today)
      val rows = tx.occurrences
        .listForAccountBetween(accountId, date, date)
        .filterNot(_.status == OccurrenceStatus.Cancelled)
        .sortBy(_.scheduledFor)
      DayView(
        date = date,
        dateLabel = ApiCopy.dayLabel(date),
        timeZone = zone.getId,
        isToday = date == today,
        previousDate = date.minusDays(1),
        nextDate = date.plusDays(1),
        occurrences = rows.map(Rendering.occurrence(tx, _)),
        nowMinute = Option.when(date == today)(now.getHour * 60 + now.getMinute)
      )
