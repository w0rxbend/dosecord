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
import dosegram.render.tokens.Tokens

/** IMG-12, `card.sleep.regularity.v1` (VISUALS section 6.13): one row per sleep day on a clock axis from 18:00 to
  * 18:00, a solid bar for each night the user entered, a thinner bar marked "n" for a nap, and dotted lines at the
  * typical bedtime and wake time once enough nights exist. No score, no index and no reference duration is drawn
  * (VA-IMG-12b); the caption names the source of every figure (VA-IMG-12c). The plot sits on a panel and its marks are
  * named by glyph under it.
  */
object SleepRegularityCard:
  val TemplateId = "card.sleep.regularity.v1"
  private val DayMinutes = 1440
  private val AxisStartMinute = 18 * 60
  private val PanelTop = 152.0
  private val PanelPadPx = 24.0
  private val PanelRadius = 40.0
  private val AxisBaseline = 212.0
  private val RowsTop = 236.0
  private val RowsBottom = 952.0
  private val LabelColumn = 136.0
  private val NightBarPx = 36.0
  private val NapBarPx = 20.0
  private val PanelToLegendPx = 24.0
  private val LegendToLinesPx = 52.0
  private val LinePx = 54.0

  /** The clock axis of the plot: x of a minute after 18:00, and the centre of a day's row. */
  final private case class Plot(left: Double, right: Double, rowPx: Double):
    def x(axisMinute: Int): Double = left + (right - left) * axisMinute / DayMinutes
    def rowCentre(index: Int): Double = RowsTop + rowPx * index + rowPx / 2

  def render(sleep: SleepRegularityData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val copy = context.copy
    val days = (sleep.to.toEpochDay - sleep.from.toEpochDay).toInt + 1
    val plot = Plot(frame.left + PanelPadPx + LabelColumn, frame.right - PanelPadPx - 12, (RowsBottom - RowsTop) / days)
    val dates = Vector.tabulate(days)(index => sleep.from.plusDays(index.toLong))
    val panelBottom = RowsBottom + PanelPadPx
    val panel =
      Node.filledRect(Box(frame.left, PanelTop, frame.width, panelBottom - PanelTop), PanelRadius, frame.tokens.panel)
    // A grid line every three hours; the closing 18:00 has its line and no label, which would leave the panel.
    val axis = (0 to 8).toVector.flatMap { step =>
      val minute = step * 180
      val hour = (AxisStartMinute + minute) / 60 % 24
      val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
      val grid =
        Node.Line(Point(plot.x(minute), RowsTop), Point(plot.x(minute), RowsBottom), Stroke.solid(frame.tokens.grid, 4))
      if step == 8 then Vector(grid)
      else Vector(grid, frame.text(copy.sleepAxisHour(hour), Point(plot.x(minute), AxisBaseline), look, 96))
    }
    val rows = dates.zipWithIndex.flatMap { (date, index) =>
      val look = TextLook(TextStyle.Label, frame.tokens.ink2, Align.Start, TextKind.Axis)
      val labelAt = Point(frame.left + PanelPadPx, plot.rowCentre(index) + 14)
      val label = frame.text(copy.sleepRowDate(date), labelAt, look, LabelColumn - 8)
      label +: sleep.bars.filter(_.date == date).map(bar => barNode(bar, plot, index, frame, copy.sleepNapLetter))
    }
    val typical = (sleep.typicalBedtime.toVector ++ sleep.typicalWake.toVector).map { clock =>
      val x = plot.x(Math.floorMod(clock - AxisStartMinute, DayMinutes))
      Node.Mark(
        MarkMeta("sleep.typical", Set(Channel.Pattern, Channel.Position)),
        Vector(Node.Line(Point(x, RowsTop), Point(x, RowsBottom), Stroke.dotted(frame.tokens.ink2)))
      )
    }
    val legend = Legend.flow(legendItems(sleep, frame.tokens, context), frame.width, context.fitter)
    val legendTop = panelBottom + PanelToLegendPx
    val firstLine = legendTop + legend.height + (if legend.rows.isEmpty then 36.0 else LegendToLinesPx)
    val lines = figureLines(sleep, context).zipWithIndex.map { (line, index) =>
      val ink = if index == 0 then frame.tokens.ink else frame.tokens.ink2
      val style = if index == 0 then TextStyle.BodyStrong else TextStyle.Body
      frame.text(line, Point(frame.left, firstLine + index * LinePx), frame.look(style, ink), frame.width)
    }
    val nodes = frame.header(copy.sleepTitle, copy.dateRange(sleep.from, sleep.to)) ++ Vector(panel) ++ axis ++
      rows ++ typical ++ legend.nodes(Point(frame.left, legendTop), frame, context.fitter) ++ lines :+ frame.footer
    val text = TextEquivalent.of(copy.sleepText(sleep), copy.sleepDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-sleep.png", context.privacy == Privacy.Discreet)

  private def barNode(bar: SleepNightBar, plot: Plot, index: Int, frame: CardFrame, napLetter: String): Node =
    val height = if bar.isNap then NapBarPx else NightBarPx
    val centre = plot.rowCentre(index)
    val box = Box(
      plot.x(bar.fromMinute),
      centre - height / 2,
      StrictMath.max(8.0, plot.x(bar.toMinute) - plot.x(bar.fromMinute)),
      height
    )
    val shape = Node.filledRect(box, height / 2, frame.tokens.dusk)
    if bar.isNap then
      val mark = frame.text(
        napLetter,
        Point(box.right + 8, centre + 14),
        frame.look(TextStyle.Label, frame.tokens.ink2),
        40
      )
      Node.Mark(MarkMeta("sleep.nap", Set(Channel.Colour, Channel.Position, Channel.Label)), Vector(shape, mark))
    else Node.Mark(MarkMeta("sleep.night", Set(Channel.Colour, Channel.Position)), Vector(shape))

  // One entry per kind of mark the plot draws: a night bar, a nap bar with its "n", the dotted typical times.
  private def legendItems(sleep: SleepRegularityData, tokens: Tokens, context: CardContext): Vector[LegendItem] =
    val copy = context.copy
    def bar(box: Box, heightPx: Double): Node =
      Node.filledRect(Box(box.x, box.center.y - heightPx / 2, box.width, heightPx), heightPx / 2, tokens.dusk)
    val night = Option.when(sleep.bars.exists(!_.isNap)) {
      LegendItem.of(
        box => Node.Mark(MarkMeta("legend.sleep.night", Set(Channel.Colour, Channel.Shape)), Vector(bar(box, 24))),
        copy.sleepEntriesMark
      )
    }
    val nap = Option.when(sleep.bars.exists(_.isNap)) {
      LegendItem.of(
        box => Node.Mark(MarkMeta("legend.sleep.nap", Set(Channel.Colour, Channel.Shape)), Vector(bar(box, 14))),
        copy.sleepNapMark
      )
    }
    val typical = Option.when(sleep.typicalBedtime.isDefined || sleep.typicalWake.isDefined) {
      LegendItem.of(
        box =>
          Node.Mark(
            MarkMeta("legend.sleep.typical", Set(Channel.Pattern)),
            Vector(Node.Line(Point(box.center.x, box.y), Point(box.center.x, box.bottom), Stroke.dotted(tokens.ink2)))
          ),
        copy.sleepTypicalMark
      )
    }
    Vector(night, nap, typical).flatten

  private def figureLines(sleep: SleepRegularityData, context: CardContext): Vector[String] =
    val copy = context.copy
    val average = sleep.averageMinutes.map(copy.sleepAverageOf(_, sleep.nights))
    val spread = for
      bedtime <- sleep.bedtimeSpreadMinutes
      wake <- sleep.wakeSpreadMinutes
    yield copy.sleepSpread(bedtime, wake)
    val first = if sleep.nights == 0 then Some(copy.sleepNoNights) else average
    (first.toVector ++ spread ++ sleep.goalMinutes.map(copy.sleepGoal)).take(3)
