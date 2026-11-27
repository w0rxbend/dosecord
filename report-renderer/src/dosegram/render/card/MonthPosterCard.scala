package dosegram.render.card

import dosegram.render.blob.MoodState
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
import java.time.YearMonth

/** One day of the month poster: the areas among water, food and sleep with a record that day, and the day's mood. */
final case class PosterDay(recorded: Set[Domain], mood: Option[MoodState])

/** IMG-24, the month in one image: every day of `month` with a dot for each of water, food and sleep that the account
  * shows (`tracked`, its consents) and the day's mood blob when mood is shown. Medication is never on the poster: no
  * picture counts dose answers by day (VISUALS section 7.3, R-GAM-2); it has its own pages.
  */
final case class MonthPosterData(month: YearMonth, weekStart: DayOfWeek, tracked: Set[Domain], days: Vector[PosterDay]):
  require(days.size == month.lengthOfMonth, s"${days.size} days given for $month")
  require(!tracked(Domain.Medication), "medication is not on the poster")

  /** The dots of every day, in their fixed order. */
  def dots: Vector[Domain] = MonthPosterCard.DotOrder.filter(tracked)

  /** The recorded days of one shown area (VISUALS section 7.1). */
  def recordedDays(domain: Domain): Int =
    if domain == Domain.Mood then days.count(_.mood.isDefined) else days.count(_.recorded(domain))

  def daysWithRecords: Int = days.count(day => day.recorded.exists(tracked) || day.mood.isDefined)

/** IMG-24, `card.month.poster.v1` (VISUALS section 6.23): a calendar of the month on one panel. Each day has its
  * number, the day's blob when mood is shown, and a row of dots, one per shown area in the order water, food, sleep: a
  * filled dot in the area's hue for a day with a record, an `ink3` ring for a day without one (VA-PR-03). The order of
  * the dots is the legend's order, so the place of a dot names its area without its colour (VA-PR-06). Nothing on it
  * ranks, scores or counts a run; the only figure is the days with records, beside the number of days of the month.
  */
