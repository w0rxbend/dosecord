package dosegram.render.card

import dosegram.render.chart.Icons
import dosegram.render.color.Rgb
import dosegram.render.scene.Align
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.text.TextFitter
import dosegram.render.tokens.Domain
import dosegram.render.tokens.TextStyle
import dosegram.render.tokens.Tokens

import java.time.LocalDate

/** The figure of a highlighted day, in the domain's own unit. */
enum HighlightFigure:
  case Water(millilitres: Int)
  case MainMeals(count: Int)

  /** The day's mean on the stored 1 to 5 scale. */
  case Mood(mean: Double)
  case Night(minutes: Int)

/** One domain of IMG-25: the week's days with a record (indexes 0 to 6), the days that share the highest figure, and
  * that figure. `highlight` is absent when the week has fewer recorded days than a highlight needs.
  */
final case class HighlightRow(domain: Domain, recorded: Vector[Int], highlight: Option[(Vector[Int], HighlightFigure)]):
  require(domain != Domain.Medication, "medication has no highlight (R-GAM-2)")
  require(recorded.nonEmpty, "a highlight row has a record")
  require(
    highlight.forall((days, _) => days.nonEmpty && days.forall(recorded.contains)),
    "a highlighted day is recorded"
  )

/** The one observation of the weekly report under the insight rules, as the core catalogue words it with its fixed
  * non-causal sentence, and the days of the window it was found in.
  */
final case class HighlightObservation(days: Int, sentence: String)

/** IMG-25: the seven days from `from`, one row per shown domain with a record, and the one observation of the insight
  * engine that the weekly report carries.
  */
final case class WeekHighlightsData(
    from: LocalDate,
    rows: Vector[HighlightRow],
    observation: Option[HighlightObservation]
):
  require(rows.nonEmpty, "a highlights card has a row")
  def to: LocalDate = from.plusDays(WeekHighlightsCard.DayCount - 1L)

/** IMG-25, `card.week.highlights.v1` (image round 5, supervisor decision of 2026-11-28): for every shown domain except
  * medication the day or days of the week with its highest figure (the most water, the most main meals, the highest
  * daily mood, the longest night), the seven days as a strip in which that day is a large dot, another recorded day a
  * small one and a day without a record a dotted ring, and under the rows the one observation of the weekly report with
  * its fixed non-causal sentence. A domain with fewer than three recorded days names its days only. Nothing ranks the
  * domains, praises a day or colours a figure.
  */
