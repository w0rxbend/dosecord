package dosegram.app

import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.contracts.CapabilityProfile
import dosegram.contracts.DigestItem
import dosegram.contracts.Inline
import dosegram.contracts.LoopDispatch
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.application.exports.ExportRequests
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CapabilityProfiles
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp
import dosegram.core.scheduling.PromptLoop
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.TelegramSettings
import dosegram.infra.TelegramUpdates
import dosegram.infra.crypto.DataKeys
import dosegram.infra.db.PgInboundInbox
import dosegram.infra.db.PgSuite
import dosegram.infra.db.PgUnitOfWork
import ox.supervised

import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.DurationInt

/** The production path end to end (the live defect of 2026-11-14: fake-wire tests seeded `platform_identities` by hand
  * while production never wrote `dm_channel_id`, so no scheduled message reached the user). Nothing here is seeded:
  * the account, its identity and its delivery channel come from `/start` on the fake wire, the schedule from the
  * add-medication form, the water plan from the offer after the water consent. Then the clock moves and the test reads
  * what reached the user's chat.
  */
class ProductionPathSuite extends PgSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(240, "s")

  // A Monday, two hours before the first dose of the day.
  private val start = Instant.parse("2026-09-21T07:00:00Z")
  private val owner = 7001L

  private val fast = Workers.Cadence(
    reminderPoll = Duration.ofMillis(100),
    materialiserPeriod = Duration.ofMinutes(15),
    outboxIdlePoll = Duration.ofMillis(50),
    sessionSweepPeriod = Duration.ofMillis(100),
    rollupIdlePoll = Duration.ofMillis(100),
    imagePurgePeriod = Duration.ofMillis(100),
    ownerAlertPeriod = Duration.ofMillis(100)
  )

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        // The append-only triggers guard the ledgers against the application; the suite owns this database.
        st.execute("SET session_replication_role = replica")
        val rs = st.executeQuery(
          "SELECT string_agg(quote_ident(tablename), ', ') FROM pg_tables " +
            "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"
        )
        rs.next()
        val tables = rs.getString(1)
        st.execute(s"TRUNCATE $tables CASCADE")
        st.execute("SET session_replication_role = DEFAULT")
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
      instanceId = "production-path",
      logLevel = LogLevel.Info,
      logFormat = LogFormat.Plain,
      consoleUserId = None,
      telegram = Some(telegram),
      publicBaseUrl = None,
      healthPort = freePort(),
      environment = Environment.Development
    )

  /** The owner's chat on the fake wire: what the owner types and taps, and what the bot wrote back. */
  private final class Chat(val clock: SteppedClock, val wire: FakeTelegramTransport, val healthPort: Int):
    private var updateSeq = 500L
    private val messageMethods = Set("sendMessage", "editMessageText")

    def messages: List[FakeTelegramTransport.RecordedCall] = wire.calls.filter(call => messageMethods(call.method))

    def body(call: FakeTelegramTransport.RecordedCall): String = call.text("text").getOrElse("")

    def buttons(call: FakeTelegramTransport.RecordedCall): List[(String, String)] =
      call.request.params.obj
        .get("reply_markup")
        .toList
        .flatMap(_("inline_keyboard").arr.toList)
        .flatMap(_.arr.toList)
        .flatMap(button => button.obj.get("callback_data").map(data => (button("text").str, data.str)))

    /** Types `text`, then waits until a message from the bot shows `expected`. */
    def say(text: String, expected: String): Unit = step(expected): () =>
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

    /** Taps the newest button whose label `matches`, then waits until a message from the bot shows `expected`. */
    def tap(matches: String => Boolean, expected: String): Unit =
      val (card, data) = messages.reverseIterator
        .flatMap(call => buttons(call).collectFirst { case (label, data) if matches(label) => (call, data) })
        .nextOption()
        .getOrElse(fail(s"no such button; messages: ${messages.map(body)}"))
      val messageId = card.sentMessageId.orElse(card.number("message_id")).getOrElse(fail("the card names no message"))
      step(expected): () =>
        wire.enqueueUpdates(
          ujson.Obj(
            "update_id" -> updateSeq.toDouble,
            "callback_query" -> ujson.Obj(
              "id" -> s"query-$updateSeq",
              "from" -> ujson.Obj("id" -> owner.toDouble, "is_bot" -> false),
              "message" -> ujson.Obj(
                "message_id" -> messageId.toDouble,
                "chat" -> ujson.Obj("id" -> owner.toDouble, "type" -> "private"),
                "date" -> clock.now().getEpochSecond.toDouble,
                "text" -> "x"
              ),
              "data" -> data
            )
          )
        )

    def tapLabel(label: String, expected: String): Unit = tap(_ == label, expected)

    // The adapter allows one message call per chat and second: each step moves the stepped clock past that.
    private def step(expected: String)(send: () => Unit): Unit =
      clock.advance(Duration.ofSeconds(5))
      updateSeq += 1
      val before = messages.size
      send()
      eventually(s"a message showing '$expected'; got ${messages.drop(before).map(call => (body(call), buttons(call).map(_._1)))}"):
        messages.drop(before).exists(call => body(call).contains(expected) || buttons(call).exists(_._1 == expected))

  private def eventually(clue: => String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 60.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(25)
    assert(condition, s"not within 60 s: $clue")

  private def launched[A](clock: SteppedClock)(test: Chat => A): A =
    val wire = FakeTelegramTransport(clock)
    supervised:
      val config = settings
      val adapter =
        TelegramAssembly.adapter(config, telegram, wire, clock)(StatusImages.textOnly, Some(PgInboundInbox(dataSource)))
      Launch.start(config, dataSource, Launch.Parts(clock, Map("telegram" -> adapter), fast), log = _ => ())
      test(Chat(clock, wire, config.healthPort))

  private def strings(query: String): List[String] = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query) // a query of this suite, never input
      Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
    finally st.close()
  }

  // ---------- The owner's set-up, through the chat only ----------

  /** `/start`, the medication consent in two stages, and the timezone: the account exists only after this. */
  private def onboard(chat: Chat): Unit =
    val medication = ConsentPurpose.Medication
    chat.say("/start", ConsentCopy.notice.head)
    chat.tapLabel(ConsentCopy.onboardingLabel(medication), ConsentCopy.statement(medication))
    chat.tapLabel(ConsentCopy.Agree, ConsentCopy.confirmation(medication))
    chat.tapLabel(ConsentCopy.Confirm, IdentityCopy.timezonePrompt)
    chat.tap(_.endsWith("— UTC"), "UTC")
    chat.tapLabel(Labels.Yes, ConsentCopy.receipt(medication, "21 Sep 2026"))
    eventually("the account exists")(strings("SELECT 1 FROM users").size == 1)

  /** `/meds`, Add medication, a name, a dose, 09:00 every day. */
  private def addDailyMedication(chat: Chat): Unit =
    chat.say("/meds", "Add medication")
    chat.tapLabel("Add medication", WizardCopy.fieldName)
    chat.say("Vitamin D", WizardCopy.fieldDose)
    chat.say("1000 IU", WizardCopy.timesPrompt)
    chat.say("09:00", WizardCopy.instructionsPrompt)
    chat.tapLabel(WizardCopy.noInstructions, WizardCopy.daysPrompt)
    chat.tapLabel(WizardCopy.everyDay, Labels.Create)
    chat.tapLabel(Labels.Create, "")
    eventually("the schedule exists")(strings("SELECT status FROM medication_schedules") == List("active"))

  /** A typed amount, the water consent in two stages, then the hourly plan from the offer. */
  private def turnOnHourlyWater(chat: Chat): Unit =
    chat.say("250 ml", ConsentCopy.statement(ConsentPurpose.Hydration))
    chat.tapLabel(ConsentCopy.Agree, ConsentCopy.confirmation(ConsentPurpose.Hydration))
    chat.tapLabel(ConsentCopy.Confirm, PromptCopy.offerWater)
    val hourly = PromptCopy.presetName("water.hourly")
    chat.tapLabel(hourly, PromptCopy.TurnOn)
    chat.tapLabel(PromptCopy.TurnOn, PromptCopy.applied(hourly))

  /** The account, its delivery channel and the chat id that channel sends to, as production wrote them. */
  private def productionAccount(): (UUID, String) =
    val accountId = UUID.fromString(strings("SELECT id::text FROM users").head)
    val chats = strings(
      "SELECT pi.dm_channel_id FROM delivery_channels dc JOIN platform_identities pi ON pi.id = dc.platform_identity_id"
    )
    (accountId, chats.mkString(","))

  /** The kinds whose rows the reminder loop ties to an occurrence and its epoch. */
  private val OccurrenceKinds = Set("reminder", "missed_notice", "reminder_finalize")

  // ---------- Acceptance ----------

  test("an account made by /start gets its 09:00 reminder and its water nudge in the owner's chat"):
    val clock = SteppedClock(start)
    launched(clock): chat =>
      onboard(chat)
      addDailyMedication(chat)
      turnOnHourlyWater(chat)
      assertEquals(productionAccount()._2, owner.toString, "onboarding recorded the owner's chat on the identity")

      val before = chat.messages.size
      // Time passes as in production: quickly up to 09:01, then slowly, so the per-chat send budget refills.
      eventually(s"the reminder and a water prompt are sent: ${strings("SELECT kind || ':' || status FROM outbox_messages")}"):
        val step = if clock.now().isBefore(Instant.parse("2026-09-21T09:01:00Z")) then 30L else 1L
        clock.advance(Duration.ofSeconds(step))
        val kinds = strings("SELECT kind FROM outbox_messages WHERE status = 'sent'")
        kinds.contains("reminder") && kinds.contains(PromptLoop.OutboxKind)

      val sentAfter = chat.messages.drop(before).filter(_.method == "sendMessage")
      val reminder = sentAfter.find(call => chat.body(call).contains("Vitamin D")).getOrElse(fail("no reminder card"))
      assertEquals(reminder.number("chat_id"), Some(owner), "the reminder reached the owner's chat")
      assert(chat.buttons(reminder).map(_._1).contains("Taken"), chat.buttons(reminder).toString)
      val prompts = strings(
        s"SELECT platform_message_id FROM outbox_messages WHERE kind = '${PromptLoop.OutboxKind}' AND status = 'sent'"
      )
      assert(prompts.nonEmpty && prompts.forall(_.startsWith(s"$owner:")), s"water prompts went to $prompts")
      assertEquals(
        strings("SELECT status FROM outbox_messages WHERE status IN ('dead', 'failed_retry', 'failed_permanent')"),
        Nil,
        "nothing failed on the way"
      )

  test("every outbox kind renders into the owner's chat for an account made by /start"):
    val clock = SteppedClock(start)
    launched(clock): chat =>
      onboard(chat)
      addDailyMedication(chat)
    val (accountId, _) = productionAccount()
    val uow = PgUnitOfWork(dataSource, clock, DataKeys(settings.kek, clock))
    val codec = CallbackCodec(settings.callbackKeys)
    val renderer = Workers.outboxRenderer(uow, codec, clock)
    val occurrence = UUID.fromString(
      strings("SELECT id::text FROM dose_occurrences ORDER BY scheduled_for LIMIT 1").headOption
        .getOrElse(fail("adding the medication materialised no occurrence"))
    )
    val epoch = strings(s"SELECT epoch::text FROM dose_occurrences WHERE id = '$occurrence'").head.toInt
    val message = OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("rendered for the test")))),
      dedupeKey = "production-path",
      correlationId = "production-path"
    )
    val ownerHandle = s"$owner:1"
    val payloads: List[(String, OutboxOp, String, Option[String])] = List(
      ("reminder", OutboxOp.Send, LoopDispatch.toJson(LoopDispatch.Reminder(occurrence, None, "initial", 0, false)), None),
      ("missed_notice", OutboxOp.Send, LoopDispatch.toJson(LoopDispatch.MissedNotice(occurrence, None, false)), None),
      (
        "digest",
        OutboxOp.Send,
        LoopDispatch.toJson(LoopDispatch.Digest(accountId, 0L, List(DigestItem(occurrence, epoch)))),
        None
      ),
      (
        "reminder_finalize",
        OutboxOp.Finalize,
        LoopDispatch.toJson(LoopDispatch.FinalizeControls(occurrence, "resolved")),
        Some(ownerHandle)
      ),
      ("interaction_reply", OutboxOp.Send, OutboundMessage.toJson(message), None),
      (PromptLoop.OutboxKind, OutboxOp.Send, OutboundMessage.toJson(message), None)
    )
    uow.transaction: tx =>
      // The channel lookup of every loop producer.
      val channel = tx.channels.activePrimaryChannels(accountId).headOption.getOrElse(fail("no primary channel"))
      payloads.foreach: (kind, op, payload, target) =>
        tx.outbox.enqueue(
          NewOutboxMessage(
            id = UUID.randomUUID(),
            sendKey = s"production-path:$kind",
            op = op,
            kind = kind,
            vendor = channel.vendor,
            accountId = Some(accountId),
            occurrenceId = Option.when(OccurrenceKinds(kind))(occurrence),
            channelId = Some(channel.channelId),
            epoch = Option.when(OccurrenceKinds(kind))(epoch),
            payload = payload,
            target = target,
            importance = "reminder",
            nextAttemptAt = clock.now()
          )
        )
      // The export producer enqueues through its own production call.
      ExportRequests.enqueue(tx, IdSource.uuidV7(java.security.SecureRandom()), accountId, message, "production-path:export", clock.now())
    val capabilities: CapabilityProfile = CapabilityProfiles.Telegram
    val rows = uow.transaction(_.outbox.claim(clock.now(), Seq("telegram"), 50, Duration.ofSeconds(60)))
    assertEquals(
      rows.map(_.kind).toSet,
      Set("reminder", "missed_notice", "digest", "reminder_finalize", "interaction_reply", "prompt", ExportRequests.OutboxKind)
    )
    rows.foreach: row =>
      val (target, rendered) =
        try renderer.render(row, capabilities)
        catch case error: Exception => fail(s"${row.kind} does not render: ${error.getClass.getSimpleName} ${error.getMessage}")
      assertEquals(target.chatId, owner.toString, s"${row.kind} goes to the owner's chat")
      assert(rendered.chunks.exists(_.nonEmpty), s"${row.kind} renders text")

  // ---------- Operations on the launched process ----------

  private def get(port: Int, path: String): (Int, String) =
    val conn = java.net.URI(s"http://127.0.0.1:$port$path").toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
    try
      val status = conn.getResponseCode
      val stream = if status < 400 then conn.getInputStream else conn.getErrorStream
      (status, String(stream.readAllBytes()))
    finally conn.disconnect()

  test("/readyz of the launched process is ready with an empty outbox and exposes /metrics"):
    launched(SteppedClock(start)): chat =>
      assertEquals(
        get(chat.healthPort, "/readyz"),
        (200, """{"status":"ready","database":"up","outbox_pending":0,"outbox_oldest_pending_seconds":0}""")
      )
      assertEquals(get(chat.healthPort, "/metrics")._1, 200)

  test("a dead outbox row and a quarantined update each reach the allowlisted owner's chat as one alert"):
    val clock = SteppedClock(start)
    launched(clock): chat =>
      clock.advance(Duration.ofMinutes(1))
      withConnection { conn =>
        val st = conn.prepareStatement(
          "INSERT INTO outbox_messages (id, send_key, op, kind, vendor, importance, payload, status, next_attempt_at, " +
            "attempted_at) VALUES (gen_random_uuid(), 'dead-1', 'send', 'reminder', 'telegram', 'reminder', '{}', " +
            "'dead', ?, ?); " +
            "INSERT INTO inbound_inbox (vendor, vendor_event_id, chat_key, sequence, status, attempts, received_at, " +
            "finished_at) VALUES ('telegram', 'q-1', '7001', 1, 'quarantined', 5, ?, ?)"
        )
        try
          val at = java.sql.Timestamp.from(clock.now())
          (1 to 4).foreach(st.setTimestamp(_, at))
          st.execute()
        finally st.close()
      }
      val dead = dosegram.core.domain.copy.OpsCopy.outboxDead(1)
      val quarantined = dosegram.core.domain.copy.OpsCopy.inboxQuarantined(1)
      eventually("both alerts are sent"):
        clock.advance(Duration.ofSeconds(2))
        Set(dead, quarantined).subsetOf(chat.messages.map(chat.body).toSet)
      val alerts = chat.messages.filter(call => Set(dead, quarantined)(chat.body(call)))
      assertEquals(alerts.map(_.number("chat_id")), List(Some(owner), Some(owner)))
      Thread.sleep(500)
      assertEquals(chat.messages.count(call => Set(dead, quarantined)(chat.body(call))), 2, "one alert per condition")
end ProductionPathSuite
