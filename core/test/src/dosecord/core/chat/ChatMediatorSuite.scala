package dosecord.core.chat

import dosecord.contracts.*
import dosecord.core.chat.MediatorFakes.*
import dosecord.core.domain.copy.ReminderCopy

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import scala.jdk.CollectionConverters.*

/** M0.12a mediator unit tests on in-memory fakes (the Testcontainers half lives in infra): per-chat ordering, the
  * principal boundary, the bad-MAC rejection, the resolution rules, the stored reply/account id and the failure
  * toast.
  */
class ChatMediatorSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  private val actor = PlatformIdentity("fake", "user-1", Some("display"))

  private def token(action: String, subject: UUID, value: Long = 0): String =
    codec.encode(CallbackMode.Direct, ActionRegistry.byName(action).get, subject, value).wire

  private def callbackRef(raw: String): CallbackRef =
    val payload = codec.decode(raw).toOption.get
    CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, raw)

  /** Claims the fields of a valid token but carries a tampered raw form. */
  private def tamperedRef(raw: String): CallbackRef =
    val payload = codec.decode(raw).toOption.get
    val tampered = raw.updated(10, if raw(10) == 'A' then 'B' else 'A')
    CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, tampered)

  private def event(
      body: Inbound,
      vendorEventId: String,
      chatId: String = "chat-1",
      interaction: Option[InteractionHandle] = None
  ): InboundEvent =
    InboundEvent(
      EventId(UUID.randomUUID()),
      "fake",
      vendorEventId,
      t0,
      actor = actor,
      chat = ChatRef("fake", chatId),
      principal = None,
      body = body,
      interaction = interaction
    )

  private def outbound(text: String, dedupeKey: String): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(text)))),
      dedupeKey = dedupeKey,
      correlationId = s"c-$dedupeKey"
    )

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    assert(ok, clue)

  /** Synchronized window over FakeAdapter's unsynchronized recording buffers. */
  private final class SyncAdapter(val inner: FakeAdapter) extends ChatAdapter:
    private def around[A](f: => A): A = inner.synchronized(f)
    override def vendor: String = inner.vendor
    override def capabilities: CapabilityProfile = inner.capabilities
    override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = around(inner.start(sink, resumeFrom))
    override def stop(): Unit = around(inner.stop())
    override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle = around(
      inner.send(chat, rendered, sendKey)
    )
    override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle = around(
      inner.edit(handle, rendered)
    )
    override def delete(handle: MessageHandle): Unit = around(inner.delete(handle))
    override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit = around(
      inner.react(handle, emoji, on, txnKey)
    )
    override def registerCommands(specs: List[CommandSpec]): Unit = around(inner.registerCommands(specs))
    override def resolveChat(identity: PlatformIdentity): ChatRef = around(inner.resolveChat(identity))
    override def renderText(text: RichText): List[String] = around(inner.renderText(text))

  private def sendsOf(adapter: SyncAdapter): List[VendorOp.Send] =
    adapter.inner.synchronized(adapter.inner.ops.collect { case s: VendorOp.Send => s })

  private def mediator(
      uow: InMemoryUnitOfWork,
      adapter: SyncAdapter,
      handler: ChatHandler,
      stripes: Int = 16
  )(using ox.Ox): ChatMediator =
    ChatMediator(uow, Map("fake" -> adapter), codec, handler, FixedClock(t0), stripes = stripes)

  // Acceptance 1 (unit half): the same vendor_event_id twice yields one row and the replayed reply.
  test("duplicate vendor_event_id yields one inbound row, one handler call and the replayed reply"):
    ox.supervised:
      val uow = InMemoryUnitOfWork()
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val handler = RecordingHandler(_ => Reply(followUps = List(outbound("hello", "reply-1"))))
      val m = mediator(uow, adapter, handler)
      val first = event(Inbound.MessageReceived("hi", None, truncated = false), "evt-1")

      m.push(first)
      await(sendsOf(adapter).size == 1, "first reply delivered")
      await(
        uow.inboundEvents.find("fake", "evt-1").exists(_.processedAt.isDefined),
        "first delivery processed"
      )
      assertEquals(handler.handled, 1)

      m.push(first)
      await(sendsOf(adapter).size == 2, "stored reply replayed")
      assertEquals(handler.handled, 1, "the handler must not re-run on a duplicate")
      assertEquals(uow.inboundEvents.all.size, 1, "one inbound_events row")
      assertEquals(sendsOf(adapter).map(_.message.chunks), List(List("hello"), List("hello")))

  // Acceptance 2: per-chat order preserved under interleaved Ox forks.
  test("per-chat order preserved under interleaved forks"):
    ox.supervised:
      val uow = InMemoryUnitOfWork()
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val handler = RecordingHandler(_ => Reply.empty)
      val m = mediator(uow, adapter, handler, stripes = 2)
      val perChat = 25

      val pushChat = (chatId: String) =>
        (1 to perChat).foreach: i =>
          m.push(event(Inbound.MessageReceived(s"$i", None, truncated = false), s"$chatId-$i", chatId = chatId))
      val t1 = new Thread(() => pushChat("chat-a"))
      val t2 = new Thread(() => pushChat("chat-b"))
      t1.start(); t2.start(); t1.join(30000); t2.join(30000)

      await(handler.handled == 2 * perChat, "all events handled")
      List("chat-a", "chat-b").foreach: chatId =>
        val seen = handler.calls.collect:
          case (e, _) if e.chat.chatId == chatId =>
            e.body.asInstanceOf[Inbound.MessageReceived].text.toInt
        assertEquals(seen, (1 to perChat).toList, s"$chatId order must be the push order")

  // Acceptance 3: an event without a stamped principal is rejected by the core.
  test("an event without a stamped principal is rejected by the core"):
    ox.supervised:
      val uow = InMemoryUnitOfWork()
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val handler = RecordingHandler(_ => Reply.empty)
      val m = mediator(uow, adapter, handler)
      val unstamped = event(Inbound.MessageReceived("hi", None, truncated = false), "evt-unstamped")
      assert(unstamped.principal.isEmpty)
      intercept[MissingPrincipal](uow.transaction(tx => m.dispatchCore(unstamped, tx)))
      assertEquals(handler.handled, 0, "nothing reaches the handler without a principal")

  // Acceptance 4 (unit half): a tampered token yields the toast and an audit row and nothing reaches the core.
  test("a tampered token yields the toast and an audit row; nothing reaches the core"):
    ox.supervised:
      val uow = InMemoryUnitOfWork()
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Discord))
      val handler = RecordingHandler(_ => Reply.empty)
      val m = mediator(uow, adapter, handler)
      val handle = RecordingInteractionHandle()
      val tap = event(
        Inbound.InteractionSubmitted(tamperedRef(token("dose.taken", UUID.randomUUID())), Nil, None),
        "evt-tampered",
        interaction = Some(handle)
      )

      m.push(tap)
      assert(
        handle.calls.asScala.exists(_.contains(ReminderCopy.staleControlToast)),
        s"the catalogue toast was delivered: ${handle.calls.asScala.mkString}"
      )
      assertEquals(uow.audit.all.size, 1, "one audit row")
      assertEquals(uow.audit.all.head.kind, "callback_tampered")
      assertEquals(handler.handled, 0, "nothing reaches the core")
      assert(uow.inboundEvents.all.isEmpty, "no inbound row is recorded")

  test("a verified component tap is acked with deferUpdate before any database work"):
    ox.supervised:
      val uow = InMemoryUnitOfWork()
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Discord))
      val latch = CountDownLatch(1)
      val handler = RecordingHandler { _ =>
        latch.await()
        Reply.empty
      }
      val m = mediator(uow, adapter, handler)
      val handle = RecordingInteractionHandle()
      val tap = event(
        Inbound.InteractionSubmitted(callbackRef(token("dose.taken", UUID.randomUUID())), Nil, None),
        "evt-ack",
        interaction = Some(handle)
      )

      m.push(tap)
      await(handle.acked, "the ack lands before the handler returns")
      assertEquals(handle.calls.peek, "deferUpdate", "a component tap defers with an update (ADR-013)")
      latch.countDown()
      await(handler.handled == 1, "handler ran")

  // Acceptance 5 (unit half): the stored reply and the domain_events row carry the resolved account_id; one
  // domain_events row with the expected type and source.
  test("stored reply and domain event carry the resolved account id; one row with type and source"):
    ox.supervised:
      val uow = InMemoryUnitOfWork()
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val accountId = UUID.randomUUID()
      uow.identities.link(actor, accountId)
      val domainEvent = Event.MoodCheckinRecorded(AccountId(accountId), MoodLevel.unsafe(8), None, Nil)
      val handler = RecordingHandler(_ => Reply(followUps = List(outbound("logged", "reply-mood")), domainEvents = List(domainEvent)))
      val m = mediator(uow, adapter, handler)

      m.push(event(Inbound.CommandInvoked("mood", Map("level" -> "8"), "/mood 8"), "evt-mood"))
      await(uow.inboundEvents.find("fake", "evt-mood").exists(_.processedAt.isDefined), "processed")

      val stored = uow.inboundEvents.all.head
      assertEquals(stored.accountId, Some(accountId), "the stored reply carries the resolved account_id")
      assert(stored.reply.exists(_.contains("logged")), "the reply is stored")

      val events = uow.domainEvents.all
      assertEquals(events.size, 1, "exactly one domain_events row")
      assertEquals(events.head.eventType, Event.MoodCheckinRecordedType)
      assertEquals(events.head.source, "dosecord.mood")
      assertEquals(events.head.accountId, Some(accountId))
      assertEquals(events.head.correlationId, Some("fake:evt-mood"))

  // Acceptance 6 (unit half): quoted-reply numbers resolve by target unconditionally; bare digits and hotkeys
  // against the latest pending prompt only; reactions resolve by target.
  test("quoted and bare '2' resolve to the same token when the prompt is latest; only the quoted one when not"):
    ox.supervised:
      val uow = InMemoryUnitOfWork()
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val handler = RecordingHandler(_ => Reply.empty)
      val m = mediator(uow, adapter, handler)
      val chat = "chat-1"

      def prompt(handle: MessageHandle, first: String, second: String): Unit =
        uow.renderedMessages.record(
          handle,
          None,
          kind = "prompt",
          subjectType = None,
          subjectId = None,
          epoch = None,
          choiceMap = List(
            ChoiceMapEntry("cs", 1, "one", Some("1️⃣"), first),
            ChoiceMapEntry("cs", 2, "two", Some("2️⃣"), second)
          ),
          t0
        )

      val handleA = MessageHandle("fake", chat, "mA")
      val tokenA1 = token("menu.open", UUID.randomUUID())
      val tokenA2 = token("menu.open", UUID.randomUUID())
      prompt(handleA, tokenA1, tokenA2)

      def resolvedCallback(vendorEventId: String): CallbackRef =
        await(handler.calls.exists(_._1.vendorEventId == vendorEventId), s"$vendorEventId handled")
        handler.calls.collectFirst:
          case (e, _) if e.vendorEventId == vendorEventId => e.body.asInstanceOf[Inbound.InteractionSubmitted].callback
        .get

      // Prompt A is the latest: bare "2" and quoted "2" resolve to the same token.
      m.push(event(Inbound.MessageReceived("2", None, truncated = false), "bare-1"))
      m.push(event(Inbound.MessageReceived("2", Some(handleA), truncated = false), "quoted-1"))
      assertEquals(resolvedCallback("bare-1"), callbackRef(tokenA2))
      assertEquals(resolvedCallback("quoted-1"), callbackRef(tokenA2))

      // Prompt B becomes the latest: bare "2" resolves against B; the quoted reply still resolves against A.
      val handleB = MessageHandle("fake", chat, "mB")
      val tokenB1 = token("menu.open", UUID.randomUUID())
      val tokenB2 = token("menu.open", UUID.randomUUID())
      prompt(handleB, tokenB1, tokenB2)

      m.push(event(Inbound.MessageReceived("2", None, truncated = false), "bare-2"))
      m.push(event(Inbound.MessageReceived("2", Some(handleA), truncated = false), "quoted-2"))
      assertEquals(resolvedCallback("bare-2"), callbackRef(tokenB2), "bare digits hit the latest prompt only")
      assertEquals(resolvedCallback("quoted-2"), callbackRef(tokenA2), "quoted replies resolve by target")

      // Reactions resolve by target unconditionally; a removal is ignored.
      m.push(event(Inbound.ReactionChanged("2️⃣", handleA, added = true), "reaction-1"))
      assertEquals(resolvedCallback("reaction-1"), callbackRef(tokenA2))
      m.push(event(Inbound.ReactionChanged("2️⃣", handleA, added = false), "reaction-2"))
      Thread.sleep(200)
      assert(handler.calls.forall(_._1.vendorEventId != "reaction-2"), "a reaction removal is ignored")

      // Once every prompt's controls are removed, a bare "2" is not eaten: it passes through as text.
      uow.renderedMessages.removeControls(handleA)
      uow.renderedMessages.removeControls(handleB)
      m.push(event(Inbound.MessageReceived("2", None, truncated = false), "bare-3"))
      await(handler.calls.exists(_._1.vendorEventId == "bare-3"), "bare-3 handled")
      val bare3 = handler.calls.collectFirst:
        case (e, _) if e.vendorEventId == "bare-3" => e.body
      assert(
        bare3.exists(_.isInstanceOf[Inbound.MessageReceived]),
        "a finalized prompt does not capture digits: the text reaches the handler unresolved"
      )

  // Acceptance 7 (unit half): with the pool paused the tap yields the catalogue failure toast.
  test("a tap with the pool paused yields the catalogue failure toast"):
    ox.supervised:
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Discord))
      val handler = RecordingHandler(_ => Reply.empty)
      val m = ChatMediator(PoolPausedUow(), Map("fake" -> adapter), codec, handler, FixedClock(t0))
      val handle = RecordingInteractionHandle()
      val tap = event(
        Inbound.InteractionSubmitted(callbackRef(token("dose.taken", UUID.randomUUID())), Nil, None),
        "evt-paused",
        interaction = Some(handle)
      )

      m.push(tap)
      await(
        handle.calls.asScala.exists(_.contains(ReminderCopy.failureToast)),
        s"the failure toast was delivered: ${handle.calls.asScala.mkString}"
      )
      assertEquals(handle.calls.peek, "deferUpdate", "the ack still lands before the database work")
      assertEquals(handler.handled, 0)
