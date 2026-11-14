package dosegram.app

import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
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
import dosegram.infra.db.PgInboundInbox
import dosegram.infra.db.PgSuite
import dosegram.infra.db.PgUnitOfWork
import dosegram.infra.db.sql
import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser
import ox.supervised

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import scala.concurrent.duration.DurationInt
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
          "TRUNCATE render_assets, telegram_file_cache, inbound_inbox, " +
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

    private val messageMethods = Set("sendMessage", "sendPhoto", "editMessageText", "editMessageCaption")

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
      val isPhoto = card.method == "sendPhoto" || card.method == "editMessageCaption"
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
  private def launchedFrom[A](firstUpdate: Long, lastMessageId: Long = 0L)(test: Bot => A): A =
    val clock = SteppedClock(monday)
    val wire = FakeTelegramTransport(clock, lastMessageId = lastMessageId)
    val rasteriser = NumberingRasteriser()
    supervised:
      val config = settings
      val stores = StatusImages.Stores.durable(dataSource, telegram.botId, clock)
      val images = StatusImages.over(rasteriser, stores, _ => ())
      val adapter = TelegramAssembly.adapter(config, telegram, wire, clock)(images, Some(PgInboundInbox(dataSource)))
      Launch.start(config, dataSource, Launch.Parts(clock, Map("telegram" -> adapter), idle, images), log = _ => ())
      test(Bot(clock, wire, rasteriser, firstUpdate))

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
    PgUnitOfWork(dataSource, () => monday).transaction(tx => purposes.foreach(tx.consents.grant(accountId, _, notice, ConsentTaps(monday, monday))))

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

      val List(receipt, goalAsk, offer) = bot.tap(stageTwo, ConsentCopy.Confirm, expected = 3): @unchecked
      assertEquals(bot.body(receipt), ConsentCopy.receipt(ConsentPurpose.Hydration, "21 Sep 2026"))
      assertEquals(bot.body(goalAsk), WaterCopy.goalAsk)
      assertEquals(bot.body(offer), PromptCopy.offerWater, "the water prompts are offered after the consent (ADR-023)")
      assertEquals(strings("SELECT purpose || ':' || action FROM consent_events"), List("hydration:granted"))
      assertEquals(strings("SELECT octet_length(notice_hash)::text FROM consent_events"), List("32"))

      val List(echo) = bot.tap(goalAsk, "2.0 l", expected = 1): @unchecked
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
      assertEquals(bot.buttons(stats).map(_._1), List("Today", "7 days", "30 days", "Show as image", "Month", "Patterns"))

      val List(photo) = bot.tap(stats, "Show as image", expected = 1): @unchecked
      assertEquals(photo.method, "sendPhoto")
      assertEquals(bot.body(photo), text, "the caption is the text of the same day")
      // The mood check-in drew the first card; the daily summary is the second.
      assertEquals(uploadOf(photo), Some("png:2"))
      assert(bot.rasteriser.shows(2, "Today") && bot.rasteriser.shows(2, "0 of 1 taken"), "it is the daily summary")

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
end CaptureLaunchSuite
