package dosegram.app

import dosegram.adapter.telegram.BotApi
import dosegram.adapter.telegram.ChatAction
import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.adapter.telegram.TelegramTransport
import dosegram.adapter.telegram.WireReply
import dosegram.adapter.telegram.WireRequest
import dosegram.contracts.AttachmentRef
import dosegram.contracts.ChatRef
import dosegram.contracts.MediaKind
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.Clock
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.SleepBar
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.FoodCardView
import dosegram.core.ports.FoodLineView
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.SleepCardView
import dosegram.core.ports.WaterCardView
import dosegram.infra.RenderSettings
import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser
import ox.discard

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import scala.collection.mutable

/** The status cards of the chat flows on the report renderer (ADR-022): a core view is laid out and then drawn, its
  * bytes are kept where the adapter loads them, and the reference carries the card's own text equivalent. The last test runs the real
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

  /** The cards as the report worker sends them (ROADMAP G4.3): laid out, then drawn for `chat`. */
  private final class DrawnCards(images: StatusImages):
    private def drawn(page: Option[CardPage], chat: Option[ChatRef]): Option[AttachmentRef] =
      page.flatMap(images.drawing.draw(_, chat).toOption)
    def mood(view: MoodCardView, look: CardLook, chat: Option[ChatRef] = None) = drawn(images.cards.mood(view, look), chat)
    def water(view: WaterCardView, look: CardLook, chat: Option[ChatRef] = None) = drawn(images.cards.water(view, look), chat)
    def day(view: DayCardView, look: CardLook, chat: Option[ChatRef] = None) = drawn(images.cards.day(view, look), chat)
    def sleep(view: SleepRegularityFigures, look: CardLook) = drawn(images.cards.sleep(view, look), None)
    def food(view: FoodCardView, look: CardLook) = drawn(images.cards.food(view, look), None)

  extension (images: StatusImages) private def drawn: DrawnCards = DrawnCards(images)

  private final class Rig(rasteriser: Rasteriser):
    val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
    val images: StatusImages = StatusImages.over(rasteriser, StatusImages.Stores.inMemory, log += _)

  test("the water image, its alt text and the chat caption state the same litres and percentage"):
    val litresAndPercent = """(\d+\.\d+) of (\d+\.\d+) l \((\d+)%\)""".r
    for totalMl <- List(150, 1250, 1450, 1950, 1995, 2000, 4750) do
      val rig = Rig(CountingRasteriser())
      val view = water.copy(totalMl = totalMl)
      val alt = rig.images.drawn.water(view, light).get.altText
      val caption = dosegram.core.domain.copy.StatsCopy
        .waterDay(Some(dosegram.core.domain.insights.HydrationDay(totalMl, view.entries, Some(view.goalMl))))
        .get
      val inAlt = litresAndPercent.findFirstMatchIn(alt).map(_.subgroups)
      val inCaption = litresAndPercent.findFirstMatchIn(caption).map(_.subgroups)
      assert(inAlt.isDefined, alt)
      assertEquals(inAlt, inCaption, s"$totalMl ml: '$alt' against '$caption'")
      val drawn = rig.images.store.load(rig.images.drawn.water(view, light).get.assetKey)
      assert(drawn.isDefined)
      // The volume is never rounded up to the goal: a day short of it never reads as "2.0 of 2.0 l".
      val (shown, goal) = (BigDecimal(inAlt.get.head), BigDecimal(inAlt.get(1)))
      assertEquals(shown >= goal, totalMl >= view.goalMl, s"$totalMl ml shows $shown of $goal")

  /** Answers after `delayMillis`, like a rasteriser on a loaded host. */
  private final class SlowRasteriser(delayMillis: Long) extends Rasteriser:
    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      Thread.sleep(delayMillis)
      Right(PngImage(IArray.unsafeFromArray(s"png:${svg.hashCode}".getBytes(UTF_8))))

  private val chat = dosegram.contracts.ChatRef("telegram", "7001")

  test("a drawing that runs past a second shows the photo upload status in its chat, once (VISUALS VA-FB-02)"):
    val shown = java.util.concurrent.ConcurrentLinkedQueue[dosegram.contracts.ChatRef]()
    val images = StatusImages.over(SlowRasteriser(1400), StatusImages.Stores.inMemory, _ => ())
    images.notices.attach("telegram", chat => { shown.add(chat); () })
    assert(images.drawn.water(water, light, Some(chat)).isDefined)
    assertEquals(shown.toArray.toList, List(chat))

  test("a quick drawing shows no status, and a drawing for no chat or another vendor shows none either"):
    val shown = java.util.concurrent.ConcurrentLinkedQueue[dosegram.contracts.ChatRef]()
    val quick = StatusImages.over(CountingRasteriser(), StatusImages.Stores.inMemory, _ => ())
    quick.notices.attach("telegram", chat => { shown.add(chat); () })
    assert(quick.drawn.water(water, light, Some(chat)).isDefined)
    val slow = StatusImages.over(SlowRasteriser(1400), StatusImages.Stores.inMemory, _ => ())
    slow.notices.attach("telegram", chat => { shown.add(chat); () })
    assert(slow.drawn.water(water, light, None).isDefined)
    assert(slow.drawn.water(water.copy(totalMl = 700), light, Some(dosegram.contracts.ChatRef("console", "owner"))).isDefined)
    assertEquals(shown.toArray.toList, Nil)

  /** The fake wire, with every `sendChatAction` held at the wire until `release` opens: a Bot API that hangs. */
  private final class HeldStatusWire(release: java.util.concurrent.CountDownLatch) extends TelegramTransport:
    val fake: FakeTelegramTransport = FakeTelegramTransport(Clock.system)
    val arrived: java.util.concurrent.ConcurrentLinkedQueue[String] = java.util.concurrent.ConcurrentLinkedQueue()

    override def call(request: WireRequest): WireReply =
      if request.method == "sendChatAction" then
        arrived.add(request.params("chat_id").num.toLong.toString)
        release.await()
      fake.call(request)

  /** Evaluates `condition` until it holds or the deadline passes, and never once more after it held. */
  private def until(deadlineMillis: Long)(condition: => Boolean): Boolean =
    val end = System.currentTimeMillis() + deadlineMillis
    var met = condition
    while !met && System.currentTimeMillis() < end do
      Thread.sleep(20)
      met = condition
    met

  test("a status that hangs on the wire holds up neither its drawing nor the status of the next drawing (VA-FB-02)"):
    val release = java.util.concurrent.CountDownLatch(1)
    val wire = HeldStatusWire(release)
    val api = BotApi(wire)
    val images = StatusImages.over(SlowRasteriser(1400), StatusImages.Stores.inMemory, _ => ())
    images.notices.attach("telegram", target => api.sendChatAction(target.chatId.toLong, ChatAction.UploadPhoto).discard)
    val other = dosegram.contracts.ChatRef("telegram", "7002")
    try
      val started = System.nanoTime()
      assert(images.drawn.water(water, light, Some(chat)).isDefined)
      assert(images.drawn.water(water.copy(totalMl = 700), light, Some(other)).isDefined)
      val tookMillis = (System.nanoTime() - started) / 1000000
      assert(tookMillis < 4000, s"two drawings took $tookMillis ms behind a hanging status")
      assert(until(2000)(wire.arrived.size == 2), s"both statuses reach the wire: ${wire.arrived}")
      assertEquals(wire.arrived.toArray.toList.map(_.toString).sorted, List("7001", "7002"))
    finally release.countDown()
    assert(until(2000)(wire.fake.calls("sendChatAction").size == 2), "the held statuses complete once the wire answers")

  test("statuses underway are bounded: one more is dropped and logged by kind, and a free slot takes the next"):
    val release = java.util.concurrent.CountDownLatch(1)
    val arrived = java.util.concurrent.atomic.AtomicInteger()
    val log = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val notices = DrawingNotices(line => log.add(line).discard, maxUnderway = 2)
    notices.attach("telegram", _ => { arrived.incrementAndGet(); release.await() })
    try
      val caller = Thread.ofVirtual().start(() => (1 to 3).foreach(i => notices.drawing(dosegram.contracts.ChatRef("telegram", s"700$i"))))
      caller.join(2000)
      assert(!caller.isAlive, "a held status never holds up its caller")
      assert(until(2000)(arrived.get() == 2), s"two statuses underway, ${arrived.get()} arrived")
      assertEquals(log.toArray.toList, List("drawing status dropped: 2 underway"))
    finally release.countDown()
    // The held statuses free their slots just after they return: a status asked for before that is dropped (one more
    // line), and the first one asked for after it is sent (no new line).
    val accepted = until(2000):
      val dropped = log.size
      notices.drawing(chat)
      log.size == dropped
    assert(accepted, "a freed slot takes the next status")
    assert(until(2000)(arrived.get() == 3), s"the next status is sent once, ${arrived.get()} arrived")

  test("a status that throws is logged by its kind, and the drawing goes on"):
    val log = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val notices = DrawingNotices(line => log.add(line).discard)
    notices.attach("telegram", _ => throw IllegalStateException("chat 7001 is gone"))
    notices.drawing(chat)
    assert(until(2000)(!log.isEmpty), "the failure is logged")
    assertEquals(log.toArray.toList, List("drawing status failed: IllegalStateException"))

  test("a mood state is drawn once and its key names the state, not a person"):
    val rasteriser = CountingRasteriser()
    val rig = Rig(rasteriser)
    val first = rig.images.drawn.mood(MoodCardView(pleasant, None), light).get
    val again = rig.images.drawn.mood(MoodCardView(pleasant, None), light).get
    assertEquals(first, again)
    assertEquals(first.assetKey, "mood.blob.v6.e3.plain.light")
    assertEquals(first.kind, MediaKind.Photo)
    assertEquals(first.mimeType, "image/png")
    assertEquals(first.altText, "Mood: Pleasant.")
    assertEquals(rasteriser.documents.size, 1, "the second request reuses the stored image")
    assert(rig.images.store.load(first.assetKey).isDefined, "the adapter finds the bytes under the key")

  test("the energy step is part of the mood state"):
    val rig = Rig(CountingRasteriser())
    val withEnergy = rig.images.drawn.mood(MoodCardView(pleasant, Energy.of(2)), light).get
    assertEquals(withEnergy.assetKey, "mood.blob.v6.e2.energy.light")
    assert(withEnergy.altText.contains("Energy"), withEnergy.altText)

  test("a water card is keyed by its bytes and carries the day's figures as its text equivalent"):
    val rig = Rig(CountingRasteriser())
    val card = rig.images.drawn.water(water, light).get
    assert(card.assetKey.matches("card\\.[0-9a-f]{64}"), card.assetKey)
    assert(card.altText.contains("1.25 of 2.0 l (62%)"), card.altText)
    assert(card.altText.contains("4 entries"), card.altText)
    assertEquals(rig.images.drawn.water(water, light).map(_.assetKey), Some(card.assetKey), "the same day is the same key")
    assertNotEquals(rig.images.drawn.water(water.copy(totalMl = 1500), light).map(_.assetKey), Some(card.assetKey))

  test("the sleep regularity card is personal, behind a spoiler in discreet mode, and states its source"):
    val rig = Rig(CountingRasteriser())
    val window = DateRange.endingOn(today, 14)
    val figures = SleepRegularityFigures(window, List(SleepBar(today, 330, 780, isNap = false)), 1, None, None, None, None, None, Some(450))
    val card = rig.images.drawn.sleep(figures, light).get
    assert(card.assetKey.matches("card\\.[0-9a-f]{64}"), card.assetKey)
    assertEquals(card.hasSpoiler, false)
    assert(card.altText.startsWith("Sleep, 31 Oct to 13 Nov. 1 night from your check-ins and entries."), card.altText)
    assert(card.altText.contains("Your goal is 7 h 30 min."), card.altText)
    val discreet = CardLook(ImageTheme.Dark, isDiscreet = true)
    assertEquals(rig.images.drawn.sleep(figures, discreet).map(_.hasSpoiler), Some(true))

  test("the daily summary states the doses with the product's status words, and leaves out an absent domain"):
    val rig = Rig(CountingRasteriser())
    val marks = List(DoseMark.TakenOnTime, DoseMark.TakenLate, DoseMark.Skipped, DoseMark.NotConfirmed, DoseMark.Open)
    val full = rig.images.drawn.day(DayCardView(today, Some(MoodCardView(pleasant, None)), Some(marks), Some(water), None), light).get
    assert(full.altText.contains("2 of 5 scheduled doses recorded as taken"), full.altText)
    assert(full.altText.contains("1 skipped, 1 not confirmed, 1 still open"), full.altText)
    assert(full.altText.contains("Water: 1.25 of 2.0 l."), full.altText)
    val bare = rig.images.drawn.day(DayCardView(today, None, None, None, None), light).get
    val slept = rig.images.drawn.day(DayCardView(today, None, None, None, Some(SleepCardView(Some(430), 450))), light).get
    assert(slept.altText.contains("Sleep: 7 h 10 min, from your check-ins."), slept.altText)
    val unslept = rig.images.drawn.day(DayCardView(today, None, None, None, Some(SleepCardView(None, 450))), light).get
    assert(unslept.altText.contains("Sleep: no record yet."), unslept.altText)
    assert(!bare.altText.contains("Medication") && !bare.altText.contains("Water"), bare.altText)
    assert(bare.altText.contains("No mood entry today."), bare.altText)

  test("the food day card is personal, lists the meals with their times, and the daily summary states the meals"):
    val rig = Rig(CountingRasteriser())
    val mains = Map(MealType.Breakfast -> LocalTime.of(8, 10), MealType.Dinner -> LocalTime.of(19, 40))
    val card = rig.images.drawn.food(FoodCardView(today, mains, 1, FoodLineView(2, 3)), light).get
    assert(card.assetKey.matches("card\\.[0-9a-f]{64}"), card.assetKey)
    assertEquals(card.hasSpoiler, false)
    assert(card.altText.contains("breakfast 08:10, dinner 19:40, 1 snack. 2 of 3 meals, 1 snack."), card.altText)
    val discreet = CardLook(ImageTheme.Dark, isDiscreet = true)
    assertEquals(rig.images.drawn.food(FoodCardView(today, mains, 1, FoodLineView(2, 3)), discreet).map(_.hasSpoiler), Some(true))
    val day = rig.images.drawn.day(DayCardView(today, None, None, None, None, Some(FoodLineView(2, 3))), light).get
    assert(day.altText.contains("Food: 2 of 3 meals logged."), day.altText)
    val unplanned = rig.images.drawn.day(DayCardView(today, None, None, None, None, Some(FoodLineView(2, 0))), light).get
    assert(unplanned.altText.contains("Food: 2 meals logged."), unplanned.altText)

  test("the image theme the account chose draws the card; auto is dark while no colour scheme is known (VISUALS 3.3.3)"):
    val rasteriser = CountingRasteriser()
    val rig = Rig(rasteriser)
    val dark = CardLook(ImageTheme.Dark, isDiscreet = false)
    assertEquals(rig.images.drawn.mood(MoodCardView(pleasant, None), dark).map(_.assetKey), Some("mood.blob.v6.e3.plain.dark"))
    val auto = CardLook(ImageTheme.Auto, isDiscreet = false)
    assertEquals(rig.images.drawn.mood(MoodCardView(pleasant, None), auto).map(_.assetKey), Some("mood.blob.v6.e3.plain.dark"))
    assertEquals(rasteriser.documents.size, 1, "auto and dark are the same picture")
    val onLight = rig.images.drawn.water(water, light).get
    val onDark = rig.images.drawn.water(water, dark).get
    assertNotEquals(onDark.assetKey, onLight.assetKey)

  test("discreet mode sends a personal card behind a spoiler, and the mood key says which mode drew it"):
    val rig = Rig(CountingRasteriser())
    val discreet = light.copy(isDiscreet = true)
    assertEquals(
      rig.images.drawn.mood(MoodCardView(pleasant, None), discreet).map(_.assetKey),
      Some("mood.blob.v6.e3.plain.light.discreet")
    )
    assertEquals(rig.images.drawn.water(water, light).map(_.hasSpoiler), Some(false))
    assertEquals(rig.images.drawn.water(water, discreet).map(_.hasSpoiler), Some(true))
    val day = DayCardView(today, None, Some(List(DoseMark.Open)), Some(water), None)
    assertEquals(rig.images.drawn.day(day, discreet).map(_.hasSpoiler), Some(true))

  test("a card that cannot be drawn gives no image, and the log names the template and the failure only"):
    val failing: Rasteriser = (_, _) => Left(RasterFailure.TimedOut)
    val rig = Rig(failing)
    assertEquals(rig.images.drawn.water(water, light), None)
    assertEquals(rig.images.drawn.mood(MoodCardView(pleasant, None), light), None)
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
      images.drawn.mood(MoodCardView(pleasant, Energy.of(2)), light),
      images.drawn.water(water, light),
      images.drawn.day(DayCardView(today, Some(MoodCardView(pleasant, None)), Some(List(DoseMark.Open)), Some(water), None), light)
    )
    assertEquals(log.toList, Nil)
    drawn.foreach: card =>
      val png = images.store.load(card.get.assetKey).get
      assertEquals(png.take(4).toList, List(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte))
      assert(png.length > 5000, s"${card.get.assetKey}: ${png.length} bytes")
end StatusImagesSuite
