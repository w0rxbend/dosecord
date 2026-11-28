package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.YearCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.YearFigures
import dosegram.core.domain.insights.YearRow
import dosegram.core.domain.insights.YearStats
import dosegram.core.ports.Clock
import dosegram.core.ports.UnitOfWork
import sttp.tapir.Schema

import java.time.DayOfWeek
import java.time.ZoneId
import java.util.UUID

/** One month of an area: its recorded days and `text`, the cell as `/stats year` prints it (the figure from 14 recorded
  * days, else those days in brackets, else a dash).
  */
final case class InsightYearMonth(month: String, label: String, recordedDays: Int, text: String)
    derives ReadWriter,
      Schema

/** One area of the year: what its figures are (`title`), each month, the year and the year before in words (`total`,
  * absent below the gate), and for water, food, sleep and mood the days with a record week by week (`heat`). Medication
  * has no `heat` (PRODUCT_SPEC R-GAM-2, VISUALS section 7.3).
  */
final case class InsightYearArea(
    id: InsightDomain,
    title: String,
    recordedDays: Int,
    months: List[InsightYearMonth],
    total: Option[String] = None,
    heat: Option[Chart] = None
) derives ReadWriter,
      Schema

/** `GET /insights/year`: the calendar year of `/stats year` (PRODUCT_SPEC R-INS-13). `lines` are the chat's text word
  * for word, the text equivalent of the whole view; `areas` the same figures as tables and the week charts.
  * `daysWithRecords` leaves out the days whose only doses are open or have no record (a service outage, counted in
  * `outageNote`). `gateNote` is absent with the counts switch off (R-GAM-5).
  */
final case class InsightYear(
    year: Int,
    yearLabel: String,
    previousYear: Option[Int] = None,
    nextYear: Option[Int] = None,
    timeZone: String,
    start: String,
    end: String,
    days: Int,
    daysWithRecords: Int,
    summary: String,
    outageNote: Option[String] = None,
    gateNote: Option[String] = None,
    areas: List[InsightYearArea],
    lines: List[String],
    notice: Option[String] = None
) derives ReadWriter,
      Schema

/** The year of the Mini App (MINI_APP section 7.11, the Year tab; supervisor decision, 2026-11-28): the figures of
  * `StatsQueryService.year`, the source of `/stats year` and of the one-year card, in the core's copy.
  */
final class InsightYears(uow: UnitOfWork, tracking: TrackingUseCases, clock: Clock):
  import InsightYears.*

  def year(accountId: UUID, year: Option[Int]): Either[ApiFailure, InsightYear] =
    uow.transactionAs(accountId): tx =>
      for
        zone <- tx.accounts.timezoneOf(accountId).map(ZoneId.of).toRight(ApiFailure.notFound())
        current = LocalDays.dateOf(clock.now(), zone).getYear
        shown <- yearOf(year, current)
        view <- tracking.stats.year(tx, accountId, shown).left.map(_ => ApiFailure.notFound())
      yield
        val countsOn = tx.accounts.gamificationOf(accountId)
        documentOf(view.figures, view.zone, countsOn, current)

object InsightYears:

  private val FirstYear = 1970
  private val WeekStart = DayOfWeek.MONDAY

  /** A year from 1970 to the current one; absent is the current one. */
  private def yearOf(year: Option[Int], current: Int): Either[ApiFailure, Int] =
    year match
      case None                             => Right(current)
      case Some(asked) if asked > current   => Left(ApiFailure.invalid("year", "in_future"))
      case Some(asked) if asked < FirstYear => Left(ApiFailure.invalid("year", "invalid"))
      case Some(asked)                      => Right(asked)

  private def documentOf(figures: YearFigures, zone: ZoneId, countsOn: Boolean, current: Int): InsightYear =
    val weeks = YearStats.weeks(figures.window, WeekStart)
    val areas = figures.rows.map(areaOf(figures, weeks, _, countsOn))
    InsightYear(
      year = figures.year,
      yearLabel = YearCopy.header(figures.year, zone),
      previousYear = Option.when(figures.previousHasRecords)(figures.year - 1),
      nextYear = Option.when(figures.year < current)(figures.year + 1),
      timeZone = zone.getId,
      start = figures.window.start.toString,
      end = figures.window.end.toString,
      days = figures.window.days,
      daysWithRecords = figures.daysWithRecords,
      summary = YearCopy.daysWithRecords(figures.daysWithRecords, figures.window.days),
      outageNote = Option.when(figures.outageOnlyDays > 0)(StatsCopy.outageDaysNotCounted(figures.outageOnlyDays)),
      gateNote = Option.when(countsOn && areas.nonEmpty)(YearCopy.gateNote),
      areas = areas,
      lines = if countsOn then YearCopy.lines(figures, zone) else YearCopy.linesOmittingGates(figures, zone),
      notice =
        if areas.isEmpty then Some(ApiCopy.noConsentedDomain)
        else Option.unless(figures.hasRecords)(YearCopy.none(figures.year))
    )

  private def areaOf(
      figures: YearFigures,
      weeks: List[DateRange],
      row: YearRow,
      countsOn: Boolean
  ): InsightYearArea =
    val recorded = figures.recorded.getOrElse(row.domain, Set.empty)
    InsightYearArea(
      id = domainOf(row.domain),
      title = YearCopy.areaName(row.domain),
      recordedDays = row.year.recordedDays,
      months = row.months.map((month, cell) =>
        InsightYearMonth(month.toString, YearCopy.monthName(month), cell.recordedDays, YearCopy.cell(cell, countsOn))
      ),
      total = YearCopy.totals(figures.year, row),
      heat = Option.when(row.domain != TrendDomain.Medication)(
        YearCharts.byWeek(row.domain, recorded, weeks, figures.window)
      )
    )

  def domainOf(domain: TrendDomain): InsightDomain =
    domain match
      case TrendDomain.Medication => InsightDomain.Medication
      case TrendDomain.Hydration  => InsightDomain.Hydration
      case TrendDomain.Mood       => InsightDomain.Mood
      case TrendDomain.Sleep      => InsightDomain.Sleep
      case TrendDomain.Nutrition  => InsightDomain.Nutrition
end InsightYears
