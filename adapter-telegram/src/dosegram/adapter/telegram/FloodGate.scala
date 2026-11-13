package dosegram.adapter.telegram

import dosegram.contracts.ChatError
import dosegram.core.ports.Clock
import dosegram.core.scheduling.TokenBuckets

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The flood-control gate every outbound call passes (ARCHITECTURE section 7.6). A call that sends, edits or deletes a
  * message takes one token of the chat's bucket and one of the global bucket and waits for them when they are empty
  * (TG-25, TG-26); a callback answer takes one of a separate bucket. After a 429 the chat is paused for the time the
  * API asked for: until then a message call for that chat fails at once with `RateLimited` and nothing goes on the
  * wire, so the retry is never earlier than `retry_after` (TG-28).
  */
final class FloodGate(clock: Clock, limits: FloodGate.Limits, sleep: Duration => Unit):

  import FloodGate.*

  private val messages =
    TokenBuckets(clock, Map(MessageKey -> TokenBuckets.Limits(limits.global, limits.perChat)), sleep)
  private val answers =
    TokenBuckets(clock, Map(AnswerKey -> TokenBuckets.Limits(limits.answers, limits.answers)), sleep)
  private val pausedUntil = ConcurrentHashMap[Long, Instant]()

  /** Blocks until the call fits the budget; throws `RateLimited` with the remaining wait while the chat is paused. */
  def beforeMessageCall(chatId: Long): Unit =
    remainingPause(chatId).foreach(wait => throw ChatError.RateLimited(wait))
    messages.acquire(MessageKey, chatId.toString, 1)

  def beforeAnswer(): Unit = answers.acquire(AnswerKey, AnswerKey, 1)

  /** Records a 429 for `chatId`; a later, shorter `retry_after` never shortens a pause that is already set. */
  def pause(chatId: Long, retryAfter: Duration): Unit =
    val until = clock.now().plus(retryAfter)
    pausedUntil.merge(chatId, until, (old, fresh) => if fresh.isAfter(old) then fresh else old)
    ()

  private def remainingPause(chatId: Long): Option[Duration] =
    Option(pausedUntil.get(chatId)).flatMap: until =>
      val wait = Duration.between(clock.now(), until)
      if wait.isPositive then Some(wait)
      else
        pausedUntil.remove(chatId, until)
        None

object FloodGate:

  private val MessageKey = "telegram"
  private val AnswerKey = "telegram:answer"

  /** `global` and `perChat` bound message calls; `answers` bounds `answerCallbackQuery` (TG-25). All configuration. */
  final case class Limits(global: TokenBuckets.Bucket, perChat: TokenBuckets.Bucket, answers: TokenBuckets.Bucket)

  object Limits:
    /** TG-26: 25 per second with a burst of 25 in total, 1 per second with a burst of 3 per chat; 20 answers a second.
      */
    val Default: Limits = Limits(
      global = TokenBuckets.Bucket(capacity = 25, refillPerSecond = 25),
      perChat = TokenBuckets.Bucket(capacity = 3, refillPerSecond = 1),
      answers = TokenBuckets.Bucket(capacity = 20, refillPerSecond = 20)
    )
