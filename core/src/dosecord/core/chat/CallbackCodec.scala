package dosecord.core.chat

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.matching.Regex

enum CallbackMode(val code: Int):
  case Direct extends CallbackMode(0)
  case Slot extends CallbackMode(1)

object CallbackMode:
  def fromCode(code: Int): Option[CallbackMode] = values.find(_.code == code)

enum CallbackError:
  case Malformed
  case UnsupportedVersion(version: Int)
  case UnknownKeyId(keyId: Int)
  case BadMac
  case UnknownMode(code: Int)
  case UnknownAction(id: Int)

final case class CallbackKey(id: Int, bytes: Array[Byte]):
  require(id >= 0 && id <= 15, "key id is a 4-bit nibble")
  require(bytes.length >= 16, "HMAC key must be at least 128 bits")

// Two active key ids during rotation: `current` signs, both verify (ADR-006).
final case class CallbackKeys(current: CallbackKey, overlap: Option[CallbackKey]):
  require(overlap.forall(_.id != current.id), "overlap key must use a different key id")
  def byId(id: Int): Option[CallbackKey] =
    if current.id == id then Some(current) else overlap.filter(_.id == id)

final case class CallbackToken private[chat] (wire: String):
  override def toString: String = wire

final case class CallbackPayload(
    mode: CallbackMode,
    action: ActionEntry,
    subject: UUID,
    value: Long
):
  require(value >= 0 && value <= CallbackCodec.MaxValue, "value must fit in 6 unsigned bytes")

object CallbackCodec:
  val Version: Int = 1
  val RawLength: Int = 34
  val MacOffset: Int = 26
  val MacLength: Int = 8
  val MaxValue: Long = (1L << 48) - 1
  val WireLength: Int = 49
  val WirePattern: Regex = "^dc:[A-Za-z0-9_-]{46}$".r

final class CallbackCodec(keys: CallbackKeys):
  import CallbackCodec.*

  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  def encode(mode: CallbackMode, action: ActionEntry, subject: UUID, value: Long): CallbackToken =
    require(value >= 0 && value <= MaxValue, "value must fit in 6 unsigned bytes")
    val raw = new Array[Byte](RawLength)
    raw(0) = ((Version << 4) | keys.current.id).toByte
    raw(1) = mode.code.toByte
    raw(2) = (action.id >>> 8).toByte
    raw(3) = action.id.toByte
    writeUuid(subject, raw, 4)
    var remaining = value
    var i = 25
    while i >= 20 do
      raw(i) = (remaining & 0xff).toByte
      remaining >>>= 8
      i -= 1
    System.arraycopy(mac(keys.current, raw), 0, raw, MacOffset, MacLength)
    CallbackToken("dc:" + encoder.encodeToString(raw))

  def decode(token: String): Either[CallbackError, CallbackPayload] =
    if token.length != WireLength || !WirePattern.matches(token) then Left(CallbackError.Malformed)
    else
      val body = token.substring(3)
      val raw = decoder.decode(body)
      if raw.length != RawLength || encoder.encodeToString(raw) != body then Left(CallbackError.Malformed)
      else
        val version = (raw(0) & 0xf0) >>> 4
        val keyId = raw(0) & 0x0f
        if version != Version then Left(CallbackError.UnsupportedVersion(version))
        else
          keys.byId(keyId) match
            case None      => Left(CallbackError.UnknownKeyId(keyId))
            case Some(key) =>
              if !MessageDigest.isEqual(mac(key, raw), raw.slice(MacOffset, RawLength)) then Left(CallbackError.BadMac)
              else parse(raw)

  private def parse(raw: Array[Byte]): Either[CallbackError, CallbackPayload] =
    val modeCode = raw(1) & 0xff
    val actionId = ((raw(2) & 0xff) << 8) | (raw(3) & 0xff)
    val subject = readUuid(raw, 4)
    var value = 0L
    var i = 20
    while i <= 25 do
      value = (value << 8) | (raw(i) & 0xff)
      i += 1
    CallbackMode.fromCode(modeCode) match
      case None       => Left(CallbackError.UnknownMode(modeCode))
      case Some(mode) =>
        ActionRegistry.byId(actionId) match
          case None         => Left(CallbackError.UnknownAction(actionId))
          case Some(action) => Right(CallbackPayload(mode, action, subject, value))

  private def mac(key: CallbackKey, raw: Array[Byte]): Array[Byte] =
    val m = Mac.getInstance("HmacSHA256")
    m.init(SecretKeySpec(key.bytes, "HmacSHA256"))
    m.update(raw, 0, MacOffset)
    m.doFinal().take(MacLength)

  private def writeUuid(id: UUID, raw: Array[Byte], offset: Int): Unit =
    writeLong(id.getMostSignificantBits, raw, offset)
    writeLong(id.getLeastSignificantBits, raw, offset + 8)

  private def writeLong(v: Long, raw: Array[Byte], offset: Int): Unit =
    var i = 0
    while i < 8 do
      raw(offset + i) = (v >>> (56 - 8 * i)).toByte
      i += 1

  private def readUuid(raw: Array[Byte], offset: Int): UUID =
    UUID(readLong(raw, offset), readLong(raw, offset + 8))

  private def readLong(raw: Array[Byte], offset: Int): Long =
    var v = 0L
    var i = 0
    while i < 8 do
      v = (v << 8) | (raw(offset + i) & 0xff)
      i += 1
    v
end CallbackCodec
