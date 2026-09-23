package dosecord.core.chat

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import dosecord.contracts.Visibility

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class CallbackCodecSuite extends munit.ScalaCheckSuite:

  private val urlEncoder = Base64.getUrlEncoder.withoutPadding()
  private val urlDecoder = Base64.getUrlDecoder

  private val genKeyBytes: Gen[Array[Byte]] =
    Gen.containerOfN[Array, Byte](32, Gen.choose(0, 255).map(_.toByte))

  private val genKey: Gen[CallbackKey] =
    for
      id <- Gen.choose(0, 15)
      bytes <- genKeyBytes
    yield CallbackKey(id, bytes)

  private val genKeys: Gen[CallbackKeys] =
    for
      current <- genKey
      overlap <- Gen.option(
        for
          id <- Gen.choose(0, 15).suchThat(_ != current.id)
          bytes <- genKeyBytes
        yield CallbackKey(id, bytes)
      )
    yield CallbackKeys(current, overlap)

  private val genDualKeys: Gen[(CallbackKey, CallbackKey)] =
    for
      a <- genKey
      b <- genKey.suchThat(_.id != a.id)
    yield (a, b)

  private val genPayload: Gen[(CallbackMode, ActionEntry, UUID, Long)] =
    for
      mode <- Gen.oneOf(CallbackMode.values.toList)
      action <- Gen.oneOf(ActionRegistry.entries)
      subject <- Gen.uuid
      value <- Gen.choose(0L, CallbackCodec.MaxValue)
    yield (mode, action, subject, value)

  private def codec(keys: CallbackKeys): CallbackCodec = CallbackCodec(keys)

  private def encodeWith(c: CallbackCodec, p: (CallbackMode, ActionEntry, UUID, Long)): CallbackToken =
    c.encode(p._1, p._2, p._3, p._4)

  private def rawOf(token: CallbackToken): Array[Byte] = urlDecoder.decode(token.wire.substring(3))

  private def wireOf(raw: Array[Byte]): String = "dc:" + urlEncoder.encodeToString(raw)

  private def expected(p: (CallbackMode, ActionEntry, UUID, Long)): Either[CallbackError, CallbackPayload] =
    Right(CallbackPayload(p._1, p._2, p._3, p._4))

  private def macOf(key: CallbackKey, data: Array[Byte]): Array[Byte] =
    val m = Mac.getInstance("HmacSHA256")
    m.init(SecretKeySpec(key.bytes, "HmacSHA256"))
    m.doFinal(data).take(CallbackCodec.MacLength)

  private def signRaw(key: CallbackKey, raw: Array[Byte]): Array[Byte] =
    System.arraycopy(
      macOf(key, raw.slice(0, CallbackCodec.MacOffset)),
      0,
      raw,
      CallbackCodec.MacOffset,
      CallbackCodec.MacLength
    )
    raw

  property("every valid token matches ^dc:[A-Za-z0-9_-]{46}$"):
    forAll(genKeys, genPayload) { (keys, p) =>
      val token = encodeWith(codec(keys), p)
      CallbackCodec.WirePattern.matches(token.wire) &&
      token.wire.startsWith("dc:") &&
      token.wire.length == CallbackCodec.WireLength &&
      rawOf(token).length == CallbackCodec.RawLength
    }

  property("every valid token fits Telegram's 64 bytes and Discord's 100 chars"):
    forAll(genKeys, genPayload) { (keys, p) =>
      val wire = encodeWith(codec(keys), p).wire
      wire.getBytes(StandardCharsets.UTF_8).length <= 64 && wire.length <= 100
    }

  property("tokens round-trip through encode/decode"):
    forAll(genKeys, genPayload) { (keys, p) =>
      codec(keys).decode(encodeWith(codec(keys), p).wire) == expected(p)
    }

  property("any single-bit tamper is rejected under both active key ids"):
    forAll(genDualKeys, genPayload, Gen.choose(0, CallbackCodec.RawLength * 8 - 1)) { case ((a, b), p, bit) =>
      val dual = codec(CallbackKeys(a, Some(b)))
      def rejectedAfterFlip(token: CallbackToken): Boolean =
        val raw = rawOf(token)
        raw(bit / 8) = (raw(bit / 8) ^ (1 << (bit % 8))).toByte
        dual.decode(wireOf(raw)).isLeft
      // Token signed with the current key and token signed with the overlap key.
      rejectedAfterFlip(encodeWith(dual, p)) &&
      rejectedAfterFlip(encodeWith(codec(CallbackKeys(b, None)), p))
    }

  property("tokens signed with the previous key verify during overlap and fail after retirement"):
    forAll(genDualKeys, genPayload) { case ((oldKey, newKey), p) =>
      val token = encodeWith(codec(CallbackKeys(oldKey, None)), p)
      codec(CallbackKeys(newKey, Some(oldKey))).decode(token.wire) == expected(p) &&
      codec(CallbackKeys(newKey, None)).decode(token.wire).isLeft
    }

  property("arbitrary strings never decode"):
    forAll(genKeys, Gen.alphaNumStr) { (keys, s) =>
      codec(keys).decode(s).isLeft
    }

  test("34-byte layout matches DESIGN.md section 4.4"):
    val key = CallbackKey(1, Array.tabulate(32)(_.toByte))
    val c = codec(CallbackKeys(key, None))
    val subject = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
    val action = ActionRegistry.byName("dose.snooze").get
    val token = c.encode(CallbackMode.Direct, action, subject, 30)
    assertEquals(token.wire.length, 49)
    val raw = rawOf(token)
    assertEquals(raw.length, 34)
    assertEquals((raw(0) & 0xf0) >>> 4, 1, clue = "version nibble")
    assertEquals(raw(0) & 0x0f, 1, clue = "key id nibble")
    assertEquals(raw(1) & 0xff, 0, clue = "mode byte")
    assertEquals(((raw(2) & 0xff) << 8) | (raw(3) & 0xff), action.id, clue = "action UInt16")
    assertEquals(
      raw.slice(4, 20).map(b => f"${b & 0xff}%02x").mkString,
      "00112233445566778899aabbccddeeff",
      clue = "subject UUID"
    )
    assertEquals(raw.slice(20, 26).toList, List[Byte](0, 0, 0, 0, 0, 30), clue = "6-byte value, zero-padded")
    assertEquals(raw.slice(26, 34).toList, macOf(key, raw.slice(0, 26)).toList, clue = "8-byte HMAC-SHA256 tag")
    assertEquals(c.decode(token.wire), Right(CallbackPayload(CallbackMode.Direct, action, subject, 30)))

  test("encode signs with the current key id"):
    val a = CallbackKey(3, Array.tabulate(32)(i => (i + 1).toByte))
    val b = CallbackKey(7, Array.tabulate(32)(i => (i + 2).toByte))
    val raw = rawOf(
      encodeWith(
        codec(CallbackKeys(a, Some(b))),
        (CallbackMode.Slot, ActionRegistry.entries.head, UUID.randomUUID(), 0L)
      )
    )
    assertEquals(raw(0) & 0x0f, 3)

  test("malformed wires are rejected"):
    val c = codec(CallbackKeys(CallbackKey(0, Array.tabulate(32)(_.toByte)), None))
    val good = c.encode(CallbackMode.Direct, ActionRegistry.entries.head, UUID.randomUUID(), 0L).wire
    assertEquals(c.decode(good.substring(2)), Left(CallbackError.Malformed))
    assertEquals(c.decode("xx:" + good.substring(3)), Left(CallbackError.Malformed))
    assertEquals(c.decode(good.dropRight(1)), Left(CallbackError.Malformed))
    assertEquals(c.decode(good + "A"), Left(CallbackError.Malformed))
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val altLast = alphabet(alphabet.indexOf(good.last) ^ 1)
    assertEquals(
      c.decode(good.dropRight(1) + altLast.toString),
      Left(CallbackError.Malformed),
      clue = "non-canonical pad bits"
    )

  test("tamper outcomes are distinguished"):
    val key0 = CallbackKey(0, Array.tabulate(32)(i => (i + 3).toByte))
    val key1 = CallbackKey(1, Array.tabulate(32)(i => (i + 4).toByte))
    val c = codec(CallbackKeys(key0, Some(key1)))
    val token = c.encode(CallbackMode.Direct, ActionRegistry.entries.head, UUID.randomUUID(), 0L)
    val raw = rawOf(token)
    val payloadFlip = raw.clone()
    payloadFlip(10) = (payloadFlip(10) ^ 1).toByte
    assertEquals(c.decode(wireOf(payloadFlip)), Left(CallbackError.BadMac))
    val macFlip = raw.clone()
    macFlip(26) = (macFlip(26) ^ 1).toByte
    assertEquals(c.decode(wireOf(macFlip)), Left(CallbackError.BadMac))
    val unknownKey = raw.clone()
    unknownKey(0) = ((1 << 4) | 5).toByte
    assertEquals(c.decode(wireOf(unknownKey)), Left(CallbackError.UnknownKeyId(5)))
    val badVersion = raw.clone()
    badVersion(0) = ((2 << 4) | 0).toByte
    assertEquals(c.decode(wireOf(badVersion)), Left(CallbackError.UnsupportedVersion(2)))

  test("signed payloads with an unknown mode or action are rejected"):
    val key = CallbackKey(0, Array.tabulate(32)(i => (i + 5).toByte))
    val c = codec(CallbackKeys(key, None))
    val raw = rawOf(c.encode(CallbackMode.Direct, ActionRegistry.entries.head, UUID.randomUUID(), 0L))
    val badMode = raw.clone()
    badMode(1) = 7
    assertEquals(c.decode(wireOf(signRaw(key, badMode))), Left(CallbackError.UnknownMode(7)))
    val badAction = raw.clone()
    badAction(2) = 0x7f.toByte
    badAction(3) = 0x7f.toByte
    assertEquals(c.decode(wireOf(signRaw(key, badAction))), Left(CallbackError.UnknownAction(0x7f7f)))

  test("registry matches the DESIGN.md section 4.4 action list"):
    assertEquals(
      ActionRegistry.entries.map(e => e.id -> e.name).toMap,
      Map(
        1 -> "dose.taken",
        2 -> "dose.snooze",
        3 -> "dose.skip",
        4 -> "dose.undo",
        5 -> "dose.note",
        6 -> "dose.correct",
        7 -> "dose.keep_missed",
        20 -> "menu.open",
        21 -> "menu.open_form",
        30 -> "wizard.step",
        31 -> "wizard.text_step",
        32 -> "wizard.confirm",
        40 -> "link.confirm"
      )
    )

  test("registry flags drive the acknowledgement policy"):
    assert(ActionRegistry.byName("dose.note").exists(_.opensForm))
    assert(ActionRegistry.byName("wizard.text_step").exists(_.opensForm))
    // dose.correct is a two-choice button row per M1.10/M2.2, not a modal.
    assert(!ActionRegistry.byName("dose.correct").exists(_.opensForm))
    assert(ActionRegistry.entries.filter(_.name.startsWith("wizard.")).forall(_.requiresSession))
    assert(ActionRegistry.entries.filter(_.name.startsWith("dose.")).forall(!_.requiresSession))
    assertEquals(ActionRegistry.byName("link.confirm").map(_.visibility), Some(Visibility.Ephemeral))
end CallbackCodecSuite
