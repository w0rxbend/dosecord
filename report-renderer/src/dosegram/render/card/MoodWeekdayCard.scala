package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.MoodState
import dosegram.render.scene.Align
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.TextStyle

/** IMG-22, `card.mood.weekday.v1` (VISUALS section 7.6, supervisor decision of 2026-11-14; card of 2026-11-15): for
  * each weekday the mean of the daily means on its check-in days, drawn as that mean's blob on one 1 to 5 track per
  * row, with the value and the number of days beside it. A weekday under three check-in days has no blob and says so.
  * Rows keep the week's order; nothing ranks, sorts or compares the weekdays.
  */
object MoodWeekdayCard:
  val TemplateId = "card.mood.weekday.v1"
  private val ScaleSteps = 5
  private val TrackLeft = 352.0
  private val TrackRight = 856.0
  private val PeriodBaseline = 180.0
  private val AxisBaseline = 248.0
  private val RowsTop = 268.0
  private val RowPitchPx = 88.0
  private val LabelBaselinePx = 58.0
  private val DaysLeftPx = 176.0
  private val TrackYPx = 44.0
  private val BlobRadiusPx = 30.0
  private val TrackStrokePx = 6.0
  private val NoteBaseline = 932.0

  def render(data: MoodWeekdayData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val copy = context.copy
    // The period stands under the title: a long range in Ukrainian would cut the title on one line.
    val periodLook = TextLook(TextStyle.BodyMedium, frame.tokens.ink2, Align.Start, TextKind.Value)
    val nodes = frame.header(copy.moodWeekdayTitle, "")
      ++ Vector(
        frame.text(copy.dateRange(data.from, data.to), Point(frame.left, PeriodBaseline), periodLook, frame.width)
      )
      ++ axis(frame)
      ++ data.rows.zipWithIndex.flatMap((row, index) => drawRow(row, RowsTop + index * RowPitchPx, frame, context))
      ++ Vector(
        frame.text(
          copy.insightScaleNote(Domain.Mood),
          Point(frame.left, NoteBaseline),
          frame.look(TextStyle.Label, frame.tokens.ink3),
          frame.width
        ),
        frame.footer
      )
    val text = TextEquivalent.of(copy.moodWeekdayText(data), copy.moodWeekdayDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-mood-weekday.png", context.privacy == Privacy.Discreet)

  /** Where a mean on the 1 to 5 scale sits on the track: the scale ends one blob inside the track's ends, so a blob at
    * 1 or 5 stays on its track and clear of the words beside it.
    */
  def trackX(mean: Double): Double =
    val (first, last) = (TrackLeft + BlobRadiusPx, TrackRight - BlobRadiusPx)
    first + (mean - 1) / (ScaleSteps - 1) * (last - first)

  private def axis(frame: CardFrame): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    (1 to ScaleSteps).toVector.map(step => frame.text(step.toString, Point(trackX(step), AxisBaseline), look, 64))

  private def drawRow(row: MoodWeekdayRow, top: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val tokens = frame.tokens
    val copy = context.copy
    val labels = Vector(
      frame.text(
        copy.weekdayName(row.day),
        Point(frame.left, top + LabelBaselinePx),
        frame.look(TextStyle.BodyMedium, tokens.ink),
        DaysLeftPx - frame.left - 8
      ),
      frame.text(
        copy.moodWeekdayDays(row.checkInDays),
        Point(DaysLeftPx, top + LabelBaselinePx),
        TextLook(TextStyle.Label, tokens.ink3, Align.Start, TextKind.Value),
        TrackLeft - DaysLeftPx - 24
      )
    )
    row.mean match
      case Some(mean) =>
        val y = top + TrackYPx
        val track = Node.Line(Point(TrackLeft, y), Point(TrackRight, y), Stroke.solid(tokens.grid, TrackStrokePx))
        val state = MoodState.quantised((mean - 3) / 2, 0.5)
        val blob = BlobFigure.of(state, BlobFrame(Point(trackX(mean), y - 4), BlobRadiusPx), tokens)
        val valueLook = TextLook(TextStyle.BodyStrong, tokens.ink, Align.End, TextKind.Value)
        labels ++ Vector(
          Node.Mark(MarkMeta("mood.weekday", Set(Channel.Position, Channel.Shape, Channel.Label)), Vector(track, blob)),
          frame.text(
            copy.insightValue(mean),
            Point(frame.right, top + LabelBaselinePx),
            valueLook,
            frame.right - TrackRight - 24
          )
        )
      case None =>
        val look = TextLook(TextStyle.Body, tokens.ink3, Align.Start, TextKind.Label)
        labels :+ frame.text(
          copy.moodWeekdayTooFew,
          Point(TrackLeft, top + LabelBaselinePx),
          look,
          frame.right - TrackLeft
        )
