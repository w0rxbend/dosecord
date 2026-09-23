package dosecord.core.scheduling

import dosecord.core.ports.Clock

import java.time.Duration
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** ROADMAP M1.7: per-vendor/per-chat token buckets counting vendor events — a burst beyond a bucket's capacity waits
  * for the refill, a drained chat bucket never blocks other chats, and multi-event acquires deduct the event count.
  */
class TokenBucketsSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")

  private final class TestClock(var at: Instant) extends Clock:
    override def now(): Instant = at
    def advance(by: Duration): Unit = at = at.plus(by)

  private final class Harness(vendor: TokenBuckets.Bucket, chat: TokenBuckets.Bucket):
    val clock = new TestClock(t0)
    val sleeps = ListBuffer.empty[Duration]
    val buckets = TokenBuckets(
      clock,
      Map("fake" -> TokenBuckets.Limits(vendor, chat)),
      d =>
        sleeps += d
        clock.advance(d)
    )

  test("a burst beyond the vendor capacity waits for the refill"):
    val h = Harness(TokenBuckets.Bucket(2, 1.0), TokenBuckets.Bucket(10, 10))
    h.buckets.acquire("fake", "chat-1", 1)
    h.buckets.acquire("fake", "chat-2", 1)
    assertEquals(h.sleeps.toList, Nil, "the capacity covers the first two events")
    h.buckets.acquire("fake", "chat-3", 1)
    assertEquals(h.sleeps.toList, List(Duration.ofSeconds(1)), "the third event waits one refill second")
    h.buckets.acquire("fake", "chat-4", 1)
    assertEquals(h.sleeps.toList, List(Duration.ofSeconds(1), Duration.ofSeconds(1)),
      "the fourth event waits for the next refill")

  test("a drained chat bucket does not block other chats"):
    val h = Harness(TokenBuckets.Bucket(100, 100), TokenBuckets.Bucket(1, 1.0))
    h.buckets.acquire("fake", "chat-a", 1)
    h.buckets.acquire("fake", "chat-b", 1)
    assertEquals(h.sleeps.toList, Nil, "chat-b has its own bucket")
    h.buckets.acquire("fake", "chat-a", 1)
    assertEquals(h.sleeps.toList, List(Duration.ofSeconds(1)))

  test("multi-event acquires deduct the event count"):
    val h = Harness(TokenBuckets.Bucket(5, 1.0), TokenBuckets.Bucket(5, 1.0))
    h.buckets.acquire("fake", "chat-1", 4)
    h.buckets.acquire("fake", "chat-1", 2)
    assertEquals(h.sleeps.toList, List(Duration.ofSeconds(1)), "one token short: a one-second refill wait")

  test("the documented vendor defaults are configured for the four vendors"):
    assertEquals(TokenBuckets.defaults.keySet, Set("discord", "telegram", "zulip", "matrix"))
    assertEquals(TokenBuckets.defaults("discord").chat, TokenBuckets.Bucket(5, 1.0))
    assertEquals(TokenBuckets.defaults("matrix").vendor, TokenBuckets.Bucket(10, 0.2))
