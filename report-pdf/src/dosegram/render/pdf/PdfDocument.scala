package dosegram.render.pdf

import java.nio.file.Path
import java.time.Instant

/** A document of text to lay out on A4 pages (VISUALS_AND_ANALYTICS DOC-01). The caller writes every word; the writer
  * only places it. `createdAt` is the one instant in the file, so equal documents give equal bytes.
  */
final case class PdfDocument(
    title: String,
    period: String,
    notice: String,
    footer: String,
    createdAt: Instant,
    sections: List[PdfSection]
)

final case class PdfSection(heading: String, blocks: List[PdfBlock])

enum PdfBlock:
  case Paragraph(text: String)
  case Subheading(text: String)

  /** Cells of text; the columns in `numeric` are right aligned. */
  case Table(header: List[String], rows: List[List[String]], numeric: Set[Int])

/** The pinned font pack (report-renderer/fonts): the writer never reads a system font. */
final case class PdfFonts(regular: Path, medium: Path, semiBold: Path, display: Path)

object PdfFonts:

  /** The files of the pack in `directory`. */
  def in(directory: Path): PdfFonts =
    PdfFonts(
      regular = directory.resolve("IBMPlexSans-Regular.ttf"),
      medium = directory.resolve("IBMPlexSans-Medium.ttf"),
      semiBold = directory.resolve("IBMPlexSans-SemiBold.ttf"),
      display = directory.resolve("Geologica-SemiBold.ttf")
    )
