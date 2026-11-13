package dosegram.render

import dosegram.render.card.AdherenceCalendarCard
import dosegram.render.card.CalendarData
import dosegram.render.card.CardFrame
import dosegram.render.card.CardOutput
import dosegram.render.card.DayAdherence
import dosegram.render.card.DayCard
import dosegram.render.card.DayData
import dosegram.render.card.Privacy
import dosegram.render.card.WaterCard
import dosegram.render.card.WaterData
import dosegram.render.card.WaterProgress
import dosegram.render.card.WeekCard
import dosegram.render.card.WeekData
import dosegram.render.chart.DoseStatus
import dosegram.render.chart.Halo
import dosegram.render.chart.HaloFrame
import dosegram.render.chart.HaloSegment
import dosegram.render.chart.HaloState
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.scene.Node
import dosegram.render.scene.PathCommand
import dosegram.render.scene.Point
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Layout rules that a golden only pins and does not explain: what sits next to what, and what is named. */
class CardLayoutSuite extends munit.FunSuite:
  private val context = Samples.context(Theme.Light, Privacy.Named)
  private val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
  private val DigitHeightPx = 28.0

  private def texts(card: CardOutput): Vector[Node.Text] =
    Node.flatten(card.scene.nodes).collect { case text: Node.Text => text }

  private def calendar(month: YearMonth, days: Vector[DayAdherence]): CardOutput =
    AdherenceCalendarCard.render(CalendarData(month, DayOfWeek.MONDAY, None, None, days, 0), context)

  private def allTaken(month: YearMonth): Vector[DayAdherence] =
    Vector.fill(month.lengthOfMonth)(DayAdherence.Resolved(2, 2, hasMissed = false))

  test("water above the goal states the true percentage, and the vessel is only full (PRODUCT_SPEC R-HYD-5)"):
    val progress = WaterProgress(4800, 2000)
    assertEquals(progress.percent, 240)
    assertEquals(progress.share, 1.0)
    val card = WaterCard.render(WaterData(LocalDate.of(2026, 11, 12), progress, 14, None, usesDrinkFactors = false), context)
    assert(texts(card).exists(_.content == "240%"), "the image draws the true percentage")
    assert(card.text.full.contains("4.8 of 2.0 l (240%)"), card.text.full)
    assertEquals(card.text.caption, card.text.full)

  test("the water percentage is floored: 100 only when the goal is reached"):
    assertEquals(WaterProgress(1999, 2000).percent, 99)
    assertEquals(WaterProgress(2000, 2000).percent, 100)
    assertEquals(WaterProgress(150, 2000).percent, 7)

  // Five weeks (September 2026 starts on a Tuesday) and six weeks (August 2026 starts on a Saturday).
  List(YearMonth.of(2026, 9), YearMonth.of(2026, 8)).foreach { month =>
    test(s"a day number of $month sits at least twice as near to its own cell as to the next row"):
      val card = calendar(month, allTaken(month))
      val cells = Node.flatten(card.scene.nodes).collect { case rect: Node.Rect => rect.box }
      val numbers = texts(card).filter(text => text.kind == TextKind.Axis && text.content.forall(_.isDigit))
      assertEquals(numbers.size, month.lengthOfMonth)
      numbers.foreach { number =>
        val column = cells.filter(cell => StrictMath.abs(cell.center.x - number.anchor.x) < 1)
        val above = number.anchor.y - DigitHeightPx - column.filter(_.bottom <= number.anchor.y).map(_.bottom).max
        column.filter(_.y >= number.anchor.y).map(_.y - number.anchor.y).minOption.foreach { below =>
          assert(above > 0 && below >= 2 * above, s"day ${number.content}: $above px above, $below px below")
        }
      }
  }

  test("a calendar keeps its legend, its fact lines and its cells clear of the footer in a month of six weeks"):
    val august = YearMonth.of(2026, 8)
    val everyClass = allTaken(august)
      .updated(1, DayAdherence.Resolved(3, 4, hasMissed = true))
      .updated(2, DayAdherence.Resolved(2, 4, hasMissed = true))
      .updated(3, DayAdherence.Resolved(1, 4, hasMissed = true))
      .updated(4, DayAdherence.Resolved(0, 4, hasMissed = true))
      .updated(5, DayAdherence.Resolved(0, 4, hasMissed = false))
      .updated(6, DayAdherence.NotKnown)
      .updated(7, DayAdherence.NothingScheduled)
      .updated(30, DayAdherence.Open)
    val card = AdherenceCalendarCard.render(CalendarData(august, DayOfWeek.MONDAY, None, None, everyClass, 3), context)
    val body = texts(card).filterNot(_.content == EnglishImageCopy.footer)
    assert(body.forall(_.anchor.y <= frame.bodyBottom), body.map(_.anchor.y).max)

  test("the calendar legend says what a blank day means, and only when a day is blank"):
    val september = YearMonth.of(2026, 9)
    val withBlank = calendar(september, allTaken(september).updated(9, DayAdherence.NothingScheduled))
    assert(texts(withBlank).exists(_.content == EnglishImageCopy.blankDayNote))
    assert(!texts(calendar(september, allTaken(september))).exists(_.content == EnglishImageCopy.blankDayNote))

  test("a week names the dose glyphs it shows when it shows more than one status (VA-ID-07)"):
    def week(statuses: Vector[DoseStatus]): CardOutput =
      WeekCard.render(WeekData(LocalDate.of(2026, 11, 2), Some(Vector.fill(7)(statuses)), None, None, None, None), context)
    val mixed = texts(week(Vector(DoseStatus.TakenOnTime, DoseStatus.Skipped))).map(_.content)
    assert(mixed.contains(EnglishImageCopy.statusName(DoseStatus.TakenOnTime)), mixed)
    assert(mixed.contains(EnglishImageCopy.statusName(DoseStatus.Skipped)), mixed)
    val uniform = texts(week(Vector(DoseStatus.TakenOnTime))).map(_.content)
    assert(!uniform.contains(EnglishImageCopy.statusName(DoseStatus.TakenOnTime)), uniform)

  test("a body that leaves room shares it above and below; a body that reaches the footer does not move"):
    assertEquals(frame.balance(CardFrame.BodyTop, frame.bodyBottom), 0.0)
    assertEquals(frame.balance(CardFrame.BodyTop - 60, frame.bodyBottom - 20), 20.0)
    val shift = frame.balance(CardFrame.BodyTop, CardFrame.BodyTop + 400)
    // Half the room left, rounded down onto the 4 px grid (VISUALS section 3.5).
    assertEquals(shift, StrictMath.floor((frame.bodyBottom - CardFrame.BodyTop - 400) / 2 / 4) * 4)
    assertEquals(shift % 4, 0.0)

  test("a day with only a mood entry has no halo and its picture stands in the middle of the card (VA-BL-05)"):
    val card = DayCard.render(DayData(LocalDate.of(2026, 11, 12), Samples.partialDay.mood, None, None, None, None), context)
    val marks = card.scene.nodes.collect { case mark: Node.Mark => mark.meta.role }
    assert(!marks.exists(_.startsWith("halo.")), marks)
    val label = texts(card).find(_.content == EnglishImageCopy.moodLabel(Samples.partialDay.mood.get)).get
    val full = DayCard.render(Samples.partialDay, context)
    val fullLabel = texts(full).find(_.content == label.content).get
    assert(label.anchor.y > fullLabel.anchor.y + 100, s"${label.anchor.y} against ${fullLabel.anchor.y}")

  test("the skipped share of the halo is a pill of its own: it starts clear of the round end of the filled arc"):
    val tokens = Tokens.of(Theme.Light)
    val halo = HaloFrame(Point(540, 410), 300, tokens.surface)
    val segment = HaloSegment(Domain.Medication, HaloState.Progress(0.5, 0.25))
    val paths = Node.flatten(Halo.of(Vector(segment), halo, tokens)).collect { case path: Node.Path => path }
    def start(path: Node.Path): Point = path.data.points.head
    def end(path: Node.Path): Point = path.data.points.last
    def isArc(path: Node.Path): Boolean = path.data.commands.exists(_.isInstanceOf[PathCommand.ArcTo])
    assert(paths.forall(isArc))
    val filled = paths.find(_.stroke.exists(_.color == tokens.spruce)).get
    val pill = paths.find(_.stroke.exists(_.color == tokens.ink2)).get
    val gap = StrictMath.hypot(start(pill).x - end(filled).x, start(pill).y - end(filled).y)
    assert(gap > Halo.StrokePx, s"the pill starts $gap px after the end of the fill")
