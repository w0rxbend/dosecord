package dosegram.render.card

import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle

/** IMG-12, `card.sleep.regularity.v1` (VISUALS section 6.13): one row per sleep day on a clock axis from 18:00 to
  * 18:00, a solid bar for each night the user entered, a thinner bar marked "n" for a nap, and dotted lines at the
  * typical bedtime and wake time once enough nights exist. No score, no index and no reference duration is drawn
  * (VA-IMG-12b); the caption names the source of every figure (VA-IMG-12c).
  */
object SleepRegularityCard:
  val TemplateId = "card.sleep.regularity.v1"
  private val DayMinutes = 1440
  private val AxisStartMinute = 18 * 60
  private val AxisBaseline = 216.0
  private val RowsTop = 240.0
  private val RowsBottom = 952.0
  private val LabelColumn = 144.0
  private val NightBarPx = 36.0
  private val NapBarPx = 20.0
  private val FirstLineBaseline = 1024.0
  private val LinePx = 56.0

  def render(sleep: SleepRegularityData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val copy = context.copy
    val plotLeft = frame.left + LabelColumn
    val days = (sleep.to.toEpochDay - sleep.from.toEpochDay).toInt + 1
    val rowPx = (RowsBottom - RowsTop) / days
    def x(axisMinute: Int): Double = plotLeft + (frame.right - plotLeft) * axisMinute / DayMinutes
    def rowCentre(index: Int): Double = RowsTop + rowPx * index + rowPx / 2
    val dates = Vector.tabulate(days)(index => sleep.from.plusDays(index.toLong))
    // A grid line every three hours; the closing 18:00 has its line and no label, which would leave the safe area.
    val axis = (0 to 8).toVector.flatMap { step =>
      val minute = step * 180
      val hour = (AxisStartMinute + minute) / 60 % 24
      val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
      val grid = Node.Line(Point(x(minute), RowsTop), Point(x(minute), RowsBottom), Stroke.solid(frame.tokens.grid, 4))
      if step == 8 then Vector(grid)
      else Vector(grid, frame.text(copy.sleepAxisHour(hour), Point(x(minute), AxisBaseline), look, 96))
    }
    val rows = dates.zipWithIndex.flatMap { (date, index) =>
      val look = TextLook(TextStyle.Label, frame.tokens.ink2, Align.Start, TextKind.Axis)
      val label = frame.text(copy.sleepRowDate(date), Point(frame.left, rowCentre(index) + 14), look, LabelColumn - 8)
      val bars = sleep.bars.filter(_.date == date).map { bar =>
        val height = if bar.isNap then NapBarPx else NightBarPx
        val box = Box(
          x(bar.fromMinute),
          rowCentre(index) - height / 2,
          StrictMath.max(8.0, x(bar.toMinute) - x(bar.fromMinute)),
          height
        )
        val shape = Node.filledRect(box, height / 2, frame.tokens.dusk)
        if bar.isNap then
          val mark = frame.text(
            "n",
            Point(box.right + 8, rowCentre(index) + 14),
            frame.look(TextStyle.Label, frame.tokens.ink2),
            40
          )
          Node.Mark(MarkMeta("sleep.nap", Set(Channel.Colour, Channel.Position, Channel.Label)), Vector(shape, mark))
        else Node.Mark(MarkMeta("sleep.night", Set(Channel.Colour, Channel.Position)), Vector(shape))
      }
      label +: bars
    }
    val typical = (sleep.typicalBedtime.toVector ++ sleep.typicalWake.toVector).map { clock =>
      val axisMinute = Math.floorMod(clock - AxisStartMinute, DayMinutes)
      Node.Mark(
        MarkMeta("sleep.typical", Set(Channel.Pattern, Channel.Position)),
        Vector(
          Node.Line(Point(x(axisMinute), RowsTop), Point(x(axisMinute), RowsBottom), Stroke.dotted(frame.tokens.ink2))
        )
      )
    }
    val lines = figureLines(sleep, context).zipWithIndex.map { (line, index) =>
      val ink = if index == 0 then frame.tokens.ink else frame.tokens.ink2
      val style = if index == 0 then TextStyle.BodyStrong else TextStyle.Body
      frame.text(line, Point(frame.left, FirstLineBaseline + index * LinePx), frame.look(style, ink), frame.width)
    }
    val nodes = frame.header(copy.sleepTitle, copy.dateRange(sleep.from, sleep.to)) ++ axis ++ rows ++ typical ++
      lines :+ frame.footer
    val text = TextEquivalent.of(copy.sleepText(sleep), copy.sleepDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-sleep.png", context.privacy == Privacy.Discreet)

  private def figureLines(sleep: SleepRegularityData, context: CardContext): Vector[String] =
    val copy = context.copy
    val average = sleep.averageMinutes.map(copy.sleepAverageOf(_, sleep.nights))
    val spread = for
      bedtime <- sleep.bedtimeSpreadMinutes
      wake <- sleep.wakeSpreadMinutes
    yield copy.sleepSpread(bedtime, wake)
    val first = if sleep.nights == 0 then Some(copy.sleepNoNights) else average
    (first.toVector ++ spread ++ Vector(copy.sleepLegend) ++ sleep.goalMinutes.map(copy.sleepGoal)).take(4)
