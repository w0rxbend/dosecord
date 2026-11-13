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

  test("a day with nothing scheduled has its own quiet mark, and the legend shows and names it when such a day exists"):
    val september = YearMonth.of(2026, 9)
    def marks(card: CardOutput, role: String): Int =
      Node.flatten(card.scene.nodes).count { case mark: Node.Mark => mark.meta.role == role; case _ => false }
    val withBlank = calendar(september, allTaken(september).updated(9, DayAdherence.NothingScheduled))
    assert(texts(withBlank).exists(_.content == EnglishImageCopy.blankDayNote))
    // One mark on the day itself and one in the legend.
    assertEquals(marks(withBlank, "calendar.none"), 2)
    val full = calendar(september, allTaken(september))
    assert(!texts(full).exists(_.content == EnglishImageCopy.blankDayNote))
    assertEquals(marks(full, "calendar.none"), 0)

  test("the calendar grid sits on a panel, the well its heat steps were checked against (VISUALS section 3.3.2)"):
    val card = calendar(YearMonth.of(2026, 9), allTaken(YearMonth.of(2026, 9)))
    val panels = card.scene.nodes.collect {
      case rect: Node.Rect if rect.fill.contains(dosegram.render.scene.Paint.Solid(context.tokens.panel)) => rect.box
    }
    assertEquals(panels.size, 1)
    val cells = Node.flatten(card.scene.nodes).collect {
      case mark: Node.Mark if mark.meta.role.startsWith("calendar.") => mark
    }
    val boxes = Node.flatten(cells).collect { case rect: Node.Rect => rect.box }
    assert(boxes.nonEmpty)
    val panel = panels.head
    assert(boxes.forall(box => box.x >= panel.x && box.right <= panel.right && box.y >= panel.y && box.bottom <= panel.bottom))

  test("a week names the dose glyphs it shows when it shows more than one status (VA-ID-07)"):
    def week(statuses: Vector[DoseStatus]): CardOutput =
      WeekCard.render(WeekData(LocalDate.of(2026, 11, 2), Some(Vector.fill(7)(statuses)), None, None, None, None), context)
    val mixed = texts(week(Vector(DoseStatus.TakenOnTime, DoseStatus.Skipped))).map(_.content)
    assert(mixed.contains(EnglishImageCopy.statusName(DoseStatus.TakenOnTime)), mixed)
    assert(mixed.contains(EnglishImageCopy.statusName(DoseStatus.Skipped)), mixed)
    val uniform = texts(week(Vector(DoseStatus.TakenOnTime))).map(_.content)
    assert(!uniform.contains(EnglishImageCopy.statusName(DoseStatus.TakenOnTime)), uniform)

  test("a week names the marks of its other rows: the goal line, the empty slot, the estimate hatch and the snack dot"):
    val typical = texts(Samples.weeks.head.render(context).head).map(_.content)
    Vector(EnglishImageCopy.yourGoal, EnglishImageCopy.noRecordMark, EnglishImageCopy.estimateMark, EnglishImageCopy.snackMark)
      .foreach(name => assert(typical.contains(name), s"'$name' is missing from $typical"))
    val medicationOnly = texts(Samples.weeks.last.render(context).head).map(_.content)
    Vector(EnglishImageCopy.yourGoal, EnglishImageCopy.noRecordMark, EnglishImageCopy.estimateMark, EnglishImageCopy.snackMark)
      .foreach(name => assert(!medicationOnly.contains(name), s"'$name' is named without its mark"))

  test("a week or a one-page strip whose body fits the square canvas collapses to it; a fuller one stays portrait"):
    def sizes(fixture: dosegram.render.Fixture): Vector[dosegram.render.scene.Size] = fixture.render(context).map(_.scene.size)
    def named(name: String): dosegram.render.Fixture = Samples.all.find(_.name == name).get
    assertEquals(sizes(named("week/medication-only")), Vector(CardFrame.Square))
    assertEquals(sizes(named("week/typical")), Vector(CardFrame.Portrait))
    assertEquals(sizes(named("strip/fortnight")), Vector(CardFrame.Square))
    assertEquals(sizes(named("strip/empty")), Vector(CardFrame.Square))
    // The pages of one album keep one size.
    assertEquals(sizes(named("strip/paginated-hostile")).distinct, Vector(CardFrame.Portrait))

  test("the sleep card names its marks by glyph, each only when it draws it, and plots on a panel"):
    def named(name: String): CardOutput = Samples.all.find(_.name == name).get.render(context).head
    def roles(card: CardOutput): Set[String] =
      Node.flatten(card.scene.nodes).collect { case mark: Node.Mark => mark.meta.role }.toSet
    val typical = named("sleep/typical")
    val names = texts(typical).map(_.content)
    Vector(EnglishImageCopy.sleepEntriesMark, EnglishImageCopy.sleepNapMark, EnglishImageCopy.sleepTypicalMark)
      .foreach(name => assert(names.contains(name), s"'$name' is missing"))
    assert(Set("legend.sleep.night", "legend.sleep.nap", "legend.sleep.typical").subsetOf(roles(typical)), roles(typical))
    // Two nights and a nap, but too few for typical times.
    val few = texts(named("sleep/few")).map(_.content)
    assert(!few.contains(EnglishImageCopy.sleepTypicalMark), few)
    val empty = named("sleep/empty")
    assert(!roles(empty).exists(_.startsWith("legend.")), roles(empty))
    val panels = typical.scene.nodes.collect {
      case rect: Node.Rect if rect.fill.contains(dosegram.render.scene.Paint.Solid(context.tokens.panel)) => rect
    }
    assertEquals(panels.size, 1)

  test("a body that leaves room shares it above and below; a body that reaches the footer does not move"):
    assertEquals(frame.balance(CardFrame.BodyTop, frame.bodyBottom), 0.0)
    assertEquals(frame.balance(CardFrame.BodyTop - 60, frame.bodyBottom - 20), 20.0)
    val shift = frame.balance(CardFrame.BodyTop, CardFrame.BodyTop + 400)
    // Half the room left, rounded down onto the 4 px grid (VISUALS section 3.5).
    assertEquals(shift, StrictMath.floor((frame.bodyBottom - CardFrame.BodyTop - 400) / 2 / 4) * 4)
    assertEquals(shift % 4, 0.0)

  test("a day with one or two domains, or mood alone, collapses to the square canvas; three or more stay portrait"):
    val date = LocalDate.of(2026, 11, 12)
    val water = Some(WaterProgress(1450, 2000))
    val doses = Some(Vector(DoseStatus.TakenOnTime, DoseStatus.Skipped))
    def size(day: DayData) = DayCard.render(day, context).scene.size
    assertEquals(size(DayData(date, Samples.partialDay.mood, None, None, None, None)), CardFrame.Square)
    assertEquals(size(DayData(date, None, None, water, None, None)), CardFrame.Square)
    assertEquals(size(DayData(date, None, doses, water, None, None)), CardFrame.Square)
    assertEquals(size(DayData(date, None, doses, water, Samples.partialDay.food, None)), CardFrame.Portrait)
    assertEquals(size(Samples.partialDay), CardFrame.Portrait)

  test("every text of a collapsed day card stays above its footer"):
    val date = LocalDate.of(2026, 11, 12)
    val sleep = Some(dosegram.render.card.SleepEvidence.Recorded(445, 480))
    val doses = Some(Vector.fill(6)(DoseStatus.TakenOnTime))
    val card = DayCard.render(DayData(date, Samples.partialDay.mood, doses, None, None, sleep), context)
    val square = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val body = texts(card).filterNot(_.content == EnglishImageCopy.footer)
    assert(body.forall(_.anchor.y <= square.bodyBottom), body.map(_.anchor.y).max)
    val glyphs = Node.flatten(card.scene.nodes).collect { case mark: Node.Mark if mark.meta.role.startsWith("dose.") => mark }
    assertEquals(glyphs.size, 6)

  test("a day with only a mood entry has no halo and its picture stands in the middle of the card (VA-BL-05)"):
    val card = DayCard.render(DayData(LocalDate.of(2026, 11, 12), Samples.partialDay.mood, None, None, None, None), context)
    val marks = card.scene.nodes.collect { case mark: Node.Mark => mark.meta.role }
    assert(!marks.exists(_.startsWith("halo.")), marks)
    val label = texts(card).find(_.content == EnglishImageCopy.moodLabel(Samples.partialDay.mood.get)).get
    val square = CardFrame(context, CardFrame.Square, context.tokens.surface)
    // The blob above its label takes the room of the ring: the label sits low in the body, not under a small blob.
    assertEquals(card.scene.size, CardFrame.Square)
    assert(label.anchor.y > square.bodyBottom - 160 && label.anchor.y <= square.bodyBottom, s"${label.anchor.y}")

  test("the skipped share of the halo is a thin ink line along the track, clear of the fill's round end"):
    val tokens = Tokens.of(Theme.Light)
    val halo = HaloFrame(Point(540, 410), 300, tokens.surface)
    val segment = HaloSegment(Domain.Medication, HaloState.Progress(0.5, 0.25))
    val paths = Node.flatten(Halo.of(Vector(segment), halo, tokens)).collect { case path: Node.Path => path }
    def start(path: Node.Path): Point = path.data.points.head
    def end(path: Node.Path): Point = path.data.points.last
    def isArc(path: Node.Path): Boolean = path.data.commands.exists(_.isInstanceOf[PathCommand.ArcTo])
    assert(paths.forall(isArc))
    val filled = paths.find(_.stroke.exists(_.color == tokens.spruce)).get
    val skipped = paths.filter(_.stroke.exists(_.color == tokens.ink2))
    assertEquals(skipped.size, 1, "one line, not an outlined band")
    val line = skipped.head
    assert(line.stroke.get.width < Halo.StrokePx / 2, s"a line of ${line.stroke.get.width} px is not thin")
    assert(paths.forall(_.stroke.forall(_.color != halo.background)), "nothing is cut out of the track")
    // The air between the round cap of the fill and the round cap of the line.
    val centres = StrictMath.hypot(start(line).x - end(filled).x, start(line).y - end(filled).y)
    val air = centres - Halo.StrokePx / 2 - line.stroke.get.width / 2
    assert(air >= 8, s"only $air px of air between the fill and the skipped line")
