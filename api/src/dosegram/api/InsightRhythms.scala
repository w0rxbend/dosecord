package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import dosegram.core.application.insights.RhythmView
import dosegram.core.domain.copy.RhythmCopy
import dosegram.core.domain.insights.RhythmRow
import dosegram.core.ports.UnitOfWork
import sttp.tapir.Schema

import java.util.UUID

/** One area of the rhythm: `text` as the chat prints its line; with its 14-day gate met, `hours` holds its records in
  * each hour of the local day from 00:00 (24 counts; sleep counts the nights asleep), `counts` names them and `summary`
  * says the rhythm in words.
  */
final case class InsightRhythmArea(
    id: InsightDomain,
    title: String,
    counts: String,
    recordedDays: Int,
    days: String,
    text: String,
    summary: Option[String] = None,
    hours: Option[List[Int]] = None
) derives ReadWriter,
      Schema

/** `GET /insights/rhythm`: the rhythm of the day as `/stats rhythm` shows it (supervisor decision, 2026-11-28), over
  * the last 30 local days. `lines` are the chat's text word for word, the text equivalent of the view; `areas` the same
  * figures as a table. `gateNote` is absent with the counts switch off (R-GAM-5).
  */
final case class InsightRhythm(
    timeZone: String,
    start: String,
    end: String,
    days: Int,
    daysWithRecords: Int,
    summary: String,
    gateNote: Option[String] = None,
    clockNote: String,
    areas: List[InsightRhythmArea],
    lines: List[String],
    notice: Option[String] = None
) derives ReadWriter,
      Schema

/** The rhythm of the Mini App (MINI_APP section 7.11, the Rhythm view): the figures of `StatsQueryService.rhythm`, the
  * source of `/stats rhythm` and of its card, in the core's copy.
  */
final class InsightRhythms(uow: UnitOfWork, tracking: TrackingUseCases):
  import InsightRhythms.*

  def rhythm(accountId: UUID): Either[ApiFailure, InsightRhythm] =
    uow.transactionAs(accountId): tx =>
      tracking.stats
        .rhythm(tx, accountId)
        .left
        .map(Refusals.of)
        .map(view => documentOf(view, tx.accounts.gamificationOf(accountId)))

object InsightRhythms:

  private def documentOf(view: RhythmView, countsOn: Boolean): InsightRhythm =
    val figures = view.figures
    InsightRhythm(
      timeZone = view.zone.getId,
      start = figures.window.start.toString,
      end = figures.window.end.toString,
      days = figures.window.days,
      daysWithRecords = figures.records.withRecords,
      summary = RhythmCopy.periodLine(figures.window, figures.records),
      gateNote = Option.when(countsOn && figures.rows.nonEmpty)(RhythmCopy.gateNote),
      clockNote = RhythmCopy.clockNote,
      areas = figures.rows.map(areaOf(_, countsOn)),
      lines = RhythmCopy.lines(figures, view.zone, countsOn),
      notice =
        if figures.rows.isEmpty then Some(ApiCopy.noConsentedDomain)
        else Option.unless(figures.hasRecords)(RhythmCopy.none)
    )

  private def areaOf(row: RhythmRow, countsOn: Boolean): InsightRhythmArea =
    val pattern = row.pattern.shown
    InsightRhythmArea(
      id = InsightYears.domainOf(row.domain),
      title = RhythmCopy.areaName(row.domain),
      counts = RhythmCopy.bandName(row.domain),
      recordedDays = row.recordedDays,
      days = RhythmCopy.recordedDays(row.domain, row.recordedDays),
      text = RhythmCopy.rowLine(row, countsOn),
      summary = pattern.map(RhythmCopy.summary),
      hours = pattern.map(_.band.counts.toList)
    )
end InsightRhythms
