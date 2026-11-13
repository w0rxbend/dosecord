package dosegram.api.auth

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** ARCHITECTURE API-02 and API-03, research brief 02 section 11.3. The suite signs its own launch data with the
  * published algorithm, written out here a second time so the verifier is not its own oracle; the first vector was
  * computed outside the JVM.
  */
final class InitDataSuite extends munit.FunSuite:
  import InitDataSuite.*

  // Computed with Python's hmac module from the published algorithm, for the token below.
  private val KnownToken = "7000000001:AAFakeTokenForTestsOnly_0123456789abcde"
  private val KnownRaw =
    "auth_date=1794571200&query_id=AAHdF6IQAAAAAN0XohDhrOrc&user=%7B%22id%22%3A424242%2C%22first_name%22%3A%22Test" +
      "%22%2C%22language_code%22%3A%22en%22%2C%22allows_write_to_pm%22%3Atrue%7D" +
      "&hash=3c5390a29e0419288647388d7535bf5e553c26c455fe7b36c01d231d3ba4dfef"
  private val KnownAuthDate = Instant.ofEpochSecond(1794571200L)

  private def hmacVerifier(token: String = KnownToken): InitDataVerifier =
    TelegramInitDataVerifier(ed25519 = None, hmacSecret = Some(InitDataHmac.secretKeyFor(token)))

  test("the secret key is HMAC-SHA-256 of the bot token keyed with the WebAppData marker"):
    assertEquals(
      HexFormat.of().formatHex(InitDataHmac.secretKeyFor(KnownToken)),
      "8b5aaa4df6230b12fc89d0e9b6f8cdd1ee2a4d5169fd325b5a16d5c3aaa80162"
    )

  test("HMAC: the known vector verifies and yields the Telegram user id and the auth date"):
    val verified = hmacVerifier().verify(KnownRaw, KnownAuthDate.plusSeconds(10))
    assertEquals(verified.map(_.telegramUserId), Right(424242L))
    assertEquals(verified.map(_.authDate), Right(KnownAuthDate))

  test("HMAC: a tampered field is refused"):
    val tampered = KnownRaw.replace("424242", "424243")
    assertEquals(hmacVerifier().verify(tampered, KnownAuthDate), Left(InitDataRejection.BadSignature))

  test("HMAC: a hash made with another bot token is refused"):
    val other = hmacVerifier("7000000002:AAAnotherFakeTokenForTestsOnly_0123456789")
    assertEquals(other.verify(KnownRaw, KnownAuthDate), Left(InitDataRejection.BadSignature))

  test("HMAC: a hash that is not 64 hex digits is refused without an exception"):
    val raw = KnownRaw.replaceAll("hash=.*$", "hash=zz")
    assertEquals(hmacVerifier().verify(raw, KnownAuthDate), Left(InitDataRejection.BadSignature))

  test("HMAC: launch data without a hash is refused"):
    val raw = KnownRaw.replaceAll("&hash=.*$", "")
    assertEquals(hmacVerifier().verify(raw, KnownAuthDate), Left(InitDataRejection.MissingSignature))

  test("auth_date older than 300 seconds is stale; exactly 300 seconds is accepted"):
    assert(hmacVerifier().verify(KnownRaw, KnownAuthDate.plusSeconds(300)).isRight)
    assertEquals(hmacVerifier().verify(KnownRaw, KnownAuthDate.plusSeconds(301)), Left(InitDataRejection.Stale))

  test("auth_date more than 60 seconds ahead is refused; exactly 60 seconds is accepted"):
    assert(hmacVerifier().verify(KnownRaw, KnownAuthDate.minusSeconds(60)).isRight)
    assertEquals(hmacVerifier().verify(KnownRaw, KnownAuthDate.minusSeconds(61)), Left(InitDataRejection.FromFuture))

  test("a duplicate key is refused before any signature is checked"):
    val raw = KnownRaw + "&auth_date=1794571200"
    assertEquals(hmacVerifier().verify(raw, KnownAuthDate), Left(InitDataRejection.DuplicateKey))

  test("launch data over 4096 bytes is refused"):
    val raw = KnownRaw + "&padding=" + "a" * 4096
    assertEquals(hmacVerifier().verify(raw, KnownAuthDate), Left(InitDataRejection.TooLarge))

  test("a broken percent escape and a pair without '=' are malformed"):
    assertEquals(hmacVerifier().verify("auth_date=1&user=%zz&hash=00", KnownAuthDate), Left(InitDataRejection.Malformed))
    assertEquals(hmacVerifier().verify("auth_date&hash=00", KnownAuthDate), Left(InitDataRejection.Malformed))
    assertEquals(hmacVerifier().verify("", KnownAuthDate), Left(InitDataRejection.Malformed))

  test("a correctly signed value without a user, with a bot user or with a non-numeric id is refused"):
    val now = Instant.ofEpochSecond(1794571200L)
    def signed(user: Option[String]): String =
      signHmac(KnownToken, Map("auth_date" -> "1794571200") ++ user.map("user" -> _))
    assertEquals(hmacVerifier().verify(signed(None), now), Left(InitDataRejection.MissingUser))
    assertEquals(
      hmacVerifier().verify(signed(Some("""{"id":7,"is_bot":true}""")), now),
      Left(InitDataRejection.MissingUser)
    )
    assertEquals(hmacVerifier().verify(signed(Some("""{"id":"7"}""")), now), Left(InitDataRejection.MissingUser))
    assertEquals(hmacVerifier().verify(signed(Some("not json")), now), Left(InitDataRejection.MissingUser))

  test("a correctly signed value without auth_date is malformed"):
    val raw = signHmac(KnownToken, Map("user" -> """{"id":7}"""))
    assertEquals(hmacVerifier().verify(raw, KnownAuthDate), Left(InitDataRejection.Malformed))

  // ---------- Ed25519 (API-02) ----------

  private val BotId = 7000000001L
  private val keys: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  private val now = Instant.ofEpochSecond(1794571200L)
  private val fields = Map("auth_date" -> "1794571200", "user" -> """{"id":424242,"first_name":"Test"}""")

  private def ed25519Verifier(botId: Long = BotId, hmacToken: Option[String] = None): InitDataVerifier =
    TelegramInitDataVerifier(
      ed25519 = Some(Ed25519InitDataCheck(botId, rawPublicKey(keys))),
      hmacSecret = hmacToken.map(InitDataHmac.secretKeyFor)
    )

  test("Ed25519: a value signed over '<bot id>:WebAppData' and the sorted fields verifies"):
    val raw = signEd25519(keys, BotId, fields)
    assertEquals(ed25519Verifier().verify(raw, now).map(_.telegramUserId), Right(424242L))

  test("Ed25519: a tampered field is refused"):
    val raw = signEd25519(keys, BotId, fields).replace("424242", "424243")
    assertEquals(ed25519Verifier().verify(raw, now), Left(InitDataRejection.BadSignature))

  test("Ed25519: a value signed for another bot id is refused"):
    val raw = signEd25519(keys, BotId + 1, fields)
    assertEquals(ed25519Verifier().verify(raw, now), Left(InitDataRejection.BadSignature))

  test("Ed25519: a value signed with another key is refused"):
    val raw = signEd25519(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), BotId, fields)
    assertEquals(ed25519Verifier().verify(raw, now), Left(InitDataRejection.BadSignature))

  test("Ed25519: a signature that is not base64url is refused without an exception"):
    val raw = encode(fields + ("signature" -> "***"))
    assertEquals(ed25519Verifier().verify(raw, now), Left(InitDataRejection.BadSignature))

  test("without a signature the value is refused as a too-old client unless the HMAC fallback is on (API-03)"):
    val hashOnly = signHmac(KnownToken, fields)
    assertEquals(ed25519Verifier().verify(hashOnly, now), Left(InitDataRejection.MissingSignature))
    assert(ed25519Verifier(hmacToken = Some(KnownToken)).verify(hashOnly, now).isRight)

  test("with a signature present the HMAC fallback is not consulted"):
    val forgedSignature = signHmac(KnownToken, fields + ("signature" -> "AAAA"))
    assertEquals(
      ed25519Verifier(hmacToken = Some(KnownToken)).verify(forgedSignature, now),
      Left(InitDataRejection.BadSignature)
    )

  test("the production key constant is the published Telegram key"):
    assertEquals(
      HexFormat.of().formatHex(Ed25519InitDataCheck.TelegramProductionKey),
      "e7bf03a2fa4602af4580703d88dda5bb59f32ed8b02a56c187fe7d34caed242d"
    )

  test("the digest identifies the raw value and never contains it"):
    val verified = hmacVerifier().verify(KnownRaw, KnownAuthDate).toOption.get
    assertEquals(verified.digest.length, 64)
    assert(!KnownRaw.contains(verified.digest))

