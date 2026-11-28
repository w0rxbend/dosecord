package dosegram.render.card

import dosegram.render.chart.Bars
import dosegram.render.chart.DoseGlyph
import dosegram.render.copy.GoalDays
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

import java.time.YearMonth

/** IMG-05 page 2: the adherence calendar and the mood calendar of the month; a domain without data is `None`. */
final case class MonthDaysData(month: YearMonth, medication: Option[CalendarData], mood: Option[MoodCalendarData]):
  require(medication.forall(_.month == month) && mood.forall(_.month == month), "every panel shows the page's month")
  def isEmpty: Boolean = medication.isEmpty && mood.forall(_.checkInDays == 0)

/** The meals of one day of the month: the distinct main meals logged and the snacks (VISUALS section 7.5). */
final case class MonthFoodDay(mainMeals: Int, snacks: Int)

/** The water of one day of the month against the goal in force that day, if the user had one. */
final case class MonthWaterDay(volumeMl: Int, goalMl: Option[Int])

/** IMG-05 page 3: the month's nights on the clock and its water by day. `sleep` covers the days of the month; `water`
  * has one entry per day of the month, `None` on a day without an entry.
  */
final case class MonthNightsData(
    month: YearMonth,
    sleep: Option[SleepRegularityData],
    water: Option[Vector[Option[MonthWaterDay]]],
    food: Option[Vector[Option[MonthFoodDay]]] = None
):
  require(water.forall(_.size == month.lengthOfMonth), "water has one entry per day of the month")
  require(food.forall(_.size == month.lengthOfMonth), "food has one entry per day of the month")
  def isEmpty: Boolean =
    sleep.forall(_.bars.isEmpty) && water.forall(_.forall(_.isEmpty)) && food.forall(_.forall(_.isEmpty))

/** Pages 2 and 3 of the monthly report (VISUALS section 6.6). Page 2, `card.month.days.v1`, is the adherence calendar
  * (IMG-06) and the mood calendar (IMG-11) as two panels; page 3, `card.month.nights.v1`, is the month's nights on the
  * clock (IMG-12) and the water of each day against the user's own goal. Each panel keeps its own scale (VA-IMG-04c)
  * and names its marks (VA-ID-07). A domain without data has no panel; a page without any panel is not sent.
  */
