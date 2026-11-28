package dosegram.core.application.mood

import dosegram.core.ports.OutboxOp
import dosegram.core.ports.StickerSets
import dosegram.core.scheduling.StickerSetRemoval

import java.time.Instant
import java.util.UUID

/** "My mood stickers": one set per account, named by a key the account deletion can find again without a row. */
class MoodStickersSuite extends munit.FunSuite:

  test("the set key is the same for an account every time, another account's differs, and every vendor accepts it"):
    val account = UUID.fromString("0193e7a0-0000-7000-8000-000000000001")
    val key = MoodStickers.setKey(account)
    assertEquals(key, MoodStickers.setKey(account))
    assertNotEquals(key, MoodStickers.setKey(UUID.fromString("0193e7a0-0000-7000-8000-000000000002")))
    assert(StickerSets.isValidKey(key), key)
    assert(key.matches("mood_[0-9a-f]{16}"), key)
    assert(!key.contains(account.toString.take(8)), "the key does not show the account id")

  test("one emoji per step, five steps"):
    assertEquals(MoodStickers.Emojis.size, 5)
    assertEquals(MoodStickers.Emojis.distinct.size, 5)

  test("the removal row names the set and nothing else, and reads back"):
    val at = Instant.parse("2026-11-28T10:00:00Z")
    val row = StickerSetRemoval.row(UUID.randomUUID(), "telegram", "mood_0123456789abcdef", at)
    assertEquals((row.kind, row.op, row.vendor, row.accountId, row.chatId), (StickerSetRemoval.Kind, OutboxOp.Send, "telegram", None, None))
    assertEquals(row.payload, """{"set":"mood_0123456789abcdef"}""")
    assertEquals(StickerSetRemoval.setKeyOf(row.payload), Some("mood_0123456789abcdef"))
    assertEquals(StickerSetRemoval.setKeyOf("""{"set":"Robert'); DROP"}"""), None)

  test("a key outside the shape is refused"):
    List("", "1mood", "mood__x", "mood_", "Mood_x", "mood-x").foreach: key =>
      assert(!StickerSets.isValidKey(key), key)
