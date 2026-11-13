package dosegram.adapter.telegram

import java.time.Instant

/** ADR-017: an update goes into the inbox as the minimal JSON the decoder reads, and comes out as the same update. */
class InboxCodecSuite extends munit.FunSuite:

  private def decoded(json: ujson.Value): Update = TelegramRig.decoded(json)

  test("every update fixture comes out of the inbox as the update that went in"):
    BotApiFixtures.updateNames.foreach: name =>
      val update = decoded(BotApiFixtures.update(name))
      assertEquals(InboxCodec.update(InboxCodec.entry(update)), Some(update), name)

  test("the shapes the fixtures may not cover come out unchanged too"):
    val chat = TgChat(7001L, isPrivate = true)
    val shapes = List(
      UpdateBody.Callback(TgCallbackQuery("q-1", TgUser(7001L), None, None)),
      UpdateBody.NewMessage(
        TgMessage(5L, chat, None, None, MessageContent.Document("doc-1", Some("caption")), Some(MessageRef(4L, true)))
      ),
      UpdateBody.EditedMessage(
        TgMessage(6L, chat, Some(TgUser(7001L)), Some(Instant.ofEpochSecond(1_700_000_000L)), MessageContent.Other, None)
      ),
      UpdateBody.NewMessage(TgMessage(7L, TgChat(-100L, isPrivate = false), None, None, MessageContent.WebAppData, None)),
      UpdateBody.NewMessage(
        TgMessage(8L, chat, None, None, MessageContent.Photo(List(TgPhotoSize("p", 90, 60)), None), Some(MessageRef(2L, false)))
      ),
      UpdateBody.ChatMemberChanged(chat, "kicked"),
      UpdateBody.Unknown("message"),
      UpdateBody.Unknown("kind_from_a_later_api_version")
    )
    shapes.zipWithIndex.foreach: (body, index) =>
      val update = Update(1000L + index, body)
      assertEquals(InboxCodec.update(InboxCodec.entry(update)), Some(update), body.productPrefix)

  test("the entry is keyed like the mediator's inbound event and ordered by update_id within its chat"):
    val entry = InboxCodec.entry(decoded(BotApiFixtures.update("message-text")))
    assertEquals(entry.vendor, "telegram")
    assertEquals(entry.vendorEventId, "telegram:update:900000001")
    assertEquals(entry.sequence, 900000001L)
    assertEquals(entry.chatKey, "7001")

  test("a callback is ordered with the chat of its message, or its sender's when the message is gone"):
    val withMessage = InboxCodec.entry(decoded(BotApiFixtures.update("callback-query")))
    assertEquals(withMessage.chatKey, "7001")
    val detached = Update(1L, UpdateBody.Callback(TgCallbackQuery("q", TgUser(8002L), None, Some("x"))))
    assertEquals(InboxCodec.entry(detached).chatKey, "8002")

  test("the stored body keeps nothing the adapter does not read: no names, no language"):
    val raw = BotApiFixtures.update("message-text")
    raw("message")("from") = ujson.Obj(
      "id" -> 7001,
      "is_bot" -> false,
      "first_name" -> "CANARY-NAME",
      "username" -> "canary_user",
      "language_code" -> "en"
    )
    val body = InboxCodec.entry(decoded(raw)).body
    List("CANARY-NAME", "canary_user", "language_code").foreach(secret => assert(!body.contains(secret), body))

  test("a body that is not an update decodes to nothing"):
    val entry = InboxCodec.entry(decoded(BotApiFixtures.update("message-text")))
    assertEquals(InboxCodec.update(entry.copy(body = "not json")), None)
