package dosegram.render.card

import dosegram.render.chart.DoseGlyph
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.CalendarLines
import dosegram.render.copy.DayClass
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

import java.time.DayOfWeek
import java.time.YearMonth

/** IMG-06, `card.med.calendar.v1`: which days of a month had all, most, some or none of the scheduled doses recorded as
  * taken. One hue in four lightness steps; "none", "no record" and "open" are shapes, never a failure colour (VISUALS
  * D5). The day number sits under its cell, so no text lies on a coloured fill (VA-CO-03).
  */
object AdherenceCalendarCard:
  val TemplateId = "card.med.calendar.v1"
  private val SlotPx = 128.0
  private val GridLeft = 92.0
  private val WeekdayBaseline = 184.0
  private val GridTop = 204.0
  private val CornerShare = 0.28
  private val GridToLegendPx = 24.0
  private val LegendToFactsPx = 50.0
  private val FactLinePx = 48.0

  /** Cell side and row pitch: 96 px cells, or 80 px in a month that spans six weeks so the legend still fits. */
  final private case class Grid(offset: Int, weekCount: Int):
    def cellPx: Double = if weekCount > 5 then 80 else 96
    def numberBelowPx: Double = if weekCount > 5 then 36 else 40
    def rowPx: Double = cellPx + numberBelowPx + (if weekCount > 5 then 8 else 12)
    def bottom: Double = GridTop + (weekCount - 1) * rowPx + cellPx + numberBelowPx

    def origin(slot: Int): Point =
      Point(GridLeft + (slot % 7) * SlotPx + (SlotPx - cellPx) / 2, GridTop + (slot / 7) * rowPx)

  def render(calendar: CalendarData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val copy = context.copy
    val classes = calendar.days.map(dayClass)
    val offset = Math.floorMod(calendar.month.atDay(1).getDayOfWeek.getValue - calendar.weekStart.getValue, 7)
    val grid = Grid(offset, (offset + calendar.days.size + 6) / 7)
    val label = filterLabel(calendar, context)
    val legend = Legend.flow(legendItems(classes.flatten, frame.tokens, context), frame.width, context.fitter)
    val legendTop = grid.bottom + GridToLegendPx
    val todayIndex = calendar.today.filter(date => YearMonth.from(date) == calendar.month).map(_.getDayOfMonth - 1)
    val nodes = frame.header(label.getOrElse(copy.calendarTitle), copy.monthYear(calendar.month))
      ++ weekdays(calendar.weekStart, frame, context)
      ++ classes.zipWithIndex.flatMap { (dayClass, index) =>
        val origin = grid.origin(offset + index)
        day(dayClass, index + 1, Box(origin.x, origin.y, grid.cellPx, grid.cellPx), grid.numberBelowPx, frame)
      }
      ++ todayIndex.map(index => todayOutline(grid, offset + index, frame))
      ++ legend.nodes(Point(frame.left, legendTop), frame, context.fitter)
      ++ facts(calendar, legendTop + legend.height + LegendToFactsPx, frame, context)
      :+ frame.footer
    val text = TextEquivalent.of(copy.calendarText(lines(calendar, classes, label)), copy.calendarDiscreet, context)
    CardOutput(
      frame.scene(TemplateId, nodes),
      text,
      "dosegram-medication-month.png",
      context.privacy == Privacy.Discreet
    )

  // Taken and resolved doses over the month.
  private def totals(calendar: CalendarData): (Int, Int) =
    calendar.days.foldLeft((0, 0)) {
      case ((taken, resolved), DayAdherence.Resolved(dayTaken, dayResolved, _)) =>
        (taken + dayTaken, resolved + dayResolved)
      case (sums, _) => sums
    }

  private def filterLabel(calendar: CalendarData, context: CardContext): Option[String] =
    calendar.medicationName.map(name => if context.privacy.showsNames then name else context.copy.itemLabel(1))

  private def dayClass(day: DayAdherence): Option[DayClass] =
    day match
      case DayAdherence.Resolved(taken, resolved, hasMissed) if resolved > 0 =>
        Some(
          if taken == resolved then DayClass.AllTaken
          else if taken * 4 >= resolved * 3 then DayClass.Most
          else if taken * 2 >= resolved then DayClass.Some
          else if taken > 0 then DayClass.Few
          else if hasMissed then DayClass.NotConfirmed
          else DayClass.Skipped
        )
      case _: DayAdherence.Resolved      => None
      case DayAdherence.NotKnown         => Some(DayClass.NoRecord)
      case DayAdherence.Open             => Some(DayClass.StillOpen)
      case DayAdherence.NothingScheduled => None

  private def cell(dayClass: DayClass, box: Box, tokens: Tokens): Node =
    def heat(step: Int): Node =
      val square = Node.filledRect(box, box.width * CornerShare, tokens.spruceHeat(step))
      Node.Mark(MarkMeta(s"calendar.$dayClass", Set(Channel.Colour, Channel.Label)), Vector(square))
    dayClass match
      case DayClass.AllTaken     => heat(3)
      case DayClass.Most         => heat(2)
      case DayClass.Some         => heat(1)
      case DayClass.Few          => heat(0)
      case DayClass.Skipped      => DoseGlyph.of(DoseStatus.Skipped, box, tokens)
      case DayClass.NotConfirmed => DoseGlyph.of(DoseStatus.Missed, box, tokens)
      case DayClass.NoRecord     => DoseGlyph.of(DoseStatus.Unknown, box, tokens)
      case DayClass.StillOpen    => DoseGlyph.of(DoseStatus.Open, box, tokens)

  private def day(
      dayClass: Option[DayClass],
      number: Int,
      box: Box,
      numberBelowPx: Double,
      frame: CardFrame
  ): Vector[Node] =
    val numberLook = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    dayClass.map(cell(_, box, frame.tokens)).toVector
      :+ frame.text(number.toString, Point(box.center.x, box.bottom + numberBelowPx), numberLook, SlotPx)

  // Today is marked by an ink outline around its cell.
  private def todayOutline(grid: Grid, slot: Int, frame: CardFrame): Node =
    val box = Box(grid.origin(slot).x, grid.origin(slot).y, grid.cellPx, grid.cellPx).inset(-5)
    Node.outlinedRect(box, grid.cellPx * CornerShare + 5, Stroke.butt(frame.tokens.ink, 4))

  private def weekdays(weekStart: DayOfWeek, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    (0 until 7).toVector.map { column =>
      val name = context.copy.weekdayShort(weekStart.plus(column))
      frame.text(name, Point(GridLeft + (column + 0.5) * SlotPx, WeekdayBaseline), look, SlotPx)
    }

  // One entry per class present, in the fixed order of the class list.
  private def legendItems(present: Vector[DayClass], tokens: Tokens, context: CardContext): Vector[LegendItem] =
    DayClass.values.toVector.filter(present.contains).map { dayClass =>
      LegendItem(box => cell(dayClass, box, tokens), context.copy.dayClassName(dayClass))
    }

  // The fact lines: the total, and the doses that are not counted.
  private def facts(
      calendar: CalendarData,
      firstBaseline: Double,
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    val (taken, resolved) = totals(calendar)
    val total = if resolved == 0 then context.copy.noData else context.copy.resolvedDosesTaken(taken, resolved)
    val notCounted =
      Option.when(calendar.unknownDoseCount > 0)(context.copy.unknownDosesNotCounted(calendar.unknownDoseCount))
    (total +: notCounted.toVector).zipWithIndex.map { (line, index) =>
      val look = TextLook(TextStyle.Label, frame.tokens.ink, Align.Start, TextKind.Value)
      frame.text(line, Point(frame.left, firstBaseline + index * FactLinePx), look, frame.width)
    }

  private def lines(calendar: CalendarData, classes: Vector[Option[DayClass]], label: Option[String]): CalendarLines =
    val (taken, resolved) = totals(calendar)
    val dayCounts = DayClass.values.toVector
      .map(dayClass => dayClass -> classes.flatten.count(_ == dayClass))
      .filter(_._2 > 0)
    CalendarLines(
      calendar.month,
      label,
      dayCounts,
      classes.count(_.isEmpty),
      taken,
      resolved,
      calendar.unknownDoseCount
    )
