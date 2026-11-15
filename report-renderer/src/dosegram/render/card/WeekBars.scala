package dosegram.render.card

import dosegram.render.chart.Bars
import dosegram.render.chart.Hatch
import dosegram.render.color.Rgb
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

/** One day of a week of bars: an amount against the user's own goal on that day, an estimate, or no record. */
enum BarDay:
  case Amount(value: Int, goal: Option[Int])
  case Estimate
  case NoRecord

/** The headline of a week of bars: one hero figure and the note under it. */
final case class BarsHero(figure: String, note: String)

/** The square layout the water and sleep weeks share (IMG-20, IMG-21): a hero figure with its note and a fact line,
  * then seven upright bars on their own scale with the user's goal as a dotted tick in each day's slot, the weekday
  * initials and the legend of the marks drawn. Bars start at zero (VISUALS section 3.2); a day without a record is a
  * dotted slot, never a bar of zero (VA-PR-03); an estimate is a hatched slot of the same size, because it is a bound
  * and not an amount: a bar of any height would read as one.
  */
object WeekBars:
  private val DayCount = 7
  private val HeroBaseline = 300.0
  private val NoteBaseline = 364.0
  private val FactBaseline = 420.0
  private val BarsTop = 468.0
  private val BarsBottom = 820.0
  private val WeekdayGapPx = 48.0
  private val LegendGapPx = 20.0
  private val BarWidthPx = 72.0
  private val GoalOverhangPx = 16.0
  private val MinimumBarPx = 8.0
  private val SlotCornerPx = 16.0

  final case class Week(
      templateId: String,
      fileName: String,
      domain: Domain,
      title: String,
      from: LocalDate,
      days: Vector[BarDay],
      hero: BarsHero,
      fact: Option[String],
      text: TextEquivalent
  ):
    require(days.size == DayCount, "seven days")

  def render(week: Week, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val tokens = frame.tokens
    val heroLook = TextLook(TextStyle.Hero, tokens.ink, Align.Start, TextKind.Value)
    val noteLook = TextLook(TextStyle.Body, tokens.ink2, Align.Start, TextKind.Label)
    val factLook = TextLook(TextStyle.BodyMedium, tokens.ink, Align.Start, TextKind.Value)
    val legend = Legend.flow(legendItems(week, context), frame.width, context.fitter)
    val weekdayBaseline = BarsBottom + WeekdayGapPx
    val nodes = frame.header(week.title, context.copy.dateRange(week.from, week.from.plusDays(DayCount - 1L)))
      ++ Vector(
        frame.text(week.hero.figure, Point(frame.left, HeroBaseline), heroLook, frame.width),
        frame.text(week.hero.note, Point(frame.left, NoteBaseline), noteLook, frame.width)
      )
      ++ week.fact.map(fact => frame.text(fact, Point(frame.left, FactBaseline), factLook, frame.width)).toVector
      ++ bars(week, frame)
      ++ weekdays(week, weekdayBaseline, frame, context)
      ++ legend.nodes(Point(frame.left, weekdayBaseline + LegendGapPx), frame, context.fitter)
      :+ frame.footer
    CardOutput(frame.scene(week.templateId, nodes), week.text, week.fileName, context.privacy == Privacy.Discreet)

  private def bars(week: Week, frame: CardFrame): Vector[Node] =
    val tokens = frame.tokens
    val slotWidth = frame.width / DayCount
    val height = BarsBottom - BarsTop
    val scale =
      (week.days.collect { case BarDay.Amount(value, goal) => StrictMath.max(value, goal.getOrElse(0)) } :+ 1).max
    val colour = tokens.hue(week.domain)
    week.days.zipWithIndex.flatMap { (day, index) =>
      val center = frame.left + (index + 0.5) * slotWidth
      val column = Box(center - BarWidthPx / 2, BarsTop, BarWidthPx, height)
      day match
        case BarDay.Amount(value, goal) =>
          val barHeight = StrictMath.max(MinimumBarPx, height * value / scale)
          val bar = Bars.upright(Box(column.x, BarsBottom - barHeight, BarWidthPx, barHeight), colour)
          val tick = goal.map { goalValue =>
            val y = BarsBottom - height * goalValue / scale
            Node.Line(
              Point(column.x - GoalOverhangPx, y),
              Point(column.right + GoalOverhangPx, y),
              Stroke.dotted(tokens.ink3)
            )
          }
          Vector(Node.Mark(MarkMeta(s"${role(week)}.day", Set(Channel.Colour, Channel.Position)), bar +: tick.toVector))
        case BarDay.Estimate =>
          val slot = Box(column.x, BarsBottom - BarWidthPx, BarWidthPx, BarWidthPx)
          val outline = Node.outlinedRect(slot.inset(2), SlotCornerPx, Stroke.butt(tokens.ink3, 4))
          Vector(
            Node.Mark(
              MarkMeta(s"${role(week)}.estimate", Set(Channel.Pattern)),
              outline +: Hatch.lines(slot.inset(6), Stroke.butt(tokens.ink3, 4))
            )
          )
        case BarDay.NoRecord =>
          val slot = Box(column.x, BarsBottom - BarWidthPx, BarWidthPx, BarWidthPx)
          Vector(emptySlot(s"${role(week)}.none", slot, tokens))
    }

  private def role(week: Week): String = week.domain.toString.toLowerCase(java.util.Locale.ROOT)

  private def emptySlot(role: String, box: Box, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta(role, Set(Channel.Pattern)),
      Vector(Node.outlinedRect(box.inset(2), SlotCornerPx, Stroke.dotted(tokens.ink3)))
    )

  private def weekdays(week: Week, baseline: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    val slotWidth = frame.width / DayCount
    (0 until DayCount).toVector.map { day =>
      val initial = context.copy.weekdayInitial(week.from.plusDays(day.toLong).getDayOfWeek)
      frame.text(initial, Point(frame.left + (day + 0.5) * slotWidth, baseline), look, slotWidth)
    }

  private def legendItems(week: Week, context: CardContext): Vector[LegendItem] =
    val tokens = context.tokens
    val copy = context.copy
    Vector(
      Option.when(week.days.exists { case BarDay.Amount(_, goal) => goal.isDefined; case _ => false })(
        LegendItem.of(box => goalGlyph(box, tokens.ink3), copy.yourGoal)
      ),
      Option.when(week.days.contains(BarDay.NoRecord))(
        LegendItem.of(box => emptySlot("legend.none", box.inset(4), tokens), copy.noRecordMark)
      ),
      Option.when(week.days.contains(BarDay.Estimate))(
        LegendItem.of(
          box =>
            Node.Mark(
              MarkMeta("legend.estimate", Set(Channel.Pattern)),
              Node.outlinedRect(box.inset(4), 10, Stroke.butt(tokens.ink3, 4)) +: Hatch.lines(
                box.inset(8),
                Stroke.butt(tokens.ink3, 4)
              )
            ),
          copy.estimateMark
        )
      )
    ).flatten

  private def goalGlyph(box: Box, ink: Rgb): Node =
    Node.Mark(
      MarkMeta("legend.goal", Set(Channel.Pattern)),
      Vector(Node.Line(Point(box.x + 2, box.center.y), Point(box.right - 2, box.center.y), Stroke.dotted(ink)))
    )

/** IMG-20, `card.water.week.v1`: the water of the last seven days as bars against the user's own goal. The hero is the
  * average a day from three recorded days, the count of days logged below that. Nothing suggests an amount to drink
  * (VA-IMG-08a).
  */
object WaterWeekCard:
  val TemplateId = "card.water.week.v1"
  private val MinimumDaysForAverage = 3

  def render(from: LocalDate, days: Vector[Option[WaterProgress]], context: CardContext): CardOutput =
    val copy = context.copy
    val recorded = days.flatten
    val hero =
      if recorded.size >= MinimumDaysForAverage then
        BarsHero(copy.litresAmount(recorded.map(_.volumeMl).sum / recorded.size), copy.averageADay(recorded.size))
      else BarsHero(recorded.size.toString, copy.daysLogged(recorded.size))
    val fact = Option.when(recorded.nonEmpty)(
      copy.goalReachedDays(dosegram.render.copy.GoalDays(recorded.count(_.isGoalReached), recorded.size))
    )
    val bars = days.map(
      _.fold(BarDay.NoRecord)(progress =>
        BarDay.Amount(progress.volumeMl, Option.when(progress.goalMl > 0)(progress.goalMl))
      )
    )
    val text = TextEquivalent.of(copy.waterWeekText(from, days), copy.waterWeekDiscreet, context)
    WeekBars.render(
      WeekBars
        .Week(TemplateId, "dosegram-water-week.png", Domain.Water, copy.waterWeekTitle, from, bars, hero, fact, text),
      context
    )

/** IMG-21, `card.sleep.week.v1`: the main sleep of the last seven nights as bars against the user's own goal. A quiet
  * window is a hatched outline and never a duration (VA-IMG-12a); the hero averages recorded nights only, from three.
  */
object SleepWeekCard:
  val TemplateId = "card.sleep.week.v1"
  private val MinimumNightsForAverage = 3

  def render(from: LocalDate, nights: Vector[SleepEvidence], context: CardContext): CardOutput =
    val copy = context.copy
    val recorded = nights.collect { case SleepEvidence.Recorded(minutes, _) => minutes }
    val hero =
      if recorded.size >= MinimumNightsForAverage then
        BarsHero(copy.duration(recorded.sum / recorded.size), copy.averageOverNights(recorded.size))
      else BarsHero(recorded.size.toString, copy.nightsLogged(recorded.size))
    val goal = nights.collectFirst { case SleepEvidence.Recorded(_, goal) if goal > 0 => goal }
    val bars = nights.map {
      case SleepEvidence.Recorded(minutes, goal) => BarDay.Amount(minutes, Option.when(goal > 0)(goal))
      case SleepEvidence.QuietWindowOnly         => BarDay.Estimate
      case SleepEvidence.NoRecord                => BarDay.NoRecord
    }
    val text = TextEquivalent.of(copy.sleepWeekText(from, nights), copy.sleepWeekDiscreet, context)
    WeekBars.render(
      WeekBars.Week(
        TemplateId,
        "dosegram-sleep-week.png",
        Domain.Sleep,
        copy.sleepWeekTitle,
        from,
        bars,
        hero,
        goal.map(copy.sleepGoal),
        text
      ),
      context
    )
