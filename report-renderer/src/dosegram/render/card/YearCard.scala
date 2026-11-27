package dosegram.render.card

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

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** One week of a strip: the days with a record and the days the week has inside the drawn window (fewer at its edges).
  */
final case class YearWeek(recorded: Int, days: Int):
  require(days >= 1 && days <= 7 && recorded >= 0 && recorded <= days, s"week of $recorded of $days days")

/** One area of the one-year card: a week cell from the week of `from` to the week of `to`, and the recorded days of
  * each month from January, for the text equivalent.
  */
final case class YearStripRow(domain: Domain, weeks: Vector[YearWeek], monthly: Vector[Int]):
  def recordedDays: Int = weeks.map(_.recorded).sum

/** IMG-30, the one-year card: one strip per shown area among water, food, sleep and mood, in that order. Medication is
  * never on it: no picture counts dose answers by day (VISUALS section 7.3, R-GAM-2).
  */
final case class YearStripData(
    year: Int,
    from: LocalDate,
    to: LocalDate,
    weekStart: DayOfWeek,
    rows: Vector[YearStripRow],
    daysWithRecords: Int
):
  require(rows.nonEmpty, "a year card has at least one area")
  require(!rows.exists(_.domain == Domain.Medication), "medication is not on the year card")
  require(rows.forall(_.weeks.size == YearCard.weeks(from, to, weekStart)), "every strip has one cell per week")

  def days: Int = (to.toEpochDay - from.toEpochDay).toInt + 1

/** IMG-30, `card.year.v1` (VISUALS section 6.26, supervisor decision, 2026-11-28): the year so far as a calendar heat
  * strip per area. A cell is one week; it fills from the bottom to the share of its days with a record, in the area's
  * hue, so the height carries the count without the colour (VA-PR-06). A week without a record is an `ink3` ring, never
  * an empty fill or a bar of zero (VA-PR-03). The cells record logging only: no value, level or goal enters them, and
  * nothing ranks a week, scores the year or joins weeks into a run. The one figure is the days with records.
  */