object InitDataSuite:

  def encode(fields: Map[String, String]): String =
    fields.toList.map((key, value) => s"$key=${URLEncoder.encode(value, UTF_8)}").mkString("&")

  private def checkString(fields: Map[String, String]): String =
    fields.toList.sortBy(_._1).map((key, value) => s"$key=$value").mkString("\n")

  private def hmacSha256(key: Array[Byte], message: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(message.getBytes(UTF_8))

  /** The published HMAC algorithm: every field except `hash`, sorted, joined by line feeds. */
  def signHmac(botToken: String, fields: Map[String, String]): String =
    val secret = hmacSha256("WebAppData".getBytes(UTF_8), botToken)
    val hash = HexFormat.of().formatHex(hmacSha256(secret, checkString(fields)))
    encode(fields + ("hash" -> hash))

  /** The published third-party algorithm: the bot id marker line, then every field except `hash` and `signature`. */
  def signEd25519(keys: KeyPair, botId: Long, fields: Map[String, String]): String =
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(keys.getPrivate)
    signer.update(s"$botId:WebAppData\n${checkString(fields)}".getBytes(UTF_8))
    val signature = Base64.getUrlEncoder.withoutPadding.encodeToString(signer.sign())
    encode(fields + ("signature" -> signature))

  /** The 32 raw key bytes: the tail of the X.509 encoding. */
  def rawPublicKey(keys: KeyPair): Array[Byte] = keys.getPublic.getEncoded.takeRight(32)
