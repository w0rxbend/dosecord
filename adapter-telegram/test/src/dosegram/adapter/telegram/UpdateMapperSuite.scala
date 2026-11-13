package dosegram.adapter.telegram

import dosegram.adapter.telegram.UpdateMapper.Mapped
import dosegram.contracts.CallbackRef
import dosegram.contracts.Inbound
import dosegram.contracts.MediaKind
import dosegram.contracts.MediaRef
import dosegram.contracts.MessageHandle
import dosegram.contracts.StartPayload

import java.util.UUID

/** ARCHITECTURE section 7.3: the update mapping, one row per test. */
class UpdateMapperSuite extends munit.FunSuite:

  private val bot = Some("dosegram_dev_bot")

  private def mapped(fixture: String, edit: ujson.Value => Unit = _ => ()): Mapped =
    val json = BotApiFixtures.update(fixture)
    edit(json)
    UpdateMapper.map(TelegramRig.decoded(json), bot)

  private def body(fixture: String, edit: ujson.Value => Unit = _ => ()): Inbound =
    mapped(fixture, edit) match
      case Mapped.Deliver(draft) => draft.body
      case other                 => fail(s"$fixture did not map to an event: $other")

  private def withText(text: String)(json: ujson.Value): Unit = json("message")("text") = text

  test("plain text is MessageReceived from the sender in the chat"):
    mapped("message-text") match
      case Mapped.Deliver(draft) =>
        assertEquals(draft.chatId, 7001L)
        assertEquals(draft.userId, 7001L)
        assertEquals(draft.body, Inbound.MessageReceived("hello there", None, truncated = false))
      case other => fail(other.toString)

  test("a reply names the answered message as its handle"):
    assertEquals(
      body("message-reply"),
      Inbound.MessageReceived("2", Some(MessageHandle("telegram", "7001", "40")), truncated = false)
    )

  test("/start is ConversationStarted without a payload"):
    assertEquals(body("command-start"), Inbound.ConversationStarted(None))

  test("/start with a payload of the deep-link grammar carries it"):
    assertEquals(body("command-start-payload"), Inbound.ConversationStarted(Some(StartPayload("r_today"))))

  test("a payload outside the grammar is ignored: /start behaves as if it had none"):
    assertEquals(body("command-start-bad-payload"), Inbound.ConversationStarted(None))

  test("a payload longer than 24 characters is ignored"):
    val long = "i_" + "a" * 23
    assertEquals(body("command-start", withText(s"/start $long")), Inbound.ConversationStarted(None))
    val fits = "i_" + "a" * 22
    assertEquals(
      body("command-start", withText(s"/start $fits")),
      Inbound.ConversationStarted(Some(StartPayload(fits)))
    )

  test("a command with this bot's name is accepted, lower-cased, with its raw text"):
    assertEquals(
      body("command-with-bot-suffix"),
      Inbound.CommandInvoked("today", Map.empty, "/Today@dosegram_dev_bot")
    )

  test("a command addressed to another bot is not for us"):
    assertEquals(mapped("command-with-bot-suffix", withText("/today@some_other_bot")), Mapped.Ignore("command_for_another_bot"))

  test("a command keeps its argument in the raw text"):
    assertEquals(body("command-with-argument"), Inbound.CommandInvoked("snooze", Map.empty, "/snooze 10"))

  test("text that only looks like a command stays text"):
    assertEquals(
      body("message-text", withText("/ not a command")),
      Inbound.MessageReceived("/ not a command", None, truncated = false)
    )

  test("a photo is MediaReceived with the size nearest to 1280 pixels"):
    assertEquals(
      body("photo"),
      Inbound.MediaReceived(MediaKind.Photo, MediaRef("AgACsynthetic-1280"), Some("lunch"))
    )

  test("a document is MediaReceived with its file id"):
    assertEquals(body("document"), Inbound.MediaReceived(MediaKind.Document, MediaRef("BQACsynthetic-doc"), None))

  test("a tap carries the clear-text fields of its token and the handle of the tapped message"):
    val subject = UUID.randomUUID()
    val token = TelegramRig.token("dose.snooze", subject, value = 30)
    mapped("callback-query", _("callback_query")("data") = token) match
      case Mapped.Deliver(draft) =>
        val source = MessageHandle("telegram", "7001", "50")
        assertEquals(draft.body, Inbound.InteractionSubmitted(CallbackRef(2, subject, 30L, slot = false, token), Some(source)))
        assertEquals(draft.callback, Some(UpdateMapper.CallbackSource("4382bfdwdsb323b2d9", Some(source))))
      case other => fail(other.toString)

  test("a tap on a media message names it as a media handle"):
    mapped("callback-query-on-photo") match
      case Mapped.Deliver(draft) =>
        assertEquals(draft.callback.flatMap(_.source), Some(MessageHandle("telegram", "7001", "m52")))
      case other => fail(other.toString)

  test("callback data that is not a token of ours keeps its raw form under an action nobody handles"):
    body("callback-query") match
      case Inbound.InteractionSubmitted(ref, _) =>
        assertEquals(ref, CallbackRef(0, UUID(0L, 0L), 0L, slot = false, "dc:placeholder"))
      case other => fail(other.toString)

  test("a callback without data is dismissed: answered, never an event"):
    assertEquals(mapped("callback-query-without-data"), Mapped.Dismiss("4382bfdwdsb323b2dc"))

  test("a message outside a private chat produces no event"):
    assertEquals(mapped("group-message"), Mapped.Ignore("not_private"))

  test("kinds without a contract event are ignored by a fixed label"):
    assertEquals(mapped("edited-message"), Mapped.Ignore("edited_message"))
    assertEquals(mapped("my-chat-member-blocked"), Mapped.Ignore("my_chat_member"))
    assertEquals(mapped("web-app-data"), Mapped.Ignore("web_app_data"))
    assertEquals(mapped("unknown-kind"), Mapped.Ignore("unknown_kind"))
    assertEquals(mapped("malformed-message"), Mapped.Ignore("unknown_kind"))
