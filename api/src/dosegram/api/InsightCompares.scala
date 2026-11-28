package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import dosegram.core.application.insights.CompareView
import dosegram.core.domain.copy.CompareCopy
import dosegram.core.domain.copy.YearCopy
import dosegram.core.domain.insights.CompareRow
import dosegram.core.domain.insights.CompareSelection
import dosegram.core.domain.insights.ComparedPeriod
import dosegram.core.domain.insights.PeriodComparisons
import dosegram.core.domain.insights.RecordedDays
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.YearCell
import dosegram.core.ports.UnitOfWork
import sttp.tapir.Schema

import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.UUID

/** One side of a comparison: its name, its days and its days with records under the one definition. */
final case class InsightComparePeriod(
    label: String,
    start: String,
    end: String,
    days: Int,
    daysWithRecords: Int,
    summary: String
) derives ReadWriter,
      Schema

/** One period of an area: `text` as the chat prints it, the recorded days, and below the period's gate no `figure` and
  * no `value` (the bar's length on the area's own scale).
  */
final case class InsightCompareCell(
    text: String,
    recordedDays: Int,
    figure: Option[String] = None,
    value: Option[Double] = None
) derives ReadWriter,
      Schema

/** One area: what its figures are (`title`), each period and the difference in words when both show a figure. */
final case class InsightCompareArea(
    id: InsightDomain,
    title: String,
    earlier: InsightCompareCell,
    later: InsightCompareCell,
    difference: Option[String] = None
) derives ReadWriter,
      Schema

/** A month the picker offers, newest first: `month` is YYYY-MM. */
final case class InsightCompareMonth(month: String, label: String) derives ReadWriter, Schema

/** `GET /insights/compare`: two periods side by side as `/stats compare` shows them (supervisor decision, 2026-11-28).
  * `lines` are the chat's text word for word, the text equivalent of the view; `areas` the same figures as a table.
  * `selection` is `weeks`, `months` or `pair`; `months` are the months a pair may name. `gateNote` is absent with the
  * counts switch off (R-GAM-5).
  */
final case class InsightCompare(
    selection: String,
    timeZone: String,
    earlier: InsightComparePeriod,
    later: InsightComparePeriod,
    gateNote: Option[String] = None,
    differenceNote: String,
    areas: List[InsightCompareArea],
    months: List[InsightCompareMonth],
    lines: List[String],
    notice: Option[String] = None
) derives ReadWriter,
      Schema

/** The comparison of the Mini App (MINI_APP section 7.11, the Compare view): the figures of
  * `StatsQueryService.compare`, the source of `/stats compare` and of its card, in the core's copy.
  */
final class InsightCompares(uow: UnitOfWork, tracking: TrackingUseCases):
  import InsightCompares.*

  def compare(
      accountId: UUID,
      period: Option[String],
      first: Option[String],
      second: Option[String]
  ): Either[ApiFailure, InsightCompare] =
    selectionOf(period, first, second).flatMap: selection =>
      uow.transactionAs(accountId): tx =>
        tracking.stats
          .compare(tx, accountId, selection)
          .left
          .map(Refusals.of)
          .map(view => documentOf(view, selection, tx.accounts.gamificationOf(accountId)))

object InsightCompares:

  /** `weeks` (the default), `months`, or two months named by `first` and `second` in either order. */
  private def selectionOf(
      period: Option[String],
      first: Option[String],
      second: Option[String]
  ): Either[ApiFailure, CompareSelection] =
    (period, first, second) match
      case (None | Some("weeks"), None, None)      => Right(CompareSelection.Weeks)
      case (Some("months"), None, None)            => Right(CompareSelection.Months)
      case (None | Some("pair"), Some(a), Some(b)) =>
        for
          earlier <- monthOf("first", a)
          later <- monthOf("second", b)
          pair <- CompareSelection.twoMonths(earlier, later).toRight(ApiFailure.invalid("second", "invalid"))
        yield pair
      case (None | Some("pair"), _, _) =>
        Left(ApiFailure.invalid(if first.isEmpty then "first" else "second", "required"))
      case _ => Left(ApiFailure.invalid("period", "invalid"))

  private def monthOf(field: String, raw: String): Either[ApiFailure, YearMonth] =
    try Right(YearMonth.parse(raw))
    catch case _: DateTimeParseException => Left(ApiFailure.invalid(field, "invalid"))

  private def documentOf(view: CompareView, selection: CompareSelection, countsOn: Boolean): InsightCompare =
    val figures = view.figures
    InsightCompare(
      selection = selection match
        case CompareSelection.Weeks        => "weeks"
        case CompareSelection.Months       => "months"
        case _: CompareSelection.TwoMonths => "pair",
      timeZone = view.zone.getId,
      earlier = periodOf(figures.frame.earlier, figures.earlierDays),
      later = periodOf(figures.frame.later, figures.laterDays),
      gateNote = Option.when(countsOn && figures.rows.nonEmpty)(CompareCopy.gateNote(figures.frame.gate)),
      differenceNote = CompareCopy.differenceNote,
      areas = figures.rows.map(areaOf(_, countsOn)),
      months = CompareSelection
        .pickable(YearMonth.from(view.today))
        .map(month => InsightCompareMonth(month.toString, CompareCopy.monthLabel(month))),
      lines = CompareCopy.lines(figures, view.zone, countsOn),
      notice =
        if figures.rows.isEmpty then Some(ApiCopy.noConsentedDomain)
        else Option.unless(figures.hasRecords)(CompareCopy.none)
    )

  private def periodOf(period: ComparedPeriod, days: RecordedDays): InsightComparePeriod =
    InsightComparePeriod(
      label = CompareCopy.label(period.name),
      start = period.window.start.toString,
      end = period.window.end.toString,
      days = period.window.days,
      daysWithRecords = days.withRecords,
      summary = CompareCopy.periodLine(period, days)
    )

  private def areaOf(row: CompareRow, countsOn: Boolean): InsightCompareArea =
    InsightCompareArea(
      id = InsightYears.domainOf(row.domain),
      title = YearCopy.areaName(row.domain),
      earlier = cellOf(row.domain, row.earlier, countsOn),
      later = cellOf(row.domain, row.later, countsOn),
      difference = row.difference.map(CompareCopy.difference(row.domain, _))
    )

  private def cellOf(domain: TrendDomain, cell: YearCell, countsOn: Boolean): InsightCompareCell =
    val shown = cell.figure.shown
    InsightCompareCell(
      text = CompareCopy.cell(domain, cell, countsOn),
      recordedDays = cell.recordedDays,
      figure = shown.map(CompareCopy.cardFigure),
      value = shown.map(PeriodComparisons.valueOf)
    )
end InsightCompares
