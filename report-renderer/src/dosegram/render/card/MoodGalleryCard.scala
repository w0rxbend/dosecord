package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.MoodReading
import dosegram.render.scene.Align
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle

import java.time.LocalDateTime

/** One check-in of the gallery: its local time and what the user recorded, nothing else (VA-BL-01). */
final case class GalleryCheckIn(at: LocalDateTime, reading: MoodReading)

/** IMG-26: the latest check-ins, oldest first, at most [[MoodGalleryCard.MaxCheckIns]]. */
final case class MoodGalleryData(checkIns: Vector[GalleryCheckIn]):
  require(checkIns.nonEmpty, "a gallery has a check-in")
  require(checkIns.size <= MoodGalleryCard.MaxCheckIns, s"a gallery holds at most ${MoodGalleryCard.MaxCheckIns}")
  require(checkIns.map(_.at) == checkIns.map(_.at).sorted, "check-ins are oldest first")

/** IMG-26, `card.mood.gallery.v1` (image round 5, supervisor decision of 2026-11-28): the latest check-ins as their
  * blobs in the order they were recorded, seven to a row on a neutral track, each with its day and time under it. The
  * blobs sit on the track at one height whatever their level: the place is the order, never the value, so nothing reads
  * as a trend, a high or a low, and no line joins them. The face is the second encoding of the colour (VA-BL-04); the
  * text equivalent names every level.
  */
object MoodGalleryCard:
  val TemplateId = "card.mood.gallery.v1"
  val MaxCheckIns = 14
  private val PerRow = 7
  private val PeriodBaseline = 180.0
  private val FirstRowCentre = 320.0
  private val RowPitchPx = 288.0
  private val BlobRadiusPx = 44.0
  private val TrackStrokePx = 4.0
  private val DayBaselinePx = 96.0
  private val TimeBaselinePx = 144.0
  private val NotesGapPx = 24.0

  def render(data: MoodGalleryData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val copy = context.copy
    val tokens = frame.tokens
    val pitch = frame.width / PerRow
    val rows = data.checkIns.grouped(PerRow).toVector
    val periodLook = TextLook(TextStyle.BodyMedium, tokens.ink2, Align.Start, TextKind.Value)
    val (first, last) = (data.checkIns.head.at.toLocalDate, data.checkIns.last.at.toLocalDate)
    val period = if first == last then copy.dayDate(first) else copy.dateRange(first, last)
    val drawnRows = rows.zipWithIndex.flatMap { (row, rowIndex) =>
      val centreY = FirstRowCentre + rowIndex * RowPitchPx
      val track = Node.Line(
        Point(frame.left + pitch / 2, centreY),
        Point(frame.left + (row.size - 0.5) * pitch, centreY),
        Stroke.solid(tokens.grid, TrackStrokePx)
      )
      track +: row.zipWithIndex.flatMap((checkIn, index) =>
        drawCheckIn(checkIn, Point(frame.left + (index + 0.5) * pitch, centreY), pitch, frame, context)
      )
    }
    val notesTop = FirstRowCentre + (rows.size - 1) * RowPitchPx + TimeBaselinePx + NotesGapPx
    val noteLook = frame.look(TextStyle.Label, tokens.ink3)
    val notes = Vector(
      frame.text(
        copy.galleryFact(data.checkIns.size),
        Point(frame.left, notesTop + 40),
        TextLook(TextStyle.Label, tokens.ink, Align.Start, TextKind.Value),
        frame.width
      ),
      frame.text(copy.galleryOrderNote, Point(frame.left, notesTop + 96), noteLook, frame.width)
    )
    val nodes = frame.header(copy.galleryTitle, "")
      ++ Vector(frame.text(period, Point(frame.left, PeriodBaseline), periodLook, frame.width))
      ++ drawnRows ++ notes :+ frame.footer
    val text = TextEquivalent.of(copy.galleryText(data), copy.galleryDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-mood-gallery.png", context.privacy == Privacy.Discreet)

  private def drawCheckIn(
      checkIn: GalleryCheckIn,
      centre: Point,
      pitch: Double,
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    val tokens = frame.tokens
    val copy = context.copy
    val date = checkIn.at.toLocalDate
    val axis = TextLook(TextStyle.Label, tokens.ink2, Align.Middle, TextKind.Axis)
    val time = TextLook(TextStyle.Label, tokens.ink3, Align.Middle, TextKind.Axis)
    Vector(
      Node.Mark(
        MarkMeta("mood.gallery.checkin", Set(Channel.Shape, Channel.Colour, Channel.Position)),
        Vector(BlobFigure.of(checkIn.reading.state, BlobFrame(centre.moved(0, -4), BlobRadiusPx), tokens))
      ),
      frame.text(
        s"${copy.weekdayShort(date.getDayOfWeek)} ${date.getDayOfMonth}",
        Point(centre.x, centre.y + DayBaselinePx),
        axis,
        pitch - 8
      ),
      frame.text(copy.time(checkIn.at.toLocalTime), Point(centre.x, centre.y + TimeBaselinePx), time, pitch - 8)
    )
end MoodGalleryCard