object MonthPages:
  val DaysTemplateId = "card.month.days.v1"
  val NightsTemplateId = "card.month.nights.v1"
  private val BodyTop = 160.0
  private val SubtitleBaselinePx = 44.0
  private val SubtitleToPanelPx = 60.0
  private val PanelPadPx = 24.0
  private val PanelRadius = 40.0
  private val PanelToFactPx = 52.0
  private val FactLinePx = 48.0
  private val SectionGapPx = 24.0
  private val WeekdayRowPx = 44.0
  private val CalendarCellPx = DoseGlyph.MinimumSidePx

  /** The grids' spacing: a month that spans six weeks draws its two grids tighter, so both panels fit one page. */
  final private case class Spacing(gapPx: Double, moodCellPx: Double)

  private val Regular = Spacing(8, 44)
  private val Tight = Spacing(4, 36)
  private val LegendGapPx = 20.0
  private val SleepPlotPx = 236.0
  private val WaterPlotPx = 200.0
  private val FoodPlotPx = 112.0
  private val SnackRoomPx = 22.0
  private val MaxMealDots = 3
  private val MealDotPx = 9.0
  private val AxisColumnPx = 96.0
  private val DayMinutes = 1440
  private val AxisStartMinute = 18 * 60
  private val MaximumStretchPx = 320.0
  private val FoodStretchPx = 64.0

  /** One part of a page: its subtitle, the height of its panel, how to draw the panel's contents in the inner box, and
    * the fact lines under the panel.
    */
  final private case class Section(
      title: String,
      panelPx: Double,
      draw: (Box, CardFrame) => Vector[Node],
      facts: Vector[String]
  ):
    def heightPx: Double =
      SubtitleToPanelPx + panelPx + (if facts.isEmpty then 0.0 else PanelToFactPx + (facts.size - 1) * FactLinePx)

  def days(page: MonthDaysData, context: CardContext): CardOutput =
    val copy = context.copy
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val sixWeeks = MonthGrid(
      page.month,
      page.medication.map(_.weekStart).orElse(page.mood.map(_.weekStart)).getOrElse(java.time.DayOfWeek.MONDAY),
      0,
      0,
      0,
      0,
      0,
      0
    ).weeks == 6
    val spacing = if sixWeeks then Tight else Regular
    val sections = Vector(
      page.medication.map(calendarSection(_, spacing, frame, context)),
      page.mood.filter(_.checkInDays > 0).map(moodSection(_, spacing, context))
    ).flatten
    val nodes = frame.header(copy.monthDaysTitle, copy.monthYear(page.month)) ++ stack(sections, frame) :+ frame.footer
    val text = TextEquivalent.of(copy.monthDaysText(page), copy.monthDiscreet, context)
    CardOutput(frame.scene(DaysTemplateId, nodes), text, "dosegram-month-days.png", context.privacy == Privacy.Discreet)

  /** At most two panels fit the page: the food bars are drawn when the sleep or the water panel is absent, and food
    * keeps its row on page 1 either way (supervisor decision, 2026-11-14).
    */
  def nights(full: MonthNightsData, context: CardContext): CardOutput =
    val hasSleep = full.sleep.exists(_.bars.nonEmpty)
    val hasWater = full.water.exists(_.exists(_.isDefined))
    val page = if hasSleep && hasWater then full.copy(food = None) else full
    val copy = context.copy
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    def build(extraPx: Double): Vector[Section] = Vector(
      page.sleep.filter(_.bars.nonEmpty).map(sleepSection(_, frame, context, extraPx)),
      page.water.filter(_.exists(_.isDefined)).map(waterSection(page.month, _, frame, context, extraPx)),
      page.food.filter(_.exists(_.isDefined)).map(foodSection(page.month, _, frame, context, extraPx))
    ).flatten
    // The plots grow into the room the page has left, so one panel does not leave the page half empty; the pages of
    // one album keep the portrait size (VISUALS section 5.3).
    val natural = build(0)
    val used = natural.map(_.heightPx).sum + (natural.size - 1) * SectionGapPx
    val spare = frame.bodyBottom - BodyTop - used
    val extra = StrictMath.min(MaximumStretchPx, StrictMath.floor(spare / natural.size.max(1) / 4) * 4).max(0.0)
    val sections = build(extra)
    val title =
      if page.food.exists(_.exists(_.isDefined)) then copy.monthNightsFoodTitle(hasSleep, hasWater)
      else copy.monthNightsTitle
    val nodes = frame.header(title, copy.monthYear(page.month)) ++ stack(sections, frame) :+ frame.footer
    val text = TextEquivalent.of(copy.monthNightsText(page), copy.monthDiscreet, context)
    CardOutput(
      frame.scene(NightsTemplateId, nodes),
      text,
      "dosegram-month-nights.png",
      context.privacy == Privacy.Discreet
    )

  // The sections one under the other, each as tall as its content; the room a short page leaves is shared above and
  // below them, as on the other cards.
  private def stack(sections: Vector[Section], frame: CardFrame): Vector[Node] =
    val used = sections.map(_.heightPx).sum + (sections.size - 1).max(0) * SectionGapPx
    val shift = frame.balance(BodyTop, BodyTop + used)
    val tops = sections.scanLeft(BodyTop + shift)((top, section) => top + section.heightPx + SectionGapPx)
    sections.zip(tops).flatMap { (section, top) =>
      val panel = Box(frame.left, top + SubtitleToPanelPx, frame.width, section.panelPx)
      val subtitle = frame.text(
        section.title,
        Point(frame.left, top + SubtitleBaselinePx),
        TextLook(TextStyle.BodyStrong, frame.tokens.ink, Align.Start, TextKind.Label),
        frame.width
      )
      val facts = section.facts.zipWithIndex.map { (fact, line) =>
        val look = TextLook(TextStyle.Label, frame.tokens.ink, Align.Start, TextKind.Value)
        frame.text(fact, Point(frame.left, panel.bottom + PanelToFactPx + line * FactLinePx), look, frame.width)
      }
      Vector(subtitle, Node.filledRect(panel, PanelRadius, frame.tokens.panel)) ++
        section.draw(panel.inset(PanelPadPx), frame) ++ facts
    }

  private def grid(month: YearMonth, weekStart: java.time.DayOfWeek, box: Box, cellPx: Double, gapPx: Double) =
    MonthGrid(month, weekStart, box.x, box.y + WeekdayRowPx, cellPx + gapPx, cellPx, 0, gapPx)

  private def calendarSection(
      calendar: CalendarData,
      spacing: Spacing,
      frame: CardFrame,
      context: CardContext
  ): Section =
    val copy = context.copy
    val classes = calendar.days.map(AdherenceCalendarCard.dayClass)
    val (taken, resolved) = AdherenceCalendarCard.totals(calendar)
    val total = if resolved == 0 then copy.noData else copy.resolvedDosesTaken(taken, resolved)
    val unknown = Option.when(calendar.unknownDoseCount > 0)(copy.unknownDosesNotCounted(calendar.unknownDoseCount))
    val inner = frame.width - 2 * PanelPadPx
    val legend = Legend.flow(AdherenceCalendarCard.legendItems(classes, frame.tokens, context), inner, context.fitter)
    val probe = grid(calendar.month, calendar.weekStart, Box(0, 0, 0, 0), CalendarCellPx, spacing.gapPx)
    val panelPx = 2 * PanelPadPx + WeekdayRowPx + (probe.bottom - probe.top) + LegendGapPx + legend.height - 12
    def draw(box: Box, frame: CardFrame): Vector[Node] =
      val cells = grid(calendar.month, calendar.weekStart, box, CalendarCellPx, spacing.gapPx)
      val days = classes.zipWithIndex.map { (dayClass, index) =>
        val cell = cells.cell(index + 1)
        dayClass.fold(AdherenceCalendarCard.nothingScheduled(cell, frame.tokens))(
          AdherenceCalendarCard.cell(_, cell, frame.tokens)
        )
      }
      // The totals sit beside the grid, each wrapped to two lines at most.
      val factsLeft = cells.right + 48
      val factLines = (total +: unknown.toVector).flatMap(fact =>
        context.fitter.twoLines(fact, TextStyle.Label, box.right - factsLeft).lines
      )
      val facts = factLines.zipWithIndex.map { (line, index) =>
        Node.Text(
          line,
          Point(factsLeft, cells.top + 32 + index * FactLinePx),
          Align.Start,
          TextStyle.Label,
          frame.tokens.ink,
          TextKind.Value
        )
      }
      MonthGrid.weekdays(cells, box.y + 28, frame, context) ++ days ++ facts ++
        legend.nodes(Point(box.x, cells.bottom + LegendGapPx), frame, context.fitter)
    Section(copy.calendarTitle, panelPx, draw, Vector.empty)

  private def moodSection(calendar: MoodCalendarData, spacing: Spacing, context: CardContext): Section =
    val probe = grid(calendar.month, calendar.weekStart, Box(0, 0, 0, 0), spacing.moodCellPx, spacing.gapPx)
    val panelPx =
      2 * PanelPadPx + StrictMath.max(WeekdayRowPx + probe.bottom - probe.top, MoodCalendarCard.panelLegendPx(calendar))
    Section(
      context.copy.moodCalendarTitle,
      panelPx,
      (box, frame) =>
        val cells = grid(calendar.month, calendar.weekStart, box, spacing.moodCellPx, spacing.gapPx)
        MonthGrid.weekdays(cells, box.y + 28, frame, context) ++
          MoodCalendarCard.panelDays(calendar, cells, Point(cells.right + 48, box.y + 8), box.right, frame, context)
      ,
      Vector.empty
    )

  /** The month's nights as columns: x is the day, y the clock time from the earliest bedtime to the latest wake time of
    * the month, so the plot shows the hours that hold nights.
    */
  private def sleepSection(
      sleep: SleepRegularityData,
      frame: CardFrame,
      context: CardContext,
      extraPx: Double
  ): Section =
    val plotPx = SleepPlotPx + extraPx
    val copy = context.copy
    val days = (sleep.to.toEpochDay - sleep.from.toEpochDay).toInt + 1
    val stepMinutes = 180
    val first = StrictMath.floorDiv(sleep.bars.map(_.fromMinute).min, stepMinutes) * stepMinutes
    val last = StrictMath.min(DayMinutes, ceilTo(sleep.bars.map(_.toMinute).max, stepMinutes))
    val legend = Legend.flow(sleepLegend(sleep, frame.tokens, context), frame.width - 2 * PanelPadPx, context.fitter)
    def draw(box: Box, frame: CardFrame): Vector[Node] =
      val tokens = frame.tokens
      val left = box.x + AxisColumnPx
      val slot = (box.right - left) / days
      val plotTop = box.y + 12
      def y(minute: Int): Double = plotTop + plotPx * (minute - first) / (last - first).toDouble
      // A grid line every three hours, a label on every other one so the labels keep their room.
      val axis = (first to last by stepMinutes).toVector.zipWithIndex.flatMap { (minute, index) =>
        val hour = (AxisStartMinute + minute) / 60 % 24
        val line = Node.Line(Point(left, y(minute)), Point(box.right, y(minute)), Stroke.solid(tokens.grid, 4))
        val label = Option.when(index % 2 == 0)(
          frame.text(
            copy.sleepAxisHour(hour),
            Point(box.x, y(minute) + 14),
            TextLook(TextStyle.Label, tokens.ink3, Align.Start, TextKind.Axis),
            AxisColumnPx - 8
          )
        )
        line +: label.toVector
      }
      val bars = sleep.bars.map { bar =>
        val index = (bar.date.toEpochDay - sleep.from.toEpochDay).toInt
        val width = StrictMath.max(8.0, slot * (if bar.isNap then 0.4 else 0.64))
        val top = y(bar.fromMinute)
        val box = Box(left + index * slot + (slot - width) / 2, top, width, StrictMath.max(12.0, y(bar.toMinute) - top))
        // A nap is an outline, so it is told from a night by its shape as well as its width.
        val shape =
          if bar.isNap then Node.outlinedRect(box.inset(2), width / 2, Stroke.solid(tokens.dusk, 4))
          else Node.filledRect(box, width / 2, tokens.dusk)
        val role = if bar.isNap then "month.sleep.nap" else "month.sleep.night"
        Node.Mark(MarkMeta(role, Set(Channel.Colour, Channel.Position, Channel.Shape)), Vector(shape))
      }
      val typical = (sleep.typicalBedtime.toVector ++ sleep.typicalWake.toVector).map { clock =>
        val at = y(Math.floorMod(clock - AxisStartMinute, DayMinutes))
        Node.Mark(
          MarkMeta("month.sleep.typical", Set(Channel.Pattern, Channel.Position)),
          Vector(Node.Line(Point(left, at), Point(box.right, at), Stroke.dotted(tokens.ink2)))
        )
      }
      // The dotted typical times run behind the nights, seen between them: `ink2` over `dusk` keeps under 2:1 in
      // greyscale (the greyscale check of ROADMAP G4.7, 2026-11-28).
      axis ++ typical ++ bars ++ legend.nodes(Point(box.x, plotTop + plotPx + LegendGapPx), frame, context.fitter)
    val average = sleep.averageMinutes.map(copy.sleepAverageOf(_, sleep.nights))
    val spread = for
      bedtime <- sleep.bedtimeSpreadMinutes
      wake <- sleep.wakeSpreadMinutes
    yield copy.sleepSpread(bedtime, wake)
    val facts = (average.toVector ++ spread).padTo(1, copy.sleepNights(sleep.nights))
    Section(copy.sleepTitle, 2 * PanelPadPx + 12 + plotPx + LegendGapPx + legend.height - 12, draw, facts)

  // One entry per kind of mark the plot draws: a night, a nap, the dotted typical times.
  private def sleepLegend(sleep: SleepRegularityData, tokens: Tokens, context: CardContext): Vector[LegendItem] =
    def bar(widthPx: Double)(box: Box): Node =
      Node.Mark(
        MarkMeta("legend.month.sleep", Set(Channel.Colour, Channel.Shape)),
        Vector(Node.filledRect(Box(box.center.x - widthPx / 2, box.y, widthPx, box.height), widthPx / 2, tokens.dusk))
      )
    def napOutline(box: Box): Node =
      Node.Mark(
        MarkMeta("legend.month.nap", Set(Channel.Colour, Channel.Shape)),
        Vector(
          Node.outlinedRect(Box(box.center.x - 8, box.y + 6, 16, box.height - 12), 8, Stroke.solid(tokens.dusk, 4))
        )
      )
    val night = Option.when(sleep.bars.exists(!_.isNap))(LegendItem.of(bar(16), context.copy.sleepEntriesMark))
    val nap = Option.when(sleep.bars.exists(_.isNap))(LegendItem.of(napOutline, context.copy.napMark))
    val typical = Option.when(sleep.typicalBedtime.isDefined || sleep.typicalWake.isDefined) {
      LegendItem.of(
        box =>
          Node.Mark(
            MarkMeta("legend.month.typical", Set(Channel.Pattern)),
            Vector(Node.Line(Point(box.x, box.center.y), Point(box.right, box.center.y), Stroke.dotted(tokens.ink2)))
          ),
        context.copy.sleepTypicalMark
      )
    }
    Vector(night, nap, typical).flatten

  /** One bar a day on the month's own scale; the user's own goal of that day is a short line across the day's slot. */
  private def waterSection(
      month: YearMonth,
      water: Vector[Option[MonthWaterDay]],
      frame: CardFrame,
      context: CardContext,
      extraPx: Double
  ): Section =
    val copy = context.copy
    val plotPx = WaterPlotPx + extraPx
    val scaleMl = (water.flatten.flatMap(day => day.volumeMl +: day.goalMl.toVector) :+ 1).max.toDouble
    val hasGoal = water.flatten.exists(_.goalMl.isDefined)
    val legend = Legend.flow(waterLegend(hasGoal, frame.tokens, context), frame.width - 2 * PanelPadPx, context.fitter)
    def draw(box: Box, frame: CardFrame): Vector[Node] =
      val tokens = frame.tokens
      val slot = box.width / water.size
      val ticksBaseline = box.y + 28
      val plotTop = box.y + WeekdayRowPx
      val baseline = plotTop + plotPx
      def height(ml: Int): Double = plotPx * ml / scaleMl
      val ticks = (1 to month.lengthOfMonth).filter(day => day % 7 == 1).toVector.map { day =>
        frame.text(
          day.toString,
          Point(box.x + (day - 0.5) * slot, ticksBaseline),
          TextLook(TextStyle.Label, tokens.ink3, Align.Middle, TextKind.Axis),
          slot * 6
        )
      }
      val bars = water.zipWithIndex.flatMap { (entry, index) =>
        val slotLeft = box.x + index * slot
        entry.toVector.flatMap { day =>
          val barWidth = StrictMath.max(8.0, slot * 0.6)
          val barHeight = StrictMath.max(8.0, height(day.volumeMl))
          val bar = Node.Mark(
            MarkMeta("month.water.day", Set(Channel.Colour, Channel.Position)),
            Vector(
              Bars.upright(
                Box(slotLeft + (slot - barWidth) / 2, baseline - barHeight, barWidth, barHeight),
                tokens.tide
              )
            )
          )
          val goal = day.goalMl.map { ml =>
            val at = baseline - height(ml)
            Node.Mark(
              MarkMeta("month.water.goal", Set(Channel.Pattern, Channel.Position)),
              Vector(Node.Line(Point(slotLeft + 2, at), Point(slotLeft + slot - 2, at), Stroke.butt(tokens.ink, 4)))
            )
          }
          // The goal tick runs behind the bar and shows beside it: `ink` over `tide` keeps under 3:1 in greyscale (the
          // greyscale check of ROADMAP G4.7, 2026-11-28).
          goal.toVector :+ bar
        }
      }
      ticks ++ bars ++ legend.nodes(Point(box.x, baseline + LegendGapPx), frame, context.fitter)
    val recorded = water.flatten
    val withGoal = recorded.filter(_.goalMl.isDefined)
    val goalDays = Option.when(withGoal.nonEmpty)(
      GoalDays(withGoal.count(day => day.goalMl.exists(day.volumeMl >= _)), withGoal.size)
    )
    val panelPx = 2 * PanelPadPx + WeekdayRowPx + plotPx + LegendGapPx + legend.height - 12
    Section(copy.waterTitle, panelPx, draw, Vector(copy.monthWaterFact(goalDays, recorded.size)))

  private def waterLegend(hasGoal: Boolean, tokens: Tokens, context: CardContext): Vector[LegendItem] =
    val day = LegendItem.of(
      box =>
        Node.Mark(
          MarkMeta("legend.month.water", Set(Channel.Colour, Channel.Shape)),
          Vector(Bars.upright(Box(box.center.x - 10, box.y + 8, 20, box.height - 8), tokens.tide))
        ),
      context.copy.waterLoggedMark
    )
    val goal = Option.when(hasGoal) {
      LegendItem.of(
        box =>
          Node.Mark(
            MarkMeta("legend.month.goal", Set(Channel.Pattern)),
            Vector(Node.Line(Point(box.x, box.center.y), Point(box.right, box.center.y), Stroke.butt(tokens.ink, 4)))
          ),
        context.copy.yourGoal
      )
    }
    day +: goal.toVector

  /** One column of dots a day, one per main meal logged (up to three), a small dot above for snacks: presence, not an
    * amount (VISUALS section 6.5, the food strip of IMG-04).
    */
  private def foodSection(
      month: YearMonth,
      food: Vector[Option[MonthFoodDay]],
      frame: CardFrame,
      context: CardContext,
      extraPx: Double
  ): Section =
    val copy = context.copy
    // Dots are presence, not an amount: they gain a little room and never spread over a tall plot.
    val plotPx = FoodPlotPx + StrictMath.min(extraPx, FoodStretchPx)
    val legend = Legend.flow(foodLegend(food, frame.tokens, context), frame.width - 2 * PanelPadPx, context.fitter)
    def draw(box: Box, frame: CardFrame): Vector[Node] =
      val tokens = frame.tokens
      val slot = box.width / food.size
      val ticksBaseline = box.y + 28
      val plotTop = box.y + WeekdayRowPx
      val baseline = plotTop + plotPx
      val radius = StrictMath.min(MealDotPx, slot * 0.3)
      val pitch = (plotPx - SnackRoomPx) / MaxMealDots
      val ticks = (1 to month.lengthOfMonth).filter(day => day % 7 == 1).toVector.map { day =>
        frame.text(
          day.toString,
          Point(box.x + (day - 0.5) * slot, ticksBaseline),
          TextLook(TextStyle.Label, tokens.ink3, Align.Middle, TextKind.Axis),
          slot * 6
        )
      }
      val dots = food.zipWithIndex.flatMap { (entry, index) =>
        val x = box.x + (index + 0.5) * slot
        entry.toVector.map { day =>
          val meals = (0 until StrictMath.min(MaxMealDots, day.mainMeals)).toVector.map { row =>
            Node.filledCircle(Point(x, baseline - pitch * (row + 0.5)), radius, tokens.rye)
          }
          val snack = Option.when(day.snacks > 0)(
            Node.Circle(Point(x, plotTop + SnackRoomPx / 2), radius * 0.6, None, Some(Stroke.butt(tokens.rye, 4)))
          )
          Node.Mark(MarkMeta("month.food.day", Set(Channel.Colour, Channel.Position)), meals ++ snack.toVector)
        }
      }
      ticks ++ dots ++ legend.nodes(Point(box.x, baseline + LegendGapPx), frame, context.fitter)
    val recorded = food.flatten
    val meals = MonthMeals(recorded.map(_.mainMeals).sum, recorded.map(_.snacks).sum)
    val panelPx = 2 * PanelPadPx + WeekdayRowPx + plotPx + LegendGapPx + legend.height - 12
    Section(copy.foodTitle, panelPx, draw, Vector(copy.monthFoodFact(meals, recorded.size)))

  private def foodLegend(food: Vector[Option[MonthFoodDay]], tokens: Tokens, context: CardContext): Vector[LegendItem] =
    val meal = LegendItem.of(
      box =>
        Node.Mark(
          MarkMeta("legend.month.food", Set(Channel.Colour, Channel.Shape)),
          Vector(Node.filledCircle(box.center, 10, tokens.rye))
        ),
      context.copy.mealLoggedMark
    )
    val snack = Option.when(food.flatten.exists(_.snacks > 0)) {
      LegendItem.of(
        box =>
          Node.Mark(
            MarkMeta("legend.month.snack", Set(Channel.Shape)),
            Vector(Node.Circle(box.center, 7, None, Some(Stroke.butt(tokens.rye, 4))))
          ),
        context.copy.snacksLabel
      )
    }
    meal +: snack.toVector

  private def ceilTo(value: Int, step: Int): Int = StrictMath.floorDiv(value + step - 1, step) * step
end MonthPages
