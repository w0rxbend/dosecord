package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.MoodState
import dosegram.render.chart.Bars
import dosegram.render.chart.DoseColumn
import dosegram.render.chart.DoseGlyph
import dosegram.render.chart.DoseStatus
import dosegram.render.chart.Hatch
import dosegram.render.chart.Icons
import dosegram.render.copy.GoalDays
import dosegram.render.copy.SleepWeek
import dosegram.render.copy.WeekSummary
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

/** IMG-04, `card.week.v1`: seven days in small multiples, one row per tracked domain, each with one headline and a
  * seven-day strip on its own scale (VA-IMG-04c, VA-CO-06). A day without a record is a dotted slot, never a zero
  * (VA-PR-03); a tracked domain without records is one line without a strip (VA-IMG-04a).
  */
object WeekCard:
  val TemplateId = "card.week.v1"
  private val DayCount = 7
  private val WeekdayBaseline = 184.0
  private val RowsTop = 212.0
  private val HeadlinePx = 64.0
  // Each strip is as tall as its own marks need, so five domains and the dose legend fit one card.
  private val GlyphStripPx = 100.0
  private val MealStripPx = 76.0
  private val SleepStripPx = 72.0
  private val EmptyRowPx = 76.0
  private val WeekdayHeightPx = 32.0
  private val StripToLegendPx = 8.0
  // The legend of the shared marks sits closer under the last row than a row gap.
  private val MarksLegendRisePx = 8.0
  private val MaximumRowGapPx = 44.0
  private val TextLeft = 136.0
  private val CellPx = DoseGlyph.MinimumSidePx
  private val BarWidthPx = 56.0
  private val BarHeightPx = 96.0
  private val SleepBarPx = Box(0, 0, 112, 32)
  private val MealDotPx = 14.0
  private val MiniBlobPx = 40.0

  /** A domain row: its headline, when the week has records how to draw its strip in a day slot, and the entries of the
    * legend under the strip.
    */
  final private case class Row(
      domain: Domain,
      headline: String,
      strip: Option[Strip],
      legendItems: Vector[LegendItem] = Vector.empty
  ):
    def stripBottom: Double = strip.fold(EmptyRowPx)(HeadlinePx + _.heightPx)

    // A domain without records states that quietly: regular weight and secondary ink, not the weight of a value.
    def headlineLook(frame: CardFrame): TextLook =
      if strip.isDefined then TextLook(TextStyle.BodyStrong, frame.tokens.ink, Align.End, TextKind.Value)
      else TextLook(TextStyle.Body, frame.tokens.ink2, Align.End, TextKind.Label)

  /** The seven-day strip of a domain: its height, and how to draw one day in its slot. */
  final private case class Strip(heightPx: Double, draw: (Int, Box) => Vector[Node])

  def render(week: WeekData, context: CardContext): CardOutput =
    val copy = context.copy
    val summary = summarise(week)
    val rows = Vector(
      medication(week, summary, context),
      water(week, summary, context),
      food(week, summary, context),
      mood(week, summary, context),
      sleep(week, summary, context)
    ).flatten
    val marks = marksLegend(week, rows, context)
    val probe = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val heights = blockHeights(rows, marks, probe, context)
    // A week whose rows fit the square at the widest gap collapses to it.
    val naturalEnd = RowsTop + heights.sum + (heights.size - 1) * MaximumRowGapPx
    val frame = CardFrame(context, CardFrame.collapsing(naturalEnd), context.tokens.surface)
    val nodes = frame.header(copy.weekTitle, copy.dateRange(summary.from, summary.to))
      ++ body(week, rows, marks, frame, context)
      :+ frame.footer
    val text = TextEquivalent.of(copy.weekText(summary), copy.weekDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-week.png", context.privacy == Privacy.Discreet)

  /** The week's figures, shared by the image headlines and the text equivalent (VA-PR-01). */
  def summarise(week: WeekData): WeekSummary =
    val sleep = week.sleep.map { nights =>
      val recorded = nights.collect { case SleepEvidence.Recorded(minutes, _) => minutes }
      SleepWeek(
        Option.when(recorded.nonEmpty)(recorded.sum / recorded.size),
        recorded.size,
        nights.count(_ == SleepEvidence.QuietWindowOnly)
      )
    }
    WeekSummary(
      week.from,
      week.from.plusDays(DayCount - 1L),
      week.medication.map(days => DoseCounts.of(days.flatten)),
      week.water.map(days => GoalDays(days.flatten.count(_.isGoalReached), days.flatten.size)),
      week.food.map(_.flatten.map(_.mainMeals).sum),
      week.mood.map(_.flatten.size),
      sleep
    )

  private def medication(week: WeekData, summary: WeekSummary, context: CardContext): Option[Row] =
    for
      days <- week.medication
      counts <- summary.doses
    yield
      if counts.counted == 0 then Row(Domain.Medication, context.copy.noRecordsThisWeek, None)
      else
        Row(
          Domain.Medication,
          context.copy.weekDoses(counts),
          Some(Strip(GlyphStripPx, (day, slot) => doses(days(day), slot, context.tokens))),
          doseLegend(days.flatten, context)
        )

  private def water(week: WeekData, summary: WeekSummary, context: CardContext): Option[Row] =
    for
      days <- week.water
      goalDays <- summary.water
    yield
      if goalDays.recorded == 0 then Row(Domain.Water, context.copy.noRecordsThisWeek, None)
      else
        val scaleMl = days.flatten.map(progress => StrictMath.max(progress.volumeMl, progress.goalMl)).max
        Row(
          Domain.Water,
          context.copy.goalReachedDays(goalDays),
          Some(Strip(GlyphStripPx, (day, slot) => waterBar(days(day), scaleMl, slot, context.tokens)))
        )

  private def food(week: WeekData, summary: WeekSummary, context: CardContext): Option[Row] =
    for
      days <- week.food
      meals <- summary.mealsLogged
    yield
      if days.flatten.isEmpty then Row(Domain.Food, context.copy.noRecordsThisWeek, None)
      else
        Row(
          Domain.Food,
          context.copy.mealsLogged(meals),
          Some(Strip(MealStripPx, (day, slot) => mealDots(days(day), slot, context.tokens)))
        )

  private def mood(week: WeekData, summary: WeekSummary, context: CardContext): Option[Row] =
    for
      days <- week.mood
      checkIns <- summary.checkInDays
    yield
      if checkIns == 0 then Row(Domain.Mood, context.copy.noRecordsThisWeek, None)
      else
        Row(
          Domain.Mood,
          context.copy.checkInDays(checkIns),
          Some(Strip(GlyphStripPx, (day, slot) => miniBlob(days(day), slot, context.tokens)))
        )

  private def sleep(week: WeekData, summary: WeekSummary, context: CardContext): Option[Row] =
    for
      nights <- week.sleep
      sleepWeek <- summary.sleep
    yield
      if sleepWeek.recordedNights == 0 && sleepWeek.estimateNights == 0 then
        Row(Domain.Sleep, context.copy.noRecordsThisWeek, None)
      else
        val scaleMinutes =
          (nights.collect { case SleepEvidence.Recorded(minutes, goal) => StrictMath.max(minutes, goal) } :+ 1).max
        val headline = sleepWeek.averageMinutes.map(context.copy.sleepAverage).getOrElse(context.copy.estimateOnly)
        val strip = Strip(SleepStripPx, (day, slot) => sleepBar(nights(day), scaleMinutes, slot, context.tokens))
        Row(Domain.Sleep, headline, Some(strip))

  // The weekday line, the rows, and the legend of the marks the rows share. Rows share the room down to the footer
  // with a capped gap; what a week with few domains leaves over is shared above and below the body.
  private def body(
      week: WeekData,
      rows: Vector[Row],
      marks: Vector[LegendItem],
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    val legends = rows.map(row => Legend.flow(row.legendItems, frame.width, context.fitter))
    val marksLegend = Legend.flow(marks, frame.width, context.fitter)
    val heights = blockHeights(rows, marks, frame, context)
    val gaps = StrictMath.max(1, heights.size - 1)
    val gap = StrictMath.min(MaximumRowGapPx, (frame.bodyBottom - RowsTop - heights.sum) / gaps)
    val natural = heights.scanLeft(RowsTop)((top, height) => top + height + gap)
    val shift = frame.balance(WeekdayBaseline - WeekdayHeightPx, natural.last - gap)
    val marksNodes = Option
      .when(marksLegend.rows.nonEmpty) {
        marksLegend.nodes(Point(frame.left, natural(rows.size) + shift - MarksLegendRisePx), frame, context.fitter)
      }
      .toVector
      .flatten
    weekdays(week, WeekdayBaseline + shift, frame, context) ++
      rows.zip(legends).zip(natural.map(_ + shift)).flatMap { case ((row, legend), top) =>
        drawRow(row, top, frame, context) ++
          legend.nodes(Point(frame.left, top + row.stripBottom + StripToLegendPx), frame, context.fitter)
      } ++ marksNodes

  // The height of each row with its legend, and of the shared marks legend when there is one.
  private def blockHeights(
      rows: Vector[Row],
      marks: Vector[LegendItem],
      frame: CardFrame,
      context: CardContext
  ): Vector[Double] =
    val marksLegend = Legend.flow(marks, frame.width, context.fitter)
    rows.map { row =>
      val legend = Legend.flow(row.legendItems, frame.width, context.fitter)
      row.stripBottom + (if legend.rows.isEmpty then 0.0 else StripToLegendPx + legend.height)
    } ++ Option.when(marksLegend.rows.nonEmpty)(marksLegend.height - MarksLegendRisePx)

  // The marks of the water, food, mood and sleep strips are named once under the rows, each only when a strip draws
  // it: the dotted goal line, the dotted slot of a day without a record, the hatch of an estimate and the snack dot.
  private def marksLegend(week: WeekData, rows: Vector[Row], context: CardContext): Vector[LegendItem] =
    val tokens = context.tokens
    val copy = context.copy
    val drawn = rows.filter(_.strip.isDefined).map(_.domain).toSet
    val water = week.water.filter(_ => drawn(Domain.Water)).getOrElse(Vector.empty)
    val food = week.food.filter(_ => drawn(Domain.Food)).getOrElse(Vector.empty)
    val mood = week.mood.filter(_ => drawn(Domain.Mood)).getOrElse(Vector.empty)
    val nights = week.sleep.filter(_ => drawn(Domain.Sleep)).getOrElse(Vector.empty)
    val hasGoal = water.exists(_.isDefined) || nights.exists(_.isInstanceOf[SleepEvidence.Recorded])
    val hasGap =
      water.contains(None) || food.contains(None) || mood.contains(None) || nights.contains(SleepEvidence.NoRecord)
    val hasEstimate = nights.contains(SleepEvidence.QuietWindowOnly)
    val hasSnack = food.flatten.exists(_.snacks > 0)
    Vector(
      Option.when(hasGoal)(LegendItem.of(box => goalGlyph(box, tokens), copy.yourGoal)),
      Option.when(hasGap)(LegendItem.of(box => gapGlyph(box, tokens), copy.noRecordMark)),
      Option.when(hasEstimate)(LegendItem.of(box => estimateGlyph(box, tokens), copy.estimateMark)),
      Option.when(hasSnack)(LegendItem.of(box => snackGlyph(box, tokens), copy.snackMark))
    ).flatten

  private def goalGlyph(box: Box, tokens: Tokens): Node =
    val y = box.center.y
    Node.Mark(
      MarkMeta("legend.goal", Set(Channel.Pattern)),
      Vector(Node.Line(Point(box.x + 2, y), Point(box.right - 2, y), Stroke.dotted(tokens.ink3)))
    )

  private def gapGlyph(box: Box, tokens: Tokens): Node =
    emptySlot("legend.none", Node.outlinedRect(box.inset(4), 10, Stroke.dotted(tokens.ink3)))

  private def estimateGlyph(box: Box, tokens: Tokens): Node =
    val inner = box.inset(4)
    val outline = Node.outlinedRect(inner, 10, Stroke.butt(tokens.ink3, 4))
    Node.Mark(
      MarkMeta("legend.estimate", Set(Channel.Pattern)),
      outline +: Hatch.lines(inner.inset(4), Stroke.butt(tokens.ink3, 4))
    )

  private def snackGlyph(box: Box, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta("legend.snack", Set(Channel.Colour, Channel.Shape, Channel.Label)),
      Vector(Node.filledCircle(box.center, 8, tokens.rye))
    )

  private def drawRow(row: Row, top: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val nameLook = frame.look(TextStyle.BodyMedium, frame.tokens.ink)
    val slotWidth = frame.width / DayCount
    val strip = row.strip.toVector.flatMap { strip =>
      (0 until DayCount).flatMap { day =>
        strip.draw(day, Box(frame.left + day * slotWidth, top + HeadlinePx, slotWidth, strip.heightPx))
      }
    }
    Vector(
      Icons.of(row.domain, Point(frame.left, top), frame.tokens.hue(row.domain)),
      frame.text(context.copy.domainName(row.domain), Point(TextLeft, top + 42), nameLook, 280),
      frame.text(row.headline, Point(frame.right, top + 42), row.headlineLook(frame), frame.width - 380)
    ) ++ strip

  // The dose glyphs are named under their strip whenever the week shows more than one status (VA-ID-07).
  private def doseLegend(statuses: Vector[DoseStatus], context: CardContext): Vector[LegendItem] =
    val present = DoseStatus.values.toVector.filter(statuses.toSet)
    if present.size < 2 then Vector.empty
    else
      present.map { status =>
        LegendItem.of(box => DoseGlyph.of(status, box, context.tokens), context.copy.statusName(status))
      }

  private def weekdays(week: WeekData, baseline: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    val slotWidth = frame.width / DayCount
    (0 until DayCount).toVector.map { day =>
      val initial = context.copy.weekdayInitial(week.from.plusDays(day.toLong).getDayOfWeek)
      frame.text(initial, Point(frame.left + (day + 0.5) * slotWidth, baseline), look, slotWidth)
    }

  // Up to four doses as glyphs: one or two side by side, three or four in a two-by-two block; more as one block
  // split by status share.
  private def doses(statuses: Vector[DoseStatus], slot: Box, tokens: Tokens): Vector[Node] =
    val pitch = CellPx + DoseColumn.GapPx
    val block = 2 * CellPx + DoseColumn.GapPx
    if statuses.size > DoseColumn.MaximumGlyphs then
      Vector(
        DoseColumn
          .proportional(statuses, Box(slot.center.x - block / 2, slot.center.y - block / 2, block, block), tokens)
      )
    else
      val columns = StrictMath.min(2, statuses.size)
      val rows = (statuses.size + 1) / 2
      val left = slot.center.x - (columns * CellPx + (columns - 1) * DoseColumn.GapPx) / 2
      val top = slot.center.y - (rows * CellPx + (rows - 1) * DoseColumn.GapPx) / 2
      statuses.zipWithIndex.map { (status, index) =>
        DoseGlyph.of(status, Box(left + (index % 2) * pitch, top + (index / 2) * pitch, CellPx, CellPx), tokens)
      }

  private def waterBar(day: Option[WaterProgress], scaleMl: Int, slot: Box, tokens: Tokens): Vector[Node] =
    val column = Box(slot.center.x - BarWidthPx / 2, slot.bottom - BarHeightPx, BarWidthPx, BarHeightPx)
    day match
      case None => Vector(emptySlot("water.none", Node.outlinedRect(column.inset(2), 12, Stroke.dotted(tokens.ink3))))
      case Some(progress) =>
        val height = StrictMath.max(8.0, BarHeightPx * progress.volumeMl / scaleMl)
        val goalY = column.bottom - BarHeightPx * progress.goalMl / scaleMl
        val bar = Bars.upright(Box(column.x, column.bottom - height, BarWidthPx, height), tokens.tide)
        val goal = Node.Line(Point(column.x - 16, goalY), Point(column.right + 16, goalY), Stroke.dotted(tokens.ink3))
        Vector(Node.Mark(MarkMeta("water.day", Set(Channel.Colour, Channel.Position)), Vector(goal, bar)))

  // One dot per logged main meal, up to three, and one small dot above when a snack was logged.
  private def mealDots(day: Option[MealDay], slot: Box, tokens: Tokens): Vector[Node] =
    val center = Point(slot.center.x, slot.center.y + 12)
    day match
      case None =>
        Vector(emptySlot("food.none", Node.Circle(center, MealDotPx, None, Some(Stroke.dotted(tokens.ink3)))))
      case Some(meals) =>
        val dots = StrictMath.min(3, meals.mainMeals)
        val pitch = 2 * MealDotPx + 8
        val first = center.x - (dots - 1) * pitch / 2
        val main = (0 until dots).toVector.map(index =>
          Node.filledCircle(Point(first + index * pitch, center.y), MealDotPx, tokens.rye)
        )
        val snack = Option.when(meals.snacks > 0)(Node.filledCircle(Point(center.x, center.y - 36), 8, tokens.rye))
        Vector(Node.Mark(MarkMeta("food.day", Set(Channel.Colour, Channel.Position)), main ++ snack))

  private def miniBlob(day: Option[MoodState], slot: Box, tokens: Tokens): Vector[Node] =
    val center = Point(slot.center.x, slot.center.y - 4)
    day match
      case None =>
        Vector(emptySlot("mood.none", Node.Circle(center, MiniBlobPx - 4, None, Some(Stroke.dotted(tokens.ink3)))))
      case Some(state) => Vector(BlobFigure.of(state, BlobFrame(center, MiniBlobPx), tokens))

  // A horizontal bar per night against a dotted goal mark; an estimate is a hatched outline of full length, because
  // a quiet window is a bound and not a duration.
  private def sleepBar(night: SleepEvidence, scaleMinutes: Int, slot: Box, tokens: Tokens): Vector[Node] =
    val track = Box(
      slot.center.x - SleepBarPx.width / 2,
      slot.center.y - SleepBarPx.height / 2,
      SleepBarPx.width,
      SleepBarPx.height
    )
    night match
      case SleepEvidence.NoRecord =>
        Vector(emptySlot("sleep.none", Node.outlinedRect(track.inset(2), 10, Stroke.dotted(tokens.ink3))))
      case SleepEvidence.QuietWindowOnly =>
        val outline = Node.outlinedRect(track.inset(2), 10, Stroke.butt(tokens.ink3, 4))
        val hatch = Hatch.lines(track.inset(6), Stroke.butt(tokens.ink3, 4))
        Vector(Node.Mark(MarkMeta("sleep.estimate", Set(Channel.Pattern)), outline +: hatch))
      case SleepEvidence.Recorded(minutes, goalMinutes) =>
        val length = StrictMath.max(8.0, track.width * minutes / scaleMinutes)
        val goalX = track.x + track.width * goalMinutes / scaleMinutes
        val bar = Bars.sideways(Box(track.x, track.y, length, track.height), tokens.dusk)
        val goal = Node.Line(Point(goalX, track.y - 18), Point(goalX, track.bottom + 18), Stroke.dotted(tokens.ink3))
        Vector(Node.Mark(MarkMeta("sleep.night", Set(Channel.Colour, Channel.Position)), Vector(bar, goal)))

  private def emptySlot(role: String, outline: Node): Node =
    Node.Mark(MarkMeta(role, Set(Channel.Pattern)), Vector(outline))
