package dosegram.app

import dosegram.core.application.exports.ExportMessages
import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.insights.RollupService
import dosegram.core.domain.insights.DateRange
import dosegram.core.ports.ExportFile
import dosegram.core.ports.ExportKind
import dosegram.core.ports.ExportRepository
import dosegram.core.ports.ExportStatus
import dosegram.core.ports.NewExportJob
import dosegram.infra.RenderSettings
import dosegram.infra.db.ExportFixture
import dosegram.infra.db.MutableClock
import dosegram.infra.db.PgSuite
import dosegram.infra.db.PgUnitOfWork
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** VISUALS DOC-01 end to end on Testcontainers Postgres 18: the record summary of a fixture account, drawn as a PDF
  * with the pinned fonts, reads back with its title, period, medication, figures and notice on every page, and carries
  * no account identifier. `DOSEGRAM_PDF_PREVIEW=<file>` keeps the PDF for a look. The adapters' asset store gives the
  * file of an export while it has not expired, and nothing after.
  */
class RecordSummaryPdfSuite extends PgSuite:

  private val now = Instant.parse("2026-11-12T18:00:00Z")
  private val zone = ZoneId.of("Europe/Kyiv")
  private val fonts = sys.env("DOSEGRAM_FONTS_DIR")

  test("the record summary of a fixture account as a PDF"):
    val fixture = ExportFixture(dataSource, now, zone).seed()
    val uow = PgUnitOfWork(dataSource, () => now)
    val period = DateRange.endingOn(LocalDate.ofInstant(now, zone), 30)
    val document = uow.transaction(RecordSummaries(RollupService(uow, () => now)).document(_, fixture.account, period, zone, now))
    val renderer = PdfRecordSummaryRenderer.from(Some(RenderSettings("unused", fonts)))
    val pdf = renderer.render(document).fold(reason => fail(reason), identity)
    sys.env.get("DOSEGRAM_PDF_PREVIEW").foreach(file => java.nio.file.Files.write(java.nio.file.Path.of(file), pdf))
    val loaded = Loader.loadPDF(pdf)
    val text =
      try PDFTextStripper().getText(loaded)
      finally loaded.close()
    List("Record summary", "14 Oct 2026 to 12 Nov 2026", fixture.medicationName, "Taken:", "Medication records",
      "Recorded entries only. Not medical advice.", "Page 1 of").foreach(expected =>
      assert(text.contains(expected), s"'$expected' in the PDF")
    )
    assert(!text.contains(fixture.account.toString), "no account identifier (VA-DOC-01c)")
    assertEquals(renderer.render(document).map(_.toList), Right(pdf.toList), "equal documents give equal bytes")

  test("without the font pack there is no record summary renderer, and a summary fails without content"):
    val renderer = PdfRecordSummaryRenderer.from(None)
    assert(renderer.render(dosegram.core.ports.SummaryDocument("t", "p", "n", "f", now, Nil)).isLeft)

  test("the adapters' store gives the file of an export until it expires"):
    val clock = MutableClock(now)
    val uow = PgUnitOfWork(dataSource, clock)
    val account = dosegram.infra.db.Fixtures(dataSource).account()
    val id = UUID.randomUUID()
    uow.transaction: tx =>
      tx.exports.create(NewExportJob(id, account, ExportKind.FullArchive, None, ExportStatus.Queued, now))
      tx.exports.claimQueued()
      tx.exports.complete(id, ExportFile("x.zip", "application/zip", Array[Byte](5)), now,
        now.plus(ExportRepository.FileLifetime))
    val store = ExportAssetStore(uow, clock, InMemoryAssetStore(4))
    store.put("card.x", Array[Byte](1))
    assertEquals(store.load(ExportMessages.assetKey(id)).map(_.toList), Some(List[Byte](5)))
    assertEquals(store.load("card.x").map(_.toList), Some(List[Byte](1)), "an image is still an image")
    clock.advance(Duration.ofMinutes(15))
    assertEquals(store.load(ExportMessages.assetKey(id)), None)
