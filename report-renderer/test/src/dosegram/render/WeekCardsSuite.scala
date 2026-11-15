package dosegram.render

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.MoodState
import dosegram.render.card.*
import dosegram.render.chart.DoseStatus.*
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.scene.Node
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Theme

import java.time.DayOfWeek
import java.time.LocalDate

/** The week cards of the image round of 2026-11-15 (VISUALS IMG-19 to IMG-22): what each draws for a day without a
  * record, an estimate, a gate that is not met, and what it never draws.
  */
class WeekCardsSuite extends munit.FunSuite:
  private val Monday = LocalDate.of(2026, 11, 2)
  private val context = Samples.context(Theme.Light, Privacy.Aggregate)

  private def roles(card: CardOutput): Vector[String] =
    Node.flatten(card.scene.nodes).collect { case Node.Mark(meta, _) => meta.role }

  private def texts(card: CardOutput, kind: TextKind): Vector[String] =
    Node.flatten(card.scene.nodes).collect { case text: Node.Text if text.kind == kind => text.content }

  // The values a card draws besides the period of its header.
  private def figures(card: CardOutput): Vector[String] = texts(card, TextKind.Value).drop(1)

  private def blobs(card: CardOutput): Int =
    Node.flatten(card.scene.nodes).count { case mark: Node.Mark => mark.meta == BlobFigure.Mark; case _ => false }

  private val week = WeekData(
    Monday,
    Some(Vector(Vector(TakenOnTime, Skipped), Vector.empty, Vector(Open), Vector(TakenOnTime), Vector.empty, Vector.empty, Vector.empty)),
    Some(Vector(Some(WaterProgress(1000, 2000)), None, None, None, None, None, None)),
    None,
    Some(Vector(Some(MoodState(6, 4)), None, None, None, None, None, None)),
    Some(Vector(SleepEvidence.QuietWindowOnly, SleepEvidence.Recorded(420, 480)) ++ Vector.fill(5)(SleepEvidence.NoRecord))
  )

  test("the glance draws one row per tracked domain, no number, and a dotted cell for a day without a record"):
    val card = WeekGlanceCard.render(week, context)
    assertEquals(figures(card), Vector.empty, "the figures are in the text equivalent only")
    assert(!roles(card).exists(_.startsWith("glance.food")), "food is not tracked, so it has no row")
    assertEquals(roles(card).count(_ == "glance.water.none"), 6)
    assertEquals(roles(card).count(_ == "glance.water"), 1)
    assertEquals(roles(card).count(_ == "glance.sleep.estimate"), 1, "a quiet window is hatched, never a fill")
    assertEquals(blobs(card), 1)
    // Two days have doses that resolved, one only an open dose; the four days without a dose have no cell at all.
    assertEquals(roles(card).count(_ == "glance.medication"), 2)
    assertEquals(roles(card).count(_ == "glance.medication.none"), 1)
    assertEquals(card.text.full, context.copy.weekText(WeekCard.summarise(week)))

  test("a glance of two rows starts under its title like one of four, and leaves the rest of the card below"):
    val two = WeekGlanceCard.render(week.copy(medication = None, sleep = None), context)
    val four = WeekGlanceCard.render(week, context)
    val weekdayRow = (card: CardOutput) => Node.flatten(card.scene.nodes).collect { case t: Node.Text if t.kind == TextKind.Axis => t.anchor.y }.distinct
    assertEquals(weekdayRow(two), weekdayRow(four))

  test("a medication day with nothing scheduled has no cell, and the legend says what the empty place means"):
    val withBlankDays = WeekGlanceCard.render(week, context)
    assert(texts(withBlankDays, TextKind.Label).contains(context.copy.glanceBlankNote), texts(withBlankDays, TextKind.Label))
    val everyDayScheduled = week.copy(medication = Some(Vector.fill(7)(Vector(TakenOnTime))))
    val full = WeekGlanceCard.render(everyDayScheduled, context)
    assert(!texts(full, TextKind.Label).contains(context.copy.glanceBlankNote), "no empty place, no note")
    val uk = Samples.context(Theme.Dark, Privacy.Aggregate, UkrainianImageCopy)
    assert(texts(WeekGlanceCard.render(week, uk), TextKind.Label).contains(UkrainianImageCopy.glanceBlankNote))

  // The cycle 10 verification: at a mean of 1.0 the blob touched the count of days beside it.
  for (copyName, copyContext) <- Vector("English" -> context, "Ukrainian" -> Samples.context(Theme.Light, Privacy.Aggregate, UkrainianImageCopy)) do
    test(s"mood by weekday keeps every blob clear of the words of its row, at both ends of the scale ($copyName)"):
      val rows = DayOfWeek.values.toVector.zip(Vector(1.0, 1.0, 5.0, 5.0, 3.0, 1.0, 5.0)).zipWithIndex.map {
        case ((day, mean), index) => MoodWeekdayRow(day, Some(mean), 12 + index % 2)
      }
      val card = MoodWeekdayCard.render(MoodWeekdayData(Monday, Monday.plusDays(90), rows), copyContext)
      val lines = Node.flatten(card.scene.nodes).collect { case text: Node.Text => text }
      val blobs = SceneGeometry.blobs(card.scene.nodes)
      assertEquals(blobs.size, 7)
      val extent = (line: Node.Text) => SceneGeometry.text(line, copyContext.fitter)
      rows.zip(blobs).foreach { (row, blob) =>
        val name = lines.find(_.content == copyContext.copy.weekdayName(row.day)).get
        val onRow = (content: String) => lines.find(line => line.content == content && StrictMath.abs(line.anchor.y - name.anchor.y) < 1).get
        val count = extent(onRow(copyContext.copy.moodWeekdayDays(row.checkInDays)))
        val value = extent(onRow(copyContext.copy.insightValue(row.mean.get)))
        assert(count.left - extent(name).right >= 16, s"${row.day}: ${count.left - extent(name).right} px from the name")
        assert(blob.left - count.right >= 24, s"${row.day}: ${blob.left - count.right} px from the count of days")
        assert(value.left - blob.right >= 24, s"${row.day}: ${value.left - blob.right} px from the value")
      }

  test("the water week averages from three logged days and counts the days below that"):
    val few = WaterWeekCard.render(Monday, week.water.get, context)
    assertEquals(figures(few).head, "1")
    assertEquals(roles(few).count(_ == "water.none"), 6, "a day without water is a dotted slot, never a bar of zero")
    val full = Vector(1500, 2500, 2000).map(ml => Some(WaterProgress(ml, 2000))) ++ Vector.fill(4)(None)
    val card = WaterWeekCard.render(Monday, full, context)
    assertEquals(figures(card).head, "2.0 l")
    assert(figures(card).contains("Goal on 2 of 3 days"))
    assert(card.text.full.contains("2.0 l a day on average"), card.text.full)

  test("the sleep week never draws a quiet window as a bar and averages recorded nights only"):
    val card = SleepWeekCard.render(Monday, week.sleep.get, context)
    assertEquals(roles(card).count(_ == "sleep.day"), 1)
    assertEquals(roles(card).count(_ == "sleep.estimate"), 1)
    assertEquals(figures(card).head, "1", "under three nights the hero is the count")
    assert(card.text.full.contains("not counted"), card.text.full)

  test("mood by weekday keeps the week's order, draws no blob under three days and ranks nothing"):
    val rows = Vector(
      MoodWeekdayRow(DayOfWeek.MONDAY, Some(4.5), 3),
      MoodWeekdayRow(DayOfWeek.TUESDAY, None, 2),
      MoodWeekdayRow(DayOfWeek.WEDNESDAY, Some(1.5), 6),
      MoodWeekdayRow(DayOfWeek.THURSDAY, Some(3.0), 4),
      MoodWeekdayRow(DayOfWeek.FRIDAY, None, 0),
      MoodWeekdayRow(DayOfWeek.SATURDAY, Some(2.0), 5),
      MoodWeekdayRow(DayOfWeek.SUNDAY, Some(5.0), 3)
    )
    val card = MoodWeekdayCard.render(MoodWeekdayData(Monday, Monday.plusDays(55), rows), context)
    assertEquals(blobs(card), 5)
    val names = Node.flatten(card.scene.nodes).collect { case text: Node.Text if text.content.length == 3 => text.content }
    assertEquals(names.filter(Set("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")), Vector("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"))
    assertEquals(figures(card).filter(_.contains('.')), Vector("4.5", "1.5", "3.0", "2.0", "5.0"))
    for word <- Vector("best", "worst", "highest", "lowest", "better", "worse") do
      assert(!card.text.full.toLowerCase.contains(word), s"'$word' in ${card.text.full}")

  test("the Ukrainian week cards keep their figures and their plural forms"):
    val uk = Samples.context(Theme.Dark, Privacy.Aggregate, UkrainianImageCopy)
    val water = WaterWeekCard.render(Monday, Vector.fill(3)(Some(WaterProgress(1900, 2000))) ++ Vector.fill(4)(None), uk)
    assertEquals(figures(water).head, "1,9 л")
    val sleep = SleepWeekCard.render(Monday, Vector.fill(2)(SleepEvidence.Recorded(400, 480)) ++ Vector.fill(5)(SleepEvidence.NoRecord), uk)
    assert(sleep.text.full.contains("2 ночі з ваших позначок"), sleep.text.full)
