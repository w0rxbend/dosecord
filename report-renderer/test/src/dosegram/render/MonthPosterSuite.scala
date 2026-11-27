package dosegram.render

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.MoodState
import dosegram.render.card.*
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.scene.Node
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.Theme

import java.time.DayOfWeek
import java.time.YearMonth

/** IMG-24, the month in one image (image round 4, 2026-11-27): one dot per shown area in a fixed order, a ring for a day
  * without a record, the day's blob, no medication and one figure.
  */
class MonthPosterSuite extends munit.FunSuite:
  private val september = YearMonth.of(2026, 9)
  private val context = Samples.context(Theme.Light, Privacy.Aggregate)
  private val areas = Set(Domain.Water, Domain.Food, Domain.Sleep, Domain.Mood)

  private def poster(tracked: Set[Domain], recorded: Int => Set[Domain], mood: Int => Option[MoodState]): MonthPosterData =
    MonthPosterData(
      september,
      DayOfWeek.MONDAY,
      tracked,
      Vector.tabulate(september.lengthOfMonth)(index => PosterDay(recorded(index + 1), mood(index + 1)))
    )

  private def marks(card: CardOutput): Vector[(String, Node)] =
    Node.flatten(card.scene.nodes).collect { case mark @ Node.Mark(meta, _) => meta.role -> mark }

  private def centreX(node: Node): Double =
    Node.flatten(Vector(node)).collectFirst { case circle: Node.Circle => circle.center.x }.get

  test("a day has a dot for each shown area with a record and a ring for one without, in the order water, food, sleep"):
    val data = poster(Set(Domain.Water, Domain.Sleep), day => if day == 1 then Set(Domain.Water) else Set.empty, _ => None)
    val card = MonthPosterCard.render(data, context)
    val roles = marks(card).map(_._1)
    assertEquals(roles.count(_ == "poster.Water.day"), 1)
    assertEquals(roles.count(_ == "poster.Water.none"), 29)
    assertEquals(roles.count(_ == "poster.Sleep.none"), 30)
    assert(!roles.exists(_.startsWith("poster.Food")), "food is not shown, so it has no place")
    val firstDay = marks(card).collect { case (role, node) if role.startsWith("poster.") => node }.take(2)
    assert(centreX(firstDay(0)) < centreX(firstDay(1)), "water stands before sleep")

  test("medication is never on the poster"):
    intercept[IllegalArgumentException](poster(Set(Domain.Medication, Domain.Water), _ => Set.empty, _ => None))
    val card = MonthPosterCard.render(poster(areas, _ => Set(Domain.Medication, Domain.Water), _ => None), context)
    assert(!marks(card).exists(_._1.toLowerCase.contains("medication")))
    assert(!card.text.full.contains("Medication"), card.text.full)

  test("the mood blob is drawn on a day with a check-in and only when mood is shown"):
    def blobs(card: CardOutput): Int = marks(card).count(_._2 match { case Node.Mark(meta, _) => meta == BlobFigure.Mark; case _ => false })
    val mood: Int => Option[MoodState] = day => Option.when(day <= 3)(MoodState(5, 3))
    // Three days and the legend's blob.
    assertEquals(blobs(MonthPosterCard.render(poster(areas, _ => Set.empty, mood), context)), 4)
    assertEquals(blobs(MonthPosterCard.render(poster(Set(Domain.Water), _ => Set.empty, mood), context)), 0)

  test("the one figure is the days with records, and the text states each shown area's recorded days"):
    val data = poster(areas, day => if day % 2 == 0 then Set(Domain.Water, Domain.Food) else Set(Domain.Sleep), day => Option.when(day <= 5)(MoodState(4, 3)))
    val card = MonthPosterCard.render(data, context)
    val values = Node.flatten(card.scene.nodes).collect { case text: Node.Text if text.kind == TextKind.Value => text.content }
    assertEquals(values, Vector("30 of 30 days with records"))
    assertEquals(
      card.text.full,
      "September 2026, your month in one image. 30 of 30 days have a record. Water: 15 days. Food: 15 days. Sleep: 15 nights. Mood: 5 check-in days."
    )
    val uk = MonthPosterCard.render(data, Samples.context(Theme.Dark, Privacy.Aggregate, UkrainianImageCopy))
    assertEquals(
      uk.text.full,
      "Вересень 2026, ваш місяць на одному зображенні. Записи є за 30 з 30 днів. Вода: 15 днів. Їжа: 15 днів. Сон: 15 ночей. Настрій: 5 днів із позначками."
    )

  test("in discreet mode the poster keeps its dots, captions a generic line and goes behind the spoiler"):
    val data = poster(areas, _ => Set(Domain.Water), _ => Some(MoodState(4, 3)))
    val named = MonthPosterCard.render(data, context)
    val discreet = MonthPosterCard.render(data, Samples.context(Theme.Light, Privacy.Discreet))
    assertEquals((discreet.text.caption, discreet.hasSpoiler), (EnglishImageCopy.posterDiscreet, true))
    assertEquals((named.text.caption, named.hasSpoiler), (named.text.full, false))
    assertEquals(marks(discreet).map(_._1), marks(named).map(_._1))
    assertEquals(discreet.fileName, "dosegram-month-poster.png")

  test("a month of six weeks keeps its legend and its figure above the footer"):
    val august = YearMonth.of(2026, 8)
    val data = MonthPosterData(august, DayOfWeek.MONDAY, areas, Vector.fill(31)(PosterDay(Set(Domain.Water), None)))
    Vector(EnglishImageCopy, UkrainianImageCopy).foreach { copy =>
      val card = MonthPosterCard.render(data, Samples.context(Theme.Light, Privacy.Aggregate, copy))
      val frame = CardFrame(Samples.context(Theme.Light, Privacy.Aggregate, copy), CardFrame.Portrait, context.tokens.surface)
      val body = Node.flatten(card.scene.nodes).collect { case text: Node.Text if text.content != copy.footer => text.anchor.y }
      assert(body.forall(_ <= frame.bodyBottom), s"$copy: ${body.max}")
    }
