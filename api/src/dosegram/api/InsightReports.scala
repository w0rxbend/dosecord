package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import dosegram.core.application.insights.ComparisonWindow
import dosegram.core.domain.copy.InsightCopy
import dosegram.core.domain.copy.MonthCopy
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.tr
import dosegram.core.domain.insights.Insight
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.MedicationFigures
import dosegram.core.domain.insights.MonthDomain
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.ports.Clock
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork
import sttp.tapir.Schema

import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** One domain of a month in the server's words: its figures when it met the section gate of PRODUCT_SPEC R-INS-11, else
  * the gate with its progress in `gateText`.
  */
final case class InsightMonthDomain(
    id: InsightDomain,
    title: String,
    recordedDays: Int,
    meetsGate: Boolean,
    lines: List[String],
    gateText: Option[String] = None
) derives ReadWriter,
      Schema

/** One medication of the month (VISUALS IMG-07, MINI_APP section 7.12): its own counts, never a mean of percentages.
  * `percent` is present only from five resolved doses (VISUALS section 7.1); an as-needed medication has logs only.
  */
final case class InsightMedicationRow(
    id: String,
    name: String,
    archived: Boolean,
    line: String,
    taken: Int,
    resolved: Int,
    percent: Option[Int] = None,
    asNeededLogs: Int
) derives ReadWriter,
      Schema

/** `GET /insights/month`: one calendar month, one block per domain whose consent is active. `reportable` is the gate of
  * R-INS-11 for the whole month; `medications` are present only with the medication consent.
  */
final case class InsightMonth(
    month: String,
    monthLabel: String,
    previousMonth: String,
    nextMonth: Option[String] = None,
    timeZone: String,
    reportable: Boolean,
    daysWithRecords: Int,
    summary: String,
    domains: List[InsightMonthDomain],
    medications: List[InsightMedicationRow],
    notice: Option[String] = None
) derives ReadWriter,
      Schema

/** One insight that passed every check, in the server's words, with how it was worked out (VISUALS VA-IN-03). */
final case class InsightItem(id: String, text: String, howWorkedOut: List[String]) derives ReadWriter, Schema

/** `GET /insights/correlations`: up to five insights in rank order, the rules close to their minimum data, and whether
  * comparisons across areas are on. The app records no consent: it says where the chat asks for it.
  */
final case class InsightList(
    windowDays: Int,
    windowLabel: String,
    crossDomain: Boolean,
    items: List[InsightItem],
    close: List[String],
    notice: Option[String] = None
) derives ReadWriter,
      Schema

/** The month and the insights of the Mini App (ARCHITECTURE section 9.5, insights-and-correlations). Every sentence is
  * the core's copy, so the app shows the words of the chat and composes none of its own (MINI_APP section 15.1).
  */
