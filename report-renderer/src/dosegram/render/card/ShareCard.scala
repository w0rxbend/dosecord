package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle

/** IMG-17, `card.share.v1` (VISUALS section 6.18): one whitelisted fact of the last seven days that the user may
  * forward. The data type holds no medication, date, time or free text (VA-IMG-17a), so none can be drawn: a day count
  * is seven marks in the domain's hue without weekday letters, the week's mood is its blob and level name. The wordmark
  * names the product and carries no link or handle (VISUALS Q13).
  */
object ShareCard:
  val TemplateId = "card.share.v1"
  private val MarkWidthPx = 80.0
  private val MarkGapPx = 36.0
  private val MarkHeightPx = 220.0
  private val MarksTop = 190.0
  private val HeadlineBaseline = 566.0
  private val CountBaseline = 732.0
  private val PeriodBaseline = 808.0
  private val BlobAt = BlobFrame(Point(540, 390), 230)
  private val MoodNameBaseline = 750.0
  private val MoodPeriodBaseline = 822.0
  private val WordmarkGapPx = 56.0

  def render(share: ShareData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val copy = context.copy
    val body = share.fact match
      case ShareFact.WeekMood(state) => mood(state, frame, context)
      case days: ShareFact.DayCount  => dayCount(days, frame, context)
    val wordmark = frame.text(
      copy.shareWordmark,
      Point(frame.right, frame.footerBaseline - WordmarkGapPx),
      TextLook(TextStyle.BodyMedium, frame.tokens.ink2, Align.End, TextKind.Label),
      frame.width
    )
    val text = TextEquivalent.of(copy.shareText(share.fact), copy.shareDiscreet, context)
    CardOutput(
      frame.scene(TemplateId, body ++ Vector(wordmark, frame.footer)),
      text,
      "dosegram-share.png",
      context.privacy == Privacy.Discreet
    )

  private def dayCount(days: ShareFact.DayCount, frame: CardFrame, context: CardContext): Vector[Node] =
    val hue = frame.tokens.hue(days.domain)
    val rowWidth = days.days.size * MarkWidthPx + (days.days.size - 1) * MarkGapPx
    val left = frame.size.width / 2 - rowWidth / 2
    val marks = days.days.zipWithIndex.map { (hit, index) =>
      val box = Box(left + index * (MarkWidthPx + MarkGapPx), MarksTop, MarkWidthPx, MarkHeightPx)
      if hit then Node.filledRect(box, 18, hue)
      else Node.outlinedRect(box.inset(4), 16, Stroke.dotted(frame.tokens.ink3))
    }
    val centerX = frame.size.width / 2
    val (headline, count, period) = context.copy.shareDayCount(days)
    Vector(
      Node.Mark(MarkMeta("share.days", Set(Channel.Colour, Channel.Pattern, Channel.Label)), marks),
      frame.text(
        headline,
        Point(centerX, HeadlineBaseline),
        TextLook(TextStyle.Title, frame.tokens.ink, Align.Middle, TextKind.Label),
        frame.width
      ),
      frame.text(
        count,
        Point(centerX, CountBaseline),
        TextLook(TextStyle.Hero, frame.tokens.ink, Align.Middle, TextKind.Value),
        frame.width
      ),
      frame.text(
        period,
        Point(centerX, PeriodBaseline),
        TextLook(TextStyle.Body, frame.tokens.ink2, Align.Middle, TextKind.Label),
        frame.width
      )
    )

  private def mood(state: dosegram.render.blob.MoodState, frame: CardFrame, context: CardContext): Vector[Node] =
    val centerX = frame.size.width / 2
    Vector(
      BlobFigure.of(state, BlobAt, frame.tokens),
      frame.text(
        context.copy.valenceName(state.valence),
        Point(centerX, MoodNameBaseline),
        TextLook(TextStyle.Title, frame.tokens.ink, Align.Middle, TextKind.Label),
        frame.width
      ),
      frame.text(
        context.copy.shareMoodPeriod,
        Point(centerX, MoodPeriodBaseline),
        TextLook(TextStyle.Body, frame.tokens.ink2, Align.Middle, TextKind.Label),
        frame.width
      )
    )
end ShareCard
