package dosegram.render.pdf

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import scala.collection.mutable.ListBuffer
import scala.util.Try

/** Lays a [[PdfDocument]] out on A4 portrait pages with Apache PDFBox (research brief 04 section 11, the JVM fallback):
  * a title block, a running header, sections of paragraphs and tables that break across pages with their header row
  * repeated, and on every page the notice and "Page n of m". Light theme only, in grey levels, so it prints the same in
  * greyscale. Text is real text in the embedded subset of the pinned fonts; no system font is read. The metadata fields
  * carry fixed neutral strings and the one instant of the document, and the file identifier is a hash of the document,
  * so equal documents give equal bytes (VISUALS VA-DOC-01b, VA-DOC-01c).
  */
object PdfWriter:

  def write(document: PdfDocument, fonts: PdfFonts): Array[Byte] =
    val pdf = PDDocument()
    try
      val faces = Faces(
        regular = PDType0Font.load(pdf, fonts.regular.toFile),
        medium = PDType0Font.load(pdf, fonts.medium.toFile),
        semiBold = PDType0Font.load(pdf, fonts.semiBold.toFile),
        display = PDType0Font.load(pdf, fonts.display.toFile)
      )
      val pages = Layout(faces).pages(document)
      pages.zipWithIndex.foreach((ops, index) => draw(pdf, faces, ops ++ furniture(faces, document, index, pages.size)))
      describe(pdf, document)
      val out = ByteArrayOutputStream()
      pdf.save(out)
      out.toByteArray
    finally pdf.close()

  // ---------- geometry (points; y grows downwards from the top edge, converted when drawn) ----------

  private[pdf] val PageWidth: Float = PDRectangle.A4.getWidth
  private[pdf] val PageHeight: Float = PDRectangle.A4.getHeight
  private[pdf] val Margin: Float = 56f
  private[pdf] val ContentWidth: Float = PageWidth - 2 * Margin
  private[pdf] val BodyTop: Float = 84f
  private[pdf] val BodyBottom: Float = PageHeight - 72f

  /** Grey levels: 0 is black. Every status is told apart by its words, never by a shade. */
  private[pdf] object Ink:
    val Text = 0.10f
    val Muted = 0.38f
    val Rule = 0.78f
    val Band = 0.93f

  private[pdf] final case class Faces(
      regular: PDType0Font,
      medium: PDType0Font,
      semiBold: PDType0Font,
      display: PDType0Font
  )

  private[pdf] enum Face:
    case Regular, Medium, SemiBold, Display

  private[pdf] enum Op:
    case Text(x: Float, baseline: Float, face: Face, size: Float, text: String, grey: Float)
    case Line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, grey: Float)
    case Box(x: Float, top: Float, width: Float, height: Float, grey: Float)

  private[pdf] def font(faces: Faces, face: Face): PDType0Font = face match
    case Face.Regular  => faces.regular
    case Face.Medium   => faces.medium
    case Face.SemiBold => faces.semiBold
    case Face.Display  => faces.display

  /** Width of `text` in points. */
  private[pdf] def widthOf(faces: Faces, face: Face, size: Float, text: String): Float =
    font(faces, face).getStringWidth(text) / 1000f * size

  /** `text` with every character the font cannot draw replaced, so a name in another script never fails the file. */
  private[pdf] def drawable(faces: Faces, face: Face, text: String): String =
    val target = font(faces, face)
    val out = StringBuilder()
    text
      .codePoints()
      .forEach: point =>
        val glyph = String(Character.toChars(point))
        if point == '\t'.toInt || point == '\n'.toInt then out.append(' ')
        else if Try(target.encode(glyph)).isSuccess then out.append(glyph)
        else out.append('?')
    out.toString

  /** The running header (pages after the first) and the footer of every page. */
  private def furniture(faces: Faces, document: PdfDocument, index: Int, count: Int): List[Op] =
    val footerBaseline = PageHeight - 40f
    val pageLabel = s"Page ${index + 1} of $count"
    val footer = List(
      Op.Line(Margin, footerBaseline - 14f, PageWidth - Margin, footerBaseline - 14f, 0.5f, Ink.Rule),
      Op.Text(Margin, footerBaseline, Face.Medium, 8.5f, drawable(faces, Face.Medium, document.notice), Ink.Text),
      Op.Text(
        PageWidth - Margin - widthOf(faces, Face.Regular, 8.5f, pageLabel),
        footerBaseline,
        Face.Regular,
        8.5f,
        pageLabel,
        Ink.Muted
      )
    )
    val header =
      if index == 0 then Nil
      else
        val period = drawable(faces, Face.Regular, document.period)
        List(
          Op.Text(Margin, 48f, Face.SemiBold, 9f, drawable(faces, Face.SemiBold, document.footer), Ink.Text),
          Op.Text(
            PageWidth - Margin - widthOf(faces, Face.Regular, 9f, period),
            48f,
            Face.Regular,
            9f,
            period,
            Ink.Muted
          ),
          Op.Line(Margin, 58f, PageWidth - Margin, 58f, 0.5f, Ink.Rule)
        )
    header ++ footer

  private def draw(pdf: PDDocument, faces: Faces, ops: List[Op]): Unit =
    val page = PDPage(PDRectangle.A4)
    pdf.addPage(page)
    val stream = PDPageContentStream(pdf, page)
    try
      ops.foreach:
        case Op.Box(x, top, width, height, grey) =>
          stream.setNonStrokingColor(grey)
          stream.addRect(x, PageHeight - top - height, width, height)
          stream.fill()
        case Op.Line(x1, y1, x2, y2, width, grey) =>
          stream.setStrokingColor(grey)
          stream.setLineWidth(width)
          stream.moveTo(x1, PageHeight - y1)
          stream.lineTo(x2, PageHeight - y2)
          stream.stroke()
        case Op.Text(x, baseline, face, size, text, grey) =>
          stream.setNonStrokingColor(grey)
          stream.beginText()
          stream.setFont(font(faces, face), size)
          stream.newLineAtOffset(x, PageHeight - baseline)
          stream.showText(text)
          stream.endText()
    finally stream.close()

  /** Fixed neutral metadata (VA-DOC-01c), the document's own instant, and an identifier derived from its content. */
  private def describe(pdf: PDDocument, document: PdfDocument): Unit =
    val info = PDDocumentInformation()
    info.setTitle(document.title)
    info.setAuthor("Dosegram")
    info.setCreator("Dosegram")
    info.setProducer("Dosegram")
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
    calendar.setTimeInMillis(document.createdAt.toEpochMilli)
    calendar.set(Calendar.MILLISECOND, 0)
    info.setCreationDate(calendar)
    info.setModificationDate(calendar)
    pdf.setDocumentInformation(info)
    pdf.getDocumentCatalog.setLanguage("en")
    val digest = MessageDigest.getInstance("SHA-256").digest(document.toString.getBytes(UTF_8)).take(16)
    val id = COSArray()
    id.add(COSString(digest))
    id.add(COSString(digest))
    pdf.getDocument.getTrailer.setItem(COSName.ID, id)

  /** Turns the document into the operations of each page. */
  private final class Layout(faces: Faces):
    private val done = ListBuffer.empty[List[Op]]
    private val current = ListBuffer.empty[Op]
    private var y = BodyTop

    def pages(document: PdfDocument): List[List[Op]] =
      titleBlock(document)
      document.sections.foreach(section)
      done += current.toList
      done.toList

    private def newPage(): Unit =
      done += current.toList
      current.clear()
      y = BodyTop

    private def ensure(height: Float): Unit = if y + height > BodyBottom then newPage()

    private def text(x: Float, face: Face, size: Float, line: String, grey: Float): Unit =
      current += Op.Text(x, y, face, size, line, grey)

    /** One line whose top is at the current position: the baseline sits one font size lower. */
    private def lineBelow(face: Face, size: Float, leading: Float, line: String): Unit =
      ensure(leading)
      current += Op.Text(Margin, y + size, face, size, line, Ink.Text)
      y += leading

    private def titleBlock(document: PdfDocument): Unit =
      y = 92f
      text(Margin, Face.Display, 28f, drawable(faces, Face.Display, document.title), Ink.Text)
      y += 24f
      text(Margin, Face.Regular, 12f, drawable(faces, Face.Regular, document.period), Ink.Muted)
      y += 14f
      current += Op.Line(Margin, y, PageWidth - Margin, y, 1.2f, Ink.Text)
      y += 22f

    private def section(section: PdfSection): Unit =
      // A heading is kept with the first lines that follow it.
      ensure(64f)
      y += 10f
      text(Margin, Face.SemiBold, 14f, drawable(faces, Face.SemiBold, section.heading), Ink.Text)
      y += 8f
      current += Op.Line(Margin, y, Margin + 28f, y, 1.6f, Ink.Text)
      y += 10f
      section.blocks.foreach(block)
      y += 6f

    private def block(block: PdfBlock): Unit = block match
      case PdfBlock.Paragraph(content)  => paragraph(content)
      case PdfBlock.Subheading(content) =>
        ensure(56f)
        y += 6f
        wrap(Face.SemiBold, 10.5f, content, ContentWidth).foreach(lineBelow(Face.SemiBold, 10.5f, 15f, _))
        y += 3f
      case PdfBlock.Table(header, rows, numeric) => table(header, rows, numeric)

    private def paragraph(content: String): Unit =
      wrap(Face.Regular, 10f, content, ContentWidth).foreach(lineBelow(Face.Regular, 10f, 14.5f, _))
      y += 5f

    private val CellSize = 9f
    private val CellLeading = 12f
    private val PadX = 6f
    private val PadY = 5f
    private val MinTableShare = 0.62f

    private def table(header: List[String], rows: List[List[String]], numeric: Set[Int]): Unit =
      val widths = columnWidths(header, rows, numeric)
      val tableWidth = widths.sum
      val headerLines = header.zip(widths).map((cell, width) => wrap(Face.Medium, CellSize, cell, width - 2 * PadX))
      val headerHeight = headerLines.map(_.size).max * CellLeading + 2 * PadY
      def drawHeader(): Unit =
        current += Op.Box(Margin, y, tableWidth, headerHeight, Ink.Band)
        cells(headerLines, widths, numeric, Face.Medium, Ink.Text)
        y += headerHeight
      val rowLines = rows.map(_.zip(widths).map((cell, width) => wrap(Face.Regular, CellSize, cell, width - 2 * PadX)))
      val rowHeights = rowLines.map(lines => lines.map(_.size).maxOption.getOrElse(1) * CellLeading + 2 * PadY)
      // A short table moves whole to the next page rather than leave a row or two behind; a long one keeps at least
      // two rows with its header.
      val whole = headerHeight + rowHeights.sum
      if whole <= (BodyBottom - BodyTop) / 2 then ensure(whole)
      else ensure(headerHeight + rowHeights.take(2).sum)
      drawHeader()
      rowLines
        .zip(rowHeights)
        .foreach: (lines, height) =>
          if y + height > BodyBottom then
            newPage()
            drawHeader()
          cells(lines, widths, numeric, Face.Regular, Ink.Text)
          y += height
          current += Op.Line(Margin, y, Margin + tableWidth, y, 0.5f, Ink.Rule)
      y += 12f

    /** One row of cells at the current position: the first baseline sits one cap height below the padding. */
    private def cells(
        lines: List[List[String]],
        widths: List[Float],
        numeric: Set[Int],
        face: Face,
        grey: Float
    ): Unit =
      var x = Margin
      lines
        .zip(widths)
        .zipWithIndex
        .foreach:
          case ((cellLines, width), index) =>
            cellLines.zipWithIndex.foreach: (line, row) =>
              val baseline = y + PadY + CellSize + row * CellLeading - 1f
              val left =
                if numeric(index) then x + width - PadX - widthOf(faces, face, CellSize, line) else x + PadX
              current += Op.Text(left, baseline, face, CellSize, line, grey)
            x += width

    /** Natural widths, widened by up to a half and to at least [[MinTableShare]] of the content width, so a narrow
      * table stays compact; when they do not fit, numeric columns keep theirs and the text columns share the rest by
      * their natural widths and wrap.
      */
    private def columnWidths(header: List[String], rows: List[List[String]], numeric: Set[Int]): List[Float] =
      val natural = header.indices.toList.map: index =>
        val cells = header(index) :: rows.flatMap(_.lift(index))
        cells.map(cell => widthOf(faces, Face.Medium, CellSize, drawable(faces, Face.Medium, cell))).max + 2 * PadX
      val total = natural.sum
      if total <= ContentWidth then
        val target = (total * 1.5f).max(ContentWidth * MinTableShare).min(ContentWidth)
        natural.map(_ * target / total)
      else
        val fixed = natural.zipWithIndex.collect { case (width, index) if numeric(index) => width }.sum
        val textTotal = total - fixed
        val room = (ContentWidth - fixed).max(ContentWidth * 0.4f)
        natural.zipWithIndex.map: (width, index) =>
          if numeric(index) then width * (ContentWidth - room).max(0f) / fixed.max(1f) else width * room / textTotal

    /** Greedy word wrap; a word longer than the line is cut. */
    private def wrap(face: Face, size: Float, content: String, width: Float): List[String] =
      val safe = drawable(faces, face, content)
      if safe.isEmpty then List("")
      else
        val lines = ListBuffer.empty[String]
        var line = ""
        safe
          .split(' ')
          .filter(_.nonEmpty)
          .foreach: word =>
            val candidate = if line.isEmpty then word else s"$line $word"
            if widthOf(faces, face, size, candidate) <= width then line = candidate
            else
              if line.nonEmpty then lines += line
              line = cut(face, size, word, width, lines)
        if line.nonEmpty then lines += line
        if lines.isEmpty then List("") else lines.toList

    private def cut(face: Face, size: Float, word: String, width: Float, lines: ListBuffer[String]): String =
      var rest = word
      while widthOf(faces, face, size, rest) > width && rest.length > 1 do
        val fits = (1 to rest.length).takeWhile(n => widthOf(faces, face, size, rest.take(n)) <= width).lastOption
        val n = fits.getOrElse(1)
        lines += rest.take(n)
        rest = rest.drop(n)
      rest
  end Layout
end PdfWriter
