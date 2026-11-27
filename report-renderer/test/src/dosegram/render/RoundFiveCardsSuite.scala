package dosegram.render

import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.GalleryCheckIn
import dosegram.render.card.HighlightFigure
import dosegram.render.card.HighlightObservation
import dosegram.render.card.HighlightRow
import dosegram.render.card.MoodGalleryCard
import dosegram.render.card.MoodGalleryData
import dosegram.render.card.Privacy
import dosegram.render.card.WeekHighlightsCard
import dosegram.render.card.WeekHighlightsData
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.scene.Node
import dosegram.render.tokens.Domain
import dosegram.render.tokens.Theme

import java.time.LocalDate
import java.time.LocalDateTime

/** Image round 5 (supervisor decision, 2026-11-28): the blob gallery of `/mood history` (IMG-26) and the week
  * highlights of the weekly report (IMG-25).
  */
class RoundFiveCardsSuite extends munit.FunSuite:

  private def marks(nodes: Vector[Node]): Vector[Node.Mark] = Node.flatten(nodes).collect { case mark: Node.Mark => mark }

  private def texts(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Text(content, _, _, _, _, _) => content }

  private def checkIn(day: Int, hour: Int, step: Int, energy: Option[Int]): GalleryCheckIn =
    GalleryCheckIn(LocalDateTime.of(2026, 11, day, hour, 5), MoodReading(MoodState.captured(step, energy), energy.isDefined))

  private val gallery = MoodGalleryData(Vector.tabulate(10)(index => checkIn(10 + index, 8 + index, 1 + index % 5, None)))

  test("the gallery draws one blob per check-in, seven to a row, every blob of a row at one height"):
    val card = MoodGalleryCard.render(gallery, Samples.context(Theme.Dark, Privacy.Aggregate))
    val blobs = marks(card.scene.nodes).filter(_.meta.role == "mood.gallery.checkin")
    assertEquals(blobs.size, 10)
    // The ground under each blob: a very unpleasant and a very pleasant check-in of one row stand on one line, because
    // the place is the order, never the level.
    val grounds = blobs.map(blob => Node.flatten(blob.children).collectFirst { case ground: Node.Ellipse => ground.center }.get)
    assertEquals(grounds.map(_.y).distinct.size, 2, "two rows")
    assertEquals(grounds.take(7).map(_.y).distinct.size, 1, "the first seven share their row")
    assertEquals(grounds.map(_.x), grounds.map(_.x).take(7) ++ grounds.map(_.x).take(3), "in order, left to right")
    val tracks = card.scene.nodes.collect { case line: Node.Line => line.from.y }
    assertEquals(tracks.distinct.size, 2, "one track per row of seven")

  test("the gallery's text names every check-in with its day, time and level, oldest first"):
    val card = MoodGalleryCard.render(gallery, Samples.context(Theme.Light, Privacy.Aggregate))
    assert(card.text.full.startsWith("Your latest 10 mood check-ins, 10 to 19 Nov, oldest first."), card.text.full)
    assert(card.text.full.contains("Tue 10 Nov 08:05: Very unpleasant."), card.text.full)
    assert(card.text.full.contains("Thu 19 Nov 17:05: Very pleasant."), card.text.full)
    assert(texts(card.scene.nodes).contains("10 check-ins"))

  test("a discreet gallery has the generic caption behind a spoiler; the Ukrainian one counts in Ukrainian"):
    val discreet = MoodGalleryCard.render(gallery, Samples.context(Theme.Dark, Privacy.Discreet))
    assertEquals((discreet.text.caption, discreet.hasSpoiler), ("Recent mood check-ins.", true))
    val ukrainian = MoodGalleryCard.render(gallery, Samples.context(Theme.Dark, Privacy.Aggregate, UkrainianImageCopy))
    assert(ukrainian.text.full.startsWith("Позначки настрою: 10 позначок"), ukrainian.text.full)

  test("a gallery holds at most fourteen check-ins, oldest first"):
    intercept[IllegalArgumentException](MoodGalleryData(Vector.tabulate(15)(index => checkIn(1 + index, 9, 3, None))))
    intercept[IllegalArgumentException](MoodGalleryData(Vector(checkIn(12, 9, 3, None), checkIn(11, 9, 3, None))))

  private val from = LocalDate.of(2026, 11, 16)
  private val water = HighlightRow(Domain.Water, Vector(0, 2, 3, 5), Some(Vector(2, 5) -> HighlightFigure.Water(2100)))
  private val sleep = HighlightRow(Domain.Sleep, Vector(1, 4), None)
  private val seen = HighlightObservation(28, "On the 18 days after a night at or above your goal, your average mood was 3.7. " +
    "This compares your own days. It does not show a cause.")

  test("the highest days are large dots, other recorded days small ones and a day without a record a dotted ring"):
    val card = WeekHighlightsCard.render(WeekHighlightsData(from, Vector(water, sleep), None), Samples.context(Theme.Light, Privacy.Aggregate))
    val roles = marks(card.scene.nodes).map(_.meta.role)
    // Two highest days of water; four recorded days in all; the legend draws one of each glyph.
    assertEquals(roles.count(_ == "highlights.day"), 2 + 1)
    assertEquals(roles.count(_ == "highlights.recorded"), 2 + 2 + 1)
    assertEquals(roles.count(_ == "highlights.none"), 3 + 5 + 1)
    val words = texts(card.scene.nodes)
    assert(words.contains("Most: Wed, Sat · 2.1 l"), words.toString)
    assert(words.contains("Records on 2 nights"), words.toString)

  test("the text states each row and the observation with its non-causal sentence; no observation, no block"):
    val withObservation = WeekHighlightsCard.render(
      WeekHighlightsData(from, Vector(water, sleep), Some(seen)),
      Samples.context(Theme.Dark, Privacy.Aggregate)
    )
    assertEquals(
      withObservation.text.full,
      "Week highlights, 16 to 22 Nov. Water: the most on Wed and Sat, 2.1 l. Sleep: records on 2 nights. " +
        s"One observation, last 28 days: ${seen.sentence}"
    )
    assert(texts(withObservation.scene.nodes).contains("One observation, last 28 days"))
    val without = WeekHighlightsCard.render(WeekHighlightsData(from, Vector(water), None), Samples.context(Theme.Dark, Privacy.Aggregate))
    assert(!texts(without.scene.nodes).exists(_.startsWith("One observation")))
    assertEquals(without.scene.size, dosegram.render.card.CardFrame.Square)

  test("medication has no highlight row (R-GAM-2), and a highlighted day is a recorded one"):
    intercept[IllegalArgumentException](HighlightRow(Domain.Medication, Vector(0), None))
    intercept[IllegalArgumentException](HighlightRow(Domain.Water, Vector(0), Some(Vector(1) -> HighlightFigure.Water(1))))

  test("a discreet highlights card captions the generic line behind a spoiler"):
    val card = WeekHighlightsCard.render(WeekHighlightsData(from, Vector(water), Some(seen)), Samples.context(Theme.Dark, Privacy.Discreet))
    assertEquals((card.text.caption, card.hasSpoiler), ("Highlights of a week.", true))

  test("the whole observation is on the card, its non-causal sentence included, in both languages (VA-IN-11)"):
    List(dosegram.render.copy.EnglishImageCopy -> "It does not show a cause.", UkrainianImageCopy -> "Воно не показує причини.")
      .foreach: (copy, ending) =>
        Theme.values.foreach: theme =>
          val card = Samples.highlights.head.render(Samples.context(theme, Privacy.Aggregate, copy)).head
          val drawn = texts(card.scene.nodes).mkString(" ")
          assert(drawn.endsWith(s"$ending ${copy.footer}"), s"$theme: $drawn")
          assert(!drawn.contains("…"), s"$theme: the observation is cut: $drawn")

  test("a gallery of one day names that day, not a range of one"):
    val card = MoodGalleryCard.render(MoodGalleryData(Vector(checkIn(9, 8, 4, None))), Samples.context(Theme.Dark, Privacy.Aggregate))
    assert(card.text.full.startsWith("Your latest 1 mood check-in, Mon 9 Nov, oldest first."), card.text.full)
    assert(texts(card.scene.nodes).contains("Mon 9 Nov"))