final class InsightReports(uow: UnitOfWork, tracking: TrackingUseCases, clock: Clock):

  def month(accountId: UUID, month: Option[String]): Either[ApiFailure, InsightMonth] =
    uow.transaction: tx =>
      for
        zone <- tx.accounts.timezoneOf(accountId).map(ZoneId.of).toRight(ApiFailure.notFound())
        current = YearMonth.from(LocalDays.dateOf(clock.now(), zone))
        shown <- InsightReports.monthOf(month, current)
        view <- tracking.stats.month(tx, accountId, shown).left.map(_ => ApiFailure.notFound())
      yield
        val figures = view.figures
        val domains = domainsOf(figures)
        InsightMonth(
          month = shown.toString,
          monthLabel = ApiCopy.monthLabel(shown),
          previousMonth = shown.minusMonths(1).toString,
          nextMonth = Option.when(shown.isBefore(current))(shown.plusMonths(1).toString),
          timeZone = zone.getId,
          reportable = figures.isReportable,
          daysWithRecords = figures.daysWithRecords,
          summary = MonthCopy.daysWithRecords(figures.daysWithRecords),
          domains = domains,
          medications = figures.medication.toList.flatMap(domain => medicationRows(tx, accountId, domain)),
          notice = Option
            .when(domains.isEmpty)(ApiCopy.noConsentedDomain)
            .orElse(Option.when(!figures.isReportable)(MonthCopy.gateTitle))
        )

  def correlations(accountId: UUID): Either[ApiFailure, InsightList] =
    uow.transaction: tx =>
      tracking.stats
        .insights(tx, accountId, ComparisonWindow.Days28)
        .left
        .map(_ => ApiFailure.notFound())
        .map: view =>
          InsightList(
            windowDays = view.list.window.days,
            windowLabel = ApiCopy.rangeLabel(view.list.window),
            crossDomain = view.crossDomain,
            items = view.list.shown.map(item),
            close = view.list.gaps.map(InsightCopy.gap),
            notice = Option.unless(view.crossDomain)(InsightCopy.crossDomainOff)
          )

  private def item(insight: Insight): InsightItem =
    val id = insight match
      case Insight.Pattern(comparison, _) => s"${comparison.rule.id}.${comparison.rule.outcome.toString}"
      case Insight.Timing(_, _)           => "IR-07"
      case Insight.Baseline(statement, _) => s"IR-11.${statement.metric.toString}"
    InsightItem(id, InsightCopy.text(insight), InsightCopy.howWorkedOut(insight))

  private def domainsOf(figures: MonthFigures): List[InsightMonthDomain] =
    def block[P](domain: InsightDomain, name: String, source: Option[MonthDomain[P]])(lines: P => List[String]) =
      source.map: month =>
        if month.meetsGate then
          InsightMonthDomain(domain, ApiCopy.insightTitle(domain), month.recordedDays, true, lines(month.figures))
        else
          InsightMonthDomain(
            domain,
            ApiCopy.insightTitle(domain),
            month.recordedDays,
            meetsGate = false,
            lines = Nil,
            gateText = Some(MonthCopy.gate(name, month))
          )
    List(
      block(InsightDomain.Medication, tr("Doses", "Дози"), figures.medication)(period =>
        StatsCopy.doseFigures(period.total) ++
          MonthCopy
            .previous(figures.month, figures.previousAdherence)
            .filter(_ => period.total.adherence.shown.isDefined)
      ),
      block(InsightDomain.Hydration, tr("Water", "Вода"), figures.hydration)(StatsCopy.waterFigures),
      block(InsightDomain.Mood, tr("Mood", "Настрій"), figures.mood)(StatsCopy.moodFigures),
      block(InsightDomain.Sleep, tr("Sleep", "Сон"), figures.sleep)(StatsCopy.sleepFigures)
    ).flatten

  /** The medications of the month in registry order, each with its own counts; one without a dose this month is left
    * out.
    */
  private def medicationRows(
      tx: Tx,
      accountId: UUID,
      domain: MonthDomain[dosegram.core.domain.insights.MedicationPeriod]
  ): List[InsightMedicationRow] =
    tx.medications
      .listForAccount(accountId)
      .flatMap(medication =>
        domain.figures.perMedication
          .get(medication.id)
          .filterNot(_.buckets.isEmpty)
          .map(InsightReports.row(medication.id, medication.name, medication.status == MedicationStatus.Archived, _))
      )

object InsightReports:

  /** One medication of a period with its own counts, the archived one marked in its line. */
  def row(id: UUID, name: String, archived: Boolean, figures: MedicationFigures): InsightMedicationRow =
    val resolved = figures.buckets.resolved
    InsightMedicationRow(
      id = id.toString,
      name = name,
      archived = archived,
      line = StatsCopy.medication(if archived then s"$name (archived)" else name, figures),
      taken = resolved.taken,
      resolved = resolved.resolved,
      percent = figures.adherence.shown.map(_.percent),
      asNeededLogs = figures.buckets.asNeeded
    )

  /** `YYYY-MM`, not after the current month; absent is the current month. */
  def monthOf(month: Option[String], current: YearMonth): Either[ApiFailure, YearMonth] =
    month match
      case None      => Right(current)
      case Some(raw) =>
        scala.util
          .Try(YearMonth.parse(raw))
          .toOption
          .toRight(ApiFailure.invalid("month", "invalid"))
          .filterOrElse(!_.isAfter(current), ApiFailure.invalid("month", "in_future"))
