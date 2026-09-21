package dosecord.adapter.console

import dosecord.contracts.*
import dosecord.core.chat.ActionRegistry
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.CallbackMode
import dosecord.core.chat.ChatHandler
import dosecord.core.chat.ChatMediator
import dosecord.core.ports.*

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** M0.12c acceptance: `#3 1` typed at the console resolves against message 3's `choice_map` through the real
  * mediator (in-memory port fakes; the Postgres half of the resolution rules is covered by M0.12a's
  * infra ChatMediatorPgSuite).
  */
class ConsoleMediatorSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    assert(ok, clue)

  private def textMessage(text: String, event: InboundEvent): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(text)))),
      dedupeKey = s"echo:${event.vendorEventId}",
      correlationId = s"console:${event.vendorEventId}"
    )

  test("`#3 1` resolves against message 3's choice_map through the mediator"):
    ox.supervised:
      val out = ByteArrayOutputStream()
      val adapter =
        ConsoleAdapter("owner", BufferedReader(StringReader("")), PrintStream(out), "s1", () => t0)
      val uow = ConsoleMediatorFakes.InMemoryUnitOfWork()
      val tokenOne = codec.encode(CallbackMode.Direct, ActionRegistry.byName("menu.open").get, UUID.randomUUID(), 0).wire
      val tokenTwo = codec.encode(CallbackMode.Direct, ActionRegistry.byName("menu.open").get, UUID.randomUUID(), 0).wire
      val prompt = OutboundMessage(
        body = List(Node.Paragraph(List(Inline.Text("Pick one:")))),
        blocks = List(
          Block.Choices(ChoiceSet("cs", None, List(Choice("one", tokenOne), Choice("two", tokenTwo))))
        ),
        dedupeKey = "prompt-1",
        correlationId = "c-prompt-1"
      )
      val handler = ConsoleMediatorFakes.ScriptedHandler { event =>
        event.body match
          case Inbound.MessageReceived("menu", _, _) => Reply(followUps = List(prompt))
          case Inbound.MessageReceived(text, None, _) => Reply(followUps = List(textMessage(s"echo: $text", event)))
          case _ => Reply.empty
      }
      val mediator =
        ChatMediator(uow, Map("console" -> adapter), codec, handler, ConsoleMediatorFakes.FixedClock(t0))

      // Two plain replies, then the choice prompt: the prompt is console message 3.
      mediator.push(adapter.eventFor("first"))
      mediator.push(adapter.eventFor("second"))
      mediator.push(adapter.eventFor("menu"))
      await(out.toString.contains("[#3]"), s"the prompt was printed as message 3:\n$out")
      assert(out.toString.contains("[#1] echo: first"))
      assert(out.toString.contains("[#2] echo: second"))
      assert(out.toString.contains("1) one 2) two"), s"the numbered legend is in the text:\n$out")

      mediator.push(adapter.eventFor("#3 1"))
      await(
        handler.calls.asScala.exists(_._1.vendorEventId == "console:msg:s1:4"),
        "the `#3 1` reply was handled"
      )
      val resolved = handler.calls.asScala.collectFirst {
        case (e, _) if e.vendorEventId == "console:msg:s1:4" => e.body
      }.get
      resolved match
        case Inbound.InteractionSubmitted(callback, _, source) =>
          assertEquals(callback.raw, tokenOne, "index 1 resolves to the first choice's token")
          assertEquals(
            source,
            Some(MessageHandle("console", "dm:owner", "3")),
            "the resolution source is message 3"
          )
        case other => fail(s"expected an InteractionSubmitted resolved from message 3, got $other")

  test("`#9 1` against a message without a choice_map passes through as plain text"):
    ox.supervised:
      val out = ByteArrayOutputStream()
      val adapter =
        ConsoleAdapter("owner", BufferedReader(StringReader("")), PrintStream(out), "s1", () => t0)
      val uow = ConsoleMediatorFakes.InMemoryUnitOfWork()
      val handler = ConsoleMediatorFakes.ScriptedHandler { event =>
        event.body match
          case Inbound.MessageReceived(text, None, _) => Reply(followUps = List(textMessage(s"echo: $text", event)))
          case _ => Reply.empty
      }
      val mediator =
        ChatMediator(uow, Map("console" -> adapter), codec, handler, ConsoleMediatorFakes.FixedClock(t0))

      mediator.push(adapter.eventFor("#9 1"))
      await(handler.calls.asScala.nonEmpty, "the event was handled")
      val body = handler.calls.asScala.head._1.body
      assert(
        body.isInstanceOf[Inbound.MessageReceived],
        s"nothing resolves against an unknown message: the text reaches the handler, got $body"
      )