object WeekHighlightsCard:
  val TemplateId = "card.week.highlights.v1"
  val DayCount = 7
  private val PeriodBaseline = 180.0
  private val WeekdayBaseline = 240.0
  private val RowsTop = 256.0
  private val RowPitchPx = 128.0
  // The tightest pitch that keeps a row's two lines and its strip apart.
  private val MinimumRowPitchPx = 104.0
  private val NameLeftPx = 140.0
  private val NameBaselinePx = 40.0
  private val FigureBaselinePx = 96.0
  private val StripLeftPx = 600.0
  private val StripCentrePx = 28.0
  private val HighlightRadiusPx = 22.0
  private val RecordRadiusPx = 9.0
  private val NoRecordRadiusPx = 14.0
  private val LegendGapPx = 8.0
  private val ObservationGapPx = 24.0
  private val ObservationTitlePx = 48.0
  private val LinePx = 50.0

  def render(data: WeekHighlightsData, context: CardContext): CardOutput =
    val copy = context.copy
    val probe = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val legendItems = Vector(
      Some(LegendItem.of(box => highlightDot(box.center, context.tokens.ink2), copy.highlightDayMark)),
      Some(LegendItem.of(box => recordDot(box.center, context.tokens.ink2), copy.highlightOtherMark)),
      Option.when(data.rows.exists(_.recorded.size < DayCount))(
        LegendItem.of(box => noRecord(box.center, context.tokens), copy.noRecordMark)
      ),
      Option.when(data.rows.exists(_.domain == Domain.Mood))(LegendItem.note(copy.insightScaleNote(Domain.Mood)))
    ).flatten
    val legend = Legend.flow(legendItems, probe.width, context.fitter)
    val lines = data.observation.toVector.flatMap(seen => wrap(seen.sentence, probe.width, context.fitter))
    val observationHeight = if lines.isEmpty then 0.0 else ObservationGapPx + ObservationTitlePx + lines.size * LinePx
    // The rows close up so that the whole observation, its non-causal sentence included, fits the portrait.
    val rowRoom = probe.bodyBottom - RowsTop - LegendGapPx - legend.height - observationHeight
    val pitch =
      StrictMath.max(MinimumRowPitchPx, StrictMath.min(RowPitchPx, StrictMath.floor(rowRoom / data.rows.size)))
    val rowsBottom = RowsTop + data.rows.size * pitch
    val legendTop = rowsBottom + LegendGapPx
    val observationTop = legendTop + legend.height + ObservationGapPx
    val room = StrictMath.floor((probe.bodyBottom - observationTop - ObservationTitlePx) / LinePx).toInt
    val shown = fitted(lines, room, probe.width, context.fitter)
    val bodyEnd =
      if shown.isEmpty then legendTop + legend.height else observationTop + ObservationTitlePx + shown.size * LinePx
    val frame = CardFrame(context, CardFrame.collapsing(bodyEnd), probe.background)
    val tokens = frame.tokens
    val periodLook = TextLook(TextStyle.BodyMedium, tokens.ink2, Align.Start, TextKind.Value)
    val observation = data.observation.filter(_ => shown.nonEmpty).toVector.flatMap { seen =>
      frame.text(
        copy.highlightObservationTitle(seen.days),
        Point(frame.left, observationTop + 36),
        frame.look(TextStyle.BodyStrong, tokens.ink),
        frame.width
      ) +: shown.zipWithIndex.map((line, index) =>
        frame.text(
          line,
          Point(frame.left, observationTop + ObservationTitlePx + (index + 1) * LinePx - 12),
          frame.look(TextStyle.Label, tokens.ink),
          frame.width
        )
      )
    }
    val nodes = frame.header(copy.highlightTitle, "")
      ++ Vector(
        frame.text(copy.dateRange(data.from, data.to), Point(frame.left, PeriodBaseline), periodLook, frame.width)
      )
      ++ weekdays(data.from, frame, context)
      ++ data.rows.zipWithIndex.flatMap((row, index) =>
        drawRow(data.from, row, RowsTop + index * pitch, frame, context)
      )
      ++ legend.nodes(Point(frame.left, legendTop), frame, context.fitter)
      ++ observation
      :+ frame.footer
    val text = TextEquivalent.of(copy.highlightText(data), copy.highlightDiscreet, context)
    CardOutput(
      frame.scene(TemplateId, nodes),
      text,
      "dosegram-week-highlights.png",
      context.privacy == Privacy.Discreet
    )

  private def slotCentre(index: Int, frame: CardFrame): Double =
    val pitch = (frame.right - StripLeftPx) / DayCount
    StripLeftPx + (index + 0.5) * pitch

  private def weekdays(from: LocalDate, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    (0 until DayCount).toVector.map: index =>
      val day = from.plusDays(index.toLong).getDayOfWeek
      frame.text(context.copy.weekdayHead(day), Point(slotCentre(index, frame), WeekdayBaseline), look, 56)

  private def drawRow(
      from: LocalDate,
      row: HighlightRow,
      top: Double,
      frame: CardFrame,
      context: CardContext
  ): Vector[Node] =
    val tokens = frame.tokens
    val copy = context.copy
    val figureLook = row.highlight match
      case Some(_) => TextLook(TextStyle.BodyStrong, tokens.ink, Align.Start, TextKind.Value)
      case None    => TextLook(TextStyle.Body, tokens.ink2, Align.Start, TextKind.Value)
    val strip = (0 until DayCount).toVector.map: index =>
      val centre = Point(slotCentre(index, frame), top + StripCentrePx)
      if row.highlight.exists(_._1.contains(index)) then highlightDot(centre, tokens.hue(row.domain))
      else if row.recorded.contains(index) then recordDot(centre, tokens.hue(row.domain))
      else noRecord(centre, tokens)
    Vector(
      Icons.of(row.domain, Point(frame.left, top), tokens.hue(row.domain)),
      frame.text(
        copy.domainName(row.domain),
        Point(NameLeftPx, top + NameBaselinePx),
        frame.look(TextStyle.Label, tokens.ink2),
        StripLeftPx - NameLeftPx - 24
      ),
      frame.text(
        copy.highlightLine(from, row),
        Point(NameLeftPx, top + FigureBaselinePx),
        figureLook,
        frame.right - NameLeftPx
      )
    ) ++ strip

  private def highlightDot(centre: Point, colour: Rgb): Node =
    Node.Mark(
      MarkMeta("highlights.day", Set(Channel.Shape, Channel.Position)),
      Vector(Node.filledCircle(centre, HighlightRadiusPx, colour))
    )

  private def recordDot(centre: Point, colour: Rgb): Node =
    Node.Mark(
      MarkMeta("highlights.recorded", Set(Channel.Shape, Channel.Position)),
      Vector(Node.filledCircle(centre, RecordRadiusPx, colour))
    )

  private def noRecord(centre: Point, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta("highlights.none", Set(Channel.Pattern)),
      Vector(Node.Circle(centre, NoRecordRadiusPx, None, Some(Stroke.dotted(tokens.ink3))))
    )

  /** Words broken at spaces into lines of at most `width` in the label style. */
  private def wrap(sentence: String, width: Double, fitter: TextFitter): Vector[String] =
    TextFitter
      .drawable(sentence, TextStyle.Label)
      .split(' ')
      .toVector
      .foldLeft(Vector.empty[String]) { (lines, word) =>
        lines.lastOption match
          case Some(last) if fitter.width(s"$last $word", TextStyle.Label) <= width => lines.init :+ s"$last $word"
          case _                                                                    => lines :+ word
      }

  /** At most `limit` lines; a sentence that still needs more after the rows closed up ends its last line in an
    * ellipsis, and its caption holds it whole.
    */
  private def fitted(lines: Vector[String], limit: Int, width: Double, fitter: TextFitter): Vector[String] =
    if lines.size <= limit then lines
    else if limit <= 0 then Vector.empty
    else lines.take(limit - 1) :+ fitter.line(lines.drop(limit - 1).mkString(" "), TextStyle.Label, width).lines.head
end WeekHighlightsCard
