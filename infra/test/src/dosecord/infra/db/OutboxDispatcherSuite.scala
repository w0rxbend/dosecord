package dosecord.infra.db

import dosecord.contracts.CapabilityProfile
import dosecord.contracts.ChatAdapter
import dosecord.contracts.ChatError
import dosecord.contracts.ChatRef
import dosecord.contracts.ChoiceMapEntry
import dosecord.contracts.CommandSpec
import dosecord.contracts.HhMm
import dosecord.contracts.InboundSink
import dosecord.contracts.Inline
import dosecord.contracts.LoopDispatch
import dosecord.contracts.MessageHandle
import dosecord.contracts.Node
import dosecord.contracts.OutboundMessage
import dosecord.contracts.PlatformIdentity
import dosecord.contracts.RenderedChoice
import dosecord.contracts.RenderedControls
import dosecord.contracts.RenderedMessage
import dosecord.contracts.RichText
import dosecord.contracts.VendorOp
import dosecord.contracts.Weekday
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.FakeAdapter
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.ports.Clock
import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.OutboxMessage
import dosecord.core.ports.OutboxMetrics
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.OutboxStatus
import dosecord.core.ports.Tx
import dosecord.core.ports.UnitOfWork
import dosecord.core.scheduling.CatalogueOutboxRenderer
import dosecord.core.scheduling.OutboxDispatcher
import dosecord.core.scheduling.OutboxRenderer
import dosecord.core.scheduling.TokenBuckets
import dosecord.infra.Metrics
import dosecord.infra.MicrometerOutboxMetrics

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

/** The outbox dispatcher protocol (ADR-009, DESIGN.md section 7.6) against Testcontainers Postgres 18 — the M0.10
  * acceptance (exactly one row per `send_key`, bounded flagged duplicate after a crash, lease-expiry re-claim,
  * exactly-once claiming with two concurrent dispatchers, `ops_done` resume of reaction sub-ops) grown in M1.7: epoch
  * fencing, the jittered backoff envelope with the `dead` metric, and the catalogue renderer at send time.
  */
class OutboxDispatcherSuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try st.execute("TRUNCATE outbox_messages, rendered_messages")
      finally st.close()
    }

  private final class MutableClock(var at: Instant) extends Clock:
    override def now(): Instant = at

  private final class SimulatedCrash extends RuntimeException("simulated process crash")

  /** Throws SimulatedCrash instead of running the `crashOn`-th transaction (1-based), simulating process death at
    * that exact point.
    */
  private final class CrashingUow(inner: UnitOfWork) extends UnitOfWork:
    private val count = AtomicInteger(0)
    @volatile var crashOn: Int = Int.MaxValue
    override def transaction[A](f: Tx => A): A =
      if count.incrementAndGet() == crashOn then throw new SimulatedCrash
      inner.transaction(f)

  /** ChatAdapter wrapper adding crash injection on send and thread safety, keeping the real FakeAdapter underneath.
    */
  private final class TestAdapter(val inner: FakeAdapter, sendDelayMillis: Long = 0) extends ChatAdapter:
    @volatile var crashNextSend = false

    private def around[A](f: => A): A = inner.synchronized(f)

    override def vendor: String = inner.vendor
    override def capabilities: CapabilityProfile = inner.capabilities
    override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = around(inner.start(sink, resumeFrom))
    override def stop(): Unit = around(inner.stop())
    override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle = around {
      if crashNextSend then
        crashNextSend = false
        throw new SimulatedCrash
      if sendDelayMillis > 0 then Thread.sleep(sendDelayMillis)
      inner.send(chat, rendered, sendKey)
    }
    override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle = around {
      inner.edit(handle, rendered)
    }
    override def delete(handle: MessageHandle): Unit = around(inner.delete(handle))
    override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit = around {
      inner.react(handle, emoji, on, txnKey)
    }
    override def registerCommands(specs: List[CommandSpec]): Unit = around(inner.registerCommands(specs))
    override def resolveChat(identity: PlatformIdentity): ChatRef = around(inner.resolveChat(identity))
    override def renderText(text: RichText): List[String] = around(inner.renderText(text))

  private def newAdapter(sendDelayMillis: Long = 0): TestAdapter =
    TestAdapter(FakeAdapter(CapabilityProfiles.Discord, vendor = "fake"), sendDelayMillis)

  private val reactions = List("✅", "⏰")

  private def renderer(withReactions: Boolean): OutboxRenderer = new OutboxRenderer:
    override def render(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
      val controls =
        if !withReactions then Nil
        else
          val choices = reactions.zipWithIndex.map: (emoji, i) =>
            RenderedChoice(index = i + 1, label = s"choice ${i + 1}", callback = s"dc:cb${i}", emoji = Some(emoji))
          List(RenderedControls.Numbered("cs1", choices, reactions))
      val choiceMap = reactions.zipWithIndex.map: (emoji, i) =>
        ChoiceMapEntry("cs1", i + 1, s"choice ${i + 1}", Some(emoji), s"dc:cb${i}")
      val rendered = RenderedMessage(
        chunks = List(s"body for ${row.sendKey}"),
        controls = if withReactions then controls else Nil,
        choiceMap = if withReactions then choiceMap else Nil
      )
      (ChatRef(row.vendor, "chat-1"), rendered)

  private def newSend(sendKey: String, at: Instant, id: UUID = UUID.randomUUID()): NewOutboxMessage =
    NewOutboxMessage(
      id = id,
      sendKey = sendKey,
      op = OutboxOp.Send,
      kind = "reminder",
      vendor = "fake",
      payload = "\"body\"",
      importance = "reminder",
      nextAttemptAt = at
    )

  private def dispatcher(
      uow: UnitOfWork,
      adapter: ChatAdapter,
      clock: Clock,
      withReactions: Boolean = false,
      claimLimit: Int = 20
  ): OutboxDispatcher =
    OutboxDispatcher(uow, Map("fake" -> adapter), renderer(withReactions), clock, claimLimit = claimLimit)

  private def rowBySendKey(sendKey: String): OutboxMessage = withConnection { conn =>
    given Connection = conn
    import PgOutboxRepository.given
    sql"""SELECT id, send_key, op, kind, vendor, account_id, occurrence_id, channel_id, epoch, payload, target,
                 importance, status, attempts, next_attempt_at, lease_until, attempted_at,
                 attempted_at AS prev_attempted_at, ops_done, possible_duplicate, platform_message_id,
                 last_error, created_at, sent_at
          FROM outbox_messages WHERE send_key = $sendKey""".queryOne[OutboxMessage]().get
  }

  private def countOutboxRows(sendKey: String): Int = withConnection { conn =>
    given Connection = conn
    sql"SELECT count(*) FROM outbox_messages WHERE send_key = $sendKey".queryOne[Int]().get
  }

  private def renderedRowsFor(handle: MessageHandle): List[(String, Option[String])] = withConnection { conn =>
    given Connection = conn
    sql"""SELECT message_id, choice_map IS NOT NULL AND choice_map <> 'null'::jsonb AS has_map
          FROM rendered_messages WHERE vendor = ${handle.vendor} AND chat_id = ${handle.chatId}"""
      .query[(String, Option[String])]()
  }

  private given RowMapper[(String, Option[String])] = rs =>
    (rs.getString("message_id"), Option.when(rs.getBoolean("has_map"))(rs.getString("message_id")))

  private def sendsOf(adapter: TestAdapter): List[VendorOp.Send] =
    adapter.inner.ops.collect { case s: VendorOp.Send => s }

  private def reactsOf(adapter: TestAdapter): List[VendorOp.React] =
    adapter.inner.ops.collect { case r: VendorOp.React => r }

  // Acceptance 1: exactly one row per send_key (UNIQUE constraint + insert path, concurrent inserts).
  test("exactly one row per send_key under concurrent inserts"):
    val uow = PgUnitOfWork(dataSource, Clock.system)
    val results = new ConcurrentLinkedQueue[Boolean]()
    val threads = (1 to 8).toList.map { _ =>
      new Thread(() =>
        results.add(uow.transaction(_.outbox.enqueue(newSend("key-dup", t0))))
        ()
      )
    }
    threads.foreach(_.start())
    threads.foreach(_.join(30000))
    assertEquals(results.asScala.count(identity), 1, "exactly one concurrent insert may win")
    assert(!uow.transaction(_.outbox.enqueue(newSend("key-dup", t0))), "sequential duplicate rejected")
    assertEquals(countOutboxRows("key-dup"), 1)

  // Acceptance 2: crash between the vendor call and the record write -> at most one extra send,
  // flagged possible_duplicate, no extra state change.
  test("crash after the vendor send yields at most one extra send flagged possible_duplicate and no extra state"):
    val clock = new MutableClock(t0)
    val pgUow = PgUnitOfWork(dataSource, clock)
    val uow = CrashingUow(pgUow)
    val adapter = newAdapter()
    pgUow.transaction(_.outbox.enqueue(newSend("key-crash", t0)))

    uow.crashOn = 2 // tx1 claim, tx2 recordHandle: process dies after adapter.send, before the record write
    intercept[SimulatedCrash](dispatcher(uow, adapter, clock).dispatchOnce())
    assertEquals(sendsOf(adapter).size, 1)
    val stuck = rowBySendKey("key-crash")
    assertEquals(stuck.status, OutboxStatus.Sending)
    assertEquals(stuck.opsDone, 0)

    clock.at = t0.plusSeconds(180) // past the 60 s lease and the 2-minute duplicate window
    assertEquals(dispatcher(pgUow, adapter, clock).dispatchOnce(), 1)

    assertEquals(sendsOf(adapter).size, 2, "the unrecorded send is repeated exactly once")
    val row = rowBySendKey("key-crash")
    assertEquals(row.status, OutboxStatus.Sent)
    assert(row.possibleDuplicate, "the retried send must be flagged possible_duplicate")
    assertEquals(row.sentAt, Some(t0.plusSeconds(180)))
    assertEquals(countOutboxRows("key-crash"), 1, "no extra outbox state")

  // Acceptance 3: lease expiry re-claims.
  test("lease expiry re-claims a row stuck in sending"):
    val clock = new MutableClock(t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter()
    uow.transaction(_.outbox.enqueue(newSend("key-lease", t0)))

    adapter.crashNextSend = true
    intercept[SimulatedCrash](dispatcher(uow, adapter, clock).dispatchOnce())
    val stuck = rowBySendKey("key-lease")
    assertEquals(stuck.status, OutboxStatus.Sending)
    assertEquals(stuck.attemptedAt, Some(t0), "claim sets attempted_at before the vendor call")
    assertEquals(stuck.leaseUntil, Some(t0.plusSeconds(60)))

    assertEquals(dispatcher(uow, adapter, clock).dispatchOnce(), 0, "lease still held: nothing to claim")

    clock.at = t0.plusSeconds(61)
    assertEquals(dispatcher(uow, adapter, clock).dispatchOnce(), 1, "expired lease is re-claimed")
    val row = rowBySendKey("key-lease")
    assertEquals(row.status, OutboxStatus.Sent)
    assertEquals(row.attemptedAt, Some(t0.plusSeconds(61)))

  // Acceptance 4: two concurrent dispatchers produce exactly-once claiming (SKIP LOCKED).
  test("two concurrent dispatchers claim each row exactly once"):
    val clock = new MutableClock(t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter(sendDelayMillis = 5)
    val keys = (1 to 30).map(i => s"key-conc-$i")
    keys.foreach(k => assert(uow.transaction(_.outbox.enqueue(newSend(k, t0)))))

    val claimed = AtomicInteger(0)
    val task: Runnable = () =>
      val d = dispatcher(uow, adapter, clock, claimLimit = 3)
      var n = d.dispatchOnce()
      while n > 0 do
        claimed.addAndGet(n)
        n = d.dispatchOnce()
    val threads = List(new Thread(task), new Thread(task))
    threads.foreach(_.start())
    threads.foreach(_.join(60000))

    assertEquals(claimed.get(), 30, "every row claimed exactly once")
    val byKey = sendsOf(adapter).groupBy(_.sendKey)
    assertEquals(byKey.keySet, keys.toSet)
    byKey.foreach((k, sends) => assertEquals(sends.size, 1, s"send_key $k sent more than once"))
    keys.foreach(k => assertEquals(rowBySendKey(k).status, OutboxStatus.Sent))

  // Acceptance 5: ops_done resume of reaction sub-ops.
  test("crash mid reaction sub-ops resumes the remaining ones without redoing completed ones"):
    val clock = new MutableClock(t0)
    val pgUow = PgUnitOfWork(dataSource, clock)
    val uow = CrashingUow(pgUow)
    val adapter = newAdapter()
    pgUow.transaction(_.outbox.enqueue(newSend("key-ops", t0)))

    // tx1 claim, tx2 recordHandle, tx3 markOpDone(r0), tx4 markOpDone(r1): r1 reached the vendor but its
    // completion record is lost.
    uow.crashOn = 4
    intercept[SimulatedCrash](dispatcher(uow, adapter, clock, withReactions = true).dispatchOnce())
    assertEquals(sendsOf(adapter).size, 1)
    assertEquals(reactsOf(adapter).map(_.emoji), reactions, "both reactions reached the vendor")
    val stuck = rowBySendKey("key-ops")
    assertEquals(stuck.opsDone, 3, "send and first reaction recorded")

    clock.at = t0.plusSeconds(61)
    assertEquals(dispatcher(pgUow, adapter, clock, withReactions = true).dispatchOnce(), 1)

    assertEquals(sendsOf(adapter).size, 1, "primary send is not redone")
    val reacts = reactsOf(adapter)
    assertEquals(reacts.count(_.emoji == "✅"), 1, "completed sub-op is not redone")
    assertEquals(reacts.count(_.emoji == "⏰"), 2, "lost record is redone once (duplicate-reaction is success)")
    assertEquals(reacts.map(_.txnKey).distinct, List("key-ops:r0", "key-ops:r1"), "per-op txn keys are stable")

    val row = rowBySendKey("key-ops")
    assertEquals(row.status, OutboxStatus.Sent)
    assertEquals(row.opsDone, 7, "all sub-ops recorded")
    assert(!row.possibleDuplicate, "no resend happened, so no duplicate flag")

    val handle = adapter.inner.sent.head._1
    val rendered = renderedRowsFor(handle)
    assertEquals(rendered.size, 1, "handle recorded before the first reaction")
    assert(rendered.head._2.isDefined, "choice_map recorded: controls resolvable after the crash")

  // Retryable failure: rescheduled with backoff, no duplicate flag, later claim succeeds.
  test("retryable failure reschedules without the duplicate flag and a later claim succeeds"):
    val clock = new MutableClock(t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter()
    uow.transaction(_.outbox.enqueue(newSend("key-retry", t0)))

    adapter.inner.failNext(ChatError.Retryable("vendor unavailable"))
    assertEquals(dispatcher(uow, adapter, clock).dispatchOnce(), 1)
    val failed = rowBySendKey("key-retry")
    assertEquals(failed.status, OutboxStatus.FailedRetry)
    assertEquals(failed.leaseUntil, None, "lease released on retry")
    assertEquals(failed.nextAttemptAt, t0.plusSeconds(5), "first backoff rung is 5 s")
    assert(!failed.possibleDuplicate)
    assertEquals(failed.lastError, Some("vendor unavailable"))

    assertEquals(dispatcher(uow, adapter, clock).dispatchOnce(), 0, "not due yet")
    clock.at = t0.plusSeconds(5)
    assertEquals(dispatcher(uow, adapter, clock).dispatchOnce(), 1)
    assertEquals(rowBySendKey("key-retry").status, OutboxStatus.Sent)

  // Edit op: idempotent by content against the recorded target.
  test("edit op edits the target handle and marks sent"):
    val clock = new MutableClock(t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter()
    val target =
      adapter.inner.send(ChatRef("fake", "chat-1"), RenderedMessage(chunks = List("old body")), "key-direct")
    uow.transaction(
      _.outbox.enqueue(
        NewOutboxMessage(
          id = UUID.randomUUID(),
          sendKey = "key-edit",
          op = OutboxOp.Edit,
          kind = "reminder",
          vendor = "fake",
          payload = "\"new body\"",
          target = Some(OutboxDispatcher.encodeHandle(target)),
          nextAttemptAt = t0
        )
      )
    )

    assertEquals(dispatcher(uow, adapter, clock).dispatchOnce(), 1)
    val edits = adapter.inner.ops.collect { case e: VendorOp.Edit => e }
    assertEquals(edits.size, 1)
    assertEquals(edits.head.handle.messageId, target.messageId)
    val row = rowBySendKey("key-edit")
    assertEquals(row.status, OutboxStatus.Sent)
    assertEquals(row.platformMessageId, Some(OutboxDispatcher.encodeHandle(target)))

  // ---------- M1.7 growth ----------

  private val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.fill[Byte](32)(7)), None))

  private final class RecordingMetrics extends OutboxMetrics:
    val deadCount = AtomicInteger(0)
    override def outboxDead(): Unit = deadCount.incrementAndGet()
    override def possibleDuplicate(vendor: String): Unit = ()

  private def fixtureSchedule(fixtures: Fixtures, clock: Clock, accountId: UUID): UUID =
    fixtures
      .schedule(
        clock,
        accountId,
        "Vitamin D",
        Rule.FixedTimes(List(SlotGroup(List(Weekday.Mon), List(HhMm.unsafe("09:00"))))),
        ZoneId.of("UTC"),
        doseAmount = Some(BigDecimal(1000)),
        doseUnit = Some("IU"),
        instructions = Some("with breakfast")
      )
      .scheduleId

  // Acceptance: a stale-epoch row is cancelled without a vendor call.
  test("a stale-epoch row is cancelled without a vendor call"):
    val clock = new MutableClock(t0)
    val fixtures = Fixtures(dataSource)
    val accountId = fixtures.account()
    val scheduleId = fixtureSchedule(fixtures, clock, accountId)
    val occurrenceId = fixtures.occurrenceAt(scheduleId, t0, "fixture-stale-1", OccurrenceStatus.Due)
    withConnection { conn =>
      given Connection = conn
      sql"UPDATE dose_occurrences SET epoch = 5 WHERE id = $occurrenceId".execute()
    }
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter()
    def enqueue(key: String, epoch: Int): Unit = uow.transaction(
      _.outbox.enqueue(
        NewOutboxMessage(
          id = UUID.randomUUID(),
          sendKey = key,
          op = OutboxOp.Send,
          kind = "reminder",
          vendor = "fake",
          accountId = Some(accountId),
          occurrenceId = Some(occurrenceId),
          epoch = Some(epoch),
          payload = "\"body\"",
          importance = "reminder",
          nextAttemptAt = t0
        )
      )
    )
    enqueue("key-stale", 1)
    enqueue("key-fresh", 5)

    assertEquals(dispatcher(uow, adapter, clock).dispatchOnce(), 2)
    assertEquals(sendsOf(adapter).map(_.sendKey), List("key-fresh"), "the stale row never reached the vendor")
    assertEquals(rowBySendKey("key-stale").status, OutboxStatus.Cancelled)
    assertEquals(rowBySendKey("key-fresh").status, OutboxStatus.Sent)

  // Acceptance: the retry instants follow 5 s..30 min with jitter within bounds; the ninth attempt is dead.
  test("the retry instants of a failing row stay within the jittered backoff envelope and the ninth attempt is dead"):
    val clock = new MutableClock(t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter()
    val metrics = RecordingMetrics()
    val random = new scala.util.Random(42)
    uow.transaction(_.outbox.enqueue(newSend("key-dead", t0)))
    val d = OutboxDispatcher(
      uow,
      Map("fake" -> adapter),
      renderer(false),
      clock,
      metrics = metrics,
      random = () => random.nextDouble()
    )

    (1 to 8).foreach { attempt =>
      adapter.inner.failNext(ChatError.Retryable("vendor down"))
      assertEquals(d.dispatchOnce(), 1, s"attempt $attempt")
      val row = rowBySendKey("key-dead")
      if attempt < OutboxDispatcher.MaxAttempts then
        assertEquals(row.status, OutboxStatus.FailedRetry)
        val base = OutboxDispatcher.backoff(attempt)
        val low =
          if base.dividedBy(2).compareTo(OutboxDispatcher.MinBackoff) > 0 then base.dividedBy(2)
          else OutboxDispatcher.MinBackoff
        val high = if base.compareTo(Duration.ofMinutes(30)) > 0 then Duration.ofMinutes(30) else base
        val delay = Duration.between(clock.at, row.nextAttemptAt)
        assert(
          delay.compareTo(low) >= 0 && delay.compareTo(high) <= 0,
          s"attempt $attempt: $delay outside [$low, $high]"
        )
        clock.at = row.nextAttemptAt
      else
        assertEquals(row.status, OutboxStatus.Dead, "the budget is spent: dead instead of a ninth retry")
    }
    assertEquals(metrics.deadCount.get(), 1, "dead increments the outbox metric exactly once")
    assertEquals(d.dispatchOnce(), 0, "a dead row is never claimed again")

    MicrometerOutboxMetrics().outboxDead()
    assert(
      Metrics.scrape().linesIterator.exists(l =>
        l.startsWith("dosecord_outbox_dead_total ") || l.startsWith("dosecord_outbox_dead_total{")
      ),
      "the dead counter is registered under its DESIGN.md name"
    )
    MicrometerOutboxMetrics().possibleDuplicate("fake")
    assert(
      Metrics.scrape().linesIterator.exists(_.startsWith("dosecord_possible_duplicates_total{")),
      "the duplicate counter is registered under its DESIGN.md name"
    )

  // Acceptance: reminder text rendered from the M1.4a catalogue at send time (Postgres path, loop-shaped row).
  test("a loop reminder row renders from the catalogue at send time"):
    val clock = new MutableClock(t0)
    val fixtures = Fixtures(dataSource)
    val accountId = fixtures.account()
    val scheduleId = fixtureSchedule(fixtures, clock, accountId)
    val occurrenceId = fixtures.occurrenceAt(scheduleId, t0, "fixture-render-1", OccurrenceStatus.Due)
    val channelId = fixtures.deliveryChannel(accountId, "fake", "dm:owner", t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter()
    val sendKey = s"occ:$occurrenceId:e0:s1:kinitial:c$channelId"
    uow.transaction(
      _.outbox.enqueue(
        NewOutboxMessage(
          id = UUID.randomUUID(),
          sendKey = sendKey,
          op = OutboxOp.Send,
          kind = "reminder",
          vendor = "fake",
          accountId = Some(accountId),
          occurrenceId = Some(occurrenceId),
          channelId = Some(channelId),
          epoch = Some(0),
          payload = LoopDispatch.toJson(LoopDispatch.Reminder(occurrenceId, None, "initial", 1, silent = false)),
          importance = "reminder",
          nextAttemptAt = t0
        )
      )
    )

    val d = OutboxDispatcher(uow, Map("fake" -> adapter), CatalogueOutboxRenderer(uow, codec, clock), clock)
    assertEquals(d.dispatchOnce(), 1)

    val sends = sendsOf(adapter)
    assertEquals(sends.size, 1)
    assertEquals(sends.head.chat.chatId, "dm:owner", "the chat resolves through the delivery channel")
    val text = sends.head.message.chunks.mkString("\n")
    assert(text.contains("Time for Vitamin D, 1000 IU."), s"body was: $text")
    assert(text.contains("with breakfast"), s"instructions line missing: $text")
    sends.head.message.controls match
      case List(RenderedControls.Buttons(row1), RenderedControls.Buttons(row2)) =>
        assertEquals(row1.flatten.map(_.label), List("Taken", "Snooze 10m", "Skip"))
        assertEquals(row2.flatten.map(_.label), List("Snooze 30m", "Snooze 1h"))
      case other => fail(s"unexpected controls: $other")

    assertEquals(rowBySendKey(sendKey).status, OutboxStatus.Sent)
    val handle = adapter.inner.sent.head._1
    val recorded = renderedRowsFor(handle)
    assertEquals(recorded.size, 1, "the handle and choice_map are recorded")
    assert(recorded.head._2.isDefined)

  // The 30 s safety row of a synchronously delivered reply: claimed only when the record write was lost, it renders
  // through the pure renderer and resolves the account's channel chat.
  test("an interaction_reply safety row redelivers through the renderer when the sync record was lost"):
    val clock = new MutableClock(t0)
    val fixtures = Fixtures(dataSource)
    val accountId = fixtures.account()
    fixtures.deliveryChannel(accountId, "fake", "dm:owner", t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter()
    val body = "Something went wrong on my side — please try again in a moment."
    uow.transaction(
      _.outbox.enqueue(
        NewOutboxMessage(
          id = UUID.randomUUID(),
          sendKey = "dedupe-safety-1",
          op = OutboxOp.Send,
          kind = "interaction_reply",
          vendor = "fake",
          accountId = Some(accountId),
          payload = OutboundMessage.toJson(
            OutboundMessage(
              body = List(Node.Paragraph(List(Inline.Text(body)))),
              dedupeKey = "dedupe-safety-1",
              correlationId = "corr-1"
            )
          ),
          importance = "interaction_reply",
          nextAttemptAt = t0.plusSeconds(30)
        )
      )
    )

    val d = OutboxDispatcher(uow, Map("fake" -> adapter), CatalogueOutboxRenderer(uow, codec, clock), clock)
    assertEquals(d.dispatchOnce(), 0, "not due before the 30 s safety delay")
    clock.at = t0.plusSeconds(30)
    assertEquals(d.dispatchOnce(), 1)
    assertEquals(sendsOf(adapter).map(_.message.chunks), List(List(body)))
    assertEquals(rowBySendKey("dedupe-safety-1").status, OutboxStatus.Sent)

  // ---------- M1.7 step 2: channel fallback and token buckets ----------

  /** An account with a healthy primary on `fake` and a fallback channel on `fake2` (delivery_channels priorities). */
  private final case class TwoChannels(accountId: UUID, primaryChannelId: UUID, fallbackChannelId: UUID)

  private def twoChannels(fixtures: Fixtures, accountId: UUID): TwoChannels =
    val primary = fixtures.deliveryChannel(accountId, "fake", "dm:owner", t0)
    val fallback = fixtures.deliveryChannel(accountId, "fake2", "dm:other", t0, role = "fallback", priority = 1)
    TwoChannels(accountId, primary, fallback)

  private def channelState(channelId: UUID): Option[String] = withConnection { conn =>
    given Connection = conn
    sql"SELECT state FROM delivery_channels WHERE id = $channelId".queryOne[String]()
  }

  // Acceptance: channelFatal -> channel dead -> fallback to the next channel.
  test("channelFatal kills the row and the channel, and the next channel's dispatch is enqueued"):
    val clock = new MutableClock(t0)
    val fixtures = Fixtures(dataSource)
    val channels = twoChannels(fixtures, fixtures.account())
    val uow = PgUnitOfWork(dataSource, clock)
    val adapterA = newAdapter()
    val adapterB = TestAdapter(FakeAdapter(CapabilityProfiles.Discord, vendor = "fake2"))
    val sendKey = s"reminder-fatal:c${channels.primaryChannelId}"
    uow.transaction(
      _.outbox.enqueue(
        NewOutboxMessage(
          id = UUID.randomUUID(),
          sendKey = sendKey,
          op = OutboxOp.Send,
          kind = "reminder",
          vendor = "fake",
          accountId = Some(channels.accountId),
          channelId = Some(channels.primaryChannelId),
          payload = "\"body\"",
          importance = "reminder",
          nextAttemptAt = t0
        )
      )
    )
    adapterA.inner.failNext(ChatError.Unreachable("50007: cannot send messages to this user"))
    val d = OutboxDispatcher(uow, Map("fake" -> adapterA, "fake2" -> adapterB), renderer(false), clock)

    assertEquals(d.dispatchOnce(), 1)
    assertEquals(sendsOf(adapterA), Nil, "the fatal send never reached the vendor")
    assertEquals(rowBySendKey(sendKey).status, OutboxStatus.Dead)
    assertEquals(channelState(channels.primaryChannelId), Some("dead"), "the channel is marked dead")

    assertEquals(d.dispatchOnce(), 1, "the fallback row is claimed on the next cycle")
    val fallbackRow = rowBySendKey(s"$sendKey:fb:${channels.fallbackChannelId}")
    assertEquals(fallbackRow.status, OutboxStatus.Sent)
    assertEquals(fallbackRow.vendor, "fake2")
    val fallbackSends = adapterB.inner.ops.collect { case s: VendorOp.Send => s }
    assertEquals(fallbackSends.size, 1)
    assertEquals(fallbackSends.head.chat.vendor, "fake2")

  // Acceptance: fallback_after — a send still unsent 5 minutes past next_attempt_at reaches the next channel.
  test("a send still unsent 5 minutes after next_attempt_at falls back to the next channel"):
    val clock = new MutableClock(t0)
    val fixtures = Fixtures(dataSource)
    val accountId = fixtures.account()
    val scheduleId = fixtureSchedule(fixtures, clock, accountId)
    val occurrenceId = fixtures.occurrenceAt(scheduleId, t0, "fixture-fallback-1", OccurrenceStatus.Due)
    val channels = twoChannels(fixtures, accountId)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapterA = newAdapter()
    val adapterB = TestAdapter(FakeAdapter(CapabilityProfiles.Discord, vendor = "fake2"))
    val sendKey = s"occ:$occurrenceId:e0:s1:kinitial:c${channels.primaryChannelId}"
    uow.transaction(
      _.outbox.enqueue(
        NewOutboxMessage(
          id = UUID.randomUUID(),
          sendKey = sendKey,
          op = OutboxOp.Send,
          kind = "reminder",
          vendor = "fake",
          accountId = Some(accountId),
          occurrenceId = Some(occurrenceId),
          channelId = Some(channels.primaryChannelId),
          epoch = Some(0),
          payload = LoopDispatch.toJson(LoopDispatch.Reminder(occurrenceId, Some("dm:owner"), "initial", 1, silent = false)),
          importance = "reminder",
          nextAttemptAt = t0.minusSeconds(360)
        )
      )
    )
    val d = OutboxDispatcher(
      uow,
      Map("fake" -> adapterA, "fake2" -> adapterB),
      CatalogueOutboxRenderer(uow, codec, clock),
      clock
    )

    assertEquals(d.dispatchOnce(), 1, "the overdue row is claimed and dispatched on the primary")
    assertEquals(sendsOf(adapterA).map(_.chat.chatId), List("dm:owner"))
    assertEquals(d.dispatchOnce(), 1, "the fallback row is claimed on the next cycle")
    assertEquals(rowBySendKey(s"$sendKey:fb:${channels.fallbackChannelId}").status, OutboxStatus.Sent)
    val fallbackSends = adapterB.inner.ops.collect { case s: VendorOp.Send => s }
    assertEquals(fallbackSends.map(_.chat.chatId), List("dm:other"),
      "the fallback renders against its own channel: chat ids are vendor-scoped")

  // Acceptance: token buckets — per-vendor/per-chat limits respected under a burst.
  test("token buckets throttle a burst per vendor and per chat"):
    val clock = new MutableClock(t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = newAdapter()
    val sleeps = ListBuffer.empty[Duration]
    val buckets = TokenBuckets(
      clock,
      Map("fake" -> TokenBuckets.Limits(TokenBuckets.Bucket(1, 1.0), TokenBuckets.Bucket(10, 10))),
      d =>
        sleeps += d
        clock.at = clock.at.plus(d)
    )
    (1 to 3).foreach(i => assert(uow.transaction(_.outbox.enqueue(newSend(s"key-burst-$i", t0)))))
    val d = OutboxDispatcher(uow, Map("fake" -> adapter), renderer(false), clock, buckets = Some(buckets))

    assertEquals(d.dispatchOnce(), 3)
    assertEquals(sendsOf(adapter).size, 3, "every row is eventually dispatched")
    assertEquals(sleeps.toList, List(Duration.ofSeconds(1), Duration.ofSeconds(1)),
      "the vendor bucket allows one event per second under the burst")