/** In-memory port fakes for the mediator integration test (the shared M0.12a harness lives in core's test
  * sources, which are not on this module's classpath).
  */
private object ConsoleMediatorFakes:

  final class FixedClock(at: Instant) extends Clock:
    override def now(): Instant = at

  final class ScriptedHandler(reply: InboundEvent => Reply) extends ChatHandler:
    val calls = ConcurrentLinkedQueue[(InboundEvent, Principal)]()
    override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
      calls.add((event, principal))
      reply(event)

  final class InMemoryInboundEvents extends InboundEventRepository:
    private val rows = scala.collection.mutable.LinkedHashMap.empty[(String, String), StoredInboundEvent]
    override def insert(event: NewInboundEvent): Boolean = this.synchronized:
      val key = (event.vendor, event.vendorEventId)
      if rows.contains(key) then false
      else
        rows += key -> StoredInboundEvent(
          event.vendor,
          event.vendorEventId,
          event.eventId,
          accountId = None,
          event.receivedAt,
          reply = None,
          processedAt = None
        )
        true
    override def find(vendor: String, vendorEventId: String): Option[StoredInboundEvent] =
      this.synchronized(rows.get((vendor, vendorEventId)))
    override def complete(
        vendor: String,
        vendorEventId: String,
        accountId: Option[UUID],
        reply: String,
        processedAt: Instant
    ): Unit = this.synchronized:
      rows.updateWith((vendor, vendorEventId))(
        _.map(_.copy(accountId = accountId, reply = Some(reply), processedAt = Some(processedAt)))
      )
      ()

  final class InMemoryIdentities extends IdentityRepository:
    private val principals = scala.collection.mutable.Map.empty[(String, String), Principal]
    override def resolve(actor: PlatformIdentity, now: Instant): Principal = this.synchronized:
      principals.getOrElseUpdate(
        (actor.vendor, actor.vendorUserId),
        Principal(IdentityId(UUID.randomUUID()), None, linked = false)
      )
    override def find(actor: PlatformIdentity): Option[Principal] =
      this.synchronized(principals.get((actor.vendor, actor.vendorUserId)))

  final class InMemoryRenderedMessages extends RenderedMessageRepository:
    private val rows = ListBuffer.empty[(MessageHandle, List[ChoiceMapEntry], Boolean)]
    override def record(
        handle: MessageHandle,
        accountId: Option[UUID],
        kind: String,
        subjectType: Option[String],
        subjectId: Option[UUID],
        epoch: Option[Int],
        choiceMap: List[ChoiceMapEntry],
        now: Instant
    ): Boolean = this.synchronized:
      rows += ((handle, choiceMap, false))
      true
    override def choiceMapFor(handle: MessageHandle): Option[RenderedChoiceMap] = this.synchronized:
      rows.collectFirst { case (h, map, removed) if h == handle =>
        RenderedChoiceMap(h, h.revision, map, removed)
      }
    override def latestPendingPrompt(vendor: String, chatId: String): Option[RenderedChoiceMap] = this.synchronized:
      rows.toList.reverse.collectFirst {
        case (h, map, removed) if h.vendor == vendor && h.chatId == chatId && map.nonEmpty && !removed =>
          RenderedChoiceMap(h, h.revision, map, controlsRemoved = false)
      }

  final class InMemoryOutbox extends OutboxRepository:
    override def enqueue(msg: NewOutboxMessage): Boolean = true
    override def claim(now: Instant, vendors: Seq[String], limit: Int, lease: Duration): List[OutboxMessage] = Nil
    override def recordHandle(id: UUID, encodedHandle: String): Unit = ()
    override def markOpDone(id: UUID, opIndex: Int): Unit = ()
    override def markSent(id: UUID, encodedHandle: String, now: Instant, possibleDuplicate: Boolean): Unit = ()
    override def retry(id: UUID, at: Instant, possibleDuplicate: Boolean, error: String): Unit = ()
    override def failPermanently(id: UUID, error: String): Unit = ()
    override def deliveredFor(occurrenceId: UUID): Boolean = false
    override def cancelOlderQueued(occurrenceId: UUID, epoch: Int): Int = 0

  final class InMemorySessions extends SessionRepository:
    override def loadForUpdate(principalKey: String, vendor: String, chatId: String): Option[ConversationSession] =
      None
    override def insert(session: ConversationSession): Unit = ()
    override def save(session: ConversationSession): Unit = ()
    override def delete(id: UUID): Unit = ()
    override def expiring(now: Instant, limit: Int): List[ConversationSession] = Nil
    override def resumable(now: Instant, limit: Int): List[ConversationSession] = Nil

  /** The console suite never touches wizard slots, form runs, or the medication repositories. */
  private object UnusedPorts:
    val slots: CallbackSlotRepository = new CallbackSlotRepository:
      override def insert(slot: CallbackSlot): Unit = ()
      override def loadForUpdate(id: UUID): Option[CallbackSlot] = None
      override def deleteForSession(sessionId: UUID): Int = 0
    val formRuns: FormRunRepository = new FormRunRepository:
      override def insert(run: FormRun): Unit = ()
      override def loadForUpdate(sessionId: UUID): Option[FormRun] = None
      override def save(run: FormRun): Unit = ()
      override def delete(sessionId: UUID): Unit = ()
    val medications: MedicationRepository = ???
    val schedules: ScheduleRepository = ???
    val revisions: ScheduleRevisionRepository = ???
    val occurrences: OccurrenceRepository = ???
    val doseActions: DoseActionRepository = ???

  final class InMemoryUnitOfWork extends UnitOfWork:
    private val inboundEvents = InMemoryInboundEvents()
    private val identities = InMemoryIdentities()
    private val renderedMessages = InMemoryRenderedMessages()
    private val outbox = InMemoryOutbox()
    private val sessions = InMemorySessions()

    private val tx: Tx = new Tx:
      override def sessions: SessionRepository = InMemoryUnitOfWork.this.sessions
      override def outbox: OutboxRepository = InMemoryUnitOfWork.this.outbox
      override def renderedMessages: RenderedMessageRepository = InMemoryUnitOfWork.this.renderedMessages
      override def inboundEvents: InboundEventRepository = InMemoryUnitOfWork.this.inboundEvents
      override def domainEvents: DomainEventRepository = _ => ()
      override def identities: IdentityRepository = InMemoryUnitOfWork.this.identities
      override def audit: AuditRepository = _ => ()
      override def slots: CallbackSlotRepository = UnusedPorts.slots
      override def formRuns: FormRunRepository = UnusedPorts.formRuns
      override def medications: MedicationRepository = UnusedPorts.medications
      override def schedules: ScheduleRepository = UnusedPorts.schedules
      override def revisions: ScheduleRevisionRepository = UnusedPorts.revisions
      override def occurrences: OccurrenceRepository = UnusedPorts.occurrences
      override def doseActions: DoseActionRepository = UnusedPorts.doseActions

    override def transaction[A](f: Tx => A): A = f(tx)
