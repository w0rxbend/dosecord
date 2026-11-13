package dosegram.core.chat

import java.util.UUID

/** The key-free peek agrees with the codec on every clear-text field and rejects what is not a token. */
class CallbackPeekSuite extends munit.FunSuite:

  private val codec = CallbackCodec(CallbackKeys(CallbackKey(3, Array.fill(32)(7.toByte)), None))
  private val subject = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

  test("peek reads the action, subject, value and mode the codec encoded, without a key"):
    val action = ActionRegistry.byName("dose.snooze").get
    val direct = codec.encode(CallbackMode.Direct, action, subject, CallbackCodec.MaxValue)
    val slot = codec.encode(CallbackMode.Slot, action, subject, 30)
    assertEquals(
      CallbackPeek.decode(direct.wire),
      Some(CallbackPeek.Peeked(action.id, subject, CallbackCodec.MaxValue, slot = false))
    )
    assertEquals(CallbackPeek.decode(slot.wire), Some(CallbackPeek.Peeked(action.id, subject, 30, slot = true)))

  test("peek does not verify the MAC: a tampered token still peeks"):
    val wire = codec.encode(CallbackMode.Direct, ActionRegistry.byName("dose.taken").get, subject, 0).wire
    // The second-to-last character lies wholly inside the MAC; the last one carries base64 padding bits.
    val macIndex = wire.length - 2
    val tampered = wire.updated(macIndex, if wire(macIndex) == 'A' then 'B' else 'A')
    assertEquals(codec.decode(tampered), Left(CallbackError.BadMac))
    assertEquals(CallbackPeek.decode(tampered).map(_.subject), Some(subject))

  test("a string that is not a callback token peeks as None"):
    assertEquals(CallbackPeek.decode("hello"), None, "no dc: prefix")
    assertEquals(CallbackPeek.decode("dc:AAAA"), None, "wrong raw length")
    assertEquals(CallbackPeek.decode("dc:not base64!"), None, "not base64url")
