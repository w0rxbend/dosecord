package dosegram.adapter.telegram

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The reader stops for good: polling on cannot help and may harm (a second consumer, a rejected token). */
final class TelegramFatal(reason: String) extends RuntimeException(s"telegram long polling stopped: $reason")

/** The `getUpdates` loop (ARCHITECTURE section 7.2.2, ROADMAP G1.6). Updates of one batch are delivered in `update_id`
  * order on this one thread, so the mediator's per-chat queues see every chat's updates in order. The offset moves only
  * past an update that was delivered; an update at or below the last delivered one is a redelivery and is dropped
  * (TG-04 on the polling path). The next call confirms what was delivered by its offset. With the durable inbox,
  * delivered means stored (ADR-017).
  */
final class LongPollReader(
    api: BotApi,
    deliver: Update => Boolean,
    resumeAfter: Option[Long],
    log: String => Unit,
    sleep: Duration => Unit,
    pollTimeout: Duration = LongPollReader.PollTimeout
):

  import LongPollReader.*

  private val nextOffset = AtomicReference(resumeAfter.map(_ + 1))
  private val running = AtomicBoolean(true)

  def stop(): Unit = running.set(false)

  /** Polls until [[stop]]; throws [[TelegramFatal]] when the API says this consumer must not go on. */
  def run(): Unit =
    while running.get() do
      pollOnce() match
        case Outcome.Delivered(_)  => ()
        case Outcome.Retry(delay)  => sleep(delay)
        case Outcome.Fatal(reason) => throw TelegramFatal(reason)

  def pollOnce(): Outcome =
    api.getUpdates(nextOffset.get(), pollTimeout) match
      case Right(updates)                              => deliverInOrder(updates.sortBy(_.updateId))
      case Left(TelegramError.Conflict)                => Outcome.Fatal("another consumer reads this bot's updates")
      case Left(TelegramError.TokenInvalid)            => Outcome.Fatal("the bot token was rejected")
      case Left(TelegramError.RateLimited(retryAfter)) => Outcome.Retry(retryAfter)
      case Left(error)                                 =>
        log(s"telegram getUpdates failed: ${error.productPrefix}")
        Outcome.Retry(RetryDelay)

  /** Stops at the first update the sink refuses (its queue is full): the offset stays, so the update comes again. */
  private def deliverInOrder(updates: List[Update]): Outcome =
    val fresh = updates.filter(update => nextOffset.get().forall(update.updateId >= _))
    val delivered = fresh.takeWhile: update =>
      val accepted = deliver(update)
      if accepted then nextOffset.set(Some(update.updateId + 1))
      accepted
    if delivered.size == fresh.size then Outcome.Delivered(delivered.size) else Outcome.Retry(RetryDelay)

object LongPollReader:

  /** The long-poll wait of ARCHITECTURE section 7.2.2. */
  val PollTimeout: Duration = Duration.ofSeconds(50)
  val RetryDelay: Duration = Duration.ofSeconds(3)

  enum Outcome:
    case Delivered(count: Int)
    case Retry(delay: Duration)
    case Fatal(reason: String)
