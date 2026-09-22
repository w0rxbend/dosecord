package dosecord.core.scheduling

import dosecord.core.ports.Clock

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Per-vendor and per-chat token buckets (DESIGN.md section 7.6): they count vendor events, not rows — a reaction-tier
  * reminder is 1 + reactions events. The rates are configuration, not code; the defaults below are the documented
  * vendor limits. Priority is not a bucket property: the dispatcher dispatches each claimed batch in priority order
  * ([[OutboxDispatcher.priorityRank]]) and buckets are FIFO per dispatcher fork.
  */
object TokenBuckets:

  /** One token bucket: `capacity` tokens, refilled continuously at `refillPerSecond`. */
  final case class Bucket(capacity: Int, refillPerSecond: Double):
    require(capacity >= 1, s"bucket capacity must be >= 1, got $capacity")
    require(refillPerSecond > 0, s"bucket refill must be positive, got $refillPerSecond")

  /** The vendor-global and per-chat limits of one vendor. */
  final case class Limits(vendor: Bucket, chat: Bucket)

  /** The limits of a vendor without documented defaults. */
  val Default: Limits = Limits(Bucket(20, 10), Bucket(5, 1))

  /** The documented vendor limits (DESIGN.md section 7.6): Discord 50 req/s global and 5 msg/5 s per channel; Telegram
    * ~30 msg/s and ~1 msg/s per chat; Zulip 200 req/min; Matrix `retry_after_ms` plus 0.2 events/s with a burst of 10
    * per account.
    */
  val defaults: Map[String, Limits] = Map(
    "discord" -> Limits(Bucket(50, 50), Bucket(5, 1)),
    "telegram" -> Limits(Bucket(30, 30), Bucket(1, 1)),
    "zulip" -> Limits(Bucket(200, 200.0 / 60.0), Bucket(200, 200.0 / 60.0)),
    "matrix" -> Limits(Bucket(10, 0.2), Bucket(10, 0.2))
  )

/** Token buckets over the process clock: `acquire` blocks (through the injected `sleep`, real time in production) until
  * the requested events fit on both the vendor and the chat bucket, then deducts them.
  */
final class TokenBuckets(
    clock: Clock,
    limits: Map[String, TokenBuckets.Limits],
    sleep: Duration => Unit = d => Thread.sleep(d.toMillis)
):
  import TokenBuckets.Bucket

  private final class State(val bucket: Bucket):
    var tokens: Double = bucket.capacity.toDouble
    var lastRefill: Instant = clock.now()

  private val vendorStates = ConcurrentHashMap[String, State]()
  private val chatStates = ConcurrentHashMap[(String, String), State]()

  /** Blocks until `events` tokens are available on both the vendor-global and the per-chat bucket, then deducts them.
    * The vendor bucket is charged first: a chat that waits holds its vendor tokens, which is the conservative direction
    * for a global limit.
    */
  def acquire(vendor: String, chatId: String, events: Int): Unit =
    val limits = this.limits.getOrElse(vendor, TokenBuckets.Default)
    acquireFrom(vendorStates.computeIfAbsent(vendor, _ => new State(limits.vendor)), events)
    acquireFrom(chatStates.computeIfAbsent((vendor, chatId), _ => new State(limits.chat)), events)

  private def acquireFrom(state: State, events: Int): Unit =
    var waits = 0
    while waits < 10000 do
      val wait = state.synchronized:
        val now = clock.now()
        val elapsed = Duration.between(state.lastRefill, now)
        val seconds = if elapsed.isNegative then 0.0 else elapsed.toNanos.toDouble / 1e9
        state.lastRefill = now
        state.tokens = math.min(state.bucket.capacity.toDouble, state.tokens + seconds * state.bucket.refillPerSecond)
        if state.tokens >= events then
          state.tokens -= events
          Duration.ZERO
        else Duration.ofMillis(math.ceil((events - state.tokens) / state.bucket.refillPerSecond * 1000.0).toLong)
      if wait.isZero then return
      sleep(wait)
      waits += 1
    throw new IllegalStateException(s"token bucket (capacity ${state.bucket.capacity}) did not refill")
