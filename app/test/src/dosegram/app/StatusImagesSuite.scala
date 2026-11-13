package dosegram.app

import dosegram.contracts.MediaKind
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.WaterCardView
import dosegram.infra.RenderSettings
import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import scala.collection.mutable

/** The status cards of the chat flows on the report renderer (ADR-022): a core view is drawn, its bytes are kept where
  * the adapter loads them, and the reference carries the card's own text equivalent. The last test runs the real
  * resvg binary when the machine has one; the others use a rasteriser that counts its calls.
  */
class StatusImagesSuite extends munit.FunSuite:

  private val today = LocalDate.parse("2026-11-13")
  private val pleasant = Valence.of(4).get
  private val water = WaterCardView(today, totalMl = 1250, goalMl = 2000, entries = 4)

  /** Answers every document with bytes that depend on it, and remembers the documents. */
  private final class CountingRasteriser extends Rasteriser:
    val documents: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      documents += svg
      Right(PngImage(IArray.unsafeFromArray(s"png:${svg.hashCode}".getBytes(UTF_8))))

  private final class Rig(rasteriser: Rasteriser):
    val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val images: StatusImages = StatusImages.over(rasteriser, log += _)

  test("a mood state is drawn once and its key names the state, not a person"):
    val rasteriser = CountingRasteriser()
    val rig = Rig(rasteriser)
    val first = rig.images.cards.mood(MoodCardView(pleasant, None)).get
    val again = rig.images.cards.mood(MoodCardView(pleasant, None)).get
    assertEquals(first, again)
    assertEquals(first.assetKey, "mood.blob.v6.e3.plain.light")
    assertEquals(first.kind, MediaKind.Photo)
    assertEquals(first.mimeType, "image/png")
    assertEquals(first.altText, "Mood: Pleasant.")
    assertEquals(rasteriser.documents.size, 1, "the second request reuses the stored image")
    assert(rig.images.store.load(first.assetKey).isDefined, "the adapter finds the bytes under the key")

  test("the energy step is part of the mood state"):
    val rig = Rig(CountingRasteriser())
    val withEnergy = rig.images.cards.mood(MoodCardView(pleasant, Energy.of(2))).get
    assertEquals(withEnergy.assetKey, "mood.blob.v6.e2.energy.light")
    assert(withEnergy.altText.contains("Energy"), withEnergy.altText)

  test("a water card is keyed by its bytes and carries the day's figures as its text equivalent"):
    val rig = Rig(CountingRasteriser())
    val card = rig.images.cards.water(water).get
    assert(card.assetKey.matches("card\\.[0-9a-f]{64}"), card.assetKey)
    assert(card.altText.contains("1.3 of 2.0 l (62%)"), card.altText)
    assert(card.altText.contains("4 entries"), card.altText)
    assertEquals(rig.images.cards.water(water).map(_.assetKey), Some(card.assetKey), "the same day is the same key")
    assertNotEquals(rig.images.cards.water(water.copy(totalMl = 1500)).map(_.assetKey), Some(card.assetKey))

  test("the daily summary states the doses with the product's status words, and leaves out an absent domain"):
    val rig = Rig(CountingRasteriser())
    val marks = List(DoseMark.TakenOnTime, DoseMark.TakenLate, DoseMark.Skipped, DoseMark.NotConfirmed, DoseMark.Open)
    val full = rig.images.cards.day(DayCardView(today, Some(MoodCardView(pleasant, None)), Some(marks), Some(water))).get
    assert(full.altText.contains("2 of 5 scheduled doses recorded as taken"), full.altText)
    assert(full.altText.contains("1 skipped, 1 not confirmed, 1 still open"), full.altText)
    assert(full.altText.contains("Water: 1.3 of 2.0 l."), full.altText)
    val bare = rig.images.cards.day(DayCardView(today, None, None, None)).get
    assert(!bare.altText.contains("Medication") && !bare.altText.contains("Water"), bare.altText)
    assert(bare.altText.contains("No mood entry today."), bare.altText)

  test("a card that cannot be drawn gives no image, and the log names the template and the failure only"):
    val failing: Rasteriser = (_, _) => Left(RasterFailure.TimedOut)
    val rig = Rig(failing)
    assertEquals(rig.images.cards.water(water), None)
    assertEquals(rig.images.cards.mood(MoodCardView(pleasant, None)), None)
    assertEquals(
      rig.log.toList,
      List(
        "status image not drawn: template=card.water.v1 reason=TimedOut",
        "status image not drawn: template=card.mood.v1 reason=TimedOut"
      )
    )

  test("the asset store drops the image that was read longest ago"):
    val store = InMemoryAssetStore(maximumImages = 2)
    store.put("a", Array[Byte](1))
    store.put("b", Array[Byte](2))
    assert(store.load("a").isDefined, "reading a makes b the eldest")
    store.put("c", Array[Byte](3))
    assertEquals(List("a", "b", "c").filter(store.holds), List("a", "c"))

  test("without renderer settings there is no image, and a binary that does not exist fails the start"):
    assertEquals(StatusImages.from(None, _ => ()).cards.water(water), None)
    val missing = RenderSettings("/nonexistent/resvg", sys.env("DOSEGRAM_FONTS_DIR"))
    val error = intercept[IllegalArgumentException](StatusImages.from(Some(missing), _ => ()))
    assertEquals(error.getMessage, "RESVG_PATH does not name an executable")

  test("with the resvg binary of this machine every card is a PNG"):
    val binary = sys.env
      .getOrElse("PATH", "")
      .split(':')
      .iterator
      .filter(_.nonEmpty)
      .map(Path.of(_, "resvg"))
      .find(Files.isExecutable(_))
    assume(binary.isDefined, "no resvg on the PATH")
    val log = mutable.ListBuffer.empty[String]
    val images = StatusImages.from(Some(RenderSettings(binary.get.toString, sys.env("DOSEGRAM_FONTS_DIR"))), log += _)
    val drawn = List(
      images.cards.mood(MoodCardView(pleasant, Energy.of(2))),
      images.cards.water(water),
      images.cards.day(DayCardView(today, Some(MoodCardView(pleasant, None)), Some(List(DoseMark.Open)), Some(water)))
    )
    assertEquals(log.toList, Nil)
    drawn.foreach: card =>
      val png = images.store.load(card.get.assetKey).get
      assertEquals(png.take(4).toList, List(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte))
      assert(png.length > 5000, s"${card.get.assetKey}: ${png.length} bytes")
end StatusImagesSuite
