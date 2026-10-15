package dosecord.infra.db

import dosecord.contracts.*
import dosecord.core.chat.*
import dosecord.core.ports.*
import dosecord.core.scheduling.*

import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.language.implicitConversions

/** M2.2a: durable recovery against real PostgreSQL commits, with synthetic records only. */
class InteractionRecoveryPgSuite extends PgSuite:
  private val now = Instant.parse("2026-10-14T09:00:00Z")
  private val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.fill[Byte](16)(1)), None))
  private val actor = PlatformIdentity("fake", "synthetic-recovery")
  private val source = MessageHandle("fake", "original-chat", "source-card")
  private val clock: Clock = () => now

  override def beforeEach(context: BeforeEach): Unit =
    withConnection: conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE inbound_events, domain_events, rendered_messages, outbox_messages, platform_identities, users CASCADE"
        )
      finally st.close()

  private def inbound(id: String): InboundEvent = InboundEvent(
    EventId(UUID.randomUUID()),
    "fake",
    id,
    now,
    actor = actor,
    chat = ChatRef("fake", source.chatId),
    body = Inbound.CommandInvoked("help", Map.empty, "/help")
  )

  private def message(key: String): OutboundMessage = OutboundMessage(
    body = List(Node.Paragraph(List(Inline.Text("Synthetic saved outcome")))),
    replaces = Some(source),
    dedupeKey = key,
    correlationId = key
  )

  private def count(table: String): Long = withConnection: conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(s"SELECT count(*) FROM $table")
      rs.next()
      rs.getLong(1)
    finally st.close()

  private def await(cond: => Boolean): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    while !cond && System.nanoTime() < deadline do Thread.sleep(10)
    assert(cond, "recovery reached its fault boundary")

  private final class FaultUow(inner: UnitOfWork, failAt: Int, afterCommit: Boolean) extends UnitOfWork:
    val calls = AtomicInteger(0)
    val faults = AtomicInteger(0)
    override def transaction[A](f: Tx => A): A =
      val index = calls.incrementAndGet()
      if index == failAt && !afterCommit then
        faults.incrementAndGet()
        throw SQLException("synthetic bookkeeping unavailable")
      val result = inner.transaction(f)
      if index == failAt && afterCommit then
        faults.incrementAndGet()
        throw SQLException("synthetic commit acknowledgement lost")
      result

  test("vendor-accepted source edit survives lost bookkeeping and dispatcher restart without creating a message"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val fault = FaultUow(pgUow, failAt = 2, afterCommit = false)
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    val handled = AtomicInteger(0)
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
        handled.incrementAndGet()
        Reply(replace = Some(message("recovery-edit")))
    ox.supervised:
      val mediator = ChatMediator(fault, Map("fake" -> adapter), codec, handler, clock)
      mediator.push(inbound("recovery-edit-event"))
      await(fault.faults.get() == 1)
    val stored = pgUow.transaction(_.inboundEvents.find("fake", "recovery-edit-event")).get
    assertEquals(Reply.fromJson(stored.reply.get).replace.flatMap(_.replaces), Some(source))
    val later: Clock = () => now.plusSeconds(31)
    val restarted = PgUnitOfWork(dataSource, later)
    val dispatcher =
      OutboxDispatcher(restarted, Map("fake" -> adapter), CatalogueOutboxRenderer(restarted, codec, later), later)
    assertEquals(dispatcher.dispatchOnce(), 1)
    assertEquals(adapter.ops.collect { case s: VendorOp.Send => s }.size, 0)
    assertEquals(
      adapter.ops.collect { case VendorOp.Edit(handle, _) => handle.messageId },
      List(source.messageId, source.messageId)
    )
    assertEquals(handled.get(), 1)
    withConnection: conn =>
      val st = conn.createStatement()
      try
        val rs = st.executeQuery("SELECT op, target, status, platform_message_id FROM outbox_messages")
        assert(rs.next())
        assertEquals(rs.getString("op"), "edit")
        assertEquals(ujson.read(rs.getString("target")).str, OutboxDispatcher.encodeHandle(source))
        assertEquals(rs.getString("status"), "sent")
        assertEquals(rs.getString("platform_message_id"), OutboxDispatcher.encodeHandle(source))
      finally st.close()

  test("a missing source creates one recorded replacement on synchronous recovery"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    val missing = upickle.default.writeJs(ChatError.Permanent("missing source", channelFatal = false))
    missing("reason") = ujson.Str("MissingTarget")
    adapter.failNext(upickle.default.read[ChatError.Permanent](missing))
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
        Reply(replace = Some(message("missing-source")))
    ox.supervised:
      val mediator = ChatMediator(pgUow, Map("fake" -> adapter), codec, handler, clock)
      mediator.push(inbound("missing-source-event"))
      await(pgUow.transaction(_.inboundEvents.find("fake", "missing-source-event")).exists(_.reply.isDefined))
      Thread.sleep(100)
    assertEquals(adapter.sent.size, 1, "missing edit target needs a replacement, not a lost outcome")
    assertEquals(count("rendered_messages"), 1L)
    val replacement = adapter.sent.head._1
    ox.supervised:
      val restarted = ChatMediator(pgUow, Map("fake" -> adapter), codec, handler, clock)
      restarted.push(inbound("missing-source-event"))
      await(adapter.ops.exists(_.isInstanceOf[VendorOp.Edit]))
    assertEquals(adapter.ops.collect { case VendorOp.Edit(handle, _) => handle.messageId }, List(replacement.messageId))
    assertEquals(adapter.sent.size, 1)
    val beforeUnknown = adapter.ops.count(_.isInstanceOf[VendorOp.Edit])
    val unreadable = FaultUow(pgUow, failAt = 2, afterCommit = false)
    ox.supervised:
      val restarted = ChatMediator(unreadable, Map("fake" -> adapter), codec, handler, clock)
      restarted.push(inbound("missing-source-event"))
      await(unreadable.faults.get() == 1)
      Thread.sleep(100)
    assertEquals(
      adapter.ops.count(_.isInstanceOf[VendorOp.Edit]),
      beforeUnknown,
      "unknown delivery handle must not replay against the missing original"
    )
    val later: Clock = () => now.plusSeconds(31)
    assertEquals(
      OutboxDispatcher(pgUow, Map("fake" -> adapter), CatalogueOutboxRenderer(pgUow, codec, later), later)
        .dispatchOnce(),
      0
    )

  test("lost commit acknowledgement with missing source records replacement for dispatcher and repeated replay"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    val handled = AtomicInteger(0)
    val calls = AtomicInteger(0)
    val fault = new UnitOfWork:
      override def transaction[A](f: Tx => A): A =
        val index = calls.incrementAndGet()
        val result = pgUow.transaction(f)
        if index == 1 then throw SQLException("synthetic commit acknowledgement lost")
        if index == 2 then
          adapter.failNext(ChatError.Permanent("missing source", false, ChatError.PermanentReason.MissingTarget))
        result
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
        handled.incrementAndGet()
        Reply(replace = Some(message("uncertain-missing")))
    ox.supervised:
      ChatMediator(fault, Map("fake" -> adapter), codec, handler, clock).push(inbound("uncertain-missing-event"))
      await(calls.get() >= 3)
    val replacement = adapter.ops.collectFirst:
      case VendorOp.Send(_, _, "uncertain-missing:replacement", _) => adapter.sent.last._1
    assert(replacement.isDefined)
    val encoded = OutboxDispatcher.encodeHandle(replacement.get)
    assertEquals(pgUow.transaction(_.outbox.recordedHandle("fake", "uncertain-missing")), Some(encoded))
    // Well beyond any nonce deduplication window: a stored replay must edit the durable replacement.
    val later: Clock = () => now.plusSeconds(86400)
    val replayCount = adapter.ops.count(_.isInstanceOf[VendorOp.Edit])
    ox.supervised:
      ChatMediator(pgUow, Map("fake" -> adapter), codec, handler, later).push(inbound("uncertain-missing-event"))
      await(adapter.ops.count(_.isInstanceOf[VendorOp.Edit]) > replayCount)
    assertEquals(
      OutboxDispatcher(pgUow, Map("fake" -> adapter), CatalogueOutboxRenderer(pgUow, codec, later), later).dispatchOnce(),
      0,
      "successful replay bookkeeping retires the original safety row"
    )
    assertEquals(adapter.ops.collect { case VendorOp.Send(_, _, "uncertain-missing:replacement", _) => 1 }.sum, 1)
    assert(adapter.ops.collect { case VendorOp.Edit(handle, _) => handle.messageId }.forall(_ == replacement.get.messageId))
    assertEquals(handled.get(), 1)
    assertEquals(count("inbound_events"), 1L)
    assertEquals(count("outbox_messages"), 1L)

  List(false, true).foreach: dispatcherFailure =>
    test(s"fatal MissingTarget never creates a persistent replacement (dispatcher=$dispatcherFailure)"):
      val pgUow = PgUnitOfWork(dataSource, clock)
      val adapter = FakeAdapter(CapabilityProfiles.Discord)
      adapter.failNext(
        if dispatcherFailure then ChatError.Retryable("synthetic outage")
        else ChatError.Permanent("fatal missing source", true, ChatError.PermanentReason.MissingTarget)
      )
      val calls = AtomicInteger(0)
      val tracking = new UnitOfWork:
        override def transaction[A](f: Tx => A): A =
          val result = pgUow.transaction(f)
          calls.incrementAndGet()
          result
      val handler = new ChatHandler:
        override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
          if event.vendorEventId == "fatal-missing-barrier" then Reply.empty
          else Reply(replace = Some(message("fatal-missing")))
      ox.supervised:
        val mediator = ChatMediator(tracking, Map("fake" -> adapter), codec, handler, clock)
        mediator.push(inbound("fatal-missing-event"))
        mediator.push(inbound("fatal-missing-barrier"))
        // The same-chat FIFO barrier commits only after the first delivery has finished.
        await(calls.get() >= 2)
      if dispatcherFailure then
        adapter.failNext(ChatError.Permanent("fatal missing source", true, ChatError.PermanentReason.MissingTarget))
        val later: Clock = () => now.plusSeconds(31)
        assertEquals(
          OutboxDispatcher(pgUow, Map("fake" -> adapter), CatalogueOutboxRenderer(pgUow, codec, later), later).dispatchOnce(),
          1
        )
        withConnection: conn =>
          val st = conn.createStatement()
          try
            val rs = st.executeQuery("SELECT status FROM outbox_messages")
            assert(rs.next())
            assertEquals(rs.getString("status"), "dead")
          finally st.close()
      assertEquals(adapter.ops.count(_.isInstanceOf[VendorOp.Send]), 0, "fatal errors cannot permit channel disclosure")
      assertEquals(pgUow.transaction(_.outbox.recordedHandle("fake", "fatal-missing")), None)
      assertEquals(count("rendered_messages"), 0L)

  test("dispatcher missing-source replacement resumes its recorded handle after terminal bookkeeping crash"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    adapter.failNext(ChatError.Retryable("synthetic edit outage"))
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
        Reply(replace = Some(message("dispatch-missing")))
    ox.supervised:
      val mediator = ChatMediator(pgUow, Map("fake" -> adapter), codec, handler, clock)
      mediator.push(inbound("dispatch-missing-event"))
      await(count("outbox_messages") == 1)
      Thread.sleep(100)
    adapter.failNext(ChatError.Permanent("missing source", false, ChatError.PermanentReason.MissingTarget))
    val later: Clock = () => now.plusSeconds(31)
    val fault = FaultUow(pgUow, failAt = 3, afterCommit = false)
    val first = OutboxDispatcher(fault, Map("fake" -> adapter), CatalogueOutboxRenderer(pgUow, codec, later), later)
    intercept[SQLException](first.dispatchOnce())
    assertEquals(adapter.sent.size, 1)
    assertEquals(count("rendered_messages"), 1L)
    val afterLease: Clock = () => now.plusSeconds(92)
    val restarted = PgUnitOfWork(dataSource, afterLease)
    val second = OutboxDispatcher(
      restarted,
      Map("fake" -> adapter),
      CatalogueOutboxRenderer(restarted, codec, afterLease),
      afterLease
    )
    assertEquals(second.dispatchOnce(), 1)
    assertEquals(adapter.sent.size, 1, "the persisted replacement handle must prevent another create")
    assertEquals(
      adapter.ops.collect { case VendorOp.Edit(handle, _) => handle.messageId },
      List(adapter.sent.head._1.messageId)
    )
    assertEquals(second.dispatchOnce(), 0)

  test("synchronous source edit replaces the stored callback map"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    val callback = codec.encode(CallbackMode.Direct, ActionRegistry.byName("menu.open").get, UUID.randomUUID(), 0).wire
    pgUow.transaction(
      _.renderedMessages.record(
        source,
        None,
        "interaction_reply",
        None,
        None,
        None,
        List(ChoiceMapEntry("old", 1, "Old", None, "old-token")),
        now
      )
    )
    val updated = message("updated-map").copy(blocks =
      List(Block.Choices(ChoiceSet("current", choices = List(Choice("Current", callback)))))
    )
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply = Reply(replace = Some(updated))
    ox.supervised:
      val mediator = ChatMediator(pgUow, Map("fake" -> adapter), codec, handler, clock)
      mediator.push(inbound("updated-map-event"))
      await(pgUow.transaction(_.outbox.recordedHandle("fake", "updated-map")).isDefined)
    assertEquals(
      pgUow.transaction(_.renderedMessages.choiceMapFor(source)).get.choiceMap.map(_.callback),
      List(callback)
    )

  test("unlinked reply destination survives restart without a primary delivery channel"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val fault = FaultUow(pgUow, failAt = 2, afterCommit = false)
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
        Reply(followUps = List(message("unlinked-reply").copy(replaces = None)))
    ox.supervised:
      val mediator = ChatMediator(fault, Map("fake" -> adapter), codec, handler, clock)
      mediator.push(inbound("unlinked-reply-event"))
      await(fault.faults.get() == 1)
    val later: Clock = () => now.plusSeconds(31)
    val restarted = PgUnitOfWork(dataSource, later)
    assertEquals(
      OutboxDispatcher(restarted, Map("fake" -> adapter), CatalogueOutboxRenderer(restarted, codec, later), later)
        .dispatchOnce(),
      1
    )
    assertEquals(adapter.sent.map(_._1.chatId), List(source.chatId, source.chatId))
    assertEquals(restarted.transaction(_.inboundEvents.find("fake", "unlinked-reply-event")).get.accountId, None)

  test("unavailable reconciliation is bounded and never claims noncommit"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val calls = AtomicInteger(0)
    val broken = new UnitOfWork:
      override def transaction[A](f: Tx => A): A =
        calls.incrementAndGet()
        throw SQLException("synthetic database unavailable")
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    val handled = AtomicInteger(0)
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
        handled.incrementAndGet()
        Reply.empty
    ox.supervised:
      val mediator = ChatMediator(broken, Map("fake" -> adapter), codec, handler, clock)
      mediator.push(inbound("unavailable-event"))
      await(calls.get() == 2)
    assertEquals(calls.get(), 2, "one write attempt and one readback, no blind retry loop")
    assertEquals(handled.get(), 0)
    assertEquals(adapter.sent.map(_._2.chunks), List(List("Checking whether this was recorded…")))
    assertEquals(count("inbound_events"), 0L)
    assertEquals(pgUow.transaction(_.outbox.recordedHandle("fake", "unavailable-event")), None)

  private final class PrivateHook(failReply: Boolean) extends InteractionHandle:
    val responses = new java.util.concurrent.ConcurrentLinkedQueue[RenderedMessage]()
    override def replyAvailable: Boolean = true
    override def acked: Boolean = true
    override def deferUpdate(): Unit = ()
    override def deferReply(ephemeral: Boolean): Unit = ()
    override def answer(toast: Option[String]): Unit = ()
    override def respond(rendered: RenderedMessage): MessageHandle =
      responses.add(rendered)
      if failReply then throw ChatError.Retryable("synthetic hook outage")
      MessageHandle("fake", source.chatId, "private-reply")
    override def editSource(rendered: RenderedMessage): Unit = fail("private response cannot edit a public source")
    override def openForm(form: RenderedForm): Unit = fail("not a modal")

  List(false, true).foreach: failHook =>
    test(s"private hook fail-closed recovery after restart (hook failure=$failHook)"):
      val pgUow = PgUnitOfWork(dataSource, clock)
      val fault = FaultUow(pgUow, failAt = 2, afterCommit = false)
      val adapter = FakeAdapter(CapabilityProfiles.Discord)
      val hook = PrivateHook(failHook)
      val privateMessage = message("private-outcome").copy(
        visibility = Visibility.Ephemeral,
        body = List(Node.Paragraph(List(Inline.Text("SYNTHETIC_PRIVATE_DETAIL"))))
      )
      val handler = new ChatHandler:
        override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
          Reply(replace = Some(privateMessage))
      ox.supervised:
        val mediator = ChatMediator(fault, Map("fake" -> adapter), codec, handler, clock)
        mediator.push(inbound("private-event").copy(interaction = Some(hook)))
        await(if failHook then !hook.responses.isEmpty else fault.faults.get() == 1)
      assertEquals(count("outbox_messages"), 0L)
      assert(adapter.ops.isEmpty, "private payload never reaches a channel send/edit")
      ox.supervised:
        val restarted = ChatMediator(pgUow, Map("fake" -> adapter), codec, handler, clock)
        restarted.push(inbound("private-event"))
        await(adapter.sent.nonEmpty)
      assertEquals(adapter.sent.size, 1)
      assert(!adapter.sent.head._2.chunks.mkString.contains("SYNTHETIC_PRIVATE_DETAIL"))
      assertEquals(adapter.ops.count(_.isInstanceOf[VendorOp.Edit]), 0)
      val later: Clock = () => now.plusSeconds(31)
      assertEquals(
        OutboxDispatcher(pgUow, Map("fake" -> adapter), CatalogueOutboxRenderer(pgUow, codec, later), later)
          .dispatchOnce(),
        0
      )

  test("source edit retries exhaust the existing attempt budget without named fallback"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    adapter.failNext(ChatError.Retryable("synthetic outage"))
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
        Reply(replace = Some(message("bounded-edit")))
    ox.supervised:
      val mediator = ChatMediator(pgUow, Map("fake" -> adapter), codec, handler, clock)
      mediator.push(inbound("bounded-edit-event"))
      await(count("outbox_messages") == 1)
      Thread.sleep(100)
    (1 to OutboxDispatcher.MaxAttempts).foreach: attempt =>
      adapter.failNext(ChatError.Retryable("synthetic outage"))
      val at: Clock = () => now.plusSeconds(attempt.toLong * 3600)
      assertEquals(
        OutboxDispatcher(
          pgUow,
          Map("fake" -> adapter),
          CatalogueOutboxRenderer(pgUow, codec, at),
          at,
          random = () => 0.0
        ).dispatchOnce(),
        1
      )
    val afterBudget: Clock = () => now.plusSeconds(100000)
    assertEquals(
      OutboxDispatcher(pgUow, Map("fake" -> adapter), CatalogueOutboxRenderer(pgUow, codec, afterBudget), afterBudget)
        .dispatchOnce(),
      0
    )
    assert(adapter.sent.isEmpty)
    withConnection: conn =>
      val st = conn.createStatement()
      try
        val rs = st.executeQuery("SELECT status, attempts FROM outbox_messages")
        assert(rs.next())
        assertEquals(rs.getString("status"), "dead")
        assertEquals(rs.getInt("attempts"), OutboxDispatcher.MaxAttempts)
      finally st.close()

  test("lost commit acknowledgement reconciles stored reply without asking to repeat a committed action"):
    val pgUow = PgUnitOfWork(dataSource, clock)
    val fault = FaultUow(pgUow, failAt = 1, afterCommit = true)
    val adapter = FakeAdapter(CapabilityProfiles.Discord)
    val handled = AtomicInteger(0)
    val handler = new ChatHandler:
      override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
        handled.incrementAndGet()
        val account = tx.accounts.createAccount(principal.identityId, "UTC", now)
        tx.moodCheckins.insert(
          NewMoodCheckin(
            UUID.randomUUID(),
            account.uuid,
            MoodLevel.unsafe(8),
            None,
            Nil,
            Some(principal.identityId.uuid),
            now
          )
        )
        Reply(
          replace = Some(message("uncertain-commit")),
          domainEvents = List(Event.MoodCheckinRecorded(account, MoodLevel.unsafe(8), None, Nil))
        )
    ox.supervised:
      val mediator = ChatMediator(fault, Map("fake" -> adapter), codec, handler, clock)
      mediator.push(inbound("uncertain-commit-event"))
      await(adapter.ops.exists(_.isInstanceOf[VendorOp.Edit]))
    val text = adapter.ops.collect { case VendorOp.Send(_, rendered, _, _) => rendered.chunks.mkString(" ") }
    assert(text.exists(_.contains("Checking whether this was recorded")))
    assert(!text.exists(_.contains("try again")), "a committed action must not be reported as failed")
    assertEquals(adapter.ops.collect { case VendorOp.Edit(_, _) => 1 }.sum, 1)
    ox.supervised:
      val restarted = ChatMediator(pgUow, Map("fake" -> adapter), codec, handler, clock)
      restarted.push(inbound("uncertain-commit-event"))
      await(adapter.ops.collect { case VendorOp.Edit(_, _) => 1 }.sum == 2)
    assertEquals(handled.get(), 1)
    assertEquals(count("inbound_events"), 1L)
    assertEquals(count("outbox_messages"), 1L)
    assertEquals(count("mood_checkins"), 1L)
    assertEquals(count("domain_events"), 1L)