object MonthPosterCard:
  val TemplateId = "card.month.poster.v1"
  val DotOrder: Vector[Domain] = Vector(Domain.Water, Domain.Food, Domain.Sleep)

  private val SubtitleBaseline = 176.0
  private val PanelTop = 200.0
  private val PanelPadPx = 28.0
  private val PanelRadius = 40.0
  private val WeekdayBaseline = 252.0
  private val GridTop = 272.0
  private val SlotPx = 128.0
  private val MaximumRowPx = 128.0
  private val MinimumRowPx = 112.0
  private val PanelBottomPadPx = 16.0
  private val PanelToLegendPx = 24.0
  private val FactLinePx = 56.0
  private val GridUnitPx = 4.0
  private val DotRadius = 10.0
  private val RingRadius = 8.0
  private val DotPitchPx = 32.0
  // A day is a tile in the surface tone on the panel, so its number, blob and dots read as one day.
  private val TileInsetPx = 4.0
  private val TileGapPx = 8.0
  private val TileRadius = 24.0
  private val NumberInsetPx = 12.0
  private val NumberBaselinePx = 36.0
  private val BlobInsetPx = 66.0
  private val BlobTopPx = 10.0
  private val MaximumBlobPx = 52.0
  // The blob, the row of dots under it and the air around them: what a tile needs besides the blob.
  private val DotsClearancePx = 68.0
  private val BlobToDotsPx = 26.0

  def render(data: MonthPosterData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    val tokens = frame.tokens
    val copy = context.copy
    val legend = Legend.flow(legendItems(data, tokens, context), frame.width, context.fitter)
    val legendTop = frame.bodyBottom - FactLinePx - legend.height
    val offset = Math.floorMod(data.month.atDay(1).getDayOfWeek.getValue - data.weekStart.getValue, 7)
    val weeks = (offset + data.days.size + 6) / 7
    val room = legendTop - PanelToLegendPx - PanelBottomPadPx - GridTop
    val rowPx = StrictMath.max(
      MinimumRowPx,
      StrictMath.min(MaximumRowPx, StrictMath.floor(room / weeks / GridUnitPx) * GridUnitPx)
    )
    val gridLeft = frame.left + PanelPadPx
    val panelBottom = GridTop + weeks * rowPx + PanelBottomPadPx
    val panel =
      Node.filledRect(Box(frame.left, PanelTop, frame.width, panelBottom - PanelTop), PanelRadius, tokens.panel)
    // The figure follows the legend at once; the room the rows did not take stays above the footer.
    val factBaseline = panelBottom + PanelToLegendPx + legend.height + FactLinePx - 4
    val subtitleLook = TextLook(TextStyle.Body, tokens.ink2, Align.Start, TextKind.Label)
    val factLook = TextLook(TextStyle.BodyMedium, tokens.ink, Align.Start, TextKind.Value)
    // The month is the title; the header has no period, because the title is the period.
    val title = frame.text(
      copy.monthYear(data.month),
      Point(frame.left, CardFrame.HeaderBaseline),
      frame.look(TextStyle.Title, tokens.ink),
      frame.width
    )
    val nodes = Vector(title)
      ++ Vector(frame.text(copy.posterSubtitle, Point(frame.left, SubtitleBaseline), subtitleLook, frame.width), panel)
      ++ weekdays(data.weekStart, gridLeft, frame, context)
      ++ data.days.zipWithIndex.flatMap { (day, index) =>
        val slot = offset + index
        val origin = Point(gridLeft + (slot % 7) * SlotPx, GridTop + (slot / 7) * rowPx)
        dayNodes(data, day, index + 1, Box(origin.x, origin.y, SlotPx, rowPx), frame)
      }
      ++ legend.nodes(Point(frame.left, panelBottom + PanelToLegendPx), frame, context.fitter)
      ++ Vector(
        frame.text(
          copy.posterFact(data.daysWithRecords, data.days.size),
          Point(frame.left, factBaseline),
          factLook,
          frame.width
        ),
        frame.footer
      )
    val text = TextEquivalent.of(copy.posterText(data), copy.posterDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-month-poster.png", context.privacy == Privacy.Discreet)

  private def weekdays(weekStart: DayOfWeek, gridLeft: Double, frame: CardFrame, context: CardContext): Vector[Node] =
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Middle, TextKind.Axis)
    (0 until 7).toVector.map { column =>
      val name = context.copy.weekdayShort(weekStart.plus(column.toLong))
      frame.text(name, Point(gridLeft + (column + 0.5) * SlotPx, WeekdayBaseline), look, SlotPx)
    }

  // On the day's tile: the number at the top start corner, the blob beside it, the dots under the blob.
  private def dayNodes(data: MonthPosterData, day: PosterDay, number: Int, slot: Box, frame: CardFrame): Vector[Node] =
    val tokens = frame.tokens
    val numberLook = TextLook(TextStyle.Label, tokens.ink3, Align.Start, TextKind.Axis)
    val tile = Node.filledRect(
      Box(slot.x + TileInsetPx, slot.y, slot.width - 2 * TileInsetPx, slot.height - TileGapPx),
      TileRadius,
      tokens.surface
    )
    val label =
      frame.text(number.toString, Point(slot.x + NumberInsetPx, slot.y + NumberBaselinePx), numberLook, BlobInsetPx)
    val blobSide = StrictMath.min(MaximumBlobPx, slot.height - DotsClearancePx)
    val blob = day.mood.filter(_ => data.tracked(Domain.Mood)).map { state =>
      MoodCalendarCard.miniBlob(state, Box(slot.x + BlobInsetPx, slot.y + BlobTopPx, blobSide, blobSide), tokens)
    }
    val dotsY = slot.y + BlobTopPx + blobSide + BlobToDotsPx
    val firstX = slot.center.x - (data.dots.size - 1) * DotPitchPx / 2
    val dots = data.dots.zipWithIndex.map { (domain, place) =>
      val centre = Point(firstX + place * DotPitchPx, dotsY)
      if day.recorded(domain) then dot(s"poster.$domain.day", domain, centre, tokens)
      else ring(s"poster.$domain.none", centre, tokens)
    }
    Vector(tile, label) ++ blob.toVector ++ dots

  private def dot(role: String, domain: Domain, centre: Point, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta(role, Set(Channel.Colour, Channel.Position, Channel.Label)),
      Vector(Node.filledCircle(centre, DotRadius, tokens.hue(domain)))
    )

  private def ring(role: String, centre: Point, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta(role, Set(Channel.Shape, Channel.Position)),
      Vector(Node.Circle(centre, RingRadius, None, Some(Stroke.solid(tokens.ink3, 4))))
    )

  // The dots in their order, the blob when mood is shown, the ring, and the note that the order is the place.
  private def legendItems(data: MonthPosterData, tokens: Tokens, context: CardContext): Vector[LegendItem] =
    val copy = context.copy
    val dots = data.dots.map(domain =>
      LegendItem.of(box => dot(s"legend.poster.$domain", domain, box.center, tokens), copy.domainName(domain))
    )
    val mood = Option.when(data.tracked(Domain.Mood)) {
      LegendItem.of(box => MoodCalendarCard.miniBlob(MoodState(5, 3), box, tokens), copy.domainName(Domain.Mood))
    }
    val noRecord = Option.when(data.dots.nonEmpty)(
      LegendItem.of(box => ring("legend.poster.none", box.center, tokens), copy.noRecordMark)
    )
    val order = Option.when(data.dots.size > 1)(LegendItem.note(copy.posterOrderNote))
    dots ++ mood ++ noRecord ++ order
end MonthPosterCard
