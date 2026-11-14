package dosegram.app

import dosegram.core.application.exports.ExportMessages
import dosegram.core.ports.Clock
import dosegram.core.ports.RecordSummaryRenderer
import dosegram.core.ports.SummaryBlock
import dosegram.core.ports.SummaryDocument
import dosegram.core.ports.UnitOfWork
import dosegram.infra.RenderSettings
import dosegram.render.pdf.PdfBlock
import dosegram.render.pdf.PdfDocument
import dosegram.render.pdf.PdfFonts
import dosegram.render.pdf.PdfSection
import dosegram.render.pdf.PdfWriter

import java.nio.file.Files
import java.nio.file.Path
import scala.util.control.NonFatal

/** The record summary drawn by the PDF writer (VISUALS DOC-01): the core's document becomes the writer's, with the
  * pinned font pack of `RENDER_FONTS_DIR`. A failure is its class name only, never content.
  */
final class PdfRecordSummaryRenderer(fonts: PdfFonts) extends RecordSummaryRenderer:

  override def render(document: SummaryDocument): Either[String, Array[Byte]] =
    try Right(PdfWriter.write(PdfRecordSummaryRenderer.pdfOf(document), fonts))
    catch case NonFatal(cause) => Left(cause.getClass.getSimpleName)

object PdfRecordSummaryRenderer:

  /** The renderer over the font pack the settings name, or none: a record summary then fails and the chat says so. */
  def from(render: Option[RenderSettings]): RecordSummaryRenderer =
    render
      .map(settings => Path.of(settings.fontsDir))
      .filter(Files.isDirectory(_))
      .fold(RecordSummaryRenderer.unavailable)(directory => PdfRecordSummaryRenderer(PdfFonts.in(directory)))

  def pdfOf(document: SummaryDocument): PdfDocument =
    PdfDocument(
      title = document.title,
      period = document.period,
      notice = document.notice,
      footer = document.footer,
      createdAt = document.createdAt,
      sections = document.sections.map: section =>
        PdfSection(
          section.heading,
          section.blocks.map:
            case SummaryBlock.Paragraph(text)              => PdfBlock.Paragraph(text)
            case SummaryBlock.Subheading(text)             => PdfBlock.Subheading(text)
            case SummaryBlock.Table(header, rows, numeric) => PdfBlock.Table(header, rows, numeric)
        )
    )

/** The asset store of the adapters, with the files of the exports in it (ROADMAP G10.4): the key of an export resolves
  * to its file while the export has not expired; every other key is an image of `images`.
  */
final class ExportAssetStore(uow: UnitOfWork, clock: Clock, images: AssetStore) extends AssetStore:
  override def put(assetKey: String, png: Array[Byte]): Unit = images.put(assetKey, png)
  override def holds(assetKey: String): Boolean = images.holds(assetKey)

  override def load(assetKey: String): Option[Array[Byte]] =
    ExportMessages.exportIdOf(assetKey) match
      case Some(id) => uow.transaction(_.exports.fileForDelivery(id, clock.now())).map(_.bytes)
      case None     => images.load(assetKey)
