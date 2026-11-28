package dosegram.app

import dosegram.infra.crypto.DataKeys
import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.application.mood.MoodStickers
import dosegram.core.domain.copy.PrivacyCopy
import dosegram.core.domain.copy.StickerCopy
import dosegram.core.scheduling.StickerSetRemoval
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.CheckInCopy
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.TelegramSettings
import dosegram.infra.TelegramUpdates
import dosegram.infra.db.Fixtures
import dosegram.infra.db.OutboxPayloads
import dosegram.infra.db.PgInboundInbox
import dosegram.infra.db.PgSuite
import dosegram.infra.db.PgUnitOfWork
import dosegram.infra.db.sql
import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser
import dosegram.infra.Metrics
import ox.supervised

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

/** The tracks together: the assembled application of [[Launch.start]] on Testcontainers Postgres, the Telegram adapter
  * of [[TelegramAssembly]] on the fake wire, the capture flows of the core, the tracking tables and the consent ledger
  * of infra, and the status cards of the report renderer. A test types and taps as the owner and reads the Bot API
  * calls and the rows. The rasteriser is a stand-in that answers every card with bytes of its own, so a test can tell
  * which picture went on the wire; [[StatusImagesSuite]] runs the real one. Nothing here connects to Telegram.
  */
class CaptureLaunchSuite extends PgSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val monday = Instant.parse("2026-09-21T08:00:00Z")
  private val owner = 7001L

  private val idle = Workers.Cadence(
    reminderPoll = Duration.ofMillis(100),
    materialiserPeriod = Duration.ofMinutes(15),
    outboxIdlePoll = Duration.ofMillis(50),
    sessionSweepPeriod = Duration.ofMillis(100),
    rollupIdlePoll = Duration.ofMillis(100),
    imagePurgePeriod = Duration.ofMillis(100)
  )

  private lazy val fixtures = Fixtures(dataSource)

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE render_assets, telegram_file_cache, report_requests, inbound_inbox, " +
            "hydration_entries, hydration_goals, hydration_presets, mood_checkins, daily_rollups, rollup_dirty, " +
            "consent_state, dose_actions, dose_occurrences, schedule_revisions, medication_schedules, " +
            "medications, inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "conversation_sessions, form_runs, callback_slots, delivery_channels, platform_identities, " +
            "worker_heartbeat CASCADE"
        )
        // consent_events is append-only for the application role; the suite owns the database.
        st.execute("ALTER TABLE consent_events DISABLE TRIGGER consent_events_append_only")
        st.execute("DELETE FROM consent_events")
        st.execute("ALTER TABLE consent_events ENABLE TRIGGER consent_events_append_only")
        st.execute("DELETE FROM users")
      finally st.close()
    }

  private def databaseUrl: String =
    val separator = if pg.jdbcUrl.contains("?") then "&" else "?"
    s"${pg.jdbcUrl}${separator}user=${pg.username}&password=${pg.password}"

  private val telegram =
    TelegramSettings("555000111:fake-wire-token", FakeTelegramTransport.Bot.id, TelegramUpdates.Poll, Set(owner))

  private def settings: Settings =
    Settings(
      enabledAdapters = List(Adapter.Telegram),
      databaseUrl = databaseUrl,
      callbackKeys = Settings.parseCallbackKeys("0:MDEyMzQ1Njc4OWFiY2RlZg").fold(error => fail(error), identity),
      role = Role.All,
      instanceId = "capture-launch",
      logLevel = LogLevel.Info,
      logFormat = LogFormat.Plain,
      consoleUserId = None,
      telegram = Some(telegram),
      publicBaseUrl = None,
      healthPort = freePort(),
      environment = Environment.Development
    )

  /** Every card becomes `png:<n>`, where `n` counts the cards drawn so far; the documents are kept for the test. */
  private final class NumberingRasteriser extends Rasteriser:
    private val documents = java.util.concurrent.CopyOnWriteArrayList[String]()

    def count: Int = documents.size

    /** Whether the card drawn as number `n` shows `text`. */
    def shows(n: Int, text: String): Boolean = documents.get(n - 1).contains(text)

    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      documents.add(svg)
      Right(PngImage(IArray.unsafeFromArray(s"png:${documents.size}".getBytes(UTF_8))))

  private final class Bot(
      val clock: SteppedClock,
      val wire: FakeTelegramTransport,
      val rasteriser: NumberingRasteriser,
      firstUpdate: Long
  ):
    private var updateSeq = firstUpdate

    private val messageMethods = Set("sendMessage", "sendPhoto", "sendAnimation", "editMessageText", "editMessageCaption")

    /** The message calls so far, in order. */
    def messages: List[FakeTelegramTransport.RecordedCall] = wire.calls.filter(call => messageMethods(call.method))

    def photos: List[FakeTelegramTransport.RecordedCall] = wire.calls("sendPhoto")

    def body(call: FakeTelegramTransport.RecordedCall): String =
      call.text("text").orElse(call.text("caption")).getOrElse("")

    def buttons(call: FakeTelegramTransport.RecordedCall): List[(String, String)] =
      call.request.params.obj
        .get("reply_markup")
        .toList
        .flatMap(_("inline_keyboard").arr.toList)
        .flatMap(_.arr.toList)
        .map(button => (button("text").str, button("callback_data").str))

    /** The id of the message a call sent or edited. */
    def messageId(call: FakeTelegramTransport.RecordedCall): Long =
      call.sentMessageId.orElse(call.number("message_id")).getOrElse(fail(s"${call.method} names no message"))

    /** Types `text` and returns the message calls it caused, once `expected` of them have been made. */
    def say(text: String, expected: Int): List[FakeTelegramTransport.RecordedCall] =
      step(expected): () =>
        wire.enqueueUpdates(
          ujson.Obj(
            "update_id" -> updateSeq.toDouble,
            "message" -> ujson.Obj(
              "message_id" -> (updateSeq + 9000).toDouble,
              "from" -> ujson.Obj("id" -> owner.toDouble, "is_bot" -> false),
              "chat" -> ujson.Obj("id" -> owner.toDouble, "type" -> "private"),
              "date" -> clock.now().getEpochSecond.toDouble,
              "text" -> text
            )
          )
        )

    /** Taps `label` on the message of `card`; a photo message is reported as one, as the Bot API does. */
    def tap(card: FakeTelegramTransport.RecordedCall, label: String, expected: Int): List[FakeTelegramTransport.RecordedCall] =
      val data = buttons(card).collectFirst { case (`label`, token) => token }.getOrElse(fail(s"no button '$label'"))
      val isPhoto = Set("sendPhoto", "sendAnimation", "editMessageCaption")(card.method)
      val message = ujson.Obj(
        "message_id" -> messageId(card).toDouble,
        "chat" -> ujson.Obj("id" -> owner.toDouble, "type" -> "private"),
        "date" -> clock.now().getEpochSecond.toDouble
      )
      if isPhoto then message("photo") = ujson.Arr(ujson.Obj("file_id" -> "fake", "width" -> 1080, "height" -> 1080))
      else message("text") = "x"
      step(expected): () =>
        wire.enqueueUpdates(
          ujson.Obj(
            "update_id" -> updateSeq.toDouble,
            "callback_query" -> ujson.Obj(
              "id" -> s"query-$updateSeq",
              "from" -> ujson.Obj("id" -> owner.toDouble, "is_bot" -> false),
              "message" -> message,
              "data" -> data
            )
          )
        )

    // The adapter allows one message call per chat and second with a burst of three, on the stepped clock.
    private def step(expected: Int)(send: () => Unit): List[FakeTelegramTransport.RecordedCall] =
      clock.advance(Duration.ofSeconds(5))
      updateSeq += 1
      val before = messages.size
      send()
      val deadline = System.nanoTime() + 60.seconds.toNanos
      while messages.size < before + expected && System.nanoTime() < deadline do Thread.sleep(25)
      Thread.sleep(300)
      val caused = messages.drop(before)
      assertEquals(caused.size, expected, s"message calls: ${caused.map(call => s"${call.method}: ${body(call)}")}")
      caused

  private def launched[A](test: Bot => A): A = launchedFrom(firstUpdate = 500L)(test)

  /** One process over the suite's database: the image bytes and the file ids are in its tables, as in production. A
    * second launch in one test is the process after a restart; its updates continue after the first one's.
    */
  private def launchedFrom[A](
      firstUpdate: Long,
      lastMessageId: Long = 0L,
      drawingWith: Option[Rasteriser] = None,
      at: Instant = monday,
      role: Role = Role.All
  )(test: Bot => A): A =
    val clock = SteppedClock(at)
    val wire = FakeTelegramTransport(clock, lastMessageId = lastMessageId)
    val rasteriser = NumberingRasteriser()
    supervised:
      val config = settings.copy(role = role)
      val stores = StatusImages.Stores.durable(dataSource, telegram.botId, clock, DataKeys.development)
      // A minute for an animation, as the pixel tests give their rasteriser; the budget itself is held by MotionSuite.
      val images = StatusImages.over(drawingWith.getOrElse(rasteriser), stores, _ => (), motionBudgetMillis = 60000)
      val adapter = TelegramAssembly.adapter(config, telegram, wire, clock)(images, Some(PgInboundInbox(dataSource, DataKeys.development)))
      Launch.start(config, dataSource, Launch.Parts(clock, Map("telegram" -> adapter), idle, images), log = _ => ())
      test(Bot(clock, wire, rasteriser, firstUpdate))

  /** The numbering stand-in for the still cards, and one real 512 px PNG for every frame of an animation, so the frames
    * go through the encoder as in production. The frames drawn are counted.
    */
  private final class FramesRasteriser extends Rasteriser:
    val stills = NumberingRasteriser()
    val frames = java.util.concurrent.atomic.AtomicInteger()
    private lazy val framePng =
      val image = java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_ARGB)
      val out = java.io.ByteArrayOutputStream()
      javax.imageio.ImageIO.write(image, "png", out)
      out.toByteArray

    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      if svg.contains("""width="512"""") then
        frames.incrementAndGet()
        Right(PngImage(IArray.unsafeFromArray(framePng)))
      else stills.rasterise(svg, zoom)

  private def animateMood(accountId: UUID, discreet: Boolean = false): Unit =
    PgUnitOfWork(dataSource, () => monday, DataKeys.development).transaction: tx =>
      val settings = tx.accounts.settingsOf(accountId).get
      tx.accounts.saveSettings(accountId, settings.copy(animatedMood = true, isDiscreet = discreet), monday)

  /** One account whose Telegram identity and chat are the owner's. */
  private def seedOwner(): UUID =
    val accountId = fixtures.account()
    val channelId = fixtures.deliveryChannel(accountId, "telegram", owner.toString, monday)
    withConnection { conn =>
      given Connection = conn
      sql"""UPDATE platform_identities SET vendor_user_id = ${owner.toString}
            WHERE id = (SELECT platform_identity_id FROM delivery_channels WHERE id = $channelId)""".execute()
    }
    accountId

  private def grant(accountId: UUID, purposes: ConsentPurpose*): Unit =
    val notice = ConsentNotice(1, "seeded for the test", "en")
    PgUnitOfWork(dataSource, () => monday, DataKeys.development).transaction(tx => purposes.foreach(tx.consents.grant(accountId, _, notice, ConsentTaps(monday, monday))))

  private def strings(query: String): List[String] = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query) // a query of this suite, never input
      Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
    finally st.close()
  }

  private def uploadOf(call: FakeTelegramTransport.RecordedCall): Option[String] =
    call.request.file.map(file => String(file.bytes, UTF_8))

  test("a typed amount goes through the two-stage consent and the goal, is recorded once, and its image is a photo"):
    seedOwner()
    launched: bot =>
      val List(stageOne) = bot.say("250 ml", expected = 1): @unchecked
      assertEquals(bot.body(stageOne), ConsentCopy.statement(ConsentPurpose.Hydration))
      assertEquals(strings("SELECT 1 FROM hydration_entries"), Nil, "nothing is recorded before the consent")

      val List(stageTwo) = bot.tap(stageOne, ConsentCopy.Agree, expected = 1): @unchecked
      assertEquals(stageTwo.method, "editMessageText")
      assertEquals(strings("SELECT 1 FROM consent_events"), Nil, "the first tap records no consent")

      val List(receipt, goalAsk) = bot.tap(stageTwo, ConsentCopy.Confirm, expected = 2): @unchecked
      assertEquals(bot.body(receipt), ConsentCopy.receipt(ConsentPurpose.Hydration, "21 Sep 2026"))
      // The typed amount waits for the goal, and the card says so (walk-through of 2026-11-27).
      assertEquals(bot.body(goalAsk), s"${WaterCopy.goalAsk}\n\n${WaterCopy.goalPending(250)}")
      assertEquals(strings("SELECT purpose || ':' || action FROM consent_events"), List("hydration:granted"))
      assertEquals(strings("SELECT octet_length(notice_hash)::text FROM consent_events"), List("32"))

      // The water prompts are offered after the goal choice, never beside the goal card (ADR-023, finding 12).
      val List(echo, offer) = bot.tap(goalAsk, "2.0 l", expected = 2): @unchecked
      assertEquals(bot.body(offer), PromptCopy.offerWater, "the water prompts are offered after the goal choice")
      assertEquals(echo.method, "editMessageText")
      assertEquals(bot.body(echo), "Water +250 ml. Today: 0.25 of 2.0 l (12%).")
      assertEquals(strings("SELECT amount_ml || ' ' || source FROM hydration_entries"), List("250 bot"))
      assertEquals(strings("SELECT target_ml::text FROM hydration_goals"), List("2000"))

      val List(imagesAsk) = bot.tap(echo, WaterCopy.ShowAsImage, expected = 1): @unchecked
      assertEquals(imagesAsk.method, "sendMessage", "the water card stays; the consent is a message of its own")
      assertEquals(bot.photos, Nil, "no personal image before the images consent")
      val List(imagesConfirm) = bot.tap(imagesAsk, ConsentCopy.Agree, expected = 1): @unchecked
      val List(_, photo) = bot.tap(imagesConfirm, ConsentCopy.Confirm, expected = 2): @unchecked
      assertEquals(photo.method, "sendPhoto")
      assertEquals(bot.body(photo), "Water today: 0.25 of 2.0 l (12%)", "the caption is the text equivalent")
      assertEquals(uploadOf(photo), Some("png:1"), "the bytes of the card are the upload")
      assert(bot.rasteriser.shows(1, "Water") && bot.rasteriser.shows(1, "0.25 of 2.0 l"), "it is the vessel card, with the caption's figures")
      assertEquals(
        strings("SELECT purpose FROM consent_state WHERE granted ORDER BY purpose"),
        List("hydration", "images_in_chat")
      )

  test("a quick-add button records one entry per tap and edits the card; Undo takes the last one back"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Hydration)
    launched: bot =>
      val List(card) = bot.say("/water", expected = 1): @unchecked
      assertEquals(bot.body(card), WaterCopy.nothingLogged)
      val List(first) = bot.tap(card, "+ 300 ml", expected = 1): @unchecked
      val List(second) = bot.tap(first, "+ 500 ml", expected = 1): @unchecked
      assertEquals(List(first, second).map(_.method), List("editMessageText", "editMessageText"))
      assertEquals(bot.body(second), "Water +500 ml. Today: 0.8 l.")
      val List(undone) = bot.tap(second, "Undo", expected = 1): @unchecked
      assertEquals(bot.body(undone), "Undone. Water today: 0.3 l.")
      assertEquals(
        strings("SELECT amount_ml || ':' || COALESCE(void_reason, 'live') FROM hydration_entries ORDER BY created_at, amount_ml"),
        List("300:live", "500:undone")
      )

  test("a mood check-in is answered with the mood image; the same state is uploaded once and then sent by file id"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    launched: bot =>
      val List(prompt) = bot.say("/mood", expected = 1): @unchecked
      assertEquals(bot.body(prompt), s"${CheckInCopy.prompt}\n\n${CheckInCopy.scale}")

      val List(promptEdit, first) = bot.tap(prompt, "4", expected = 2): @unchecked
      assertEquals(promptEdit.method, "editMessageText")
      assertEquals(bot.body(promptEdit), "Pleasant · 08:00", "the prompt shows the recorded level")
      assertEquals(bot.buttons(promptEdit), Nil, "and its steps are gone")
      assertEquals(first.method, "sendPhoto")
      assertEquals(bot.body(first), "Pleasant · 08:00")
      assertEquals(bot.buttons(first).map(_._1), List(CheckInCopy.AddEnergy, "Undo"))
      assertEquals(uploadOf(first), Some("png:1"))
      assert(bot.rasteriser.shows(1, "Pleasant"), "it is the mood card of the tapped step")

      // One prompt is one check-in (owner decision 2026-11-13): the second check-in comes from a new prompt.
      val List(secondPrompt) = bot.say("/mood", expected = 1): @unchecked
      val List(_, again) = bot.tap(secondPrompt, "4", expected = 2): @unchecked
      assertEquals(again.method, "sendPhoto")
      assertEquals(uploadOf(again), None, "the second send of the state carries no upload")
      assertEquals(again.text("photo"), Some("fake-photo-2"), "it names the file id of the first upload")
      assertEquals(bot.rasteriser.count, 1, "a mood state is drawn once")
      assertEquals(strings("SELECT valence::text FROM mood_checkins ORDER BY created_at"), List("4", "4"))

      val List(energyAsk) = bot.tap(first, CheckInCopy.AddEnergy, expected = 1): @unchecked
      assertEquals(energyAsk.method, "editMessageCaption", "the photo message keeps its picture")
      val List(withEnergy) = bot.tap(energyAsk, "2", expected = 1): @unchecked
      assertEquals(bot.body(withEnergy), "Pleasant. Energy: low. · 08:00")
      assertEquals(
        strings("SELECT valence || ':' || COALESCE(energy::text, '-') FROM mood_checkins WHERE voided_at IS NULL ORDER BY created_at"),
        List("4:-", "4:2")
      )

  test("with Animated mood on, a check-in is answered with the moving blob, captioned, with its buttons and its still"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    animateMood(accountId)
    val drawing = FramesRasteriser()
    launchedFrom(firstUpdate = 500L, drawingWith = Some(drawing)): bot =>
      val List(prompt) = bot.say("/mood", expected = 1): @unchecked
      val List(_, first) = bot.tap(prompt, "4", expected = 2): @unchecked
      assertEquals(first.method, "sendAnimation")
      assertEquals(bot.body(first), "Pleasant · 08:00", "the caption names the level (VA-BL-04)")
      assertEquals(bot.buttons(first).map(_._1), List(CheckInCopy.AddEnergy, "Undo"))
      val upload = first.request.file.getOrElse(fail("the animation is uploaded"))
      assertEquals((upload.field, upload.fileName, upload.mimeType), ("animation", "dosegram-mood.mp4", "video/mp4"))
      assertEquals(String(upload.bytes.slice(4, 8), UTF_8), "ftyp", "the upload is an MP4 file")
      assertEquals(drawing.frames.get, 60, "one loop of 60 frames")
      assertEquals(drawing.stills.count, 1, "the still card is drawn too, as the fallback")
      // The adapter keeps the file id after Telegram's answer, so the row can follow the recorded call.
      eventually("the animation's file id is kept"):
        strings("SELECT media_kind FROM telegram_file_cache") == List("animation")
      assertEquals(strings("SELECT mime FROM render_assets ORDER BY mime"), List("image/png", "video/mp4"))

      // A tap on the animation edits its caption, as on a photo.
      val List(energyAsk) = bot.tap(first, CheckInCopy.AddEnergy, expected = 1): @unchecked
      assertEquals(energyAsk.method, "editMessageCaption")

      // The same state again is sent by its file id: nothing is drawn or uploaded a second time.
      val List(secondPrompt) = bot.say("/mood", expected = 1): @unchecked
      val List(_, again) = bot.tap(secondPrompt, "4", expected = 2): @unchecked
      assertEquals(again.method, "sendAnimation")
      assertEquals(uploadOf(again), None)
      assert(again.text("animation").exists(_.startsWith("fake-animation-")), again.text("animation").toString)
      assertEquals(drawing.frames.get, 60, "a state's animation is drawn once")

  /** A check-in answered by a gateway alone (no report worker), so its image waits in the queue until the next launch:
    * a claim that comes as late on the clock as the test asks, which the rehearsal of 2026-11-28 met by chance.
    */
  private def queuedCheckIn(drawing: Rasteriser, firstUpdate: Long, lastMessageId: Long, at: Instant): Unit =
    launchedFrom(firstUpdate, lastMessageId, Some(drawing), at, Role.Gateway): bot =>
      bot.say("mood 4", expected = 0)
      eventually("the image is queued")(strings("SELECT status FROM report_requests WHERE status = 'queued'").size == 1)

  test("an animated answer claimed past the still's 5 seconds but inside its own 9 is the animation (VISUALS 4.6)"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    animateMood(accountId)
    val drawing = FramesRasteriser()
    // The check-in is made 5 s after Monday 08:00 (the bot steps its clock before each update).
    queuedCheckIn(drawing, firstUpdate = 500L, lastMessageId = 0L, at = monday)
    launchedFrom(firstUpdate = 600L, lastMessageId = 100L, drawingWith = Some(drawing), at = monday.plusSeconds(12)):
      bot =>
        eventually("the answer is sent")(bot.messages.nonEmpty)
        val List(answer) = bot.messages: @unchecked
        assertEquals(answer.method, "sendAnimation")
        assertEquals(bot.body(answer), "Pleasant · 08:00")
        assertEquals(drawing.frames.get, 60)
        eventually("the request is finished")(strings("SELECT status FROM report_requests") == List("sent"))

  test("a check-in claimed long after its deadline is still the animation when its loop was drawn before"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    animateMood(accountId)
    val drawing = FramesRasteriser()
    launchedFrom(firstUpdate = 500L, drawingWith = Some(drawing)): bot =>
      val List(first) = bot.say("mood 4", expected = 1): @unchecked
      assertEquals(first.method, "sendAnimation")
      eventually("the animation's file id is kept"):
        strings("SELECT media_kind FROM telegram_file_cache") == List("animation")
    queuedCheckIn(drawing, firstUpdate = 600L, lastMessageId = 100L, at = monday.plusSeconds(60))
    // Ten minutes on the clock pass before a worker claims it: far past both deadlines, but nothing needs drawing.
    launchedFrom(firstUpdate = 700L, lastMessageId = 200L, drawingWith = Some(drawing), at = monday.plusSeconds(600)):
      bot =>
        eventually("the answer is sent")(bot.messages.nonEmpty)
        val List(answer) = bot.messages: @unchecked
        assertEquals(answer.method, "sendAnimation", "not the text the late claim sent in the rehearsal")
        assertEquals(uploadOf(answer), None)
        assert(answer.text("animation").exists(_.startsWith("fake-animation-")), answer.text("animation").toString)
        assertEquals(drawing.frames.get, 60, "nothing is drawn for a late claim")
        eventually("the request is finished")(strings("SELECT status FROM report_requests WHERE status <> 'sent'") == Nil)

  test("a first check-in claimed after its own deadline, with nothing drawn yet, is answered with its text"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    animateMood(accountId)
    val drawing = FramesRasteriser()
    queuedCheckIn(drawing, firstUpdate = 500L, lastMessageId = 0L, at = monday)
    launchedFrom(firstUpdate = 600L, lastMessageId = 100L, drawingWith = Some(drawing), at = monday.plusSeconds(15)):
      bot =>
        eventually("the answer is sent")(bot.messages.nonEmpty)
        val List(answer) = bot.messages: @unchecked
        assertEquals(answer.method, "sendMessage")
        assertEquals(drawing.frames.get, 0)
        eventually("the request is finished")(strings("SELECT status FROM report_requests") == List("text_fallback"))

  /** The set of the account as Telegram names it for the fake bot. */
  private def stickerSetOf(accountId: UUID): String =
    s"${MoodStickers.setKey(accountId)}_by_${FakeTelegramTransport.Bot.username.get}"

  private def stickerCalls(bot: Bot): List[String] =
    bot.wire.calls.map(_.method).filter(method => method.contains("Sticker"))

  test("/mood stickers asks for the images consent, then makes the account's set and answers with its link"):
    val accountId = seedOwner()
    launched: bot =>
      val List(ask) = bot.say("/mood stickers", expected = 1): @unchecked
      assertEquals(bot.body(ask), ConsentCopy.statement(ConsentPurpose.ImagesInChat))
      assertEquals(stickerCalls(bot), Nil, "nothing is made before the consent")
      val List(confirm) = bot.tap(ask, ConsentCopy.Agree, expected = 1): @unchecked
      val List(_, answer) = bot.tap(confirm, ConsentCopy.Confirm, expected = 2): @unchecked
      val name = stickerSetOf(accountId)
      val link = s"https://t.me/addstickers/$name"
      assertEquals(answer.method, "sendMessage")
      assert(bot.body(answer).startsWith(StickerCopy.ready), bot.body(answer))
      assert(bot.body(answer).contains(link), bot.body(answer))
      val url = answer.request.params("reply_markup")("inline_keyboard").arr.head.arr.head
      assertEquals((url("text").str, url("url").str), (StickerCopy.Add, link))
      assertEquals(stickerCalls(bot), List("getStickerSet", "createNewStickerSet"))
      val create = bot.wire.calls("createNewStickerSet").head
      assertEquals(create.request.params("user_id").num.toLong, owner)
      assertEquals(create.request.params("title").str, StickerCopy.title)
      assertEquals(create.request.files.size, 5, "one sticker for each of the five steps")
      assertEquals(
        create.request.params("stickers").arr.toList.map(_("emoji_list").arr.head.str),
        MoodStickers.Emojis
      )
      assertEquals(bot.rasteriser.count, 5, "the five stickers were drawn by the report worker")
      assertEquals(bot.wire.stickerSet(name).map(_.stickers.size), Some(5))

      // A second request updates the one set: no second set, the stickers replaced in place, nothing drawn again.
      val List(again) = bot.say("/mood stickers", expected = 1): @unchecked
      assert(bot.body(again).contains(link), bot.body(again))
      assertEquals(stickerCalls(bot).count(_ == "createNewStickerSet"), 1)
      assertEquals(stickerCalls(bot).count(_ == "replaceStickerInSet"), 5)
      assertEquals(bot.rasteriser.count, 5, "the stickers are shared pictures, drawn once")
      eventually("the requests are finished")(strings("SELECT DISTINCT status FROM report_requests") == List("sent"))

  test("deleting the account deletes the account's sticker set"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.ImagesInChat)
    launched: bot =>
      bot.say("/mood stickers", expected = 1)
      val name = stickerSetOf(accountId)
      assert(bot.wire.stickerSet(name).isDefined)
      val List(card) = bot.say("/privacy", expected = 1): @unchecked
      val List(menu) = bot.tap(card, PrivacyCopy.DeleteData, expected = 1): @unchecked
      val List(confirm) = bot.tap(menu, PrivacyCopy.DeleteMyAccount, expected = 1): @unchecked
      val List(last) = bot.tap(confirm, PrivacyCopy.Continue, expected = 1): @unchecked
      bot.tap(last, PrivacyCopy.DeleteMyAccount, expected = 1)
      eventually("the set is deleted")(bot.wire.stickerSet(name).isEmpty)
      assertEquals(bot.wire.calls("deleteStickerSet").map(_.request.params("name").str), List(name))
      eventually("the removal row is sent and names no account or chat"):
        strings(s"SELECT status FROM outbox_messages WHERE kind = '${StickerSetRemoval.Kind}' AND account_id IS NULL") ==
          List("sent")

  test("with Animated mood on but a video that cannot be made, the still card is the answer"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    animateMood(accountId)
    launched: bot =>
      val List(prompt) = bot.say("/mood", expected = 1): @unchecked
      val List(_, answer) = bot.tap(prompt, "4", expected = 2): @unchecked
      assertEquals(answer.method, "sendPhoto")
      assertEquals(uploadOf(answer), Some("png:1"), "the still card of the state")
      assertEquals(bot.body(answer), "Pleasant · 08:00")
      assertEquals(bot.rasteriser.count, 2, "the first frame was not a picture, so the drawing stopped there")
      eventually("the request is finished")(strings("SELECT status FROM report_requests") == List("sent"))

  test("in discreet mode Animated mood stays still: the still card is the answer and no frame is drawn"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    animateMood(accountId, discreet = true)
    val drawing = FramesRasteriser()
    launchedFrom(firstUpdate = 500L, drawingWith = Some(drawing)): bot =>
      val List(prompt) = bot.say("/mood", expected = 1): @unchecked
      val List(_, answer) = bot.tap(prompt, "4", expected = 2): @unchecked
      assertEquals(answer.method, "sendPhoto")
      assertEquals(drawing.frames.get, 0)
      assertEquals(drawing.stills.count, 1)

  test("/stats today is text first; its image is the daily summary, drawn from the day's doses, water and mood"):
    val accountId = seedOwner()
    val daily = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    fixtures.schedule(SteppedClock(monday), accountId, "Vitamin D", daily, ZoneOffset.UTC)
    grant(accountId, ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    launched: bot =>
      bot.say("300 ml", expected = 1)
      bot.say("mood 5", expected = 1)
      val List(stats) = bot.say("/stats today", expected = 1): @unchecked
      val text = bot.body(stats)
      assert(text.startsWith("Today · UTC"), text)
      assert(text.contains("Water today: 0.3 l"), text)
      assert(text.contains("Mood today: 5.0 (very pleasant) · 1 check-in"), text)
      assertEquals(bot.buttons(stats).map(_._1), List("Today", "7 days", "30 days", "Show as image", "Month", "Patterns", "By medication"))

      val List(photo) = bot.tap(stats, "Show as image", expected = 1): @unchecked
      assertEquals(photo.method, "sendPhoto")
      assertEquals(bot.body(photo), text, "the caption is the text of the same day")
      // The mood check-in drew the first card; the daily summary is the second.
      assertEquals(uploadOf(photo), Some("png:2"))
      assert(bot.rasteriser.shows(2, "Today") && bot.rasteriser.shows(2, "0 of 1 taken"), "it is the daily summary")

  test("the seven-day /stats shares a card: the facts by their sentences, then the card itself, never medication"):
    val accountId = seedOwner()
    val daily = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    fixtures.schedule(SteppedClock(monday), accountId, "Vitamin D", daily, ZoneOffset.UTC)
    grant(accountId, ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    launched: bot =>
      bot.say("300 ml", expected = 1)
      bot.say("mood 4", expected = 1)
      val List(stats) = bot.say("/stats", expected = 1): @unchecked
      assertEquals(
        bot.buttons(stats).map(_._1),
        List(
          "Today",
          "7 days",
          "30 days",
          "Show as image",
          "Share a card",
          "Water, 7 days",
          "Sleep, 7 nights",
          "Mood by weekday",
          "Month",
          "Patterns",
          "By medication"
        )
      )
      val List(picker) = bot.tap(stats, "Share a card", expected = 1): @unchecked
      assert(bot.body(picker).contains("Anyone you send this to can keep it."), bot.body(picker))
      assertEquals(bot.buttons(picker).map(_._1), List("Mood logged on 1 of 7 days", "This week's mood: pleasant"))
      val List(card) = bot.tap(picker, "Mood logged on 1 of 7 days", expected = 1): @unchecked
      assertEquals(card.method, "sendPhoto")
      assertEquals(bot.body(card), "Mood logged on 1 of 7 days this week.")
      assert(bot.rasteriser.shows(bot.rasteriser.count, "1 of 7 days"), "it is the share card")
      assert(!bot.rasteriser.shows(bot.rasteriser.count, "Vitamin"), "no medication reaches the card")
      eventually("the request is finished")(strings("SELECT card_kind FROM report_requests WHERE status = 'sent'").contains("card.share.v1"))

  test("the seven-day /stats sends the water, sleep and mood week cards (IMG-20 to IMG-22)"):
    val accountId = seedOwner()
    grant(
      accountId,
      ConsentPurpose.Medication,
      ConsentPurpose.Hydration,
      ConsentPurpose.Sleep,
      ConsentPurpose.Mood,
      ConsentPurpose.ImagesInChat
    )
    launched: bot =>
      bot.say("mood 4", expected = 1)
      val List(stats) = bot.say("/stats", expected = 1): @unchecked
      assertEquals(
        bot.buttons(stats).map(_._1),
        List(
          "Today",
          "7 days",
          "30 days",
          "Show as image",
          "Share a card",
          "Water, 7 days",
          "Sleep, 7 nights",
          "Mood by weekday",
          "Month",
          "Patterns",
          "By medication"
        )
      )
      val List(water) = bot.tap(stats, "Water, 7 days", expected = 1): @unchecked
      assertEquals(water.method, "sendPhoto")
      assert(bot.body(water).contains("The goal is the one you set."), bot.body(water))
      assert(bot.rasteriser.shows(bot.rasteriser.count, "Water, 7 days"))
      assert(water.request.params.obj.get("has_spoiler") != Some(ujson.True))

      val List(sleep) = bot.tap(stats, "Sleep, 7 nights", expected = 1): @unchecked
      assertEquals(sleep.method, "sendPhoto")
      assert(bot.body(sleep).contains("No night logged in these 7 nights."), bot.body(sleep))
      assert(bot.rasteriser.shows(bot.rasteriser.count, "Sleep, 7 nights"))

      val List(mood) = bot.tap(stats, "Mood by weekday", expected = 1): @unchecked
      assertEquals(mood.method, "sendPhoto")
      assert(bot.body(mood).contains("fewer than 3"), bot.body(mood))
      assert(bot.rasteriser.shows(bot.rasteriser.count, "Mood by weekday"))
      eventually("the week cards are sent"):
        val kinds = strings("SELECT card_kind FROM report_requests WHERE status = 'sent'").toSet
        kinds.contains("card.water.week.v1") && kinds.contains("card.sleep.week.v1") &&
        kinds.contains("card.mood.weekday.v1")

  test("a discreet Ukrainian seven-day water card is spoiled and in Ukrainian (IMG-20)"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.ImagesInChat)
    PgUnitOfWork(dataSource, () => monday, DataKeys.development).transaction: tx =>
      val settings = tx.accounts.settingsOf(accountId).get
      tx.accounts.saveSettings(accountId, settings.copy(isDiscreet = true, locale = Some("uk")), monday)
    launched: bot =>
      val List(stats) = bot.say("/stats", expected = 1): @unchecked
      assert(bot.buttons(stats).map(_._1).contains("Вода за 7 днів"))
      val List(photo) = bot.tap(stats, "Вода за 7 днів", expected = 1): @unchecked
      assertEquals(photo.method, "sendPhoto")
      // Behind the spoiler the caption is the card's generic line, not its figures (VISUALS VA-PV-03).
      assertEquals(bot.body(photo), "Вода за останні дні.")
      assertEquals(photo.request.params.obj.get("has_spoiler"), Some(ujson.True))
      assert(bot.rasteriser.shows(bot.rasteriser.count, "Вода за 7 днів"))

  test("in discreet mode a share asks once more; with the counts switched off only the week's mood is offered"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Medication, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    PgUnitOfWork(dataSource, () => monday, DataKeys.development).transaction: tx =>
      tx.accounts.saveSettings(accountId, tx.accounts.settingsOf(accountId).get.copy(isDiscreet = true), monday)
      tx.accounts.setGamification(accountId, false, monday)
    launched: bot =>
      bot.say("mood 2", expected = 1)
      val List(stats) = bot.say("/stats", expected = 1): @unchecked
      val List(picker) = bot.tap(stats, "Share a card", expected = 1): @unchecked
      assertEquals(bot.buttons(picker).map(_._1), List("This week's mood: unpleasant"))
      val List(confirm) = bot.tap(picker, "This week's mood: unpleasant", expected = 1): @unchecked
      assert(bot.body(confirm).startsWith("Discreet mode is on."), bot.body(confirm))
      val List(card) = bot.tap(confirm, "Send the card", expected = 1): @unchecked
      assertEquals(card.method, "sendPhoto")
      // The user confirmed sharing it: the caption is the card's own sentence, the photo is behind a spoiler.
      assertEquals(bot.body(card), "Mood this week, on average: unpleasant.")
      assertEquals(card.request.params.obj.get("has_spoiler"), Some(ujson.True))

  test("withdrawing the medication consent pauses the schedules and cancels the open doses, and /today asks again"):
    val accountId = seedOwner()
    val daily = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    fixtures.schedule(SteppedClock(monday), accountId, "Vitamin D", daily, ZoneOffset.UTC)
    grant(accountId, ConsentPurpose.Medication)
    launched: bot =>
      val List(card) = bot.say("/privacy", expected = 1): @unchecked
      val label = ConsentCopy.withdrawLabel(ConsentPurpose.Medication)
      val List(done) = bot.tap(card, label, expected = 1): @unchecked
      assertEquals(bot.body(done), ConsentCopy.withdrawn(ConsentPurpose.Medication))
      assertEquals(strings("SELECT granted::text FROM consent_state WHERE purpose = 'medication'"), List("false"))
      assertEquals(strings("SELECT status FROM medication_schedules"), List("paused"))
      assertEquals(strings("SELECT DISTINCT status::text FROM dose_occurrences"), List("cancelled"))
      assertEquals(strings("SELECT purpose || ':' || action FROM consent_events ORDER BY occurred_at, id").last, "medication:withdrawn")

      val List(asked) = bot.say("/today", expected = 1): @unchecked
      assertEquals(bot.body(asked), ConsentCopy.statement(ConsentPurpose.Medication), "no dose is shown")

  test("after a restart a mood image is sent by its stored file id: no second upload and no second drawing"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Mood, ConsentPurpose.ImagesInChat)
    launched: bot =>
      val List(prompt) = bot.say("/mood", expected = 1): @unchecked
      val List(_, first) = bot.tap(prompt, "4", expected = 2): @unchecked // the prompt's edit, then the image
      assertEquals(uploadOf(first), Some("png:1"))
    assertEquals(strings("SELECT media_kind || ':' || bot_id FROM telegram_file_cache"), List(s"photo:${telegram.botId}"))
    assertEquals(strings("SELECT mime FROM render_assets"), List("image/png"))

    // Telegram never reuses a message id in a chat: the restarted process continues after the first one's ids.
    launchedFrom(firstUpdate = 900L, lastMessageId = 100L): bot =>
      val List(prompt) = bot.say("/mood", expected = 1): @unchecked
      val List(_, again) = bot.tap(prompt, "4", expected = 2): @unchecked
      assertEquals(again.method, "sendPhoto")
      assertEquals(uploadOf(again), None, "the new process sends the file id the old one stored")
      assertEquals(bot.rasteriser.count, 0, "the bytes of the old process are still in the asset store")

  test("the rollup worker drains the queue a capture filled, without a read"):
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Hydration)
    launched: bot =>
      bot.say("300 ml", expected = 1)
      val deadline = System.nanoTime() + 30.seconds.toNanos
      def drained: Boolean = strings("SELECT 1 FROM rollup_dirty").isEmpty && strings("SELECT 1 FROM daily_rollups").nonEmpty
      while !drained && System.nanoTime() < deadline do Thread.sleep(50)
      assertEquals(strings("SELECT domain FROM rollup_dirty"), Nil, "the worker took the dirty day")
      assertEquals(strings("SELECT domain || ':' || (metrics ->> 'totalMl') FROM daily_rollups"), List("hydration:300"))

  // ---------- The report queue (ROADMAP G4.3) ----------

  /** A rasteriser that never answers until the process stops: a drawing that hangs, or one cut off by a crash. */
  private final class HangingRasteriser extends Rasteriser:
    val started = java.util.concurrent.atomic.AtomicInteger()
    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      started.incrementAndGet()
      java.util.concurrent.CountDownLatch(1).await()
      Left(RasterFailure.Unavailable)

  private def eventually(clue: => String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(25)
    assert(condition, s"not within 30 s: $clue")

  private def withStats(accountId: UUID): Unit =
    grant(accountId, ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.ImagesInChat)

  test("the image of a tap is drawn by the report worker after the event's transaction, and its request reads sent"):
    withStats(seedOwner())
    launched: bot =>
      bot.say("300 ml", expected = 1)
      val List(stats) = bot.say("/stats today", expected = 1): @unchecked
      val List(photo) = bot.tap(stats, "Show as image", expected = 1): @unchecked
      assertEquals(photo.method, "sendPhoto")
      assertEquals(uploadOf(photo), Some("png:1"))
      eventually("the request is finished")(strings("SELECT status FROM report_requests") == List("sent"))
      assertEquals(strings("SELECT card_kind FROM report_requests"), List("card.day.v1"))

  test("with the rasteriser stopped the image is answered with its text equivalent at once"):
    withStats(seedOwner())
    val stopped: Rasteriser = (_, _) => Left(RasterFailure.Unavailable)
    launchedFrom(firstUpdate = 500L, drawingWith = Some(stopped)): bot =>
      bot.say("300 ml", expected = 1)
      val List(stats) = bot.say("/stats today", expected = 1): @unchecked
      val List(text) = bot.tap(stats, "Show as image", expected = 1): @unchecked
      assertEquals(text.method, "sendMessage")
      assert(bot.body(text).startsWith("[image: Mon 21 Sep."), bot.body(text))
      assert(bot.body(text).contains("Water today: 0.3 l"), "the caption follows the text equivalent")
      assertEquals(bot.photos, Nil)
      eventually("the request is finished")(strings("SELECT status FROM report_requests") == List("text_fallback"))

  test("a drawing that hangs delays no dose reminder: render work is never in the send path of a reminder"):
    val accountId = seedOwner()
    val daily = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    fixtures.schedule(SteppedClock(monday), accountId, "Vitamin D", daily, ZoneOffset.UTC)
    grant(accountId, ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.ImagesInChat)
    val hanging = HangingRasteriser()
    launchedFrom(firstUpdate = 500L, drawingWith = Some(hanging)): bot =>
      bot.say("300 ml", expected = 1)
      val List(stats) = bot.say("/stats today", expected = 1): @unchecked
      bot.tap(stats, "Show as image", expected = 0)
      eventually("the drawing started")(hanging.started.get() == 1)
      bot.clock.advance(Duration.ofMinutes(61))
      // Past the claim's lease the other report worker sends the image as text, which takes the chat's one message a
      // second; the clock then moves on as it does in production, so the reminder gets the next one.
      eventually(s"the 09:00 reminder is sent: ${strings("SELECT kind || ':' || status FROM outbox_messages")}"):
        bot.clock.advance(Duration.ofMillis(100))
        strings("SELECT status FROM outbox_messages WHERE kind = 'reminder'").contains("sent")
      assertEquals(hanging.started.get(), 1, "the drawing still hangs")
      assertEquals(bot.photos, Nil)

  test("an image cut off by a crash between its request and its send is sent once after the restart, as its text"):
    withStats(seedOwner())
    val hanging = HangingRasteriser()
    launchedFrom(firstUpdate = 500L, drawingWith = Some(hanging)): bot =>
      bot.say("300 ml", expected = 1)
      val List(stats) = bot.say("/stats today", expected = 1): @unchecked
      bot.tap(stats, "Show as image", expected = 0)
      eventually("the drawing started")(hanging.started.get() == 1)
    // The process stopped while the request was being drawn: it is claimed, and nothing was sent.
    assertEquals(strings("SELECT status || ':' || attempts FROM report_requests"), List("rendering:1"))
    val queued = withConnection { conn =>
      given java.sql.Connection = conn
      OutboxPayloads.open(sql"SELECT id, account_id, payload, payload_enc FROM outbox_messages")
    }
    assertEquals(queued.filter(_.contains("[image:")), Nil)

    // The restarted process comes up after the claim's lease; the request is late and is not drawn again.
    val restarted = NumberingRasteriser()
    launchedFrom(firstUpdate = 900L, lastMessageId = 100L, drawingWith = Some(restarted), at = monday.plus(Duration.ofMinutes(10))): bot =>
      eventually("the text equivalent is sent")(bot.messages.exists(call => bot.body(call).startsWith("[image: Mon 21 Sep.")))
      Thread.sleep(500)
      assertEquals(bot.messages.count(call => bot.body(call).startsWith("[image:")), 1, "sent once")
      assertEquals(bot.photos, Nil)
      assertEquals(restarted.count, 0, "a request a crashed worker held is not drawn again")
      assertEquals(strings("SELECT status || ':' || attempts FROM report_requests"), List("text_fallback:2"))

  /** A "Show as image" request cut off by a crash while it was drawn, so the next start finds it unfinished. */
  private def requestCutOffByACrash(): Unit =
    withStats(seedOwner())
    val hanging = HangingRasteriser()
    launchedFrom(firstUpdate = 500L, drawingWith = Some(hanging)): bot =>
      bot.say("300 ml", expected = 1)
      val List(stats) = bot.say("/stats today", expected = 1): @unchecked
      bot.tap(stats, "Show as image", expected = 0)
      eventually("the drawing started")(hanging.started.get() == 1)
    assertEquals(strings("SELECT status FROM report_requests"), List("rendering"))

  // Leftover 2 of the verification of 2026-11-28: a request older than an hour when the app started was purged unsent.
  test("a request the app was down for hours on the same day is sent at the next start, as its text with a late note"):
    requestCutOffByACrash()
    val restarted = NumberingRasteriser()
    launchedFrom(firstUpdate = 900L, lastMessageId = 100L, drawingWith = Some(restarted), at = monday.plus(Duration.ofHours(3))): bot =>
      eventually("the text equivalent is sent")(bot.messages.exists(call => bot.body(call).startsWith("[image: Mon 21 Sep.")))
      val List(answer) = bot.messages.filter(call => bot.body(call).startsWith("[image:")): @unchecked
      assert(bot.body(answer).endsWith("Sent late: you asked for this at 08:00."), bot.body(answer))
      assertEquals(strings("SELECT status || ':' || coalesce(error_class, '') FROM report_requests"), List("text_fallback:Late"))

  test("a request of an earlier day is not sent at the next start: it is finished as stale and counted, not deleted"):
    requestCutOffByACrash()
    val restarted = NumberingRasteriser()
    val before = Metrics.registry.find("dosegram_report_requests_dropped_total").counters().asScala.map(_.count()).sum
    launchedFrom(firstUpdate = 900L, lastMessageId = 100L, drawingWith = Some(restarted), at = monday.plus(Duration.ofHours(17))): bot =>
      eventually("the request is finished")(strings("SELECT status FROM report_requests") == List("text_fallback"))
      assertEquals(strings("SELECT coalesce(error_class, '') FROM report_requests"), List("Stale"))
      Thread.sleep(500)
      assertEquals(bot.messages.filter(call => bot.body(call).startsWith("[image:")), Nil, "yesterday's figures are not sent")
      val after = Metrics.registry.find("dosegram_report_requests_dropped_total").counters().asScala.map(_.count()).sum
      assertEquals(after - before, 1.0)

  /** The pinned resvg of this machine, counting what it draws; absent only where the pixel tests may skip. */
  private final class CountingResvg(binary: java.nio.file.Path) extends Rasteriser:
    private val resvg = dosegram.render.resvg.ResvgRasteriser(
      dosegram.render.resvg.ResvgConfig
        .standard(binary, java.nio.file.Path.of(sys.env("DOSEGRAM_FONTS_DIR")), java.nio.file.Files.createTempDirectory("resvg-empty"))
        // A minute, as the pixel tests give their rasteriser on a loaded build host; nothing here waits on it.
        .copy(timeoutMillis = 60000)
    )
    val drawn = java.util.concurrent.atomic.AtomicInteger()

    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      drawn.incrementAndGet()
      resvg.rasterise(svg, zoom)

  private def resvgOfThisMachine(): java.nio.file.Path =
    val binary = sys.env
      .get("DOSEGRAM_RESVG")
      .map(java.nio.file.Path.of(_))
      .orElse(sys.env.getOrElse("PATH", "").split(':').iterator.filter(_.nonEmpty).map(java.nio.file.Path.of(_, "resvg")).find(java.nio.file.Files.isExecutable(_)))
    if sys.env.get("DOSEGRAM_REQUIRE_RESVG").contains("true") then assert(binary.isDefined, "no resvg on the PATH")
    else assume(binary.isDefined, "no resvg on the PATH")
    binary.get

  // VISUALS VA-PS-08: the weekly album is drawn in the 30 minutes before Monday 09:00, so at 09:00 it is sent as its
  // pictures at once, with nothing drawn at the send and nothing sent as text for lateness.
  test("a scheduled weekly album is drawn ahead and arrives as its pictures at 09:00 on the stepped clock"):
    val resvg = CountingResvg(resvgOfThisMachine())
    val accountId = seedOwner()
    grant(accountId, ConsentPurpose.Hydration, ConsentPurpose.ImagesInChat)
    val sunday = Instant.parse("2026-09-20T20:00:00Z")
    // Water on three days of the week before Monday 21 September, through the use case the chat calls.
    List("2026-09-14T09:00:00Z" -> 500, "2026-09-16T12:00:00Z" -> 900, "2026-09-18T18:00:00Z" -> 1200).foreach: (at, ml) =>
      val clock = SteppedClock(Instant.parse(at))
      val water = dosegram.core.application.hydration.HydrationService(clock, dosegram.core.ports.IdSource.uuidV7(java.security.SecureRandom()))
      val amount = dosegram.core.domain.hydration.WaterAmount.ofMillilitres(ml).toOption.get
      val command = dosegram.core.application.hydration.LogWater(
        accountId, amount, dosegram.core.domain.EntryDay.Today, dosegram.core.domain.EntrySource.Bot, s"week-$at"
      )
      PgUnitOfWork(dataSource, clock, DataKeys.development).transaction(tx => assert(water.log(tx, command).isRight))
    launchedFrom(firstUpdate = 500L, drawingWith = Some(resvg), at = sunday): bot =>
      val weekly = dosegram.core.domain.reminders.PromptKind.WeeklyReport
      val List(hub) = bot.say("/reminders", expected = 1): @unchecked
      val List(choose) = bot.tap(hub, PromptCopy.kindButton(weekly), expected = 1): @unchecked
      val List(preview) = bot.tap(choose, PromptCopy.presetName("report.weekly"), expected = 1): @unchecked
      bot.tap(preview, PromptCopy.TurnOn, expected = 1): Unit
      val nineAm = Instant.parse("2026-09-21T09:00:00Z")
      def albums = bot.wire.calls("sendMediaGroup")
      // Time passes as in production: to 08:25 at once, then in 30-second steps while the workers run.
      bot.clock.advance(Duration.between(bot.clock.now(), nineAm.minusSeconds(35 * 60)))
      while bot.clock.now().isBefore(nineAm.minusSeconds(30)) do
        bot.clock.advance(Duration.ofSeconds(30))
        Thread.sleep(150)
      val deadline = System.nanoTime() + 60.seconds.toNanos
      while resvg.drawn.get() < 2 && System.nanoTime() < deadline do Thread.sleep(50)
      assertEquals(resvg.drawn.get(), 2, "the week card and its highlights are drawn before 09:00")
      assertEquals(albums, Nil, "nothing is sent before its time")
      bot.clock.advance(Duration.between(bot.clock.now(), nineAm.plusSeconds(5)))
      while albums.isEmpty && System.nanoTime() < deadline + 60.seconds.toNanos do Thread.sleep(50)
      val List(album) = albums: @unchecked
      assert(!album.at.isBefore(nineAm) && album.at.isBefore(nineAm.plusSeconds(60)), s"the album is sent at its time: ${album.at}")
      val media = album.request.params("media").arr.toList
      assertEquals(media.map(_("type").str), List("photo", "photo"))
      media.foreach(item => assert(item("caption").str.nonEmpty, "each picture is captioned with its text equivalent"))
      val uploads = album.request.files
      assertEquals(uploads.size, 2, "both pictures are uploaded as drawn")
      uploads.foreach(file => assertEquals(file.bytes.take(4).toList, List(0x89.toByte, 'P'.toByte, 'N'.toByte, 'G'.toByte)))
      assertEquals(resvg.drawn.get(), 2, "nothing is drawn at the send")
      val textDeadline = System.nanoTime() + 60.seconds.toNanos
      while !bot.messages.exists(call => bot.body(call).startsWith("Your week")) && System.nanoTime() < textDeadline do
        bot.clock.advance(Duration.ofSeconds(1))
        Thread.sleep(50)
      val text = bot.messages.find(call => bot.body(call).startsWith("Your week")).getOrElse(fail("no weekly text"))
      assert(text.at.isAfter(album.at) || text.at == album.at, "the text follows the album")
      assertEquals(bot.buttons(text).map(_._1), List("Stop these"))
end CaptureLaunchSuite
