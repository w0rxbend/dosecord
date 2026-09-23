package dosecord.adapter.discord

import dosecord.contracts.ChatError

import java.time.Duration

/** Transport failures into the closed `ChatError` hierarchy (DESIGN.md section 4.5): Discord 50007 (Cannot send
  * messages to this user — DM closed or bot blocked) is `Unreachable` with `channelFatal = true` so the dispatcher's
  * channel-dead/fallback machinery engages; 429 carries the vendor's Retry-After; an unknown message (10008) on
  * edit/delete is a non-fatal `Permanent`; 5xx is `Retryable`; any other 4xx is non-fatal `Permanent`.
  */
object DiscordErrorMapper:

  def guard[A](op: => A): A =
    try op
    catch case e: DiscordApiException => throw map(e)

  def map(e: DiscordApiException): ChatError =
    e.code match
      case 50007               => ChatError.Unreachable(e.meaning)
      case 429                 => ChatError.RateLimited(Duration.ofSeconds(e.retryAfterSeconds.getOrElse(1L)))
      case 10008               => ChatError.Permanent(e.meaning, channelFatal = false)
      case code if code >= 500 => ChatError.Retryable(e.meaning)
      case _                   => ChatError.Permanent(e.meaning, channelFatal = false)
