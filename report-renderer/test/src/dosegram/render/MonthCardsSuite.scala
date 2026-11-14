package dosegram.render

import dosegram.render.blob.MoodState
import dosegram.render.card.CardFrame
import dosegram.render.card.CardOutput
import dosegram.render.card.InsightCard
import dosegram.render.card.InsightData
import dosegram.render.card.InsightGroup
import dosegram.render.card.InsightRule
import dosegram.render.card.MonthDaysData
import dosegram.render.card.MonthNightsData
import dosegram.render.card.MonthPages
import dosegram.render.card.MonthWaterDay
import dosegram.render.card.MoodCalendarCard
import dosegram.render.card.MoodCalendarData
import dosegram.render.card.MoodDayPoint
import dosegram.render.card.MoodTimelineCard
import dosegram.render.card.MoodTimelineData
import dosegram.render.card.Privacy
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.scene.Node
import dosegram.render.tokens.Theme

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** The mood calendar (IMG-11), the mood timeline (IMG-10), pages 2 and 3 of the monthly report (IMG-05) and the two
  * insight rules the card gained (IMG-13): what each must draw, what it must leave as a gap, and what it must say.
  */
class MonthCardsSuite extends munit.FunSuite:
  private val context = Samples.context(Theme.Light, Privacy.Named)
  private val october = YearMonth.of(2026, 10)

  private def nodes(card: CardOutput): Vector[Node] = Node.flatten(card.scene.nodes)
  private def texts(card: CardOutput): Vector[Node.Text] = nodes(card).collect { case text: Node.Text => text }
  private def marks(card: CardOutput, role: String): Vector[Node.Mark] =
    nodes(card).collect { case mark: Node.Mark if mark.meta.role == role => mark }

  private def page(name: String, index: Int = 0, privacy: Privacy = Privacy.Named): CardOutput =
    Samples.all.find(_.name == name).get.render(Samples.context(Theme.Light, privacy))(index)

  private val sparse = MoodCalendarData(
    october,
    DayOfWeek.MONDAY,
    Vector.tabulate(31)(index => Option.when(index % 3 == 0)(MoodState(2 + index % 5, 3)))
  )

  test("the mood calendar draws a blob per check-in day and a dot for a day without one, never a low mood"):
    val card = MoodCalendarCard.render(sparse, context)
    // One blob per check-in day plus the seven of the legend.
    assertEquals(marks(card, "blob").size, sparse.checkInDays + 7)
    assertEquals(marks(card, "mood.calendar.none").size, sparse.days.count(_.isEmpty) + 1)
    assertEquals(card.scene.size, CardFrame.Portrait)
    assertEquals(MoodCalendarCard.TemplateId, "card.mood.calendar.v1")

  test("the mood calendar legend names both ends of the scale and the gap, and its text counts the days"):
    val card = MoodCalendarCard.render(sparse, context)
    val drawn = texts(card).map(_.content)
    Vector("Very unpleasant", "Very pleasant", "No check-in", "11 check-in days.").foreach { label =>
      assert(drawn.contains(label), s"$label is missing from $drawn")
    }
    assert(card.text.full.startsWith("October 2026: 11 check-in days. Most frequent: "), card.text.full)
    assert(card.text.full.endsWith("20 days have no check-in."), card.text.full)

  test("the mood timeline joins consecutive days only, and a gap breaks the line"):
    val point = Some(MoodDayPoint(3.0, 3, 3, 1, None))
    val days = Vector(point, point, None, point, point, point)
    val card = MoodTimelineCard.render(MoodTimelineData(LocalDate.of(2026, 11, 1), days), context)
    val joins = nodes(card).collect { case line: Node.Line if line.stroke.color == context.tokens.beet => line }
    assertEquals(joins.size, 3)
    assertEquals(marks(card, "mood.timeline.day").size, 5)
    assertEquals(card.scene.size, CardFrame.Wide)
    assert(card.text.full.contains("5 check-in days of 6."), card.text.full)
    assert(card.text.full.contains("1 day has no check-in and is shown as a gap."), card.text.full)

  test("the mood timeline draws a whisker only on a day with more than one check-in, and energy on its own strip"):
    val days = Vector(Some(MoodDayPoint(3.5, 3, 4, 2, Some(4.0))), Some(MoodDayPoint(4.0, 4, 4, 1, None)))
    val card = MoodTimelineCard.render(MoodTimelineData(LocalDate.of(2026, 11, 1), days), context)
    val whiskers = nodes(card).collect { case line: Node.Line if line.stroke.color == context.tokens.ink2 => line }
    assertEquals(whiskers.size, 1)
    assertEquals(marks(card, "mood.timeline.energy").size, 1)
    assert(texts(card).exists(_.content == EnglishImageCopy.energyLabel))

  test("the mood timeline states no day-to-day change below four consecutive pairs"):
    val point = Some(MoodDayPoint(3.0, 3, 3, 1, None))
    val card = MoodTimelineCard.render(MoodTimelineData(LocalDate.of(2026, 11, 1), Vector(point, point, point)), context)
    assert(!card.text.full.contains("Day-to-day"), card.text.full)

  test("page 2 holds the adherence calendar and the mood calendar of the month"):
    val days = page("month/report", 1)
    assertEquals(days.scene.templateId, MonthPages.DaysTemplateId)
    val drawn = texts(days).map(_.content)
    assert(drawn.contains(EnglishImageCopy.calendarTitle), drawn)
    assert(drawn.contains(EnglishImageCopy.moodCalendarTitle), drawn)
    assert(drawn.contains("All taken"), drawn)
    assert(days.text.full.startsWith("October 2026, day by day. Medication: "), days.text.full)
    assert(days.text.full.contains("22 check-in days."), days.text.full)

  test("a page omits the panel of a domain without data"):
    val moodOnly = page("month/days-mood-only")
    assert(!texts(moodOnly).map(_.content).contains(EnglishImageCopy.calendarTitle))
    assert(!moodOnly.text.full.contains("Medication"), moodOnly.text.full)
    val waterOnly = page("month/nights-water-only")
    assertEquals(marks(waterOnly, "month.sleep.night"), Vector.empty)
    assert(marks(waterOnly, "month.water.day").nonEmpty)
    assert(MonthDaysData(october, None, None).isEmpty)
    assert(MonthNightsData(october, None, Some(Vector.fill(31)(None))).isEmpty)

  test("page 3 draws each night on the clock and each day's water against the user's own goal"):
    val nights = page("month/report", 2)
    assertEquals(nights.scene.templateId, MonthPages.NightsTemplateId)
    assertEquals(marks(nights, "month.sleep.night").size, 26)
    assertEquals(marks(nights, "month.sleep.nap").size, 1)
    assertEquals(marks(nights, "month.water.day").size, 26)
    assertEquals(marks(nights, "month.water.goal").size, 23)
    val drawn = texts(nights).map(_.content)
    Vector("Your entries", "Nap", "Typical times", "Water logged", "Your goal").foreach { label =>
      assert(drawn.contains(label), s"$label is missing from $drawn")
    }
    assert(nights.text.full.contains("Water: Goal reached on"), nights.text.full)

  test("a water day without a goal has a bar and no goal line"):
    val water = Vector.tabulate(31)(day => Option.when(day < 3)(MonthWaterDay(1500, None)))
    val card = MonthPages.nights(MonthNightsData(october, None, Some(water)), context)
    assertEquals(marks(card, "month.water.day").size, 3)
    assertEquals(marks(card, "month.water.goal"), Vector.empty)
    assert(card.text.full.contains("3 days with water recorded."), card.text.full)

  test("every text of the month pages sits above the footer, in every fixture"):
    val frame = CardFrame(context, CardFrame.Portrait, context.tokens.surface)
    for
      name <- Vector("month/report", "month/days-mood-only", "month/nights-water-only", "month/days-six-weeks")
      theme <- Vector(Theme.Light, Theme.Dark)
      card <- Samples.all.find(_.name == name).get.render(Samples.context(theme, Privacy.Named))
    do
      val body = texts(card).filterNot(_.content == EnglishImageCopy.footer)
      assert(body.forall(_.anchor.y <= frame.bodyBottom), s"$name: ${body.map(_.anchor.y).max}")
      val shapes = nodes(card).collect { case rect: Node.Rect => rect.box.bottom }
      assert(shapes.forall(_ <= frame.bodyBottom), s"$name: a panel reaches ${shapes.max}")

  test("the month pages are discreet like page 1"):
    val discreet = page("month/report", 1, Privacy.Discreet)
    assertEquals(discreet.text.caption, EnglishImageCopy.monthDiscreet)
    assert(discreet.hasSpoiler)

  test("the insight card words the bedtime and work-day rules with the fixed sentence"):
    for rule <- Vector(InsightRule.BedtimeMood, InsightRule.WorkDayMood) do
      val data = InsightData(rule, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 11, 9), InsightGroup(16, 3.9), InsightGroup(11, 3.2))
      val card = InsightCard.render(data, context)
      assert(card.text.full.endsWith(EnglishImageCopy.insightNotCause), card.text.full)
      assert(card.text.full.contains("16"), card.text.full)
      assert(card.text.full.contains("11"), card.text.full)
end MonthCardsSuite
