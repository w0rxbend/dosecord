package dosegram.core.application.mood

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** "My mood stickers" (supervisor decision of 2026-11-28): one personal sticker set per account, the blob of each of
  * the five valence steps.
  */
object MoodStickers:

  /** The emoji each step's sticker answers to, from very unpleasant (1) to very pleasant (5). */
  val Emojis: List[String] = List("😞", "🙁", "😐", "🙂", "😄")

  /** The key of the account's set. It is derived from the account id, so no row keeps it and the account deletion finds
    * it; the digest says nothing of the account to whoever sees the set's link, and a second request names the same
    * set, which it updates.
    */
  def setKey(accountId: UUID): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(s"$KeyContext:$accountId".getBytes(UTF_8))
    s"mood_${HexFormat.of().formatHex(digest, 0, KeyBytes)}"

  private val KeyContext = "dosegram.mood-stickers.v1"
  private val KeyBytes = 8
