package dosegram.api.auth

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/** Why a launch value was refused. The session route maps each case to a problem code; none carries the value. */
enum InitDataRejection:
  case TooLarge, Malformed, DuplicateKey, MissingSignature, BadSignature, Stale, FromFuture, MissingUser

/** What a verified launch value proves: who opened the app and when. `digest` is the SHA-256 of the raw value, kept for
  * replay detection in place of the value itself (ARCHITECTURE API-05).
  */
final case class VerifiedInitData(telegramUserId: Long, authDate: Instant, digest: String)

/** The port the session exchange verifies launch data through (ROADMAP G1.12). */
trait InitDataVerifier:
  def verify(raw: String, now: Instant): Either[InitDataRejection, VerifiedInitData]

/** How long after `auth_date` a launch value is accepted, and how far ahead of the server clock it may be (research
  * brief 06 section 4.2, step 5).
  */
final case class FreshnessWindow(maxAge: Duration, maxAhead: Duration)

object FreshnessWindow:
  val Default: FreshnessWindow = FreshnessWindow(Duration.ofSeconds(300), Duration.ofSeconds(60))

/** The HMAC-SHA-256 scheme of research brief 02 section 11.3. */
object InitDataHmac:
  private val Algorithm = "HmacSHA256"
  private val Marker = "WebAppData".getBytes(UTF_8)

  /** `HMAC_SHA256(key = "WebAppData", message = bot token)`. The composition root derives it, so the verifier holds
    * this key and never the token (ARCHITECTURE DEP-05).
    */
  def secretKeyFor(botToken: String): Array[Byte] = mac(Marker, botToken)

  private[auth] def mac(key: Array[Byte], message: String): Array[Byte] =
    val hmac = Mac.getInstance(Algorithm)
    hmac.init(SecretKeySpec(key, Algorithm))
    hmac.doFinal(message.getBytes(UTF_8))

/** The Ed25519 scheme "for third-party use": the signature covers the bot id marker line and the sorted fields. */
final class Ed25519InitDataCheck(botId: Long, rawPublicKey: Array[Byte]):
  require(rawPublicKey.length == 32, "an Ed25519 public key is 32 bytes")

  private val publicKey: PublicKey =
    val encoded = Ed25519InitDataCheck.X509Prefix ++ rawPublicKey
    KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))

  private[auth] def accepts(checkString: String, signature: String): Boolean =
    Try(Base64.getUrlDecoder.decode(signature)).toOption.exists: bytes =>
      val verifier = Signature.getInstance("Ed25519")
      verifier.initVerify(publicKey)
      verifier.update(s"$botId:WebAppData\n$checkString".getBytes(UTF_8))
      try verifier.verify(bytes)
      catch case _: GeneralSecurityException => false

object Ed25519InitDataCheck:
  // SubjectPublicKeyInfo header of an Ed25519 key (RFC 8410); the 32 key bytes follow it.
  private val X509Prefix: Array[Byte] = HexFormat.of().parseHex("302a300506032b6570032100")

  /** Telegram's production key (research brief 02 section 11.3); another key is a test or staging setting. */
  val TelegramProductionKey: Array[Byte] =
    HexFormat.of().parseHex("e7bf03a2fa4602af4580703d88dda5bb59f32ed8b02a56c187fe7d34caed242d")

/** Launch data validation of research brief 06 section 4.2: size limit, duplicate keys, signature, freshness, user. A
  * value with a `signature` is checked with Ed25519 when that check is configured (API-02); otherwise the `hash` is
  * checked with the HMAC key when one is configured (API-03); with neither the value is refused.
  */
final class TelegramInitDataVerifier(
    ed25519: Option[Ed25519InitDataCheck],
    hmacSecret: Option[Array[Byte]],
    window: FreshnessWindow = FreshnessWindow.Default
) extends InitDataVerifier:
  import TelegramInitDataVerifier.*

  override def verify(raw: String, now: Instant): Either[InitDataRejection, VerifiedInitData] =
    for
      fields <- parse(raw)
      _ <- checkSignature(fields)
      authDate <- authDateOf(fields)
      _ <- checkFreshness(authDate, now)
      userId <- userIdOf(fields)
    yield VerifiedInitData(userId, authDate, sha256Hex(raw))

  private def checkSignature(fields: Map[String, String]): Either[InitDataRejection, Unit] =
    val accepted = (fields.get(SignatureKey), ed25519) match
      case (Some(signature), Some(check)) =>
        Some(check.accepts(checkString(fields - HashKey - SignatureKey), signature))
      case _ =>
        for
          hash <- fields.get(HashKey)
          secret <- hmacSecret
        yield hashMatches(secret, checkString(fields - HashKey), hash)
    accepted match
      case None        => Left(InitDataRejection.MissingSignature)
      case Some(false) => Left(InitDataRejection.BadSignature)
      case Some(true)  => Right(())

  private def checkFreshness(authDate: Instant, now: Instant): Either[InitDataRejection, Unit] =
    if authDate.isBefore(now.minus(window.maxAge)) then Left(InitDataRejection.Stale)
    else if authDate.isAfter(now.plus(window.maxAhead)) then Left(InitDataRejection.FromFuture)
    else Right(())

object TelegramInitDataVerifier:
  val MaxBytes = 4096
  private val HashKey = "hash"
  private val SignatureKey = "signature"

  private def parse(raw: String): Either[InitDataRejection, Map[String, String]] =
    if raw.getBytes(UTF_8).length > MaxBytes then Left(InitDataRejection.TooLarge)
    else
      val pairs = raw.split('&').toList.map(decodePair)
      if raw.isEmpty || pairs.contains(None) then Left(InitDataRejection.Malformed)
      else
        val decoded = pairs.flatten
        val fields = decoded.toMap
        if fields.size != decoded.size then Left(InitDataRejection.DuplicateKey) else Right(fields)

  private def decodePair(pair: String): Option[(String, String)] =
    pair.split("=", 2) match
      case Array(key, value) if key.nonEmpty =>
        Try(URLDecoder.decode(key, UTF_8) -> URLDecoder.decode(value, UTF_8)).toOption
      case _ => None

  /** Every given field as `key=value`, sorted by key, joined by line feeds. */
  private def checkString(fields: Map[String, String]): String =
    fields.toList.sortBy(_._1).map((key, value) => s"$key=$value").mkString("\n")

  private def hashMatches(secret: Array[Byte], checkString: String, hexHash: String): Boolean =
    Try(HexFormat.of().parseHex(hexHash)).toOption.exists: claimed =>
      // MessageDigest.isEqual compares in constant time for equal lengths.
      MessageDigest.isEqual(InitDataHmac.mac(secret, checkString), claimed)

  private def authDateOf(fields: Map[String, String]): Either[InitDataRejection, Instant] =
    fields.get("auth_date").flatMap(_.toLongOption).map(Instant.ofEpochSecond).toRight(InitDataRejection.Malformed)

  /** A numeric `id` of a user that is not a bot; every other user field is ignored for identity. */
  private def userIdOf(fields: Map[String, String]): Either[InitDataRejection, Long] =
    val userId =
      for
        json <- fields.get("user")
        user <- Try(ujson.read(json)).toOption.flatMap(_.objOpt)
        if !user.get("is_bot").flatMap(_.boolOpt).contains(true)
        id <- user.get("id").flatMap(_.numOpt)
        if id.isWhole
      yield id.toLong
    userId.toRight(InitDataRejection.MissingUser)

  private def sha256Hex(value: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))
