package dosegram.core.scheduling

import dosegram.contracts.MessageHandle

import java.time.Duration

class OutboxDispatcherSuite extends munit.FunSuite:

  test("ops_done bit 0 marks the primary send as recorded"):
    assert(!OutboxDispatcher.sendRecorded(0))
    assert(OutboxDispatcher.sendRecorded(1))
    assert(!OutboxDispatcher.sendRecorded(2))

  test("handle codec round-trips and splits on the last separator (chat ids may contain ':')"):
    val handle = MessageHandle("fake", "chat:room:1", "m42")
    assertEquals(OutboxDispatcher.decodeHandle("fake", OutboxDispatcher.encodeHandle(handle)), handle)
    assertEquals(OutboxDispatcher.encodeHandle(MessageHandle("fake", "chat-1", "m1")), "chat-1:m1")
    intercept[IllegalArgumentException](OutboxDispatcher.decodeHandle("fake", "no-separator"))

  test("backoff grows exponentially from 5 s and is capped at 30 min"):
    assertEquals(OutboxDispatcher.backoff(1), Duration.ofSeconds(5))
    assertEquals(OutboxDispatcher.backoff(2), Duration.ofSeconds(10))
    assertEquals(OutboxDispatcher.backoff(3), Duration.ofSeconds(20))
    assertEquals(OutboxDispatcher.backoff(8), Duration.ofSeconds(640))
    assertEquals(OutboxDispatcher.backoff(20), Duration.ofMinutes(30))

  test("jitteredBackoff stays within [max(5 s, base/2), min(30 min, base)] for every attempt and roll"):
    val rolls = List(0.0, 0.25, 0.5, 0.75, 1.0)
    (1 to 20).foreach: attempts =>
      val base = OutboxDispatcher.backoff(attempts)
      val low = if base.dividedBy(2).compareTo(Duration.ofSeconds(5)) > 0 then base.dividedBy(2)
        else Duration.ofSeconds(5)
      val high = if base.compareTo(Duration.ofMinutes(30)) > 0 then Duration.ofMinutes(30) else base
      rolls.foreach: roll =>
        val delay = OutboxDispatcher.jitteredBackoff(attempts, roll)
        assert(
          delay.compareTo(low) >= 0 && delay.compareTo(high) <= 0,
          s"attempts=$attempts roll=$roll -> $delay outside [$low, $high]"
        )
    assertEquals(OutboxDispatcher.jitteredBackoff(1, 0.5), Duration.ofSeconds(5), "first rung is exactly 5 s")
    assertEquals(OutboxDispatcher.jitteredBackoff(8, 1.0), OutboxDispatcher.backoff(8))

  test("a claimed batch goes in priority order, then in the order its rows fell due"):
    // The claim's UPDATE ... RETURNING has no order: a report's card and its text came back either way round.
    val t0 = java.time.Instant.parse("2026-11-23T06:00:05Z")
    def row(key: String, importance: String, due: java.time.Instant) =
      dosegram.core.ports.OutboxMessage(
        id = java.util.UUID.randomUUID(),
        sendKey = key,
        op = dosegram.core.ports.OutboxOp.Send,
        kind = "prompt",
        vendor = "fake",
        accountId = None,
        occurrenceId = None,
        channelId = None,
        epoch = None,
        payload = "{}",
        target = None,
        importance = importance,
        status = dosegram.core.ports.OutboxStatus.Sending,
        attempts = 1,
        nextAttemptAt = due,
        leaseUntil = None,
        attemptedAt = None,
        previousAttemptedAt = None,
        opsDone = 0,
        possibleDuplicate = false,
        platformMessageId = None,
        lastError = None,
        createdAt = t0,
        sentAt = None
      )
    val claimed = List(
      row("text", "info", t0.plusMillis(1)),
      row("card", "info", t0),
      row("ack", "ack", t0.plusSeconds(1))
    )
    assertEquals(OutboxDispatcher.dispatchOrder(claimed).map(_.sendKey), List("ack", "card", "text"))

  test("the dispatch order is ack > interaction_reply > reminder > info > bulk"):
    val shuffled = List("bulk", "reminder", "ack", "info", "interaction_reply")
    assertEquals(
      shuffled.sortBy(OutboxDispatcher.priorityRank),
      List("ack", "interaction_reply", "reminder", "info", "bulk")
    )
