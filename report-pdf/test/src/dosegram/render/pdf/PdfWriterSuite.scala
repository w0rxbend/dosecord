package dosegram.render.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** VISUALS DOC-01 and VA-DOC-01b/c on the PDF writer: the text golden of a fixture document, equal bytes for equal
  * documents, neutral metadata, the notice and the page count on every page, and a name in a script the fonts cannot
  * draw. `DOSEGRAM_UPDATE_GOLDENS=<repository root>` rewrites the golden; `DOSEGRAM_PDF_PREVIEW=<file>` keeps the
  * fixture PDF for a look.
  */
class PdfWriterSuite extends munit.FunSuite:

  private val fonts = PdfFonts.in(Path.of(sys.env("DOSEGRAM_FONTS_DIR")))

  private def textOf(bytes: Array[Byte]): List[String] =
    val pdf = Loader.loadPDF(bytes)
    try
      (1 to pdf.getNumberOfPages).toList.map: page =>
        val stripper = PDFTextStripper()
        stripper.setStartPage(page)
        stripper.setEndPage(page)
        stripper.getText(pdf)
    finally pdf.close()

  test("the fixture document reads back as its golden text, page by page"):
    val bytes = PdfWriter.write(PdfFixture.document, fonts)
    sys.env.get("DOSEGRAM_PDF_PREVIEW").foreach(file => Files.write(Path.of(file), bytes))
    val actual = textOf(bytes).zipWithIndex.map((text, index) => s"--- page ${index + 1}\n$text").mkString
    sys.env
      .get("DOSEGRAM_UPDATE_GOLDENS")
      .foreach(root => Files.writeString(Path.of(root).resolve(PdfFixture.GoldenFile), actual, UTF_8))
    val expected = Option(getClass.getResourceAsStream(PdfFixture.GoldenResource))
      .map(stream => String(stream.readAllBytes(), UTF_8))
      .getOrElse(fail(s"${PdfFixture.GoldenResource} is not on the test classpath"))
    assertEquals(actual, expected)

  test("equal documents give equal bytes (VA-DOC-01b)"):
    assertEquals(
      PdfWriter.write(PdfFixture.document, fonts).toList,
      PdfWriter.write(PdfFixture.document, fonts).toList
    )

  test("the metadata carries fixed neutral strings and the document's instant (VA-DOC-01c)"):
    val pdf = Loader.loadPDF(PdfWriter.write(PdfFixture.document, fonts))
    try
      val info = pdf.getDocumentInformation
      assertEquals(info.getTitle, "Record summary")
      assertEquals(info.getAuthor, "Dosegram")
      assertEquals(info.getProducer, "Dosegram")
      assertEquals(info.getCreationDate.toInstant, PdfFixture.createdAt)
      assertEquals(pdf.getDocumentCatalog.getLanguage, "en")
    finally pdf.close()

  test("every page carries the notice and its page count; a long document breaks its tables across pages"):
    val pages = textOf(PdfWriter.write(PdfFixture.document, fonts))
    assert(pages.size >= 2, s"${pages.size} pages")
    pages.zipWithIndex.foreach: (text, index) =>
      assert(text.contains("Recorded entries only. Not medical advice."), s"page ${index + 1}")
      assert(text.contains(s"Page ${index + 1} of ${pages.size}"), s"page ${index + 1}")

  test("a character the fonts cannot draw is replaced, and the file is still made"):
    val document = PdfFixture.document.copy(
      sections = List(PdfSection("Medications", List(PdfBlock.Paragraph("Vitamin 💊 D"))))
    )
    val text = textOf(PdfWriter.write(document, fonts)).mkString
    assert(text.contains("Vitamin ? D"), text)

/** A record summary of three months with two medications, long enough for a second page. */
object PdfFixture:
  val GoldenFile = "report-pdf/test/resources/goldens/record-summary.txt"
  val GoldenResource = "/goldens/record-summary.txt"
  val createdAt: Instant = Instant.parse("2026-11-10T09:30:00Z")

  private val months = List("Aug 2026", "Sep 2026", "Oct 2026", "Nov 2026")

  val document: PdfDocument = PdfDocument(
    title = "Record summary",
    period = "13 Aug 2026 to 10 Nov 2026",
    notice = "Recorded entries only. Not medical advice.",
    footer = "Record summary",
    createdAt = createdAt,
    sections = List(
      PdfSection(
        "Scope",
        List(
          PdfBlock.Paragraph(
            "This document lists entries recorded by the user in Dosegram. It is not a medical record and contains " +
              "no medical advice or interpretation."
          ),
          PdfBlock.Paragraph("Made on 10 Nov 2026 at 11:30, time zone Europe/Kyiv."),
          PdfBlock.Paragraph("Sections: Medications, Medication records, Sleep, Mood, Water, Definitions.")
        )
      ),
      PdfSection(
        "Medications",
        List(
          PdfBlock.Table(
            List("Medication", "Dose", "Schedule", "Status", "Instructions"),
            List(
              List("Metformin", "500 mg", "every day 08:00, 20:00", "Active", "With food"),
              List("Vitamin D", "1000 IU", "on Mon, Wed, Fri 09:00", "Paused", ""),
              List("Ibuprofen", "200 mg", "as needed", "Active", "No more than the label says, as written by me")
            ),
            Set.empty
          )
        )
      ),
      PdfSection(
        "Medication records",
        List("Metformin 500 mg", "Vitamin D 1000 IU").flatMap: name =>
          List(
            PdfBlock.Subheading(name),
            PdfBlock.Table(
              List("Month", "Scheduled", "On time", "Late", "Skipped", "Not confirmed", "No record"),
              months.map(month => List(month, "38", "33", "3", "1", "1", "0")),
              Set(1, 2, 3, 4, 5, 6)
            ),
            PdfBlock.Paragraph("Taken: 144 of 152 resolved doses (94%).")
          )
      ),
      PdfSection(
        "Sleep",
        List(
          PdfBlock.Table(
            List("Month", "Nights", "Mean length"),
            months.map(month => List(month, "27", "7 h 12 min")),
            Set(1, 2)
          )
        )
      ),
      PdfSection(
        "Mood",
        List(
          PdfBlock.Table(List("Month", "Check-ins", "Days with a check-in"), months.map(List(_, "41", "26")), Set(1, 2)),
          PdfBlock.Table(
            List("Recorded step", "Check-ins"),
            List("Very pleasant", "Pleasant", "Neutral", "Unpleasant", "Very unpleasant").map(List(_, "12")),
            Set(1)
          )
        )
      ),
      PdfSection(
        "Water",
        List(
          PdfBlock.Table(
            List("Month", "Days recorded", "Total", "Mean per recorded day", "Goal days"),
            months.map(List(_, "25", "51 250 ml", "2 050 ml", "18 of 25")),
            Set(1, 2, 3, 4)
          )
        )
      ),
      PdfSection(
        "Definitions",
        List.fill(6)(
          PdfBlock.Paragraph(
            "Scheduled: doses a schedule planned in the period. On time and late: doses recorded as taken, inside or " +
              "after the window of the schedule. Not confirmed: doses with no answer before the window closed."
          )
        )
      )
    )
  )
end PdfFixture
