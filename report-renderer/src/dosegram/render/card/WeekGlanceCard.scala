package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.MoodState
import dosegram.render.chart.DoseStatus
import dosegram.render.chart.Icons
import dosegram.render.chart.LevelCell
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

/** IMG-19, `card.week.glance.v1` (supervisor decision of 2026-11-15): the seven days of every tracked domain as one
  * grid of cells, the product's one geometric unit (VISUALS D1). Each row is one domain with its icon and name; each
  * cell fills toward that day's own plan or goal (doses taken of the resolved ones, water and sleep against the user's
  * goal, main meals of three), mood is the day's blob. No number, score or count is drawn: the figures are in the text
  * equivalent, which is the weekly report's (VA-PR-01, VA-PR-05). A day without a record is a dotted cell, never an
  * empty fill (VA-PR-03). A day with no dose scheduled has no cell, and the legend's note says so (supervisor decision
  * of 2026-11-15, cycle 10 verification).
  */
object WeekGlanceCard:
  val TemplateId = "card.week.glance.v1"
  private val DayCount = 7
  private val PlannedMeals = 3.0
  private val NameLeftPx = 136.0
  private val NameWidthPx = 228.0
  private val CellsLeftPx = 380.0
  private val CellPx = 80.0
  private val RowPitchPx = 112.0
  private val WeekdayBaseline = 204.0
  private val RowsTop = 236.0
  private val RowsToLegendPx = 8.0
  private val NameBaselinePx = 54.0
  private val IconTopPx = 12.0

  /** What one cell of the grid shows. `None` in a row is a day without a record. */
  private enum Cell:
    case Level(share: Double)
    case Mood(state: MoodState)
    case Estimate
    case NoRecord
    case Nothing

  import Cell.*

  def render(week: WeekData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val copy = context.copy
    val rows = Vector(
      week.medication.map(days => Domain.Medication -> days.map(medicationCell(_)).map(Some(_))),
      week.water.map(days => Domain.Water -> days.map(_.map(progress => Level(progress.share)))),
      week.food.map(days =>
        Domain.Food -> days.map(_.map(meals => Level(StrictMath.min(1.0, meals.mainMeals / PlannedMeals))))
      ),
      week.mood.map(days => Domain.Mood -> days.map(_.map(Mood(_)))),
      week.sleep.map(nights => Domain.Sleep -> nights.map(sleepCell))
    ).flatten
    val cells = rows.flatMap(_._2)
    val legend = Legend.flow(legendItems(cells, context), frame.width, context.fitter)
    // The grid starts under the title whatever its row count: a week of two domains leaves its room below, not above.
    val legendTop = RowsTop + rows.size * RowPitchPx + RowsToLegendPx
    val nodes = frame.header(copy.glanceTitle, copy.dateRange(week.from, week.from.plusDays(DayCount - 1L)))
      ++ weekdays(week, WeekdayBaseline, frame, context)
      ++ rows.zipWithIndex.flatMap { case ((domain, days), index) =>
        row(domain, days, RowsTop + index * RowPitchPx, frame, context)
      }
      ++ legend.nodes(Point(frame.left, legendTop), frame, context.fitter)
      :+ frame.footer
    val text = TextEquivalent.of(copy.weekText(WeekCard.summarise(week)), copy.glanceDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-week-glance.png", context.privacy == Privacy.Discreet)

  // The day's share of its resolved doses taken (VISUALS section 7.2, day fraction). A day whose doses are all open or
  // unknown has no record yet; a day with no dose scheduled has no cell at all.
  private def medicationCell(statuses: Vector[DoseStatus]): Cell =
    val resolved =
      statuses.filter(status => status.isTaken || status == DoseStatus.Skipped || status == DoseStatus.Missed)
    if resolved.nonEmpty then Level(resolved.count(_.isTaken).toDouble / resolved.size)
    else if statuses.nonEmpty then NoRecord
    else Nothing

  private def sleepCell(night: SleepEvidence): Option[Cell] =
    night match
      case SleepEvidence.Recorded(minutes, goal) => Some(Level(if goal <= 0 then 1.0 else minutes.toDouble / goal))
      case SleepEvidence.QuietWindowOnly         => Some(Estimate)
      case SleepEvidence.NoRecord                => None

  private def row(
      domain: Domain,
      days: Vector[Option[Cell]],
      top: Double,
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    val slotWidth = (frame.right - CellsLeftPx) / DayCount
    val nameLook = frame.look(TextStyle.BodyMedium, frame.tokens.ink)
    val label = Vector(
      Icons.of(domain, Point(frame.left, top + IconTopPx), frame.tokens.hue(domain)),
      frame.text(context.copy.domainName(domain), Point(NameLeftPx, top + NameBaselinePx), nameLook, NameWidthPx)
    )
    label ++ days.zipWithIndex.flatMap { (day, index) =>
      val box = Box(CellsLeftPx + index * slotWidth + (slotWidth - CellPx) / 2, top, CellPx, CellPx)
      cell(domain, day.getOrElse(NoRecord), box, frame.tokens)
    }

  private def cell(domain: Domain, cell: Cell, box: Box, tokens: Tokens): Vector[Node] =
    val role = s"glance.${domain.toString.toLowerCase(java.util.Locale.ROOT)}"
    cell match
      case Level(share) => Vector(LevelCell.filled(role, box, share, tokens.hue(domain), tokens))
      case Mood(state)  => Vector(BlobFigure.of(state, BlobFrame(box.center.moved(0, -4), box.width / 2 - 4), tokens))
      case Estimate     => Vector(LevelCell.estimate(s"$role.estimate", box, tokens))
      case NoRecord     =>
        if domain == Domain.Mood then
          Vector(
            Node.Mark(
              MarkMeta(s"$role.none", Set(Channel.Pattern)),
              Vector(Node.Circle(box.center, box.width / 2 - 6, None, Some(Stroke.dotted(tokens.ink3))))
            )
          )
        else Vector(LevelCell.noRecord(s"$role.none", box, tokens))
      case Nothing => Vector.empty

  private def weekdays(week: WeekData, baseline: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    val slotWidth = (frame.right - CellsLeftPx) / DayCount
    (0 until DayCount).toVector.map { day =>
      val initial = context.copy.weekdayInitial(week.from.plusDays(day.toLong).getDayOfWeek)
      frame.text(initial, Point(CellsLeftPx + (day + 0.5) * slotWidth, baseline), look, slotWidth)
    }

  // The fill is explained in words once; the dotted and hatched cells are named by glyph when the grid draws them, and
  // the empty place of a day with no dose scheduled by a note, since no glyph can show an absence.
  private def legendItems(cells: Vector[Option[Cell]], context: CardContext): Vector[LegendItem] =
    val tokens = context.tokens
    val shown = cells.map(_.getOrElse(NoRecord))
    Vector(
      Some(
        LegendItem.of(
          box => LevelCell.filled("legend.fill", box.inset(4), 0.5, tokens.ink2, tokens),
          context.copy.glanceFillNote
        )
      ),
      Option.when(shown.contains(NoRecord))(
        LegendItem.of(box => LevelCell.noRecord("legend.none", box, tokens), context.copy.noRecordMark)
      ),
      Option.when(shown.contains(Estimate))(
        LegendItem.of(box => LevelCell.estimate("legend.estimate", box, tokens), context.copy.estimateMark)
      ),
      Option.when(shown.contains(Nothing))(LegendItem.note(context.copy.glanceBlankNote))
    ).flatten
