package dosegram.adapter.telegram

import dosegram.contracts.*
import dosegram.core.domain.copy.AdapterCopy

import java.time.Duration
import java.util.UUID

/** ROADMAP G1.5: the Telegram `ChatAdapter` on the fake wire. Every test reads what the wire saw. */
class TelegramAdapterSuite extends munit.FunSuite:

  private val chat = TelegramRig.Chat

  private def text(body: String): RenderedMessage = RenderedMessage(chunks = List(body))

  private def buttons(tokens: String*): RenderedControls =
    RenderedControls.Buttons(
      List(tokens.toList.zipWithIndex.map((token, i) => RenderedChoice(i + 1, s"Choice ${i + 1}", ChoiceAction.Callback(token))))
    )

  private val photo = AttachmentRef(MediaKind.Photo, "asset-week", "image/png", "Seven days, five recorded.")

  // ---------- Start ----------

  test("start checks the bot's identity and sets the command list as the default menu button"):
    val rig = TelegramRig().started()
    assertEquals(rig.wire.calls.map(_.method), List("getMe", "setChatMenuButton"))
    assertEquals(
      rig.wire.calls.last.request.params,
      ujson.Obj("menu_button" -> ujson.Obj("type" -> "commands"))
    )

  test("start refuses a token that belongs to another bot than the configured one"):
    val rig = TelegramRig(TelegramRig.webhook.copy(expectedBotId = Some(42L)))
    val error = intercept[IllegalStateException](rig.started())
    assert(error.getMessage.contains("another bot"), error.getMessage)
    assertEquals(rig.wire.calls.map(_.method), List("getMe"))

  test("start fails when the bot's identity cannot be read"):
    val rig = TelegramRig()
    val unauthorized = BotApiFixtures.error("unauthorized-401")
    rig.wire.replyNext("getMe", unauthorized.status, unauthorized.body)
    assert(intercept[IllegalStateException](rig.started()).getMessage.contains("TokenInvalid"))

  test("polling deletes the webhook of a development bot only, then runs the reader"):
    val dev = TelegramRig(TelegramRig.webhook.copy(updates = UpdatesMode.Poll, developmentBot = true))
    dev.wire.enqueueUpdates(BotApiFixtures.update("message-text"))
    // The reader is the timer's first task; stopping the adapter from the sink ends its loop after one batch.
    dev.adapter.start(
      event =>
        val accepted = dev.sink.push(event)
        dev.adapter.stop()
        accepted
      ,
      None
    )
    assertEquals(dev.wire.calls.map(_.method), List("getMe", "setChatMenuButton", "deleteWebhook"))
    dev.timer.advance(Duration.ZERO)
    assertEquals(dev.events.map(_.vendorEventId), List("telegram:update:900000001"))

    val staging = TelegramRig(TelegramRig.webhook.copy(updates = UpdatesMode.Poll, developmentBot = false))
    staging.started()
    assertEquals(staging.wire.calls("deleteWebhook"), Nil)

  // ---------- Inbound ----------

  test("an update becomes one inbound event keyed by its update id"):
    val rig = TelegramRig().started()
    assert(rig.ingest("message-text"))
    val event = rig.events.head
    assertEquals(event.vendor, "telegram")
    assertEquals(event.vendorEventId, "telegram:update:900000001")
    assertEquals(event.cursor, Some("900000001"))
    assertEquals(event.chat, ChatRef("telegram", "7001"))
    assertEquals(event.actor, PlatformIdentity("telegram", "7001"))
    assertEquals(event.receivedAt, TelegramRig.T0)
    assertEquals(event.body, Inbound.MessageReceived("hello there", None, truncated = false))
    assertEquals(event.interaction, None)

  test("before start an update is not accepted, so it is delivered again"):
    val rig = TelegramRig()
    assert(!rig.ingest("message-text"))
    assertEquals(rig.events, Nil)

  test("a full mediator queue refuses the update"):
    val rig = TelegramRig().started()
    rig.acceptEvents = false
    assert(!rig.ingest("message-text"))

  test("an update from a group produces no event and no reply"):
    val rig = TelegramRig().started()
    assert(rig.ingest("group-message"))
    assertEquals(rig.events, Nil)
    assertEquals(rig.callsAfterStart, Nil)
    assertEquals(rig.logs, List("telegram update ignored: reason=not_private update_id=900000013"))

  test("an update of an unknown kind is accepted and its body is never logged"):
    val rig = TelegramRig().started()
    assert(rig.ingest("unknown-kind"))
    assertEquals(rig.events, Nil)
    assert(!rig.logs.exists(_.contains("CANARY-UNKNOWN-BODY")), rig.logs.toString)

  test("a sender outside the allowlist gets the one fixed reply and produces no event"):
    val rig = TelegramRig(TelegramRig.webhook.copy(access = AccessPolicy.Allowlist(Set(1L)))).started()
    assert(rig.ingest("message-text"))
    assertEquals(rig.events, Nil)
    assertEquals(rig.messageCalls.map(call => (call.method, call.text("text"))), List(("sendMessage", Some(AdapterCopy.notAvailable))))

  test("a tap from a sender outside the allowlist is answered with the fixed reply and goes nowhere"):
    val rig = TelegramRig(TelegramRig.webhook.copy(access = AccessPolicy.Allowlist(Set(1L)))).started()
    assert(rig.ingest("callback-query"))
    assertEquals(rig.events, Nil)
    assertEquals(rig.answers.map(_.text("text")), List(Some(AdapterCopy.notAvailable)))

  test("a sender on the allowlist is served"):
    val rig = TelegramRig(TelegramRig.webhook.copy(access = AccessPolicy.Allowlist(Set(7001L)))).started()
    rig.ingest("message-text")
    assertEquals(rig.events.size, 1)

  test("a callback without data is answered and produces no event"):
    val rig = TelegramRig().started()
    assert(rig.ingest("callback-query-without-data"))
    assertEquals(rig.events, Nil)
    assertEquals(rig.answers.map(_.text("callback_query_id")), List(Some("4382bfdwdsb323b2dc")))

  // ---------- The single callback answer (TG-11, TG-12) ----------

  test("a tap nobody answers is answered by the watchdog at 1.5 seconds, once, with no text"):
    val rig = TelegramRig().started()
    val handle = rig.tap("dc:placeholder").interaction.get
    rig.timer.advance(Duration.ofMillis(1499))
    assertEquals(rig.answers, Nil)
    assert(!handle.acked)
    rig.timer.advance(Duration.ofMillis(1))
    assertEquals(rig.answers.map(_.request.params), List(ujson.Obj("callback_query_id" -> "4382bfdwdsb323b2d9")))
    assert(handle.acked)
    rig.timer.advance(Duration.ofSeconds(60))
    assertEquals(rig.answers.size, 1)
    assertEquals(rig.ackLatencies, List(Duration.ofMillis(1500)))

  test("a toast that follows the early neutral answer inside the grace time is the one answer"):
    val rig = TelegramRig().started()
    val handle = rig.tap("dc:placeholder").interaction.get
    handle.answer(None)
    assert(!handle.acked, "the neutral answer is held back, so the toast can still be shown")
    rig.timer.advance(Duration.ofMillis(100))
    handle.answer(Some("Already recorded at 09:03."))
    assert(handle.acked)
    rig.timer.advance(Duration.ofSeconds(5))
    assertEquals(rig.answers.map(_.text("text")), List(Some("Already recorded at 09:03.")))

  test("without a toast the neutral answer goes out when the grace time ends"):
    val rig = TelegramRig().started()
    val handle = rig.tap("dc:placeholder").interaction.get
    handle.answer(None)
    rig.timer.advance(AckTiming.Default.neutralGrace)
    assertEquals(rig.answers.map(_.text("text")), List(None))
    assert(handle.acked)
    handle.answer(Some("too late for a toast"))
    rig.timer.advance(Duration.ofSeconds(5))
    assertEquals(rig.answers.size, 1)

  test("a toast answered at once spends the answer; the watchdog adds nothing"):
    val rig = TelegramRig().started()
    val handle = rig.tap("dc:placeholder").interaction.get
    handle.answer(Some("This button is no longer valid."))
    rig.timer.advance(Duration.ofSeconds(5))
    assertEquals(rig.answers.map(_.text("text")), List(Some("This button is no longer valid.")))

  test("a query older than the answer age is not answered at all"):
    val slow = AckTiming(Duration.ofMinutes(2), Duration.ofMillis(300), Duration.ofSeconds(30))
    val rig = TelegramRig(TelegramRig.webhook.copy(ack = slow)).started()
    val handle = rig.tap("dc:placeholder").interaction.get
    rig.timer.advance(Duration.ofSeconds(31))
    handle.answer(Some("late"))
    assert(handle.acked)
    assertEquals(rig.answers, Nil)

  test("an answer the API calls expired is a success: counted, not logged as a failure, nothing thrown"):
    val rig = TelegramRig().started()
    val expired = BotApiFixtures.error("callback-expired-400")
    rig.wire.replyNext("answerCallbackQuery", expired.status, expired.body)
    rig.tap("dc:placeholder").interaction.get.answer(Some("x"))
    assertEquals(rig.expiredCallbacks, 1)
    assertEquals(rig.logs, Nil)

  test("an answer that fails for another reason is logged by its kind and thrown nowhere"):
    val rig = TelegramRig().started()
    rig.wire.replyNext("answerCallbackQuery", WireReply.Failed(WireFailure.Timeout))
    rig.tap("dc:placeholder").interaction.get.answer(Some("x"))
    assertEquals(rig.logs, List("telegram answerCallbackQuery failed: Unavailable"))

  test("editSource edits the message the tap came from"):
    val rig = TelegramRig().started()
    rig.tap("dc:placeholder").interaction.get.editSource(text("This button is no longer valid."))
    val edit = rig.messageCalls.last
    assertEquals(edit.method, "editMessageText")
    assertEquals(edit.number("message_id"), Some(50L))

  // ---------- Send ----------

  test("a text message with buttons is one sendMessage with an inline keyboard; the handle is its message id"):
    val rig = TelegramRig().started()
    val handle = rig.adapter.send(chat, text("Time for <b>Sample</b>.").copy(controls = List(buttons("dc:a", "dc:b"))), "k1")
    assertEquals(handle, MessageHandle("telegram", "7001", "1"))
    val call = rig.messageCalls.last
    assertEquals(call.method, "sendMessage")
    assertEquals(call.number("chat_id"), Some(7001L))
    assertEquals(call.text("text"), Some("Time for <b>Sample</b>."))
    assertEquals(call.text("parse_mode"), Some("HTML"))
    assertEquals(
      call.request.params("reply_markup")("inline_keyboard")(0).arr.map(_("callback_data").str).toList,
      List("dc:a", "dc:b")
    )

  test("several chunks are several messages; only the last carries the keyboard and names the handle"):
    val rig = TelegramRig().started()
    val handle =
      rig.adapter.send(chat, RenderedMessage(chunks = List("one", "two", "three"), controls = List(buttons("dc:a"))), "k1")
    assertEquals(rig.messageCalls.map(_.text("text")), List(Some("one"), Some("two"), Some("three")))
    assertEquals(rig.messageCalls.map(_.request.params.obj.contains("reply_markup")), List(false, false, true))
    assertEquals(handle.messageId, "3")

  test("a silent message with previews off sets both flags"):
    val rig = TelegramRig().started()
    rig.adapter.send(chat, text("quiet").copy(silent = true, suppressPreview = true), "k1")
    val params = rig.messageCalls.last.request.params
    assertEquals(params("disable_notification"), ujson.True)
    assertEquals(params("link_preview_options"), ujson.Obj("is_disabled" -> true))

  test("a chat id that is not a number is a permanent error and nothing is sent"):
    val rig = TelegramRig().started()
    val error = intercept[ChatError.Permanent](rig.adapter.send(ChatRef("telegram", "dm:owner"), text("x"), "k1"))
    assert(!error.channelFatal)
    assertEquals(rig.messageCalls, Nil)

  test("a photo is uploaded once with its caption; the handle marks a media message and the file id is kept"):
    val rig = TelegramRig().started()
    rig.storeAsset("asset-week", Array[Byte](1, 2, 3))
    val message = RenderedMessage(chunks = List("Your week"), controls = List(buttons("dc:a")), attachment = Some(photo))
    val first = rig.adapter.send(chat, message, "k1")
    assertEquals(first.messageId, "m1")
    val upload = rig.messageCalls.last
    assertEquals(upload.method, "sendPhoto")
    assertEquals(upload.text("caption"), Some("Your week"))
    assertEquals(upload.request.file.map(f => (f.fileName, f.mimeType, f.bytes.toList)), Some(("image.png", "image/png", List[Byte](1, 2, 3))))
    assert(upload.request.params.obj.contains("reply_markup"))
    assertEquals(rig.fileIds.get("asset-week", MediaKind.Photo), Some("fake-photo-1"))

    rig.timer.advance(Duration.ofSeconds(5))
    rig.adapter.send(chat, message, "k2")
    val resend = rig.messageCalls.last
    assertEquals(resend.text("photo"), Some("fake-photo-1"))
    assertEquals(resend.request.file, None)

  test("a cached file id the API rejects is forgotten and the bytes are uploaded once"):
    val rig = TelegramRig().started()
    rig.storeAsset("asset-week", Array[Byte](1))
    rig.fileIds.put("asset-week", MediaKind.Photo, "stale-file-id")
    val rejected = BotApiFixtures.error("wrong-file-id-400")
    rig.wire.replyNext("sendPhoto", rejected.status, rejected.body)
    val handle = rig.adapter.send(chat, RenderedMessage(chunks = Nil, attachment = Some(photo)), "k1")
    assertEquals(rig.messageCalls.map(_.request.file.isDefined), List(false, true))
    assertEquals(rig.fileIds.get("asset-week", MediaKind.Photo), Some("fake-photo-1"))
    assertEquals(handle.messageId, "m1")

  test("without the asset's bytes the text equivalent is sent in place of the image"):
    val rig = TelegramRig().started()
    val attachment = photo.copy(altText = "Doses <5 of 7> recorded & counted.")
    val handle = rig.adapter.send(chat, RenderedMessage(chunks = List("Your week"), attachment = Some(attachment)), "k1")
    val call = rig.messageCalls.last
    assertEquals(call.method, "sendMessage")
    assertEquals(call.text("text"), Some("[image: Doses &lt;5 of 7&gt; recorded &amp; counted.]\n\nYour week"))
    assertEquals(handle.messageId, "1")
    assertEquals(rig.logs, List("telegram asset unavailable: sent the text equivalent (kind=Photo)"))

  test("a first chunk too long for a caption follows the image as text"):
    val rig = TelegramRig().started()
    rig.storeAsset("asset-week", Array[Byte](1))
    val long = "x" * 1025
    val handle = rig.adapter.send(chat, RenderedMessage(chunks = List(long), attachment = Some(photo)), "k1")
    assertEquals(rig.messageCalls.map(_.method), List("sendPhoto", "sendMessage"))
    assertEquals(rig.messageCalls.head.text("caption"), None)
    assertEquals(rig.messageCalls.last.text("text"), Some(long))
    assertEquals(handle.messageId, "2")

  test("a document goes out with sendDocument under its file name"):
    val rig = TelegramRig().started()
    rig.storeAsset("asset-export", Array[Byte](7))
    val document = AttachmentRef(MediaKind.Document, "asset-export", "text/csv", "Your records as a file.", Some("export.csv"))
    rig.adapter.send(chat, RenderedMessage(chunks = Nil, attachment = Some(document)), "k1")
    assertEquals(rig.messageCalls.last.method, "sendDocument")
    assertEquals(rig.messageCalls.last.request.file.map(_.fileName), Some("export.csv"))

  test("a short-lived message is deleted when its time is up"):
    val rig = TelegramRig().started()
    val handle = rig.adapter.send(chat, text("toast").copy(deleteAfter = Some(Duration.ofSeconds(30))), "k1")
    rig.timer.advance(Duration.ofSeconds(29))
    assertEquals(rig.wire.calls("deleteMessage"), Nil)
    rig.timer.advance(Duration.ofSeconds(1))
    assertEquals(rig.wire.calls("deleteMessage").map(_.number("message_id")), List(handle.messageId.toLongOption))

  test("callback data over 64 bytes is refused before anything goes on the wire"):
    val rig = TelegramRig().started()
    intercept[ChatError.Permanent](rig.adapter.send(chat, text("x").copy(controls = List(buttons("dc:" + "y" * 62))), "k1"))
    assertEquals(rig.messageCalls, Nil)

  test("an app choice is a web_app button that opens the Mini App at its route"):
    val rig = TelegramRig().started()
    val app = RenderedControls.Buttons(List(List(RenderedChoice(0, "Open app", ChoiceAction.OpenApp(AppRoute("today"))))))
    rig.adapter.send(chat, text("Open the app.").copy(controls = List(app)), "k1")
    assertEquals(
      rig.messageCalls.last.request.params("reply_markup"),
      ujson.Obj(
        "inline_keyboard" -> ujson.Arr(
          ujson.Arr(ujson.Obj("text" -> "Open app", "web_app" -> ujson.Obj("url" -> "https://app.example/?route=today")))
        )
      )
    )

  // ---------- Edit and delete ----------

  test("an edit replaces text and keyboard in place and returns the next revision"):
    val rig = TelegramRig().started()
    val sent = rig.adapter.send(chat, text("Time for Sample.").copy(controls = List(buttons("dc:a"))), "k1")
    val edited = rig.adapter.edit(sent, text("Recorded at 09:03."))
    assertEquals(edited, sent.copy(revision = 1))
    val call = rig.messageCalls.last
    assertEquals(call.method, "editMessageText")
    assertEquals(call.number("message_id"), Some(1L))
    assertEquals(call.text("text"), Some("Recorded at 09:03."))
    assertEquals(call.request.params("reply_markup"), ujson.Obj("inline_keyboard" -> ujson.Arr()))

  test("an edit of a media message edits its caption"):
    val rig = TelegramRig().started()
    rig.adapter.edit(MessageHandle("telegram", "7001", "m52"), text("Your week, updated"))
    val call = rig.messageCalls.last
    assertEquals(call.method, "editMessageCaption")
    assertEquals(call.number("message_id"), Some(52L))
    assertEquals(call.text("caption"), Some("Your week, updated"))

  test("an edit with several chunks edits the first and sends the rest; the keyboard is on the last"):
    val rig = TelegramRig().started()
    val handle = MessageHandle("telegram", "7001", "9")
    rig.adapter.edit(handle, RenderedMessage(chunks = List("one", "two"), controls = List(buttons("dc:a"))))
    assertEquals(rig.messageCalls.map(_.method), List("editMessageText", "sendMessage"))
    assertEquals(rig.messageCalls.head.request.params("reply_markup"), ujson.Obj("inline_keyboard" -> ujson.Arr()))
    assert(rig.messageCalls.last.request.params.obj.contains("reply_markup"))

  test("an edit that changes nothing is a success"):
    val rig = TelegramRig().started()
    val unchanged = BotApiFixtures.error("not-modified-400")
    rig.wire.replyNext("editMessageText", unchanged.status, unchanged.body)
    val handle = MessageHandle("telegram", "7001", "9")
    assertEquals(rig.adapter.edit(handle, text("same")), handle.copy(revision = 1))

  test("an edit of a message that is gone is a typed missing-target error"):
    val rig = TelegramRig().started()
    val missing = BotApiFixtures.error("edit-not-found-400")
    rig.wire.replyNext("editMessageText", missing.status, missing.body)
    val error = intercept[ChatError.Permanent](rig.adapter.edit(MessageHandle("telegram", "7001", "9"), text("x")))
    assertEquals(error.reason, ChatError.PermanentReason.MissingTarget)
    assert(!error.channelFatal)

  test("an edit never carries an attachment"):
    val rig = TelegramRig().started()
    val error = intercept[ChatError.Unsupported]:
      rig.adapter.edit(MessageHandle("telegram", "7001", "9"), RenderedMessage(chunks = List("x"), attachment = Some(photo)))
    assertEquals(error.op, "editMedia")
    assertEquals(rig.messageCalls, Nil)

  test("delete removes the message; one that is already gone is a success"):
    val rig = TelegramRig().started()
    rig.adapter.delete(MessageHandle("telegram", "7001", "m9"))
    assertEquals(rig.messageCalls.last.request.params, ujson.Obj("chat_id" -> 7001, "message_id" -> 9))
    val gone = BotApiFixtures.error("delete-not-found-400")
    rig.wire.replyNext("deleteMessage", gone.status, gone.body)
    rig.timer.advance(Duration.ofSeconds(5))
    rig.adapter.delete(MessageHandle("telegram", "7001", "9"))

  test("a delete outside the window is a permanent error that leaves the channel alone"):
    val rig = TelegramRig().started()
    val window = BotApiFixtures.error("delete-window-400")
    rig.wire.replyNext("deleteMessage", window.status, window.body)
    val error = intercept[ChatError.Permanent](rig.adapter.delete(MessageHandle("telegram", "7001", "9")))
    assert(!error.channelFatal)

  // ---------- Flood control and reachability ----------

  test("a 429 surfaces with its wait, and nothing more goes to that chat until the wait is over"):
    val rig = TelegramRig().started()
    val flood = BotApiFixtures.error("flood-429")
    rig.wire.replyNext("sendMessage", flood.status, flood.body)
    assertEquals(intercept[ChatError.RateLimited](rig.adapter.send(chat, text("one"), "k1")).retryAfter, Duration.ofSeconds(30))
    assertEquals(rig.messageCalls.size, 1)

    rig.timer.advance(Duration.ofSeconds(10))
    val early = intercept[ChatError.RateLimited](rig.adapter.send(chat, text("two"), "k2"))
    assertEquals(early.retryAfter, Duration.ofSeconds(20))
    intercept[ChatError.RateLimited](rig.adapter.edit(MessageHandle("telegram", "7001", "1"), text("three")))
    assertEquals(rig.messageCalls.size, 1, "the paused chat must not reach the wire")

    rig.adapter.send(ChatRef("telegram", "7002"), text("another chat is not paused"), "k3")
    assertEquals(rig.messageCalls.size, 2)

    rig.timer.advance(Duration.ofSeconds(20))
    rig.adapter.send(chat, text("after the wait"), "k4")
    assertEquals(rig.messageCalls.last.text("text"), Some("after the wait"))

  test("a blocked user is Unreachable, fatal for the channel"):
    val rig = TelegramRig().started()
    val blocked = BotApiFixtures.error("blocked-403")
    rig.wire.replyNext("sendMessage", blocked.status, blocked.body)
    val error = intercept[ChatError.Unreachable](rig.adapter.send(chat, text("Time for Sample."), "k1"))
    assert(error.channelFatal)

  test("an outage is retryable and a rejected request is permanent without touching the channel"):
    val rig = TelegramRig().started()
    rig.wire.replyNext("sendMessage", WireReply.Failed(WireFailure.Connection))
    intercept[ChatError.Retryable](rig.adapter.send(chat, text("x"), "k1"))
    val rejected = BotApiFixtures.error("parse-entities-400")
    rig.wire.replyNext("sendMessage", rejected.status, rejected.body)
    val error = intercept[ChatError.Permanent](rig.adapter.send(chat, text("x"), "k2"))
    assert(!error.channelFatal)
    assert(!error.getMessage.contains("CANARY-DESCRIPTION"), error.getMessage)

  test("paced by the gate, a burst to one chat never draws a 429 from a wire that enforces the limits"):
    val rig = TelegramRig().started()
    rig.wire.enforce(FakeTelegramTransport.FloodLimits(burst = 3, perSecond = 1.0, retryAfter = Duration.ofSeconds(30)))
    (1 to 12).foreach(i => rig.adapter.send(chat, text(s"message $i"), s"k$i"))
    assertEquals(rig.messageCalls.size, 12, "every send reached the wire exactly once")
    // Three at once, then one per second: the twelfth leaves nine seconds after the first.
    assertEquals(Duration.between(TelegramRig.T0, rig.timer.now()), Duration.ofSeconds(9))

  test("the same burst without pacing does draw a 429: the fake wire enforces what it claims"):
    val rig = TelegramRig(TelegramRig.webhook.copy(limits = unlimited)).started()
    rig.wire.enforce(FakeTelegramTransport.FloodLimits(burst = 3, perSecond = 1.0, retryAfter = Duration.ofSeconds(30)))
    (1 to 3).foreach(i => rig.adapter.send(chat, text(s"message $i"), s"k$i"))
    intercept[ChatError.RateLimited](rig.adapter.send(chat, text("message 4"), "k4"))

  private def unlimited: FloodGate.Limits =
    val wide = dosegram.core.scheduling.TokenBuckets.Bucket(capacity = 1000, refillPerSecond = 1000)
    FloodGate.Limits(wide, wide, wide)

  // ---------- Commands ----------

  test("registerCommands sets the command list of every private chat"):
    val rig = TelegramRig().started()
    rig.adapter.registerCommands(List(CommandSpec("today", "Show today's doses."), CommandSpec("help", "Show this help.")))
    assertEquals(rig.api.getMyCommands(), Right(List(BotCommand("today", "Show today's doses."), BotCommand("help", "Show this help."))))

  test("renderText lowers rich text to Telegram HTML inside the text limit"):
    val rig = TelegramRig()
    val rich: RichText = List(Node.Paragraph(List(Inline.Text("a < b & "), Inline.Bold("c"))))
    assertEquals(rig.adapter.renderText(rich), List("a &lt; b &amp; <b>c</b>"))

  // ---------- TG-02: nothing sensitive in any log line ----------

  test("no log line carries message text, callback data, a caption or an identifier of a user"):
    val rig = TelegramRig(TelegramRig.webhook.copy(access = AccessPolicy.Allowlist(Set(7001L)))).started()
    val secretToken = TelegramRig.token("dose.taken", UUID.randomUUID())
    rig.ingest("message-text")
    rig.ingest("photo")
    rig.ingest("unknown-kind")
    rig.ingest("group-message")
    rig.tap(secretToken).interaction.get.answer(Some("CANARY-TOAST"))
    rig.wire.replyNext("sendMessage", WireReply.Failed(WireFailure.Timeout))
    intercept[ChatError](rig.adapter.send(chat, text("CANARY-BODY Metformin 500 mg"), "k1"))
    rig.adapter.send(chat, RenderedMessage(chunks = List("CANARY-CAPTION"), attachment = Some(photo)), "k2")
    rig.wire.replyNext("answerCallbackQuery", WireReply.Failed(WireFailure.Connection))
    rig.tap(secretToken).interaction.get.answer(Some("CANARY-TOAST"))
    val canaries = List("CANARY", "Metformin", "hello there", "lunch", secretToken, "7001", "Synthetic")
    val leaked = for line <- rig.logs; canary <- canaries if line.contains(canary) yield s"$canary in: $line"
    assertEquals(leaked, Nil)
    assert(rig.logs.nonEmpty, "the scenario must produce log lines to check")
