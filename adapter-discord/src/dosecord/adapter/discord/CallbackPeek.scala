package dosecord.adapter.discord

import java.util.Base64
import java.util.UUID

/** Reads the clear-text fields of a `dc:` callback token without verifying the MAC (DESIGN.md section 4.4 layout: the
  * MAC covers only bytes 0..25, so the action id, subject, value and mode are readable by anyone). The mediator
  * re-verifies the MAC before anything reaches the core — this peek exists solely so the adapter's ack watchdog can
  * pick the declared ack type ([[dosecord.core.chat.ActionRegistry]] `opensForm`, ADR-013) without holding keys. A
  * token that does not parse is treated as an unknown action: the watchdog defers (safe default) and the mediator's
  * verification rejects it with the tamper toast.
  */
object CallbackPeek:

  final case class Peeked(actionId: Int, subject: UUID, value: Long, slot: Boolean)

  private val RawLength = 34

  def decode(token: String): Option[Peeked] =
    if !token.startsWith("dc:") then None
    else
      try
        val raw = Base64.getUrlDecoder.decode(token.substring(3))
        if raw.length != RawLength then None
        else
          val actionId = ((raw(2) & 0xff) << 8) | (raw(3) & 0xff)
          val subject = UUID(readLong(raw, 4), readLong(raw, 12))
          var value = 0L
          var i = 20
          while i <= 25 do
            value = (value << 8) | (raw(i) & 0xff)
            i += 1
          Some(Peeked(actionId, subject, value, slot = (raw(1) & 0xff) == 1))
      catch case _: IllegalArgumentException => None

  private def readLong(raw: Array[Byte], offset: Int): Long =
    var v = 0L
    var i = 0
    while i < 8 do
      v = (v << 8) | (raw(offset + i) & 0xffL)
      i += 1
    v
