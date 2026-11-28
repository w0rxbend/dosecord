package dosegram.render.card

import dosegram.render.chart.Icons
import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.TextStyle
import dosegram.render.tokens.Tokens

import java.time.LocalDate

/** One area: its records in each hour of the local day (absent below the 14-day gate), the caller's summary under the
  * band and recorded days, and whether the band counts nights asleep rather than records.
  */
final case class RhythmRow(domain: Domain, hours: Option[Vector[Int]], summary: String, days: String, asleep: Boolean):
  require(hours.forall(_.size == RhythmCard.Hours), "a band has 24 hours")

/** The values of IMG-28: the window, the rows in domain order and the text equivalent, written by the caller from the
  * same values.
  */
final case class RhythmData(from: LocalDate, to: LocalDate, rows: Vector[RhythmRow], text: String):
  require(rows.nonEmpty, "a rhythm card has a row")

/** IMG-28, `card.rhythm.v1` (supervisor decision, 2026-11-28): the rhythm of the day, one 24-hour band per shown area
  * on one page, from 00:00 on the left to 24:00 on the right. Each hour is a bar from the band's floor whose height is
  * the hour's records on the row's own scale, so the hours compare without the colour (VA-PR-06); an hour without a
  * record has no bar. An area below its gate has a dotted track and its recorded days, never an empty band (VA-PR-03).
  * Nothing marks an hour as a target or ranks an area.
  */
object RhythmCard:
  val TemplateId = "card.rhythm.v1"
  val Hours = 24
  private val BandLeftPx = 140.0
  private val RowsTop = 184.0
  private val RowPitchPx = 168.0
  private val NameBaselinePx = 36.0
  private val TrackTopPx = 52.0
  private val TrackPx = 60.0
  private val SummaryBaselinePx = 148.0
  private val AxisBaselinePx = 44.0
  private val AxisPx = 64.0
  private val TrackRadius = 8.0
  private val BarGapPx = 4.0
  private val MinimumBarPx = 8.0
  private val LegendGapPx = 8.0
  private val AxisHours = Vector(0, 6, 12, 18, 24)

  def render(data: RhythmData, context: CardContext): CardOutput =
    val copy = context.copy.rhythm
    val probe = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val legendItems = Vector(
      Option.when(data.rows.exists(_.hours.isEmpty))(
        LegendItem.of(box => emptyTrack("legend.rhythm.none", box.inset(8), context.tokens), copy.noPattern)
      ),
      Some(LegendItem.note(copy.scaleNote)),
      Option.when(data.rows.exists(row => row.asleep && row.hours.isDefined))(LegendItem.note(copy.sleepNote))
    ).flatten
    val legend = Legend.flow(legendItems, probe.width, context.fitter)
    // Rows close up when a legend of more lines (a longer language) would otherwise reach the footer.
    val room = probe.bodyBottom - RowsTop - AxisPx - LegendGapPx - legend.height
    val pitch = StrictMath.min(RowPitchPx, StrictMath.floor(room / data.rows.size))
    val rowsBottom = RowsTop + data.rows.size * pitch
    val axisBottom = rowsBottom + AxisPx
    val frame = CardFrame(context, CardFrame.collapsing(axisBottom + LegendGapPx + legend.height), probe.background)
    val nodes = frame.header(copy.title, copy.dates(data.from, data.to))
      ++ data.rows.zipWithIndex.flatMap((row, index) => drawRow(row, RowsTop + index * pitch, frame, context))
      ++ axis(rowsBottom, frame)
      ++ legend.nodes(Point(frame.left, axisBottom + LegendGapPx), frame, context.fitter)
      :+ frame.footer
    val text = TextEquivalent.of(data.text, copy.discreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-rhythm.png", context.privacy == Privacy.Discreet)

  private def drawRow(row: RhythmRow, top: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val tokens = frame.tokens
    val width = frame.right - BandLeftPx
    // The days keep their whole width at the line's end; the name has the rest.
    val daysWidth = StrictMath.min(width / 2, context.fitter.width(row.days, TextStyle.Label))
    val name = frame.text(
      context.copy.domainName(row.domain),
      Point(BandLeftPx, top + NameBaselinePx),
      frame.look(TextStyle.Label, tokens.ink2),
      width - daysWidth - BarGapPx * 4
    )
    val days = frame.text(
      row.days,
      Point(frame.right, top + NameBaselinePx),
      TextLook(TextStyle.Label, tokens.ink2, Align.End, TextKind.Value),
      daysWidth
    )
    val summary = frame.text(
      row.summary,
      Point(BandLeftPx, top + SummaryBaselinePx),
      TextLook(TextStyle.Label, tokens.ink, Align.Start, TextKind.Value),
      width
    )
    val track = Box(BandLeftPx, top + TrackTopPx, width, TrackPx)
    val role = row.domain.toString.toLowerCase(java.util.Locale.ROOT)
    val shown = row.hours match
      case Some(hours) => band(s"rhythm.$role", row.domain, hours, track, tokens)
      case None        => emptyTrack(s"rhythm.$role.none", track, tokens)
    Vector(Icons.of(row.domain, Point(frame.left, top), tokens.hue(row.domain)), name, days, shown, summary)

  /** The 24 hours on the band's ground, each a bar from the floor on the row's own scale. */
  private def band(role: String, domain: Domain, hours: Vector[Int], track: Box, tokens: Tokens): Node =
    val colour = tokens.hue(domain)
    val scale = StrictMath.max(1, hours.max).toDouble
    val slot = track.width / Hours
    val bars = hours.zipWithIndex.collect {
      case (count, hour) if count > 0 =>
        val height = StrictMath.max(MinimumBarPx, track.height * count / scale)
        val box = Box(track.x + hour * slot + BarGapPx / 2, track.y + track.height - height, slot - BarGapPx, height)
        Node.filledRect(box, BarGapPx, colour)
    }
    val ground = Node.filledRect(track, TrackRadius, tokens.grid)
    Node.Mark(MarkMeta(role, Set(Channel.Colour, Channel.Position)), ground +: bars)

  /** The clock hours under the last band: 00:00 at its left edge to 24:00 at its right. */
  private def axis(top: Double, frame: CardFrame): Vector[Node] =
    val width = frame.right - BandLeftPx
    AxisHours.map: hour =>
      val x = BandLeftPx + width * hour / Hours
      val align = if hour == 0 then Align.Start else if hour == Hours then Align.End else Align.Middle
      frame.text(
        f"$hour%02d:00",
        Point(x, top + AxisBaselinePx),
        TextLook(TextStyle.Label, frame.tokens.ink2, align, TextKind.Label),
        width / 4
      )

  private def emptyTrack(role: String, box: Box, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta(role, Set(Channel.Pattern)),
      Vector(Node.outlinedRect(box.inset(2), 4, Stroke.dotted(tokens.ink3)))
    )
end RhythmCard
