package dosecord.infra.db

import dosecord.contracts.CapabilityProfile
import dosecord.contracts.ChatAdapter
import dosecord.contracts.ChatError
import dosecord.contracts.ChatRef
import dosecord.contracts.ChoiceMapEntry
import dosecord.contracts.CommandSpec
import dosecord.contracts.InboundSink
import dosecord.contracts.MessageHandle
import dosecord.contracts.PlatformIdentity
import dosecord.contracts.RenderedChoice
import dosecord.contracts.RenderedControls
import dosecord.contracts.RenderedMessage
import dosecord.contracts.RichText
import dosecord.contracts.VendorOp
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.FakeAdapter
import dosecord.core.ports.Clock
import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.OutboxMessage
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.OutboxStatus
import dosecord.core.ports.Tx
import dosecord.core.ports.UnitOfWork
import dosecord.core.scheduling.OutboxDispatcher
import dosecord.core.scheduling.OutboxRenderer

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

/** M0.10 acceptance: the outbox dispatcher protocol (ADR-009, DESIGN.md section 7.6) against Testcontainers Postgres
  * 18 — exactly one row per `send_key`, bounded flagged duplicate after a crash, lease-expiry re-claim, exactly-once
  * claiming with two concurrent dispatchers, and `ops_done` resume of reaction sub-ops.
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
    val clock = MutableClock(t0)
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
    val clock = MutableClock(t0)
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
    val clock = MutableClock(t0)
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
    val clock = MutableClock(t0)
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
    val clock = MutableClock(t0)
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
    val clock = MutableClock(t0)
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
