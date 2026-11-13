package dosegram.app

import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.adapter.telegram.WireReply
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.AdapterCopy
import dosegram.core.domain.copy.InputCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.domain.input.TimeOfDay
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.scheduling.OutboxDispatcher
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.Metrics
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.TelegramSettings
import dosegram.infra.TelegramUpdates
import dosegram.infra.db.Fixtures
import dosegram.infra.db.PgSuite
import dosegram.infra.db.sql
import ox.supervised

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.language.implicitConversions

/** ROADMAP G1.5 and G1.6 without the live step: the assembled application of [[Launch.start]] on Testcontainers
  * Postgres with the Telegram adapter that [[TelegramAssembly]] builds, on the fake wire. A test seeds rows, steps the
  * clock and queues Bot API updates; it reads the calls the fake wire received and what the database holds. Nothing
  * here opens a connection to Telegram.
  */
class TelegramLaunchSuite extends PgSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val monday = Instant.parse("2026-09-21T08:00:00Z")
  private val dueAfter = Duration.ofMinutes(61)
  private val owner = 7001L
  private val stranger = 9999L

  private val fast = Workers.Cadence(
    reminderPoll = Duration.ofMillis(100),
    materialiserPeriod = Duration.ofMinutes(15),
    outboxIdlePoll = Duration.ofMillis(50),
    sessionSweepPeriod = Duration.ofMillis(100),
    rollupIdlePoll = Duration.ofMillis(100),
    imagePurgePeriod = Duration.ofMillis(100)
  )
  private val settleMillis = 700L

  private lazy val fixtures = Fixtures(dataSource)

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "conversation_sessions, form_runs, callback_slots, delivery_channels, platform_identities, " +
            "worker_heartbeat, users CASCADE"
        )
      finally st.close()
    }

  // ---------- The launched application ----------

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
      instanceId = "telegram-launch",
      logLevel = LogLevel.Info,
      logFormat = LogFormat.Plain,
      consoleUserId = None,
      telegram = Some(telegram),
      publicBaseUrl = None,
      healthPort = freePort(),
      environment = Environment.Development,
      miniAppUrl = Some("https://app.example/")
    )

  private final class Bot(val clock: SteppedClock, val wire: FakeTelegramTransport):
    private var updateSeq = 500L

    def sends: List[FakeTelegramTransport.RecordedCall] = wire.calls("sendMessage")
    def edits: List[FakeTelegramTransport.RecordedCall] = wire.calls("editMessageText")
    def answers: List[FakeTelegramTransport.RecordedCall] = wire.calls("answerCallbackQuery")

    /** `(label, callback_data)` of every button of a call, row by row. */
    def buttons(call: FakeTelegramTransport.RecordedCall): List[(String, String)] =
      call.request.params.obj
        .get("reply_markup")
        .toList
        .flatMap(_("inline_keyboard").arr.toList)
        .flatMap(_.arr.toList)
        .map(button => (button("text").str, button("callback_data").str))

    /** A tap by `userId` on the button with `data` under the message `messageId`, as the next update. */
    def tap(data: String, messageId: Long, userId: Long = owner): ujson.Obj =
      updateSeq += 1
      val update = ujson.Obj(
        "update_id" -> updateSeq.toDouble,
        "callback_query" -> ujson.Obj(
          "id" -> s"query-$updateSeq",
          "from" -> ujson.Obj("id" -> userId.toDouble, "is_bot" -> false),
          "message" -> ujson.Obj(
            "message_id" -> messageId.toDouble,
            "chat" -> ujson.Obj("id" -> userId.toDouble, "type" -> "private"),
            "date" -> clock.now().getEpochSecond.toDouble,
            "text" -> "x"
          ),
          "data" -> data
        )
      )
      wire.enqueueUpdates(update)
      update

    def text(userId: Long, body: String): Unit =
      updateSeq += 1
      wire.enqueueUpdates(
        ujson.Obj(
          "update_id" -> updateSeq.toDouble,
          "message" -> ujson.Obj(
            "message_id" -> (updateSeq + 9000).toDouble,
            "from" -> ujson.Obj("id" -> userId.toDouble, "is_bot" -> false),
            "chat" -> ujson.Obj("id" -> userId.toDouble, "type" -> "private"),
            "date" -> clock.now().getEpochSecond.toDouble,
            "text" -> body
          )
        )
      )

  /** Runs `test` against the application of [[Launch.start]] with the adapter of [[TelegramAssembly]] on a fake wire
    * that `prepare` may script before anything starts.
    */
  private def launched[A](clock: SteppedClock, prepare: FakeTelegramTransport => Unit = _ => ())(test: Bot => A): A =
    val wire = FakeTelegramTransport(clock)
    prepare(wire)
    supervised:
      val config = settings
      val adapter = TelegramAssembly.adapter(config, telegram, wire, clock)(StatusImages.textOnly)
      Launch.start(config, dataSource, Launch.Parts(clock, Map("telegram" -> adapter), fast), log = _ => ())
      test(Bot(clock, wire))

  // ---------- Seeding and observation ----------

  /** One account whose Telegram identity and chat are the owner's, with one daily 09:00 schedule. */
  private def seedOwner(clock: SteppedClock): UUID =
    val accountId = fixtures.medicationAccount("UTC")
    val channelId = fixtures.deliveryChannel(accountId, "telegram", owner.toString, clock.now())
    withConnection { conn =>
      given Connection = conn
      sql"""UPDATE platform_identities SET vendor_user_id = ${owner.toString}
            WHERE id = (SELECT platform_identity_id FROM delivery_channels WHERE id = $channelId)""".execute()
    }
    val daily = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    fixtures.schedule(clock, accountId, "Vitamin D", daily, ZoneOffset.UTC)
    channelId

  private def eventually(clue: => String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 60.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(25)
    assert(condition, s"not within 60 s: $clue")

  private def settle(): Unit = Thread.sleep(settleMillis)

  private def strings(query: String): List[String] = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query) // a query of this suite, never input
      Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
    finally st.close()
  }

  private def reminderRows(): List[String] =
    strings("SELECT status::text FROM outbox_messages WHERE kind = 'reminder' ORDER BY created_at")

  private def takenActions(): Int =
    strings("SELECT action::text FROM dose_actions WHERE action::text = 'taken'").size

  private def dueStatus(): List[String] =
    strings("SELECT status::text FROM dose_occurrences WHERE scheduled_for < '2026-09-21T09:30:00Z'")

  /** Starts the application with the 09:00 dose due and waits for its reminder card. */
  private def withReminder[A](prepare: FakeTelegramTransport => Unit = _ => ())(
      test: (Bot, FakeTelegramTransport.RecordedCall) => A
  ): A =
    val clock = SteppedClock(monday)
    seedOwner(clock)
    clock.advance(dueAfter)
    launched(clock, prepare): bot =>
      eventually("the reminder card is sent")(bot.sends.nonEmpty && reminderRows() == List("sent"))
      test(bot, bot.sends.head)

  private def tokenOf(bot: Bot, card: FakeTelegramTransport.RecordedCall, label: String): String =
    bot.buttons(card).collectFirst { case (`label`, data) => data }.getOrElse(fail(s"no button '$label' on the card"))

  // ---------- Acceptance ----------

  test("a seeded due dose becomes one reminder card with Taken, Snooze and Skip, and Taken edits it"):
    withReminder(): (bot, card) =>
      assertEquals(card.number("chat_id"), Some(owner))
      assertEquals(card.text("text"), Some("Time for Vitamin D."))
      assertEquals(card.text("parse_mode"), Some("HTML"))
      val buttons = bot.buttons(card)
      assertEquals(buttons.map(_._1).take(3), List("Taken", "Snooze 10m", "Skip"))
      buttons.foreach((label, data) => assert(data.getBytes(UTF_8).length <= 64, s"$label: ${data.length} bytes"))
      val messageId = card.sentMessageId.getOrElse(fail("the fake wire answers a send with a message id"))

      bot.tap(tokenOf(bot, card, "Taken"), messageId)
      eventually("the dose is recorded")(takenActions() == 1)
      assertEquals(dueStatus(), List("taken"))
      // The dispatcher allows one message call per chat and second; its bucket refills on the stepped clock.
      bot.clock.advance(Duration.ofSeconds(2))
      eventually("the card is edited to its recorded state")(bot.edits.nonEmpty)
      val edit = bot.edits.head
      assertEquals(edit.number("message_id"), Some(messageId))
      assertEquals(edit.text("text"), Some("Recorded at 09:01."))
      assertEquals(bot.buttons(edit).map(_._1), List("Undo", "Correct"))
      eventually("the tap is answered")(bot.answers.size == 1)

      settle()
      // The outcome is an edit of the card. The confirmation is the callback answer; when the handler was slower than
      // the grace time it is a short message instead, and nothing else may be sent.
      assertEquals(bot.sends.drop(1).flatMap(_.text("text")).distinct.filterNot(_ == "Recorded at 09:01."), Nil)
      assert(bot.sends.size <= 2, s"sent: ${bot.sends.map(_.text("text"))}")
      assertEquals(bot.answers.size, 1, "exactly one answer per callback query")
      assert(
        Metrics.scrape().contains("dosegram_interaction_ack_latency_seconds"),
        "the acknowledgement latency histogram exists"
      )

  test("an update the wire delivers again is ignored: one inbound row, one recorded dose"):
    withReminder(): (bot, card) =>
      val messageId = card.sentMessageId.getOrElse(fail("no message id"))
      val update = bot.tap(tokenOf(bot, card, "Taken"), messageId)
      eventually("the dose is recorded")(takenActions() == 1)
      val batch = ujson.Obj("ok" -> true, "result" -> ujson.Arr(update))
      bot.wire.replyNext("getUpdates", 200, batch)
      bot.wire.replyNext("getUpdates", 200, batch)
      // The first delivery and the two redeliveries are the three polls that returned this batch.
      val sameBatch = WireReply.Http(200, ujson.write(batch))
      eventually("the redelivered batches were polled")(bot.wire.calls("getUpdates").count(_.reply == sameBatch) == 3)
      settle()
      assertEquals(strings("SELECT vendor_event_id FROM inbound_events").size, 1)
      assertEquals(takenActions(), 1)
      assertEquals(bot.answers.size, 1)

  test("a 429 is honoured: nothing goes to the chat before retry_after has passed, then the card is sent once"):
    val flood = FakeTelegramTransport.tooManyRequests(Duration.ofSeconds(30))
    val clock = SteppedClock(monday)
    seedOwner(clock)
    clock.advance(dueAfter)
    launched(clock, _.replyNext("sendMessage", flood)): bot =>
      eventually("the first attempt was refused")(reminderRows() == List("failed_retry"))
      clock.advance(Duration.ofSeconds(29))
      settle()
      assertEquals(bot.sends.size, 1, "no second attempt inside the 30 seconds the API asked for")
      assertEquals(reminderRows(), List("failed_retry"))
      clock.advance(Duration.ofSeconds(2))
      eventually("the card is sent after the wait")(reminderRows() == List("sent"))
      settle()
      assertEquals(bot.sends.size, 2)
      assertEquals(bot.sends.last.text("text"), Some("Time for Vitamin D."))

  test("a user who blocked the bot is marked unreachable: the row is dead, the channel is dead, nothing is retried"):
    val blocked = ujson.Obj("ok" -> false, "error_code" -> 403, "description" -> "Forbidden: bot was blocked by the user")
    val clock = SteppedClock(monday)
    val channelId = seedOwner(clock)
    clock.advance(dueAfter)
    launched(clock, _.replyNext("sendMessage", 403, blocked)): bot =>
      eventually("the reminder row is dead")(reminderRows() == List("dead"))
      assertEquals(strings(s"SELECT state FROM delivery_channels WHERE id = '$channelId'"), List("dead"))
      clock.advance(OutboxDispatcher.FallbackAfter.plusMinutes(1))
      settle()
      assertEquals(bot.sends.size, 1, "a blocked chat is not tried again")

  test("a tap on a card whose dose is already recorded is answered politely and changes nothing"):
    withReminder(): (bot, card) =>
      val messageId = card.sentMessageId.getOrElse(fail("no message id"))
      val taken = tokenOf(bot, card, "Taken")
      bot.tap(taken, messageId)
      eventually("the dose is recorded")(takenActions() == 1 && bot.answers.size == 1)
      bot.tap(taken, messageId)
      val told = "Already recorded at 09:01."
      // The handler's toast is the callback answer when it comes inside the grace time, a short message otherwise.
      eventually("the user is told the dose is already recorded"):
        bot.answers.exists(_.text("text").contains(told)) || bot.sends.exists(_.text("text").contains(told))
      eventually("the second tap is answered")(bot.answers.size == 2)
      settle()
      assertEquals(takenActions(), 1)
      assertEquals(dueStatus(), List("taken"))

  test("a forged button is answered with the stale-button toast and reaches nothing"):
    withReminder(): (bot, card) =>
      val messageId = card.sentMessageId.getOrElse(fail("no message id"))
      bot.tap("dc:" + "A" * 46, messageId)
      eventually("the forged tap is answered")(bot.answers.nonEmpty)
      assertEquals(bot.answers.map(_.text("text")), List(Some(ReminderCopy.staleControlToast)))
      settle()
      assertEquals(takenActions(), 0)
      assertEquals(dueStatus(), List("due"))
      assertEquals(bot.edits, Nil)

  test("a sender who is not on the allowlist gets the fixed reply and leaves no row in any table"):
    val clock = SteppedClock(monday)
    launched(clock): bot =>
      bot.text(stranger, "/start")
      eventually("the fixed reply is sent")(bot.sends.nonEmpty)
      settle()
      assertEquals(bot.sends.map(call => (call.number("chat_id"), call.text("text"))), List((Some(stranger), Some(AdapterCopy.notAvailable))))
      List("inbound_events", "platform_identities", "users", "conversation_sessions", "outbox_messages").foreach: table =>
        assertEquals(strings(s"SELECT 1 FROM $table").size, 0, s"$table must stay empty")

  test("/help, /today, /meds and /app are answered in the chat; /app carries a button that opens the Mini App"):
    val clock = SteppedClock(monday)
    seedOwner(clock)
    launched(clock): bot =>
      def answerTo(command: String): FakeTelegramTransport.RecordedCall =
        val before = bot.sends.size
        bot.text(owner, command)
        eventually(s"$command is answered")(bot.sends.size > before)
        // One message call per chat and second: let the buckets refill on the stepped clock.
        clock.advance(Duration.ofSeconds(2))
        bot.sends(before)
      val help = answerTo("/help").text("text").getOrElse(fail("/help has text"))
      List("/today", "/meds", "/app", "/help").foreach(command => assert(help.contains(command), help))
      assert(answerTo("/today").text("text").exists(_.contains("Vitamin D")), "today lists the scheduled dose")
      val meds = answerTo("/meds")
      assert(meds.text("text").exists(_.contains("Vitamin D — every day 09:00")), meds.text("text").toString)
      assert(bot.buttons(meds).map(_._1).contains("Add medication"))
      val app = answerTo("/app")
      assertEquals(
        app.request.params("reply_markup"),
        ujson.Obj(
          "inline_keyboard" -> ujson.Arr(
            ujson.Arr(ujson.Obj("text" -> "Open app", "web_app" -> ujson.Obj("url" -> "https://app.example/?route=today")))
          )
        )
      )

  // The owner's report on the live bot (2026-11-13): "9:00" at the times question was refused without a reason. This
  // drives the add-medication form through the Telegram adapter, the mediator and Postgres on the fake wire.
  test("Add medication on Telegram: 9:75 is refused with the reason and examples, then 9:00 is read as 09:00"):
    val clock = SteppedClock(monday)
    seedOwner(clock)
    launched(clock): bot =>
      // The texts of every sent and edited message, in the order the wire received them.
      def written: List[String] =
        bot.wire.calls.filter(call => Set("sendMessage", "editMessageText")(call.method)).flatMap(_.text("text"))
      def answer(clue: String)(act: => Unit)(expected: String): Unit =
        val before = written.size
        act
        eventually(s"$clue: $expected")(written.drop(before).exists(_.contains(expected)))
        // One message call per chat and second: let the buckets refill on the stepped clock.
        clock.advance(Duration.ofSeconds(2))
      answer("/meds")(bot.text(owner, "/meds"))("Vitamin D")
      val meds = bot.sends.findLast(_.text("text").exists(_.contains("Vitamin D"))).getOrElse(fail("no /meds card"))
      val messageId = meds.sentMessageId.getOrElse(fail("no message id"))
      answer("Add medication")(bot.tap(tokenOf(bot, meds, "Add medication"), messageId))(WizardCopy.fieldName)
      answer("the name")(bot.text(owner, "Vitamin C"))(WizardCopy.fieldDose)
      answer("the dose")(bot.text(owner, "500 mg"))(WizardCopy.timesPrompt)
      answer("a time that is not one")(bot.text(owner, "9:75"))(InputCopy.time(TimeOfDay.Rejection.NotATime("9:75")))
      assert(written.last.contains(WizardCopy.timesPrompt), s"the times step stays open: ${written.last}")
      answer("the owner's input")(bot.text(owner, "9:00"))(WizardCopy.timesEcho(List("09:00")))
      assert(written.last.contains(WizardCopy.instructionsPrompt), s"the next step: ${written.last}")

  test("start-up registers the command list and leaves the command list as the default menu button"):
    val clock = SteppedClock(monday)
    launched(clock): bot =>
      eventually("the commands are registered")(bot.wire.calls("setMyCommands").nonEmpty)
      val registered = bot.wire.calls("setMyCommands").head.request.params("commands").arr.map(_("command").str).toList
      assertEquals(registered, dosegram.core.application.CommandRegistry.withCapture.map(_.name))
      assertEquals(
        bot.wire.calls("setChatMenuButton").map(_.request.params),
        List(ujson.Obj("menu_button" -> ujson.Obj("type" -> "commands")))
      )
      assertEquals(bot.wire.calls("deleteWebhook").size, 1, "a development bot polls, so its webhook is removed")
