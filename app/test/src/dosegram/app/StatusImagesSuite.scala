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
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.Clock
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.SleepBar
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.insights.Stat
import dosegram.core.domain.insights.WeekdayMood
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.FoodCardView
import dosegram.core.ports.FoodLineView
import dosegram.core.ports.MedicationStripRow
import dosegram.core.ports.MedicationStripView
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.SleepCardView
import dosegram.core.ports.WaterCardView
import dosegram.core.ports.WeekCardView
import dosegram.core.ports.WeekDayNight
import dosegram.core.ports.WeekDayWater
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

  test("a card laid out in Ukrainian is drawn with the Ukrainian words and cached apart from the English one (G2.22)"):
    val rasteriser = CountingRasteriser()
    val rig = Rig(rasteriser)
    val english = rig.images.cards.water(water, light).get
    val ukrainian = Lang.within(Lang.Uk)(rig.images.cards.water(water, light)).get
    assert(english.altText.contains("1.25 of 2.0 l"), english.altText)
    assert(ukrainian.altText.contains("1,25 з 2,0 л"), ukrainian.altText)
    assert(ukrainian.svg.contains("Вода"), "the image itself is in Ukrainian")
    // The mood card is shared between accounts by its state: the language is part of the key.
    val view = MoodCardView(pleasant, None)
    val englishMood = rig.images.cards.mood(view, light).get
    val ukrainianMood = Lang.within(Lang.Uk)(rig.images.cards.mood(view, light)).get
    assertNotEquals(ukrainianMood.sharedKey, englishMood.sharedKey)
    assert(ukrainianMood.altText.contains("Приємно"), ukrainianMood.altText)

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
    assertEquals(first.assetKey, "mood.dg-blob-2.blob.v6.e3.plain.light")
    assertEquals(first.kind, MediaKind.Photo)
    assertEquals(first.mimeType, "image/png")
    assertEquals(first.altText, "Mood: Pleasant.")
    assertEquals(rasteriser.documents.size, 1, "the second request reuses the stored image")
    assert(rig.images.store.load(first.assetKey).isDefined, "the adapter finds the bytes under the key")

  test("the energy step is part of the mood state"):
    val rig = Rig(CountingRasteriser())
    val withEnergy = rig.images.drawn.mood(MoodCardView(pleasant, Energy.of(2)), light).get
    assertEquals(withEnergy.assetKey, "mood.dg-blob-2.blob.v6.e2.energy.light")
    assert(withEnergy.altText.contains("Energy"), withEnergy.altText)

  test("an animated mood page is keyed by its state and theme only; the still is laid out beside it (VISUALS 4.6)"):
    val rig = Rig(CountingRasteriser())
    val animated = light.copy(isAnimated = true)
    val plain = rig.images.cards.mood(MoodCardView(pleasant, None), animated).get
    val withEnergy = rig.images.cards.mood(MoodCardView(pleasant, Energy.of(3)), animated).get
    val ukrainian = Lang.within(Lang.Uk)(rig.images.cards.mood(MoodCardView(pleasant, None), animated)).get
    val motion = plain.motion.getOrElse(fail("no animated form"))
    assertEquals(motion.sharedKey, "mood.motion.dg-blob-2.dg-motion-1.blob.v6.e3.light")
    assertEquals(withEnergy.motion.map(_.sharedKey), Some(motion.sharedKey), "no words, so energy given or not is one video")
    assertEquals(ukrainian.motion.map(_.sharedKey), Some(motion.sharedKey), "and one video for every language")
    assertEquals((motion.frames.size, motion.framesPerSecond, motion.fileName), (60, 20, "dosegram-mood.mp4"))
    assert(motion.frames.forall(_.contains("""width="512"""")))
    assertEquals(rig.images.cards.mood(MoodCardView(pleasant, None), light).get.motion, None, "off unless asked for")
    assertEquals(
      rig.images.cards.mood(MoodCardView(pleasant, None), CardLook(ImageTheme.Dark, isDiscreet = false, isAnimated = true)).get.motion.map(_.sharedKey),
      Some("mood.motion.dg-blob-2.dg-motion-1.blob.v6.e3.dark")
    )

  test("a video that cannot be made leaves the still card as the answer, logged by template and kind only"):
    val rig = Rig(CountingRasteriser())
    val answer = rig.images.drawn.mood(MoodCardView(pleasant, None), light.copy(isAnimated = true)).get
    assertEquals(answer.kind, MediaKind.Photo)
    assertEquals(rig.log.toList, List("animation not drawn, still sent: template=card.mood.v1 reason=NotPng"))

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
    assert(card.altText.startsWith("Sleep, 31 Oct to 13 Nov. 1 night from your records."), card.altText)
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
    assert(slept.altText.contains("Sleep: 7 h 10 min, from your records."), slept.altText)
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
    assertEquals(rig.images.drawn.mood(MoodCardView(pleasant, None), dark).map(_.assetKey), Some("mood.dg-blob-2.blob.v6.e3.plain.dark"))
    val auto = CardLook(ImageTheme.Auto, isDiscreet = false)
    assertEquals(rig.images.drawn.mood(MoodCardView(pleasant, None), auto).map(_.assetKey), Some("mood.dg-blob-2.blob.v6.e3.plain.dark"))
    assertEquals(rasteriser.documents.size, 1, "auto and dark are the same picture")
    val onLight = rig.images.drawn.water(water, light).get
    val onDark = rig.images.drawn.water(water, dark).get
    assertNotEquals(onDark.assetKey, onLight.assetKey)

  test("discreet mode sends a personal card behind a spoiler, and the mood key says which mode drew it"):
    val rig = Rig(CountingRasteriser())
    val discreet = light.copy(isDiscreet = true)
    assertEquals(
      rig.images.drawn.mood(MoodCardView(pleasant, None), discreet).map(_.assetKey),
      Some("mood.dg-blob-2.blob.v6.e3.plain.light.discreet")
    )
    assertEquals(rig.images.drawn.water(water, light).map(_.hasSpoiler), Some(false))
    assertEquals(rig.images.drawn.water(water, discreet).map(_.hasSpoiler), Some(true))
    val day = DayCardView(today, None, Some(List(DoseMark.Open)), Some(water), None)
    assertEquals(rig.images.drawn.day(day, discreet).map(_.hasSpoiler), Some(true))

  test("a card laid out in discreet mode carries its generic caption to the photo; a named one carries none (VA-PV-03)"):
    val rig = Rig(CountingRasteriser())
    val discreet = light.copy(isDiscreet = true)
    val day = DayCardView(today, None, Some(List(DoseMark.Open)), Some(water), None)
    val shown = rig.images.cards.day(day, discreet).get
    assertEquals(shown.discreetCaption, Some("Your day so far."))
    assertEquals(rig.images.drawing.draw(shown, None).toOption.flatMap(_.caption), Some("Your day so far."))
    assert(shown.altText.contains("Water: 1.25 of 2.0 l."), shown.altText)
    assertEquals(rig.images.cards.water(water, discreet).flatMap(_.discreetCaption), Some("Water today."))
    val mood = rig.images.cards.mood(MoodCardView(pleasant, None), discreet)
    assertEquals(mood.flatMap(_.discreetCaption), Some("Mood logged."))
    val strip = MedicationStripView(today, 7, List(MedicationStripRow("Item 1", false, List.fill(7)(Nil), List.fill(7)(0))))
    assertEquals(rig.images.cards.medicationStrip(strip, discreet).map(_.discreetCaption), List(Some("By medication.")))
    val named = rig.images.cards.day(day, light).get
    assertEquals(named.discreetCaption, None)
    assertEquals(rig.images.drawing.draw(named, None).toOption.flatMap(_.caption), None)

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

  test("the week cards of 2026-11-15 are personal, state their figures, and leave out a domain the view does not show"):
    val rig = Rig(CountingRasteriser())
    val from = today.minusDays(6)
    val water = List(Some(WeekDayWater(1500, Some(2000))), Some(WeekDayWater(2400, Some(2000))), None, Some(WeekDayWater(2000, Some(2000)))) ++
      List.fill(3)(None)
    val nights = List(Some(WeekDayNight(420, Some(480)))) ++ List.fill(6)(None)
    val view = WeekCardView(from, None, Some(water), None, Some(nights))
    def draw(page: Option[CardPage]) = page.flatMap(rig.images.drawing.draw(_, None).toOption)
    val glance = draw(rig.images.cards.weekGlance(view, light)).get
    assert(glance.assetKey.matches("card\\.[0-9a-f]{64}"), glance.assetKey)
    assert(glance.altText.startsWith("Week 7 to 13 Nov."), glance.altText)
    val waterWeek = draw(rig.images.cards.waterWeek(view, light)).get
    assert(waterWeek.altText.contains("1.96 l a day on average. Goal reached on 2 of 3 recorded days."), waterWeek.altText)
    val sleepWeek = draw(rig.images.cards.sleepWeek(view, light)).get
    assert(sleepWeek.altText.contains("1 night from your records."), sleepWeek.altText)
    assertEquals(rig.images.cards.sleepWeek(view.copy(sleep = None), light), None)
    val window = DateRange.endingOn(today, 28)
    val weekdays = java.time.DayOfWeek.values.toList.map(day => WeekdayMood(day, 0, Stat.Insufficient(0, 3, window)))
    assertEquals(rig.images.cards.moodWeekday(window, weekdays, light), None, "no check-in, no card")
    val withDays = weekdays.updated(0, WeekdayMood(java.time.DayOfWeek.MONDAY, 2, Stat.Insufficient(2, 3, window)))
    val moodWeekday = draw(rig.images.cards.moodWeekday(window, withDays, CardLook(ImageTheme.Dark, isDiscreet = true))).get
    assertEquals(moodWeekday.hasSpoiler, true)
    assert(moodWeekday.altText.contains("Mon: 2 check-in days, fewer than 3"), moodWeekday.altText)

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

  test("a worker path that does not name an executable fails the start"):
    val missing = RenderSettings("/bin/sh", sys.env("DOSEGRAM_FONTS_DIR"), Some("/nonexistent/dosegram-raster"))
    val error = intercept[IllegalArgumentException](StatusImages.from(Some(missing), StatusImages.Stores.inMemory, _ => ()))
    assertEquals(error.getMessage, "RENDER_WORKER_PATH does not name an executable")

  test("with the long-lived worker of this machine every card is a PNG (ADR-022 addendum of 2026-11-15)"):
    val worker = sys.env
      .get("DOSEGRAM_RASTER_WORKER")
      .map(Path.of(_))
      .orElse(sys.env.getOrElse("PATH", "").split(':').iterator.filter(_.nonEmpty).map(Path.of(_, "dosegram-raster")).find(Files.isExecutable(_)))
    if sys.env.get("DOSEGRAM_REQUIRE_RESVG").contains("true") then assert(worker.isDefined, "no dosegram-raster")
    else assume(worker.isDefined, "no dosegram-raster")
    val log = mutable.ListBuffer.empty[String]
    // The resvg path must name an executable, but with a worker it is never run.
    val settings = RenderSettings("/bin/sh", sys.env("DOSEGRAM_FONTS_DIR"), Some(worker.get.toString))
    val images = StatusImages.from(Some(settings), StatusImages.Stores.inMemory, log += _)
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

  test("with the long-lived worker of this machine an animated mood answer is an MP4 loop with its still"):
    val worker = sys.env
      .get("DOSEGRAM_RASTER_WORKER")
      .map(Path.of(_))
      .orElse(sys.env.getOrElse("PATH", "").split(':').iterator.filter(_.nonEmpty).map(Path.of(_, "dosegram-raster")).find(Files.isExecutable(_)))
    if sys.env.get("DOSEGRAM_REQUIRE_RESVG").contains("true") then assert(worker.isDefined, "no dosegram-raster")
    else assume(worker.isDefined, "no dosegram-raster")
    val log = mutable.ListBuffer.empty[String]
    val settings = RenderSettings("/bin/sh", sys.env("DOSEGRAM_FONTS_DIR"), Some(worker.get.toString))
    // A minute for the loop, as the pixel tests give their rasteriser: the budget itself is held by MotionSuite.
    val images = StatusImages.from(Some(settings), StatusImages.Stores.inMemory, log += _, motionBudgetMillis = 60000)
    val look = light.copy(isAnimated = true)
    images.drawn.mood(MoodCardView(Valence.of(1).get, Energy.of(1)), look) // warms the worker and the encoder
    val started = System.nanoTime()
    val answer = images.drawn.mood(MoodCardView(pleasant, Energy.of(5)), look).get
    val millis = (System.nanoTime() - started) / 1_000_000
    assertEquals(log.toList, Nil)
    assertEquals((answer.kind, answer.mimeType, answer.fileName), (MediaKind.Animation, "video/mp4", Some("dosegram-mood.mp4")))
    val video = images.store.load(answer.assetKey).get
    assertEquals(String(video.slice(4, 8), UTF_8), "ftyp")
    println(s"animated mood answer: ${video.length} bytes in $millis ms (PERFORMANCE.md section 4.7)")
    assert(video.length < 1024 * 1024, s"${video.length} bytes")
    val still = answer.still.get
    assertEquals(images.store.load(still.assetKey).get.take(4).toList, List(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte))
    assertEquals(answer.altText, still.altText, "the animation has the still card's text equivalent")
end StatusImagesSuite
