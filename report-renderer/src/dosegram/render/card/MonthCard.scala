package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.MoodState
import dosegram.render.chart.Bars
import dosegram.render.chart.Icons
import dosegram.render.copy.GoalDays
import dosegram.render.copy.MonthSummary
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

import java.time.YearMonth

/** One week of a month, as page 1 of the monthly report draws it. Every figure is absent when the week has no record of
  * its domain, and the week is then a dotted slot, never a zero (VA-PR-03).
  */
final case class MonthWeek(
    doses: Option[DoseCounts],
    waterMeanMl: Option[Int],
    mood: Option[MoodState],
    sleepMeanMinutes: Option[Int]
)

/** IMG-05 page 1, `card.month.v1`: a domain that is `None` is not tracked, or did not meet the gate of PRODUCT_SPEC
  * R-IR-11 (records on 14 days of the month), and has no row. `previousAdherence` is the user's own previous month, the
  * only comparison (VA-PR-07).
  */
final case class MonthData(
    month: YearMonth,
    daysWithRecords: Int,
    weeks: Vector[MonthWeek],
    doses: Option[DoseCounts],
    water: Option[GoalDays],
    moodCheckInDays: Option[Int],
    sleep: Option[(Int, Int)],
    previousAdherence: Option[Int]
):
  require(weeks.nonEmpty && weeks.size <= 6, s"a month has 1 to 6 weeks, got ${weeks.size}")

/** IMG-05, page 1 of the monthly report (VISUALS section 6.6): the hero figure, then one row per tracked domain with a
  * headline and one mark per week on the row's own scale (VA-IMG-04c). The hero is the adherence percentage when
  * medication is tracked with at least five resolved doses, else the number of days with records.
  */
