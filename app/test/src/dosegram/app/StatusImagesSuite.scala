package dosegram.app

import dosegram.contracts.MediaKind
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.CardLook
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.SleepBar
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.SleepCardView
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
  private val light = CardLook(ImageTheme.Light, isDiscreet = false)

  /** Answers every document with bytes that depend on it, and remembers the documents. */
  private final class CountingRasteriser extends Rasteriser:
    val documents: mutable.ListBuffer[String] = mutable.ListBuffer.empty

    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      documents += svg
      Right(PngImage(IArray.unsafeFromArray(s"png:${svg.hashCode}".getBytes(UTF_8))))

  private final class Rig(rasteriser: Rasteriser):
    val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val images: StatusImages = StatusImages.over(rasteriser, StatusImages.Stores.inMemory, log += _)

  test("a mood state is drawn once and its key names the state, not a person"):
    val rasteriser = CountingRasteriser()
    val rig = Rig(rasteriser)
    val first = rig.images.cards.mood(MoodCardView(pleasant, None), light).get
    val again = rig.images.cards.mood(MoodCardView(pleasant, None), light).get
    assertEquals(first, again)
    assertEquals(first.assetKey, "mood.blob.v6.e3.plain.light")
    assertEquals(first.kind, MediaKind.Photo)
    assertEquals(first.mimeType, "image/png")
    assertEquals(first.altText, "Mood: Pleasant.")
    assertEquals(rasteriser.documents.size, 1, "the second request reuses the stored image")
    assert(rig.images.store.load(first.assetKey).isDefined, "the adapter finds the bytes under the key")

  test("the energy step is part of the mood state"):
    val rig = Rig(CountingRasteriser())
    val withEnergy = rig.images.cards.mood(MoodCardView(pleasant, Energy.of(2)), light).get
    assertEquals(withEnergy.assetKey, "mood.blob.v6.e2.energy.light")
    assert(withEnergy.altText.contains("Energy"), withEnergy.altText)

  test("a water card is keyed by its bytes and carries the day's figures as its text equivalent"):
    val rig = Rig(CountingRasteriser())
    val card = rig.images.cards.water(water, light).get
    assert(card.assetKey.matches("card\\.[0-9a-f]{64}"), card.assetKey)
    assert(card.altText.contains("1.3 of 2.0 l (62%)"), card.altText)
    assert(card.altText.contains("4 entries"), card.altText)
    assertEquals(rig.images.cards.water(water, light).map(_.assetKey), Some(card.assetKey), "the same day is the same key")
    assertNotEquals(rig.images.cards.water(water.copy(totalMl = 1500), light).map(_.assetKey), Some(card.assetKey))

  test("the sleep regularity card is personal, behind a spoiler in discreet mode, and states its source"):
    val rig = Rig(CountingRasteriser())
    val window = DateRange.endingOn(today, 14)
    val figures = SleepRegularityFigures(window, List(SleepBar(today, 330, 780, isNap = false)), 1, None, None, None, None, None, Some(450))
    val card = rig.images.cards.sleep(figures, light).get
    assert(card.assetKey.matches("card\\.[0-9a-f]{64}"), card.assetKey)
    assertEquals(card.hasSpoiler, false)
    assert(card.altText.startsWith("Sleep, 31 Oct to 13 Nov. 1 night from your check-ins and entries."), card.altText)
    assert(card.altText.contains("Your goal is 7 h 30 min."), card.altText)
    val discreet = CardLook(ImageTheme.Dark, isDiscreet = true)
    assertEquals(rig.images.cards.sleep(figures, discreet).map(_.hasSpoiler), Some(true))

  test("the daily summary states the doses with the product's status words, and leaves out an absent domain"):
    val rig = Rig(CountingRasteriser())
    val marks = List(DoseMark.TakenOnTime, DoseMark.TakenLate, DoseMark.Skipped, DoseMark.NotConfirmed, DoseMark.Open)
    val full = rig.images.cards.day(DayCardView(today, Some(MoodCardView(pleasant, None)), Some(marks), Some(water), None), light).get
    assert(full.altText.contains("2 of 5 scheduled doses recorded as taken"), full.altText)
    assert(full.altText.contains("1 skipped, 1 not confirmed, 1 still open"), full.altText)
    assert(full.altText.contains("Water: 1.3 of 2.0 l."), full.altText)
    val bare = rig.images.cards.day(DayCardView(today, None, None, None, None), light).get
    val slept = rig.images.cards.day(DayCardView(today, None, None, None, Some(SleepCardView(Some(430), 450))), light).get
    assert(slept.altText.contains("Sleep: 7 h 10 min, from your check-ins."), slept.altText)
    val unslept = rig.images.cards.day(DayCardView(today, None, None, None, Some(SleepCardView(None, 450))), light).get
    assert(unslept.altText.contains("Sleep: no record yet."), unslept.altText)
    assert(!bare.altText.contains("Medication") && !bare.altText.contains("Water"), bare.altText)
    assert(bare.altText.contains("No mood entry today."), bare.altText)

  test("the image theme the account chose draws the card; auto is dark while no colour scheme is known (VISUALS 3.3.3)"):
    val rasteriser = CountingRasteriser()
    val rig = Rig(rasteriser)
    val dark = CardLook(ImageTheme.Dark, isDiscreet = false)
    assertEquals(rig.images.cards.mood(MoodCardView(pleasant, None), dark).map(_.assetKey), Some("mood.blob.v6.e3.plain.dark"))
    val auto = CardLook(ImageTheme.Auto, isDiscreet = false)
    assertEquals(rig.images.cards.mood(MoodCardView(pleasant, None), auto).map(_.assetKey), Some("mood.blob.v6.e3.plain.dark"))
    assertEquals(rasteriser.documents.size, 1, "auto and dark are the same picture")
    val onLight = rig.images.cards.water(water, light).get
    val onDark = rig.images.cards.water(water, dark).get
    assertNotEquals(onDark.assetKey, onLight.assetKey)

  test("discreet mode sends a personal card behind a spoiler, and the mood key says which mode drew it"):
    val rig = Rig(CountingRasteriser())
    val discreet = light.copy(isDiscreet = true)
    assertEquals(
      rig.images.cards.mood(MoodCardView(pleasant, None), discreet).map(_.assetKey),
      Some("mood.blob.v6.e3.plain.light.discreet")
    )
    assertEquals(rig.images.cards.water(water, light).map(_.hasSpoiler), Some(false))
    assertEquals(rig.images.cards.water(water, discreet).map(_.hasSpoiler), Some(true))
    val day = DayCardView(today, None, Some(List(DoseMark.Open)), Some(water), None)
    assertEquals(rig.images.cards.day(day, discreet).map(_.hasSpoiler), Some(true))

  test("a card that cannot be drawn gives no image, and the log names the template and the failure only"):
    val failing: Rasteriser = (_, _) => Left(RasterFailure.TimedOut)
    val rig = Rig(failing)
    assertEquals(rig.images.cards.water(water, light), None)
    assertEquals(rig.images.cards.mood(MoodCardView(pleasant, None), light), None)
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
    assertEquals(StatusImages.from(None, StatusImages.Stores.inMemory, _ => ()).cards.water(water, light), None)
    val missing = RenderSettings("/nonexistent/resvg", sys.env("DOSEGRAM_FONTS_DIR"))
    val error = intercept[IllegalArgumentException](StatusImages.from(Some(missing), StatusImages.Stores.inMemory, _ => ()))
    assertEquals(error.getMessage, "RESVG_PATH does not name an executable")

  test("with the resvg binary of this machine every card is a PNG"):
    val binary = sys.env
      .getOrElse("PATH", "")
      .split(':')
      .iterator
      .filter(_.nonEmpty)
      .map(Path.of(_, "resvg"))
      .find(Files.isExecutable(_))
    if sys.env.get("DOSEGRAM_REQUIRE_RESVG").contains("true") then assert(binary.isDefined, "no resvg on the PATH")
    else assume(binary.isDefined, "no resvg on the PATH")
    val log = mutable.ListBuffer.empty[String]
    val images = StatusImages.from(Some(RenderSettings(binary.get.toString, sys.env("DOSEGRAM_FONTS_DIR"))), StatusImages.Stores.inMemory, log += _)
    val drawn = List(
      images.cards.mood(MoodCardView(pleasant, Energy.of(2)), light),
      images.cards.water(water, light),
      images.cards.day(DayCardView(today, Some(MoodCardView(pleasant, None)), Some(List(DoseMark.Open)), Some(water), None), light)
    )
    assertEquals(log.toList, Nil)
    drawn.foreach: card =>
      val png = images.store.load(card.get.assetKey).get
      assertEquals(png.take(4).toList, List(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte))
      assert(png.length > 5000, s"${card.get.assetKey}: ${png.length} bytes")
end StatusImagesSuite
