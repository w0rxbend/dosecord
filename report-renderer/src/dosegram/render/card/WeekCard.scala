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
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val copy = context.copy
    val summary = summarise(week)
    val rows = Vector(
      medication(week, summary, context),
      water(week, summary, context),
      food(week, summary, context),
      mood(week, summary, context),
      sleep(week, summary, context)
    ).flatten
    val nodes = frame.header(copy.weekTitle, copy.dateRange(summary.from, summary.to))
      ++ body(week, rows, frame, context)
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

  // The weekday line and the rows. Rows share the room down to the footer with a capped gap; what a week with few
  // domains leaves over is shared above and below the body.
  private def body(week: WeekData, rows: Vector[Row], frame: CardFrame, context: CardContext): Vector[Node] =
    val legends = rows.map(row => Legend.flow(row.legendItems, frame.width, context.fitter))
    val heights = rows.zip(legends).map { (row, legend) =>
      row.stripBottom + (if legend.rows.isEmpty then 0.0 else StripToLegendPx + legend.height)
    }
    val gaps = StrictMath.max(1, rows.size - 1)
    val gap = StrictMath.min(MaximumRowGapPx, (frame.bodyBottom - RowsTop - heights.sum) / gaps)
    val natural = heights.scanLeft(RowsTop)((top, height) => top + height + gap)
    val shift = frame.balance(WeekdayBaseline - WeekdayHeightPx, natural.last - gap)
    weekdays(week, WeekdayBaseline + shift, frame, context) ++
      rows.zip(legends).zip(natural.map(_ + shift)).flatMap { case ((row, legend), top) =>
        drawRow(row, top, frame, context) ++
          legend.nodes(Point(frame.left, top + row.stripBottom + StripToLegendPx), frame, context.fitter)
      }

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