object MonthCard:
  val TemplateId = "card.month.v1"
  private val HeroBaseline = 316.0
  private val HeroToNotesPx = 36.0
  private val NoteLinePx = 52.0
  private val WeekHeaderBaseline = 452.0
  private val RowsTop = 484.0
  private val HeadlinePx = 64.0
  private val StripPx = 80.0
  private val RowGapPx = 24.0
  private val TextLeft = 136.0
  private val BarWidthPx = 64.0
  private val MiniBlobPx = 38.0
  private val MinimumDosesForPercent = 5

  final private case class Row(domain: Domain, headline: String, draw: (MonthWeek, Box) => Vector[Node])

  def render(month: MonthData, context: CardContext): CardOutput =
    val copy = context.copy
    val summary = MonthSummary(
      month.month,
      month.daysWithRecords,
      month.doses,
      month.water,
      month.moodCheckInDays,
      month.sleep,
      month.previousAdherence
    )
    val rows =
      Vector(medication(month, context), water(month, context), mood(month, context), sleep(month, context)).flatten
    // A month with few rows collapses to the square; the comparison line, when there is one, ends the body.
    val hasComparison = month.previousAdherence.isDefined && adherence(month).isDefined
    val bodyEnd = RowsTop + rows.size * (HeadlinePx + StripPx + RowGapPx) + (if hasComparison then 32.0 else 0.0)
    val frame = CardFrame(context, CardFrame.collapsing(bodyEnd), context.tokens.surface)
    val nodes = frame.header(copy.monthTitle, copy.monthYear(month.month))
      ++ hero(month, frame, context)
      ++ weekHeader(month, frame, context)
      ++ rows.zipWithIndex.flatMap((row, index) =>
        drawRow(row, month, RowsTop + index * (HeadlinePx + StripPx + RowGapPx), frame, context)
      )
      ++ comparison(month, rows.size, frame, context)
      :+ frame.footer
    val text = TextEquivalent.of(copy.monthText(summary), copy.monthDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-month.png", context.privacy == Privacy.Discreet)

  private def adherence(month: MonthData): Option[DoseCounts] =
    month.doses.filter(_.counted >= MinimumDosesForPercent)

  // The one headline number of the card and what it counts, start-aligned (VISUALS section 3.5).
  private def hero(month: MonthData, frame: CardFrame, context: CardContext): Vector[Node] =
    val copy = context.copy
    val (figure, notes) = adherence(month)
      .flatMap(counts => counts.takenPercent.map(percent => (copy.percent(percent), copy.monthHeroDoses(counts))))
      .getOrElse((month.daysWithRecords.toString, copy.monthHeroDays(month.daysWithRecords)))
    val heroLook = TextLook(TextStyle.Hero, frame.tokens.ink, Align.Start, TextKind.Value)
    // The notes end on the hero's baseline; the count in brackets, when there is one, is the quieter last line.
    val firstNote = HeroBaseline - (notes.size - 1) * NoteLinePx
    val noteLeft = frame.left + context.fitter.width(figure, TextStyle.Hero) + HeroToNotesPx
    frame.text(figure, Point(frame.left, HeroBaseline), heroLook, frame.width) +:
      notes.zipWithIndex.map { (note, index) =>
        val isCount = notes.size > 1 && index == notes.size - 1
        val look =
          if isCount then TextLook(TextStyle.Body, frame.tokens.ink2, Align.Start, TextKind.Value)
          else TextLook(TextStyle.BodyMedium, frame.tokens.ink, Align.Start, TextKind.Value)
        frame.text(note, Point(noteLeft, firstNote + index * NoteLinePx), look, frame.right - noteLeft)
      }

  private def slotWidth(month: MonthData, frame: CardFrame): Double = (frame.right - TextLeft) / month.weeks.size

  private def weekHeader(month: MonthData, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    val slot = slotWidth(month, frame)
    month.weeks.indices.toVector.map { index =>
      frame.text(
        context.copy.weekNumber(index + 1),
        Point(TextLeft + (index + 0.5) * slot, WeekHeaderBaseline),
        look,
        slot
      )
    }

  private def drawRow(row: Row, month: MonthData, top: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val nameLook = frame.look(TextStyle.BodyMedium, frame.tokens.ink)
    val headlineLook = TextLook(TextStyle.BodyStrong, frame.tokens.ink, Align.End, TextKind.Value)
    val slot = slotWidth(month, frame)
    val marks = month.weeks.zipWithIndex.flatMap { (week, index) =>
      row.draw(week, Box(TextLeft + index * slot, top + HeadlinePx, slot, StripPx))
    }
    Vector(
      Icons.of(row.domain, Point(frame.left, top), frame.tokens.hue(row.domain)),
      frame.text(context.copy.domainName(row.domain), Point(TextLeft, top + 42), nameLook, 280),
      frame.text(row.headline, Point(frame.right, top + 42), headlineLook, frame.width - 380)
    ) ++ marks

  private def medication(month: MonthData, context: CardContext): Option[Row] =
    month.doses.map { counts =>
      val tokens = context.tokens
      Row(
        Domain.Medication,
        context.copy.takenOf(counts.taken, counts.counted),
        (week, slot) =>
          week.doses.filter(_.counted > 0) match
            case None        => Vector(emptySlot(slot, tokens))
            case Some(doses) =>
              Vector(bar(slot, doses.taken.toDouble / doses.counted, tokens.spruce, "month.medication"))
      )
    }

  private def water(month: MonthData, context: CardContext): Option[Row] =
    month.water.map { goalDays =>
      val scale = (month.weeks.flatMap(_.waterMeanMl) :+ 1).max.toDouble
      val tokens = context.tokens
      Row(
        Domain.Water,
        context.copy.goalReachedDays(goalDays),
        (week, slot) =>
          week.waterMeanMl match
            case None       => Vector(emptySlot(slot, tokens))
            case Some(mean) => Vector(bar(slot, mean / scale, tokens.tide, "month.water"))
      )
    }

  private def mood(month: MonthData, context: CardContext): Option[Row] =
    month.moodCheckInDays.map { days =>
      val tokens = context.tokens
      Row(
        Domain.Mood,
        context.copy.checkInDays(days),
        (week, slot) =>
          week.mood match
            case None        => Vector(emptyCircle(slot, tokens))
            case Some(state) => Vector(BlobFigure.of(state, BlobFrame(slot.center.moved(0, -4), MiniBlobPx), tokens))
      )
    }

  private def sleep(month: MonthData, context: CardContext): Option[Row] =
    month.sleep.map { (averageMinutes, _) =>
      val scale = (month.weeks.flatMap(_.sleepMeanMinutes) :+ 1).max.toDouble
      val tokens = context.tokens
      Row(
        Domain.Sleep,
        context.copy.sleepAverage(averageMinutes),
        (week, slot) =>
          week.sleepMeanMinutes match
            case None       => Vector(emptySlot(slot, tokens))
            case Some(mean) => Vector(bar(slot, mean / scale, tokens.dusk, "month.sleep"))
      )
    }

  // A bar from the baseline of its slot, on the row's own scale; bars start at zero (VISUALS section 3.2).
  private def bar(slot: Box, share: Double, color: dosegram.render.color.Rgb, role: String): Node =
    val height = StrictMath.max(8.0, slot.height * StrictMath.max(0.0, StrictMath.min(1.0, share)))
    val box = Box(slot.center.x - BarWidthPx / 2, slot.bottom - height, BarWidthPx, height)
    Node.Mark(MarkMeta(role, Set(Channel.Colour, Channel.Position)), Vector(Bars.upright(box, color)))

  private def emptySlot(slot: Box, tokens: Tokens): Node =
    val box = Box(slot.center.x - BarWidthPx / 2, slot.bottom - slot.height, BarWidthPx, slot.height)
    Node.Mark(
      MarkMeta("month.none", Set(Channel.Pattern)),
      Vector(Node.outlinedRect(box.inset(2), 12, Stroke.dotted(tokens.ink3)))
    )

  private def emptyCircle(slot: Box, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta("month.none", Set(Channel.Pattern)),
      Vector(Node.Circle(slot.center.moved(0, -4), MiniBlobPx - 4, None, Some(Stroke.dotted(tokens.ink3))))
    )

  // The user's own previous month, stated, never judged (VA-PR-07).
  private def comparison(month: MonthData, rowCount: Int, frame: CardFrame, context: CardContext): Vector[Node] =
    val baseline = RowsTop + rowCount * (HeadlinePx + StripPx + RowGapPx) + 32
    month.previousAdherence
      .filter(_ => adherence(month).isDefined)
      .map { percent =>
        val look = TextLook(TextStyle.Body, frame.tokens.ink2, Align.Start, TextKind.Value)
        frame.text(
          context.copy.monthPrevious(month.month.minusMonths(1), percent),
          Point(frame.left, baseline),
          look,
          frame.width
        )
      }
      .toVector
