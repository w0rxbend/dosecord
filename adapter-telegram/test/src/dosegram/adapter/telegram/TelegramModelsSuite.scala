package dosegram.adapter.telegram

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Instant

/** ARCHITECTURE TG-01: updates with unknown fields and unknown kinds decode without error. */
class TelegramModelsSuite extends ScalaCheckSuite:

  private def decode(name: String): Update =
    TelegramModels.decodeUpdate(BotApiFixtures.update(name)).getOrElse(fail(s"$name did not decode"))

  test("a text message decodes with its chat, sender, time and text"):
    decode("message-text") match
      case Update(900000001L, UpdateBody.NewMessage(message)) =>
        assertEquals(message.messageId, 41L)
        assertEquals(message.chat, TgChat(7001L, isPrivate = true))
        assertEquals(message.from, Some(TgUser(7001L, locale = Some("en"))))
        assertEquals(message.sentAt, Some(Instant.ofEpochSecond(1794000000L)))
        assertEquals(message.content, MessageContent.Text("hello there"))
      case other => fail(s"unexpected update: $other")

  test("a reply carries the id of the message it answers"):
    decode("message-reply").body match
      case UpdateBody.NewMessage(message) =>
        assertEquals(message.replyTo, Some(MessageRef(40L, carriesMedia = false)))
      case other => fail(s"unexpected body: $other")

  test("a photo keeps every size and its caption"):
    decode("photo").body match
      case UpdateBody.NewMessage(TgMessage(_, _, _, _, MessageContent.Photo(sizes, caption), _)) =>
        assertEquals(sizes.map(_.longSide), List(90, 320, 1280, 2560))
        assertEquals(caption, Some("lunch"))
      case other => fail(s"unexpected body: $other")

  test("a callback on a message the bot can no longer read still names the message"):
    decode("callback-query-inaccessible-message").body match
      case UpdateBody.Callback(query) =>
        assertEquals(query.message.map(_.messageId), Some(51L))
        assertEquals(query.message.flatMap(_.sentAt), None)
        assertEquals(query.data, Some("dc:placeholder"))
      case other => fail(s"unexpected body: $other")

  test("my_chat_member reports the new status"):
    assertEquals(
      decode("my-chat-member-blocked").body,
      UpdateBody.ChatMemberChanged(TgChat(7001L, isPrivate = true), "kicked")
    )

  test("an update kind the adapter does not know decodes to Unknown with the field name only"):
    assertEquals(decode("unknown-kind"), Update(900000017L, UpdateBody.Unknown("kind_from_a_later_api_version")))

  test("a known kind with a malformed body decodes to Unknown"):
    assertEquals(decode("malformed-message"), Update(900000019L, UpdateBody.Unknown("message")))

  test("identifiers with 52 significant bits decode exactly"):
    decode("large-ids").body match
      case UpdateBody.NewMessage(message) => assertEquals(message.chat.id, 4503599627370495L)
      case other                          => fail(s"unexpected body: $other")

  test("a body without update_id is not an update"):
    assertEquals(TelegramModels.decodeUpdate(ujson.Obj("message" -> ujson.Obj())), None)
    assertEquals(TelegramModels.decodeUpdate(ujson.Str("not an object")), None)

  // ---------- TG-01 as a property: unknown fields anywhere never change what is decoded ----------

  private val unknownValue: Gen[ujson.Value] = Gen.oneOf(
    Gen.const(ujson.Null),
    Gen.alphaNumStr.map(ujson.Str(_)),
    Gen.choose(-1000000, 1000000).map(n => ujson.Num(n.toDouble)),
    Gen.const(ujson.Arr(ujson.Obj("deep" -> ujson.True))),
    Gen.const(ujson.Obj("message_id" -> "shadow", "chat" -> 1))
  )

  private def withUnknownFields(json: ujson.Value, name: String, value: ujson.Value): ujson.Value =
    json match
      case obj: ujson.Obj =>
        val grown = ujson.Obj.from(obj.value.map((k, v) => k -> withUnknownFields(v, name, value)))
        grown(name) = ujson.copy(value)
        grown
      case arr: ujson.Arr => ujson.Arr.from(arr.value.map(withUnknownFields(_, name, value)))
      case other          => other

  property("a field the codecs do not know, added at every level of every fixture, changes nothing"):
    forAll(Gen.oneOf(BotApiFixtures.updateNames), Gen.identifier.map("zz_" + _), unknownValue): (name, field, value) =>
      val original = BotApiFixtures.update(name)
      val expected = TelegramModels.decodeUpdate(original)
      // An unknown kind is named after its field, and the added field may sort first: compare the kinds apart.
      val grown = TelegramModels.decodeUpdate(withUnknownFields(original, field, value))
      (expected, grown) match
        case (Some(Update(a, UpdateBody.Unknown(_))), Some(Update(b, UpdateBody.Unknown(_)))) => assertEquals(a, b)
        case _ => assertEquals(grown, expected)
