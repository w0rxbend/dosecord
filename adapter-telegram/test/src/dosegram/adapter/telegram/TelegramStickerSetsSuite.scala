package dosegram.adapter.telegram

import dosegram.contracts.ChatRef
import dosegram.core.ports.StickerImage
import dosegram.core.ports.StickerSetFailure

import java.nio.charset.StandardCharsets.UTF_8

/** "My mood stickers" on the fake wire (supervisor decision of 2026-11-28): the set is created once under Telegram's
  * naming rule, a second publish updates it in place, and deleting it is idempotent. The wire answers with the shapes of
  * the recorded-shape fixtures.
  */
class TelegramStickerSetsSuite extends munit.FunSuite:

  private val chat = ChatRef("telegram", "7001")
  private val setKey = "mood_0123456789abcdef"
  private val name = "mood_0123456789abcdef_by_dosegram_dev_bot"
  private val five = (1 to 5).toList.map(n => StickerImage(s"mood.sticker.$n", s"e$n"))

  private final class Rig(username: Option[String] = Some("dosegram_dev_bot")):
    val rig: TelegramRig = TelegramRig()
    five.foreach(sticker => rig.storeAsset(sticker.assetKey, s"png:${sticker.assetKey}".getBytes(UTF_8)))
    val sets: TelegramStickerSets = TelegramStickerSets(rig.api, key => rig.loadAsset(key), () => username, _ => ())
    def calls: List[String] = rig.wire.calls.map(_.method)

  test("the first publish creates the set under <key>_by_<bot>, owned by the chat's user, and returns its link"):
    val rig = Rig()
    assertEquals(rig.sets.publish(chat, setKey, "Mood blobs", five), Right(s"https://t.me/addstickers/$name"))
    assertEquals(rig.calls, List("getStickerSet", "createNewStickerSet"))
    val create = rig.rig.wire.calls("createNewStickerSet").head
    val params = create.request.params
    assertEquals(params("user_id").num.toLong, 7001L)
    assertEquals((params("name").str, params("title").str, params("sticker_type").str), (name, "Mood blobs", "regular"))
    val inputs = params("stickers").arr.toList
    assertEquals(inputs.map(_("sticker").str), (0 until 5).toList.map(i => s"attach://sticker$i"))
    assertEquals(inputs.map(_("format").str).distinct, List("static"))
    assertEquals(inputs.map(_("emoji_list").arr.toList.map(_.str)), five.map(sticker => List(sticker.emoji)))
    val files = create.request.files
    assertEquals(files.map(_.field), (0 until 5).toList.map(i => s"sticker$i"))
    assertEquals(files.map(_.mimeType).distinct, List("image/png"))
    assertEquals(String(files.head.bytes, UTF_8), "png:mood.sticker.1", "the bytes come from the asset store")
    assertEquals(rig.rig.wire.stickerSet(name).map(set => (set.owner, set.stickers.size)), Some((7001L, 5)))

  test("a second publish updates the one set in place: each sticker replaced, the title set when it changed"):
    val rig = Rig()
    rig.sets.publish(chat, setKey, "Mood blobs", five)
    val before = rig.rig.wire.stickerSet(name).get.stickers
    val calls = rig.calls.size
    assertEquals(rig.sets.publish(chat, setKey, "Блоби настрою", five), Right(s"https://t.me/addstickers/$name"))
    assertEquals(
      rig.calls.drop(calls),
      "getStickerSet" :: List.fill(5)("replaceStickerInSet") ++ List("setStickerSetTitle")
    )
    val replaced = rig.rig.wire.calls("replaceStickerInSet").map(_.request.params("old_sticker").str)
    assertEquals(replaced, before, "every old sticker in its own place")
    val after = rig.rig.wire.stickerSet(name).get
    assertEquals((after.title, after.stickers.size), ("Блоби настрою", 5))
    assert(after.stickers.intersect(before).isEmpty)
    // The same title again: no title call.
    val again = rig.calls.size
    rig.sets.publish(chat, setKey, "Блоби настрою", five)
    assert(!rig.calls.drop(again).contains("setStickerSetTitle"))

  test("a set with fewer or more stickers than asked is filled up or trimmed"):
    val rig = Rig()
    rig.sets.publish(chat, setKey, "Mood blobs", five.take(3))
    rig.sets.publish(chat, setKey, "Mood blobs", five)
    assertEquals(rig.calls.count(_ == "addStickerToSet"), 2)
    assertEquals(rig.rig.wire.stickerSet(name).get.stickers.size, 5)
    rig.sets.publish(chat, setKey, "Mood blobs", five.take(2))
    assertEquals(rig.calls.count(_ == "deleteStickerFromSet"), 3)
    assertEquals(rig.rig.wire.stickerSet(name).get.stickers.size, 2)

  test("an existing set is read in the shape of the getStickerSet fixture"):
    val rig = Rig()
    rig.rig.wire.replyNext("getStickerSet", 200, BotApiFixtures.response("get-sticker-set"))
    List.fill(2)(rig.rig.wire.replyNext("replaceStickerInSet", 200, BotApiFixtures.response("true")))
    assertEquals(rig.sets.publish(chat, setKey, "Mood blobs", five.take(2)), Right(s"https://t.me/addstickers/$name"))
    assertEquals(
      rig.rig.wire.calls("replaceStickerInSet").map(_.request.params("old_sticker").str),
      List("CAACAgIAAxkBAAEsticker-1", "CAACAgIAAxkBAAEsticker-2")
    )

  test("removing a set deletes it, and a set that is gone is already removed"):
    val rig = Rig()
    rig.sets.publish(chat, setKey, "Mood blobs", five)
    assertEquals(rig.sets.remove("telegram", setKey), Right(()))
    assertEquals(rig.rig.wire.stickerSet(name), None)
    assertEquals(rig.sets.remove("telegram", setKey), Right(()))
    assertEquals(rig.calls.count(_ == "deleteStickerSet"), 2)

  test("failures: no username, a name over Telegram's rules, bytes that are gone, flood control, a refusal"):
    assertEquals(Rig(username = None).sets.publish(chat, setKey, "t", five), Left(StickerSetFailure.Unsupported))
    val long = Rig(username = Some("a" * 40 + "_bot"))
    assertEquals(long.sets.publish(chat, setKey, "t", five), Left(StickerSetFailure.Refused))
    assertEquals(long.calls, Nil, "nothing is sent for a name Telegram would refuse")
    val gone = Rig()
    assertEquals(gone.sets.publish(chat, setKey, "t", List(StickerImage("missing", "x"))), Left(StickerSetFailure.Retryable))
    val flood = Rig()
    flood.rig.wire.replyNext("getStickerSet", 429, BotApiFixtures.error("flood-429").body)
    assertEquals(flood.sets.publish(chat, setKey, "t", five), Left(StickerSetFailure.Retryable))
    val refused = Rig()
    refused.rig.wire.replyNext("createNewStickerSet", 400, BotApiFixtures.error("sticker-set-occupied-400").body)
    assertEquals(refused.sets.publish(chat, setKey, "t", five), Left(StickerSetFailure.Refused))
    assertEquals(Rig().sets.publish(ChatRef("telegram", "not-a-user"), setKey, "t", five), Left(StickerSetFailure.Refused))

  test("Telegram's naming rule: a letter first, single underscores, at most 64 characters"):
    assert(TelegramStickerSets.isValidName(name))
    List("1mood_by_bot", "mood__x_by_bot", "_mood_by_bot", "mood-x_by_bot", "m" * 60 + "_by_bot").foreach: bad =>
      assert(!TelegramStickerSets.isValidName(bad), bad)
