package dosegram.render.card

import dosegram.render.chart.Bars
import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle

import java.time.LocalDate

/** One local day of the mood timeline: the daily mean on the stored 1 to 5 scale, the lowest and the highest check-in
  * of the day, how many check-ins there were, and the mean energy when any check-in gave it.
  */
final case class MoodDayPoint(mean: Double, lowest: Int, highest: Int, checkIns: Int, energy: Option[Double]):
  require(mean >= 1 && mean <= 5, s"a daily mean is on the 1 to 5 scale, got $mean")

/** IMG-10: 14 or 30 days from `from`; a day without a check-in is `None` and is a gap. */
final case class MoodTimelineData(from: LocalDate, days: Vector[Option[MoodDayPoint]]):
  require(days.nonEmpty, "a timeline has days")
  def to: LocalDate = from.plusDays(days.size.toLong - 1)
  def checkInDays: Int = days.count(_.isDefined)

/** IMG-10, `card.mood.timeline.v1` (VISUALS section 6.11): the daily mean as a dot per day, joined by a line between
  * consecutive days only, a whisker from the lowest to the highest check-in of a day with more than one, and a separate
  * energy strip on its own scale (VA-CO-06). A day without a check-in is a gap in both (VA-PR-03).
  */
object MoodTimelineCard:
  val TemplateId = "card.mood.timeline.v1"

  /** The narrowest level column; a language with longer level names widens it (VA-L10N-02). */
  private val MinLabelColumn = 300.0
  private val PlotTop = 184.0
  private val PlotBottom = 396.0
  private val TicksBaseline = 440.0
  private val EnergyTop = 464.0
  private val EnergyBottom = 520.0
  private val DotRadius = 10.0
  private val LinePx = 6.0
  private val WhiskerPx = 4.0
  private val EnergyBarPx = 16.0
  private val FactBaseline = 584.0
  private val Levels = Vector(1, 4, 7)

  def render(timeline: MoodTimelineData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Wide, context.tokens.surface)
    val copy = context.copy
    val tokens = frame.tokens
    // A label that would be cut even condensed widens the column to its full width (VA-TY-04 before VA-L10N-02).
    val labels = Levels.map(copy.valenceName) :+ copy.energyLabel
    val cut =
      labels.exists(label => context.fitter.line(label, TextStyle.Label, MinLabelColumn - 16).lines.head != label)
    val labelColumn =
      if cut then StrictMath.max(MinLabelColumn, labels.map(context.fitter.width(_, TextStyle.Label)).max + 24)
      else MinLabelColumn
    val plotLeft = frame.left + labelColumn
    val slot = (frame.right - plotLeft) / timeline.days.size
    def x(index: Int): Double = plotLeft + (index + 0.5) * slot
    // The stored scale 1 to 5 runs from the bottom to the top of the plot.
    def y(value: Double): Double = PlotBottom - (PlotBottom - PlotTop) * (value - 1) / 4
    val levelLook = TextLook(TextStyle.Label, tokens.ink3, Align.Start, TextKind.Axis)
    // V1, V4 and V7 sit at the stored values 1, 3 and 5 (VISUALS section 4.2, m = 3 + 2v).
    val levels = Levels.flatMap { level =>
      val value = 1 + (level - 1) * 4.0 / 6
      Vector(
        Node.Line(Point(plotLeft, y(value)), Point(frame.right, y(value)), Stroke.dotted(tokens.grid)),
        frame.text(copy.valenceName(level), Point(frame.left, y(value) + 14), levelLook, labelColumn - 16)
      )
    }
    val joins = timeline.days.indices.drop(1).flatMap { index =>
      for
        before <- timeline.days(index - 1)
        now <- timeline.days(index)
      yield Node.Line(
        Point(x(index - 1), y(before.mean)),
        Point(x(index), y(now.mean)),
        Stroke.solid(tokens.beet, LinePx)
      )
    }
    val dots = timeline.days.zipWithIndex.flatMap { (day, index) =>
      day.map { point =>
        val whisker = Option.when(point.checkIns > 1 && point.highest > point.lowest) {
          Node.Line(
            Point(x(index), y(point.lowest.toDouble)),
            Point(x(index), y(point.highest.toDouble)),
            Stroke.solid(tokens.ink2, WhiskerPx)
          )
        }
        Node.Mark(
          MarkMeta("mood.timeline.day", Set(Channel.Colour, Channel.Position)),
          whisker.toVector :+ Node.Circle(
            Point(x(index), y(point.mean)),
            DotRadius,
            Some(dosegram.render.scene.Paint.Solid(tokens.beet)),
            Some(Stroke.solid(tokens.surface, 4))
          )
        )
      }
    }
    val tickLook = TextLook(TextStyle.Label, tokens.ink3, Align.Middle, TextKind.Axis)
    val tickEvery = if timeline.days.size > 16 then 7 else 2
    val ticks = timeline.days.indices.filter(_ % tickEvery == 0).toVector.map { index =>
      val date = timeline.from.plusDays(index.toLong)
      frame.text(date.getDayOfMonth.toString, Point(x(index), TicksBaseline), tickLook, slot * tickEvery)
    }
    val hasEnergy = timeline.days.exists(_.exists(_.energy.isDefined))
    val energy =
      if !hasEnergy then Vector.empty
      else
        frame.text(copy.energyLabel, Point(frame.left, EnergyBottom), levelLook, labelColumn - 16) +:
          timeline.days.zipWithIndex.flatMap { (day, index) =>
            day.flatMap(_.energy).map { value =>
              val height = (EnergyBottom - EnergyTop) * StrictMath.max(1.0, StrictMath.min(5.0, value)) / 5
              Node.Mark(
                MarkMeta("mood.timeline.energy", Set(Channel.Colour, Channel.Position)),
                Vector(
                  Bars.upright(Box(x(index) - EnergyBarPx / 2, EnergyBottom - height, EnergyBarPx, height), tokens.beet)
                )
              )
            }
          }
    val fact = frame.text(
      copy.moodTimelineFact(timeline.checkInDays, timeline.days.size),
      Point(frame.left, FactBaseline),
      TextLook(TextStyle.Label, tokens.ink, Align.Start, TextKind.Value),
      frame.width
    )
    val nodes = frame.header(copy.moodTimelineTitle, copy.dateRange(timeline.from, timeline.to))
      ++ levels ++ joins ++ dots ++ ticks ++ energy :+ fact :+ frame.footer
    val text = TextEquivalent.of(copy.moodTimelineText(timeline), copy.moodTimelineDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-mood-days.png", context.privacy == Privacy.Discreet)
end MoodTimelineCard
