package dosegram.adapter.telegram

import dosegram.contracts.MediaKind
import dosegram.core.ports.Clock

import java.time.Duration
import java.time.Instant

/** ROADMAP G1.4: the typed client builds the documented requests and reads the documented replies, on the fake wire. */
class BotApiSuite extends munit.FunSuite:

  private val clock: Clock = () => Instant.parse("2026-11-13T09:00:00Z")
  private val target = MessageTarget(7001L, 60L)
  private val loud = Delivery(silent = false, noPreview = false)

  private def wired(): (FakeTelegramTransport, BotApi) =
    val wire = FakeTelegramTransport(clock, idlePoll = Duration.ZERO)
    (wire, BotApi(wire))

  private def lastParams(wire: FakeTelegramTransport): ujson.Obj = wire.calls.last.request.params

  test("getMe reads the bot's identity"):
    val (wire, api) = wired()
    wire.replyNext("getMe", 200, BotApiFixtures.response("get-me"))
    assertEquals(api.getMe(), Right(BotIdentity(555000111L, Some("dosegram_dev_bot"))))

  test("sendMessage sends HTML to the chat and returns the message id"):
    val (wire, api) = wired()
    wire.replyNext("sendMessage", 200, BotApiFixtures.response("send-message"))
    val sent = api.sendMessage(OutgoingText(7001L, "Time for <b>Sample</b>.", InlineKeyboard.empty, loud))
    assertEquals(sent, Right(SentMessage(60L, None)))
    assertEquals(
      lastParams(wire),
      ujson.Obj("chat_id" -> 7001, "text" -> "Time for <b>Sample</b>.", "parse_mode" -> "HTML")
    )

  test("a silent message without previews sets both delivery flags"):
    val (wire, api) = wired()
    api.sendMessage(OutgoingText(7001L, "quiet", InlineKeyboard.empty, Delivery(silent = true, noPreview = true)))
    assertEquals(lastParams(wire)("disable_notification"), ujson.True)
    assertEquals(lastParams(wire)("link_preview_options"), ujson.Obj("is_disabled" -> true))

  test("an inline keyboard carries callback data, a style, a Mini App button and a link"):
    val (wire, api) = wired()
    val keyboard = InlineKeyboard(
      List(
        List(InlineButton.Callback("Taken", "dc:token", Some("success")), InlineButton.Callback("Skip", "dc:skip", None)),
        List(InlineButton.WebApp("Open app", "https://app.example/?route=today")),
        List(InlineButton.Url("Privacy notice", "https://example.org/privacy"))
      )
    )
    api.sendMessage(OutgoingText(7001L, "Pick", keyboard, loud))
    assertEquals(
      lastParams(wire)("reply_markup"),
      ujson.Obj(
        "inline_keyboard" -> ujson.Arr(
          ujson.Arr(
            ujson.Obj("text" -> "Taken", "callback_data" -> "dc:token", "style" -> "success"),
            ujson.Obj("text" -> "Skip", "callback_data" -> "dc:skip")
          ),
          ujson.Arr(ujson.Obj("text" -> "Open app", "web_app" -> ujson.Obj("url" -> "https://app.example/?route=today"))),
          ujson.Arr(ujson.Obj("text" -> "Privacy notice", "url" -> "https://example.org/privacy"))
        )
      )
    )

  test("a chat id with 52 significant bits is written as an exact integer"):
    val (wire, api) = wired()
    api.sendMessage(OutgoingText(4503599627370495L, "x", InlineKeyboard.empty, loud))
    assert(ujson.write(lastParams(wire)).contains("\"chat_id\":4503599627370495,"), ujson.write(lastParams(wire)))

  test("a photo upload goes as a file part and returns the file id of the largest size"):
    val (wire, api) = wired()
    wire.replyNext("sendPhoto", 200, BotApiFixtures.response("send-photo"))
    val payload = MediaPayload.Upload("card.png", "image/png", Array[Byte](1, 2, 3))
    val sent =
      api.sendMedia(OutgoingMedia(7001L, MediaKind.Photo, payload, Some("Your week"), InlineKeyboard.empty, loud))
    assertEquals(sent, Right(SentMessage(61L, Some("AgACuploaded-1280"))))
    val request = wire.calls.last.request
    assertEquals(request.params, ujson.Obj("chat_id" -> 7001, "caption" -> "Your week", "parse_mode" -> "HTML"))
    assertEquals(request.file.map(f => (f.field, f.fileName, f.mimeType, f.bytes.toList)),
      Some(("photo", "card.png", "image/png", List[Byte](1, 2, 3))))

  test("a cached photo is sent by file id, with no file part"):
    val (wire, api) = wired()
    api.sendMedia(
      OutgoingMedia(7001L, MediaKind.Photo, MediaPayload.Cached("AgACcached"), None, InlineKeyboard.empty, loud)
    )
    val request = wire.calls.last.request
    assertEquals(request.method, "sendPhoto")
    assertEquals(request.params, ujson.Obj("chat_id" -> 7001, "photo" -> "AgACcached"))
    assertEquals(request.file, None)

  test("a document is sent with sendDocument and returns its file id"):
    val (wire, api) = wired()
    wire.replyNext("sendDocument", 200, BotApiFixtures.response("send-document"))
    val payload = MediaPayload.Upload("export.csv", "text/csv", Array[Byte](9))
    val sent = api.sendMedia(OutgoingMedia(7001L, MediaKind.Document, payload, None, InlineKeyboard.empty, loud))
    assertEquals(sent, Right(SentMessage(62L, Some("BQACuploaded-doc"))))
    assertEquals(wire.calls.last.request.file.map(_.field), Some("document"))

  test("editMessageText replaces the text and the keyboard of one message"):
    val (wire, api) = wired()
    assertEquals(api.editMessageText(target, "Recorded at 09:03.", InlineKeyboard.empty), Right(()))
    assertEquals(wire.calls.last.method, "editMessageText")
    assertEquals(
      lastParams(wire),
      ujson.Obj(
        "chat_id" -> 7001,
        "message_id" -> 60,
        "text" -> "Recorded at 09:03.",
        "parse_mode" -> "HTML",
        "reply_markup" -> ujson.Obj("inline_keyboard" -> ujson.Arr())
      )
    )

  test("editMessageCaption edits the caption of a media message"):
    val (wire, api) = wired()
    assertEquals(api.editMessageCaption(target, "Your week, updated", InlineKeyboard.empty), Right(()))
    assertEquals(wire.calls.last.method, "editMessageCaption")
    assertEquals(lastParams(wire)("caption"), ujson.Str("Your week, updated"))

  test("editMessageReplyMarkup removes the buttons and sends no text"):
    val (wire, api) = wired()
    assertEquals(api.editMessageReplyMarkup(target, InlineKeyboard.empty), Right(()))
    assertEquals(
      lastParams(wire),
      ujson.Obj("chat_id" -> 7001, "message_id" -> 60, "reply_markup" -> ujson.Obj("inline_keyboard" -> ujson.Arr()))
    )

  test("deleteMessage names the chat and the message"):
    val (wire, api) = wired()
    assertEquals(api.deleteMessage(target), Right(()))
    assertEquals(lastParams(wire), ujson.Obj("chat_id" -> 7001, "message_id" -> 60))

  test("answerCallbackQuery clips a toast to 200 characters and omits an empty one"):
    val (wire, api) = wired()
    api.answerCallbackQuery("q1", Some("x" * 250))
    assertEquals(lastParams(wire)("text").str.length, 200)
    api.answerCallbackQuery("q2", None)
    assertEquals(lastParams(wire), ujson.Obj("callback_query_id" -> "q2"))

  test("setMyCommands registers the list for every private chat and getMyCommands reads it back"):
    val (wire, api) = wired()
    val commands = List(BotCommand("today", "Show today's doses."), BotCommand("help", "Show this help."))
    assertEquals(api.setMyCommands(commands), Right(()))
    assertEquals(lastParams(wire)("scope"), ujson.Obj("type" -> "all_private_chats"))
    assertEquals(api.getMyCommands(), Right(commands))

  test("setChatMenuButton sets the default button without a chat and a Mini App button for one chat"):
    val (wire, api) = wired()
    api.setChatMenuButton(None, MenuButton.Commands)
    assertEquals(lastParams(wire), ujson.Obj("menu_button" -> ujson.Obj("type" -> "commands")))
    api.setChatMenuButton(Some(7001L), MenuButton.WebApp("App", "https://app.example/"))
    assertEquals(
      lastParams(wire),
      ujson.Obj(
        "menu_button" -> ujson.Obj(
          "type" -> "web_app",
          "text" -> "App",
          "web_app" -> ujson.Obj("url" -> "https://app.example/")
        ),
        "chat_id" -> 7001
      )
    )

  test("getUpdates long-polls from the offset for the allowed kinds only"):
    val (wire, api) = wired()
    wire.enqueueUpdates(BotApiFixtures.update("message-text"), BotApiFixtures.update("unknown-kind"))
    val updates = api.getUpdates(Some(900000001L), Duration.ofSeconds(50))
    assertEquals(updates.map(_.map(_.updateId)), Right(List(900000001L, 900000017L)))
    val request = wire.calls.last.request
    assertEquals(
      request.params,
      ujson.Obj(
        "timeout" -> 50,
        "allowed_updates" -> ujson.Arr("message", "edited_message", "callback_query", "my_chat_member"),
        "offset" -> 900000001L.toDouble
      )
    )
    assert(request.timeout.compareTo(Duration.ofSeconds(50)) > 0, "the HTTP timeout must outlast the long poll")

  test("getUpdates with a higher offset confirms the earlier updates"):
    val (wire, api) = wired()
    wire.enqueueUpdates(BotApiFixtures.update("message-text"), BotApiFixtures.update("message-reply"))
    assertEquals(api.getUpdates(Some(900000002L), Duration.ZERO).map(_.map(_.updateId)), Right(List(900000002L)))
    assertEquals(api.getUpdates(Some(900000003L), Duration.ZERO), Right(Nil))

  test("a reply that is not JSON is classified by its status"):
    val (wire, api) = wired()
    wire.replyNext("getMe", WireReply.Http(502, "<html>Bad Gateway</html>"))
    assertEquals(api.getMe(), Left(TelegramError.Unavailable))
    wire.replyNext("getMe", WireReply.Http(200, "not json"))
    assertEquals(api.getMe(), Left(TelegramError.Unavailable))

  test("a timeout and a connection failure are Unavailable"):
    val (wire, api) = wired()
    wire.replyNext("getMe", WireReply.Failed(WireFailure.Timeout))
    assertEquals(api.getMe(), Left(TelegramError.Unavailable))
    wire.replyNext("getMe", WireReply.Failed(WireFailure.Connection))
    assertEquals(api.getMe(), Left(TelegramError.Unavailable))

  test("every error fixture surfaces as its case from the method it belongs to"):
    BotApiFixtures.errors.foreach: fixture =>
      val (wire, api) = wired()
      wire.replyNext(fixture.method, fixture.status, fixture.body)
      val result: Either[TelegramError, Any] = fixture.method match
        case "sendMessage"         => api.sendMessage(OutgoingText(7001L, "x", InlineKeyboard.empty, loud))
        case "sendPhoto"           =>
          api.sendMedia(
            OutgoingMedia(7001L, MediaKind.Photo, MediaPayload.Cached("stale"), None, InlineKeyboard.empty, loud)
          )
        case "editMessageText"     => api.editMessageText(target, "x", InlineKeyboard.empty)
        case "deleteMessage"       => api.deleteMessage(target)
        case "answerCallbackQuery" => api.answerCallbackQuery("q", None)
        case "getMe"               => api.getMe()
        case "getUpdates"          => api.getUpdates(None, Duration.ZERO)
        case other                 => fail(s"fixture ${fixture.name} names an unexpected method $other")
      assertEquals(result.left.map(_.productPrefix), Left(fixture.expect), fixture.name)