object YearCard:
  val TemplateId = "card.year.v1"
  val Order: Vector[Domain] = Vector(Domain.Water, Domain.Food, Domain.Sleep, Domain.Mood)

  private val SubtitleBaseline = 176.0
  private val AxisBaseline = 244.0
  private val RowsTop = 268.0
  private val NameBaselinePx = 44.0
  private val StripTopPx = 64.0
  private val StripPx = 80.0
  private val RowGapPx = 24.0
  private val PanelPadPx = 12.0
  private val PanelRadius = 24.0
  private val CellGapPx = 3.0
  private val MaximumPitchPx = 40.0
  private val CellRadius = 3.0
  private val MinimumFillPx = 8.0
  private val RingRadius = 4.5
  private val RowsToLegendPx = 32.0
  private val FactLinePx = 56.0

  /** The week cells from the week of `from` to the week of `to`, weeks starting on `weekStart`. */
  def weeks(from: LocalDate, to: LocalDate, weekStart: DayOfWeek): Int =
    (ChronoUnit.WEEKS.between(firstWeek(from, weekStart), to) + 1).toInt

  private def firstWeek(from: LocalDate, weekStart: DayOfWeek): LocalDate =
    from.`with`(TemporalAdjusters.previousOrSame(weekStart))

  def render(data: YearStripData, context: CardContext): CardOutput =
    // The width is the same on every canvas: the probe lays out the legend before the canvas is chosen.
    val probe = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val copy = context.copy.year
    val cells = weeks(data.from, data.to, data.weekStart)
    val pitch = StrictMath.min(MaximumPitchPx, StrictMath.floor(probe.width / cells))
    val rowPx = StripTopPx + StripPx + RowGapPx
    val rowsBottom = RowsTop + data.rows.size * rowPx - RowGapPx
    val legend = Legend.flow(legendItems(data, probe.tokens, context), probe.width, context.fitter)
    val legendTop = rowsBottom + RowsToLegendPx
    val factBaseline = legendTop + legend.height + FactLinePx - 4
    // One or two areas fit the square; a card collapses rather than ending in an empty band.
    val frame = CardFrame(context, CardFrame.collapsing(factBaseline), context.tokens.surface)
    val tokens = frame.tokens
    val factLook = TextLook(TextStyle.BodyMedium, tokens.ink, Align.Start, TextKind.Value)
    val subtitleLook = TextLook(TextStyle.Body, tokens.ink2, Align.Start, TextKind.Label)
    val title = frame.text(
      copy.title(data.year),
      Point(frame.left, CardFrame.HeaderBaseline),
      frame.look(TextStyle.Title, tokens.ink),
      frame.width
    )
    val nodes = Vector(title, frame.text(copy.subtitle, Point(frame.left, SubtitleBaseline), subtitleLook, frame.width))
      ++ axis(data, pitch, frame, context)
      ++ data.rows.zipWithIndex.flatMap((row, index) => rowNodes(row, RowsTop + index * rowPx, pitch, frame, context))
      ++ legend.nodes(Point(frame.left, legendTop), frame, context.fitter)
      ++ Vector(
        frame.text(
          copy.fact(data.daysWithRecords, data.days),
          Point(frame.left, factBaseline),
          factLook,
          frame.width
        ),
        frame.footer
      )
    val text = TextEquivalent.of(copy.text(data), copy.discreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-year.png", context.privacy == Privacy.Discreet)

  /** A month's initial over the cell of the week that holds its first day. */
  private def axis(data: YearStripData, pitch: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Start, TextKind.Axis)
    val start = firstWeek(data.from, data.weekStart)
    (data.from.getMonthValue to data.to.getMonthValue).toVector.map { month =>
      val first = LocalDate.of(data.year, month, 1)
      val cell = ChronoUnit.WEEKS.between(start, if first.isBefore(data.from) then data.from else first).toInt
      frame.text(context.copy.year.monthInitial(month), Point(frame.left + cell * pitch, AxisBaseline), look, pitch * 4)
    }

  private def rowNodes(row: YearStripRow, top: Double, pitch: Double, frame: CardFrame, context: CardContext) =
    val tokens = frame.tokens
    val name = frame.text(
      context.copy.domainName(row.domain),
      Point(frame.left, top + NameBaselinePx),
      TextLook(TextStyle.Label, tokens.ink2, Align.Start, TextKind.Label),
      frame.width
    )
    val stripTop = top + StripTopPx
    val panel = Node.filledRect(
      Box(
        frame.left - PanelPadPx,
        stripTop - PanelPadPx,
        row.weeks.size * pitch + 2 * PanelPadPx - CellGapPx,
        StripPx + 2 * PanelPadPx
      ),
      PanelRadius,
      tokens.panel
    )
    val cells = row.weeks.zipWithIndex.map { (week, index) =>
      cell(row.domain, week, Box(frame.left + index * pitch, stripTop, pitch - CellGapPx, StripPx), tokens)
    }
    Vector(name, panel) ++ cells

  private def cell(domain: Domain, week: YearWeek, slot: Box, tokens: Tokens): Node =
    if week.recorded == 0 then ring(s"year.$domain.none", Point(slot.center.x, slot.bottom - RingRadius - 2), tokens)
    else
      val height = StrictMath.max(MinimumFillPx, StrictMath.round(slot.height * week.recorded / week.days).toDouble)
      Node.Mark(
        MarkMeta(s"year.$domain.week", Set(Channel.Colour, Channel.Position, Channel.Fill)),
        Vector(
          Node.filledRect(slot, CellRadius, tokens.grid),
          Node.filledRect(Box(slot.x, slot.bottom - height, slot.width, height), CellRadius, tokens.hue(domain))
        )
      )

  private def ring(role: String, centre: Point, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta(role, Set(Channel.Shape, Channel.Position)),
      Vector(Node.Circle(centre, RingRadius, None, Some(Stroke.solid(tokens.ink3, 4))))
    )

  private def legendItems(data: YearStripData, tokens: Tokens, context: CardContext): Vector[LegendItem] =
    val copy = context.copy.year
    val domain = data.rows.head.domain
    val fill = LegendItem.of(
      box =>
        Node.Mark(
          MarkMeta("legend.year.week", Set(Channel.Colour, Channel.Fill)),
          Vector(
            Node.filledRect(Box(box.x + box.width / 3, box.y, box.width / 3, box.height), CellRadius, tokens.grid),
            Node.filledRect(
              Box(box.x + box.width / 3, box.y + box.height / 3, box.width / 3, box.height * 2 / 3),
              CellRadius,
              tokens.hue(domain)
            )
          )
        ),
      copy.fillMark
    )
    val empty = LegendItem.of(box => ring("legend.year.none", box.center, tokens), copy.emptyMark)
    Vector(fill, empty, LegendItem.note(copy.weekNote))
end YearCard
