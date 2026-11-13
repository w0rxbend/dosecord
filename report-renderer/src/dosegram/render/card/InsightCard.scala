package dosegram.render.card

import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.LineCap
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.TextStyle

/** IMG-13, `card.insight.v1` (VISUALS section 6.14): one descriptive comparison of two groups of the user's own days,
  * drawn as two dumbbell rows on one shared scale in the outcome's hue, each with its value and its group size. The
  * fixed sentence "This compares your own days. It does not show a cause." is part of the template (VA-IN-11). The
  * engine decides whether a pattern is shown; this template draws only one it was given.
  */
object InsightCard:
  val TemplateId = "card.insight.v1"
  private val PeriodBaseline = 188.0
  private val PanelTop = 236.0
  private val PanelPadPx = 32.0
  private val PanelRadius = 40.0
  private val GroupPx = 216.0
  private val LabelOffsetPx = 76.0
  private val TrackOffsetPx = 140.0
  private val SizeOffsetPx = 192.0
  private val PanelBottomPadPx = 44.0
  private val TrackPx = 12.0
  private val StemPx = 8.0
  private val DotRadius = 22.0
  private val ValueColumnPx = 132.0
  private val ScaleBelowPanelPx = 52.0
  private val NoteBelowPanelPx = 112.0
  private val SentenceGapPx = 56.0
  private val NoteLinePx = 52.0

  def render(insight: InsightData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val copy = context.copy
    val tokens = frame.tokens
    val trackLeft = frame.left + PanelPadPx
    val trackRight = frame.right - PanelPadPx - ValueColumnPx
    def x(value: Double): Double =
      val share = (value - InsightData.ScaleMinimum) / (InsightData.ScaleMaximum - InsightData.ScaleMinimum)
      trackLeft + (trackRight - trackLeft) * StrictMath.max(0.0, StrictMath.min(1.0, share))
    val groups = Vector(insight.first, insight.second)
    val panelBottom = PanelTop + (groups.size - 1) * GroupPx + SizeOffsetPx + PanelBottomPadPx
    val panel =
      Node.filledRect(Box(frame.left, PanelTop, frame.width, panelBottom - PanelTop), PanelRadius, tokens.panel)
    val rows = groups.zipWithIndex.flatMap { (group, index) =>
      val top = PanelTop + index * GroupPx
      val trackY = top + TrackOffsetPx
      val labelLook = frame.look(TextStyle.BodyMedium, tokens.ink)
      val sizeLook = frame.look(TextStyle.Label, tokens.ink2)
      val valueLook = TextLook(TextStyle.Value, tokens.ink, Align.End, TextKind.Value)
      val track = Node.Line(
        Point(trackLeft, trackY),
        Point(trackRight, trackY),
        Stroke(tokens.grid, TrackPx, LineCap.Round, None)
      )
      val stem = Node.Line(
        Point(trackLeft, trackY),
        Point(x(group.value), trackY),
        Stroke.solid(tokens.hue(insight.outcome), StemPx)
      )
      val dot = Node.Circle(
        Point(x(group.value), trackY),
        DotRadius,
        Some(dosegram.render.scene.Paint.Solid(tokens.hue(insight.outcome))),
        Some(Stroke.solid(tokens.panel, 6))
      )
      Vector(
        frame.text(
          copy.insightGroup(insight.rule, index == 0),
          Point(trackLeft, top + LabelOffsetPx - 16),
          labelLook,
          frame.width - 2 * PanelPadPx
        ),
        track,
        Node.Mark(
          MarkMeta(s"insight.group.${index + 1}", Set(Channel.Colour, Channel.Position, Channel.Label)),
          Vector(stem, dot)
        ),
        frame
          .text(copy.insightValue(group.value), Point(frame.right - PanelPadPx, trackY + 20), valueLook, ValueColumnPx),
        frame.text(copy.insightDays(group.days), Point(trackLeft, top + SizeOffsetPx), sizeLook, frame.width)
      )
    }
    val scaleLook = TextLook(TextStyle.Label, tokens.ink3, Align.Middle, TextKind.Axis)
    val scale = (InsightData.ScaleMinimum.toInt to InsightData.ScaleMaximum.toInt).toVector.map { level =>
      frame.text(level.toString, Point(x(level.toDouble), panelBottom + ScaleBelowPanelPx), scaleLook, 60)
    }
    val noteBaseline = panelBottom + NoteBelowPanelPx
    val scaleNote =
      frame.text(
        copy.insightScaleNote(insight.outcome),
        Point(frame.left, noteBaseline),
        frame.look(TextStyle.Label, tokens.ink2),
        frame.width
      )
    // The fixed sentence is never cut (VA-IN-11): one line when it fits, else one line per sentence, else the fitting
    // order of VA-TY-04.
    val sentence = notCauseLines(copy.insightNotCause, frame.width, context)
    val notCause = sentence.lines.zipWithIndex.map { (line, index) =>
      Node.Text(
        line,
        Point(frame.left, noteBaseline + SentenceGapPx + index * NoteLinePx),
        Align.Start,
        sentence.style,
        tokens.ink,
        TextKind.Label
      )
    }
    val notes = scaleNote +: notCause
    val period = frame.text(
      copy.dateRange(insight.from, insight.to),
      Point(frame.left, PeriodBaseline),
      TextLook(TextStyle.BodyMedium, tokens.ink2, Align.Start, TextKind.Value),
      frame.width
    )
    val nodes = Vector(
      frame.text(
        copy.insightTitle,
        Point(frame.left, CardFrame.HeaderBaseline),
        frame.look(TextStyle.Title, tokens.ink),
        frame.width
      ),
      period,
      panel
    ) ++ rows ++ scale ++ notes :+ frame.footer
    val text = TextEquivalent.of(copy.insightText(insight), copy.insightDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-pattern.png", context.privacy == Privacy.Discreet)

  private def notCauseLines(text: String, width: Double, context: CardContext): dosegram.render.text.FittedText =
    val style = TextStyle.BodyMedium
    val sentences = text.split("(?<=\\.) ").toVector
    if context.fitter.width(text, style) <= width then dosegram.render.text.FittedText(Vector(text), style)
    else if sentences.size == 2 && sentences.forall(context.fitter.width(_, style) <= width) then
      dosegram.render.text.FittedText(sentences, style)
    else context.fitter.twoLines(text, style, width)

/** The rules the insight card can word (VISUALS section 8.4). The list is closed: a rule without wording cannot be
  * drawn. No rule reads a medication measure (VA-IN-13).
  */
enum InsightRule:
  /** IR-04: water goal reached against not reached, recorded days only; outcome the daily mood mean. */
  case WaterGoalMood

  /** IR-01: nights at or above the user's sleep goal against shorter nights; outcome the next day's mood mean. */
  case SleepGoalMood

  def outcome: Domain = Domain.Mood

/** One group of days: how many, and the mean of the outcome over them on the 1 to 5 scale. */
final case class InsightGroup(days: Int, value: Double):
  require(days > 0, "a group has days")

/** IMG-13: a pattern the insight engine passed, with its period and its two groups (VISUALS section 8.5). */
final case class InsightData(
    rule: InsightRule,
    from: java.time.LocalDate,
    to: java.time.LocalDate,
    first: InsightGroup,
    second: InsightGroup
):
  def outcome: Domain = rule.outcome

object InsightData:
  /** The stored mood scale (VISUALS section 7.6). */
  val ScaleMinimum: Double = 1
  val ScaleMaximum: Double = 5
