package dosegram.infra.db

import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.insights.RollupService
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.insights.DateRange
import dosegram.core.ports.SummaryBlock

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** VISUALS DOC-01 for a Ukrainian account on Testcontainers Postgres 18: the medication table of the record summary
  * names the dose unit, the schedule and the status in Ukrainian.
  */
class RecordSummaryWordsPgSuite extends PgSuite:

  private val now = Instant.parse("2026-11-12T18:00:00Z")
  private val zone = ZoneId.of("Europe/Kyiv")

  test("a Ukrainian record summary has no English word in its medication table"):
    val fixture = ExportFixture(dataSource, now, zone).seed()
    val uow = PgUnitOfWork(dataSource, () => now)
    val period = DateRange.endingOn(LocalDate.ofInstant(now, zone), 30)
    val summaries = RecordSummaries(RollupService(uow, () => now))
    val document = Lang.within(Lang.Uk)(uow.transaction(summaries.document(_, fixture.account, period, zone, now)))
    val rows = document.sections.flatMap(_.blocks).collect { case SummaryBlock.Table(_, rows, _) => rows }.flatten
    val medication = rows.find(_.headOption.contains(fixture.medicationName)).getOrElse(fail(s"no medication row: $rows"))
    assertEquals(medication.slice(1, 4), List("10 мг", "щодня 09:00", "Активно"))
end RecordSummaryWordsPgSuite
