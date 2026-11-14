package dosegram.core.ports

import java.time.Instant

/** The record summary as a document of text (VISUALS_AND_ANALYTICS DOC-01, PRODUCT_SPEC R-IMX-6): every word and every
  * number is the core's, so the same view feeds any renderer and the forbidden-phrase gate sees the copy. The renderer
  * only lays it out. `createdAt` is the one instant the file carries, so two runs give the same bytes.
  */
final case class SummaryDocument(
    title: String,
    period: String,
    notice: String,
    footer: String,
    createdAt: Instant,
    sections: List[SummarySection]
)

final case class SummarySection(heading: String, blocks: List[SummaryBlock])

enum SummaryBlock:
  case Paragraph(text: String)

  /** A sub-heading inside a section, such as the name of one medication. */
  case Subheading(text: String)

  /** A table of text cells. `numeric` marks the columns whose cells are right aligned. */
  case Table(header: List[String], rows: List[List[String]], numeric: Set[Int])

/** Lays a [[SummaryDocument]] out as a file (a PDF). A failure is a reason without content, never a record. */
trait RecordSummaryRenderer:
  def render(document: SummaryDocument): Either[String, Array[Byte]]

object RecordSummaryRenderer:

  /** A composition without a document renderer: every record summary fails with this reason. */
  val unavailable: RecordSummaryRenderer = _ => Left("no document renderer")

  val Mime = "application/pdf"
