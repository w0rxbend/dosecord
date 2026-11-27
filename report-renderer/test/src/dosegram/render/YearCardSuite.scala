package dosegram.render

import dosegram.render.card.*
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.scene.Node
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.Theme

import java.time.DayOfWeek
import java.time.LocalDate

/** IMG-30, the one-year card (analytics round 6, supervisor decision, 2026-11-28): a strip of week cells per shown area
  * that fill to the share of days with a record, a ring for a week without one, no medication and one figure.
  */
class YearCardSuite extends munit.FunSuite:
  private val context = Samples.context(Theme.Light, Privacy.Aggregate)
  private val from = LocalDate.of(2026, 1, 1)
  private val to = LocalDate.of(2026, 3, 4)

  private def marks(card: CardOutput): Vector[(String, Node)] =
    Node.flatten(card.scene.nodes).collect { case mark @ Node.Mark(meta, _) => meta.role -> mark }

  private def rects(node: Node): Vector[Node.Rect] = Node.flatten(Vector(node)).collect { case rect: Node.Rect => rect }

  private def row(domain: Domain, recorded: Int => Int): YearStripRow =
    // 1 January 2026 is a Thursday: the first week holds four days of the year, the tenth (from 2 March) three.
    val sizes = 4 +: Vector.fill(8)(7) :+ 3
    val weeks = sizes.zipWithIndex.map((days, index) => YearWeek(math.min(days, recorded(index)), days))
    YearStripRow(domain, weeks, Vector(weeks.take(5).map(_.recorded).sum, weeks.drop(5).map(_.recorded).sum, 0))

  private def data(rows: YearStripRow*): YearStripData =
    YearStripData(2026, from, to, DayOfWeek.MONDAY, rows.toVector, rows.map(_.recordedDays).max)

  test("one cell per week from the week of 1 January; a week fills to its share of days, a week without one is a ring"):
    assertEquals(YearCard.weeks(from, to, DayOfWeek.MONDAY), 10)
    val card = YearCard.render(data(row(Domain.Water, index => if index == 0 then 0 else index)), context)
    val roles = marks(card).map(_._1)
    assertEquals(roles.count(_ == "year.Water.week"), 9)
    assertEquals(roles.count(_ == "year.Water.none"), 1)
    val cells = marks(card).collect { case ("year.Water.week", node) => rects(node) }
    // Each cell is its track and its fill: the week of 7 recorded days fills the track, one of 1 day a seventh.
    val full = cells(6)
    assertEquals(full(1).box.height, full(0).box.height)
    val seventh = cells(0)
    assertEqualsDouble(seventh(1).box.height, math.round(seventh(0).box.height / 7).toDouble, 0.0)
    assertEquals(seventh(1).box.bottom, seventh(0).box.bottom)

  test("medication is never on the year card"):
    intercept[IllegalArgumentException](data(row(Domain.Medication, _ => 3)))

  test("every strip has one cell per week of the window"):
    intercept[IllegalArgumentException](
      YearStripData(2026, from, to, DayOfWeek.MONDAY, Vector(YearStripRow(Domain.Water, Vector(YearWeek(1, 7)), Vector(1))), 1)
    )

  test("the one figure is the days with records; the text states each area's days and months in both languages"):
    val card = YearCard.render(data(row(Domain.Water, _ => 2), row(Domain.Mood, index => if index < 2 then 1 else 0)), context)
    val values = Node.flatten(card.scene.nodes).collect { case text: Node.Text if text.kind == TextKind.Value => text.content }
    assertEquals(values, Vector("20 of 63 days with records"))
    assertEquals(
      card.text.full,
      "2026, your year in records, 1 Jan to 4 Mar. 20 of 63 days have a record. Water: 20 days (Jan 10, Feb 10). " +
        "Mood: 2 check-in days (Jan 2)."
    )
    val uk = YearCard.render(
      data(row(Domain.Water, _ => 2), row(Domain.Mood, index => if index < 2 then 1 else 0)),
      Samples.context(Theme.Dark, Privacy.Aggregate, UkrainianImageCopy)
    )
    assert(uk.text.full.startsWith("2026, ваш рік у записах, "), uk.text.full)
    assert(uk.text.full.contains("Записи є за 20 з 63 днів. Вода: 20 днів (січ 10, лют 10). Настрій: 2 дні з позначками (січ 2)."), uk.text.full)

  test("in discreet mode the card keeps its cells, captions a generic line and goes behind the spoiler"):
    val year = data(row(Domain.Water, _ => 3), row(Domain.Sleep, _ => 5))
    val named = YearCard.render(year, context)
    val discreet = YearCard.render(year, Samples.context(Theme.Light, Privacy.Discreet))
    assertEquals((discreet.text.caption, discreet.hasSpoiler), (EnglishImageCopy.year.discreet, true))
    assertEquals((named.text.caption, named.hasSpoiler), (named.text.full, false))
    assertEquals(marks(discreet).map(_._1), marks(named).map(_._1))
    assertEquals(discreet.fileName, "dosegram-year.png")

  test("a full year of four areas keeps its legend and its figure above the footer, in both languages"):
    val end = LocalDate.of(2026, 12, 31)
    val count = YearCard.weeks(from, end, DayOfWeek.MONDAY)
    val weeks = Vector.tabulate(count)(index => YearWeek(if index % 5 == 0 then 0 else 3, if index == 0 then 4 else if index == count - 1 then 4 else 7))
    val rows = YearCard.Order.map(domain => YearStripRow(domain, weeks, Vector.fill(12)(10)))
    val full = YearStripData(2026, from, end, DayOfWeek.MONDAY, rows, 200)
    Vector(EnglishImageCopy, UkrainianImageCopy).foreach { copy =>
      val local = Samples.context(Theme.Light, Privacy.Aggregate, copy)
      val card = YearCard.render(full, local)
      val frame = CardFrame(local, CardFrame.Portrait, local.tokens.surface)
      val body = Node.flatten(card.scene.nodes).collect { case text: Node.Text if text.content != copy.footer => text.anchor.y }
      assert(body.forall(_ <= frame.bodyBottom), s"$copy: ${body.max}")
      val right = Node.flatten(card.scene.nodes).collect { case rect: Node.Rect => rect.box.right }
      assert(right.forall(_ <= frame.right + 12), s"$copy: ${right.max}")
    }
end YearCardSuite
