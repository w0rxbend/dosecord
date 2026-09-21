package dosecord.core.chat

import dosecord.contracts.*
import dosecord.core.ports.*

import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** In-memory harness for the ChatMediator unit tests (suite B2 machinery): one fake per port, a recording
  * InteractionHandle and a recording handler.
  */
object MediatorFakes:

  final class FixedClock(var at: Instant) extends Clock:
    override def now(): Instant = at

  final class InMemoryInboundEvents extends InboundEventRepository:
    private val rows = scala.collection.mutable.LinkedHashMap.empty[(String, String), StoredInboundEvent]
    def all: List[StoredInboundEvent] = this.synchronized(rows.values.toList)

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
      val key = (vendor, vendorEventId)
      rows.updateWith(key)(_.map(_.copy(accountId = accountId, reply = Some(reply), processedAt = Some(processedAt))))
      ()

  final class InMemoryDomainEvents extends DomainEventRepository:
    private val appended = ListBuffer.empty[NewDomainEvent]
    def all: List[NewDomainEvent] = this.synchronized(appended.toList)
    override def append(event: NewDomainEvent): Unit = this.synchronized(appended += event)

  final class InMemoryIdentities extends IdentityRepository:
    private val principals = scala.collection.mutable.Map.empty[(String, String), Principal]

    /** Pre-links an actor to an account, as an existing `platform_identities` row would. */
    def link(actor: PlatformIdentity, accountId: UUID): Unit = this.synchronized:
      principals((actor.vendor, actor.vendorUserId)) =
        Principal(IdentityId(UUID.randomUUID()), Some(AccountId(accountId)), linked = true)

    override def resolve(actor: PlatformIdentity, now: Instant): Principal = this.synchronized:
      principals.getOrElseUpdate(
        (actor.vendor, actor.vendorUserId),
        Principal(IdentityId(UUID.randomUUID()), None, linked = false)
      )

    override def find(actor: PlatformIdentity): Option[Principal] =
      this.synchronized(principals.get((actor.vendor, actor.vendorUserId)))

  final class InMemoryAudit extends AuditRepository:
    private val entries = ListBuffer.empty[AuditEntry]
    def all: List[AuditEntry] = this.synchronized(entries.toList)
    override def append(entry: AuditEntry): Unit = this.synchronized(entries += entry)

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

    /** Test hook: finalize a message (controls removed). */
    def removeControls(handle: MessageHandle): Unit = this.synchronized:
      rows.mapInPlace((h, map, removed) => (h, map, removed || h == handle))

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
    private val enqueued = ListBuffer.empty[NewOutboxMessage]
    private val sent = ListBuffer.empty[UUID]
    def allEnqueued: List[NewOutboxMessage] = this.synchronized(enqueued.toList)
    def allSent: List[UUID] = this.synchronized(sent.toList)
    override def enqueue(msg: NewOutboxMessage): Boolean = this.synchronized:
      enqueued += msg
      true
    override def claim(now: Instant, vendors: Seq[String], limit: Int, lease: Duration): List[OutboxMessage] = Nil
    override def recordHandle(id: UUID, encodedHandle: String): Unit = ()
    override def markOpDone(id: UUID, opIndex: Int): Unit = ()
    override def markSent(id: UUID, encodedHandle: String, now: Instant, possibleDuplicate: Boolean): Unit =
      this.synchronized(sent += id)
    override def retry(id: UUID, at: Instant, possibleDuplicate: Boolean, error: String): Unit = ()
    override def failPermanently(id: UUID, error: String): Unit = ()

  final class InMemorySessions extends SessionRepository:
    private val rows = scala.collection.mutable.LinkedHashMap.empty[UUID, ConversationSession]
    def all: List[ConversationSession] = this.synchronized(rows.values.toList)

    override def loadForUpdate(principalKey: String, vendor: String, chatId: String): Option[ConversationSession] =
      this.synchronized(rows.values.find(s =>
        s.principalKey == principalKey && s.vendor == vendor && s.chatId == chatId
      ))

    override def insert(session: ConversationSession): Unit = this.synchronized(rows += session.id -> session)

    override def save(session: ConversationSession): Unit = this.synchronized:
      rows.get(session.id) match
        case Some(stored) if stored.version == session.version =>
          rows += session.id -> session.copy(version = session.version + 1, updatedAt = Instant.now())
        case Some(_) => throw StaleSessionVersion(session.id, session.version)
        case None    => throw StaleSessionVersion(session.id, session.version)

    override def delete(id: UUID): Unit = this.synchronized:
      rows -= id
      ()

    override def expiring(now: Instant, limit: Int): List[ConversationSession] = this.synchronized:
      rows.values.filter(_.expiresAt.compareTo(now) <= 0).toList.sortBy(_.expiresAt).take(limit)

    override def resumable(now: Instant, limit: Int): List[ConversationSession] = this.synchronized:
      rows.values
        .filter(s => s.lastPrompt.isDefined && s.expiresAt.isAfter(now))
        .toList
        .sortBy(_.updatedAt)
        .take(limit)

  final class InMemorySlots extends CallbackSlotRepository:
    private val rows = scala.collection.mutable.LinkedHashMap.empty[UUID, CallbackSlot]
    def all: List[CallbackSlot] = this.synchronized(rows.values.toList)
    override def insert(slot: CallbackSlot): Unit = this.synchronized(rows += slot.id -> slot)
    override def loadForUpdate(id: UUID): Option[CallbackSlot] = this.synchronized(rows.get(id))
    override def deleteForSession(sessionId: UUID): Int = this.synchronized:
      val doomed = rows.values.filter(_.sessionId.contains(sessionId)).map(_.id).toList
      doomed.foreach(rows -= _)
      doomed.size

  final class InMemoryFormRuns extends FormRunRepository:
    private val rows = scala.collection.mutable.LinkedHashMap.empty[UUID, FormRun]
    def all: List[FormRun] = this.synchronized(rows.values.toList)
    override def insert(run: FormRun): Unit = this.synchronized(rows += run.sessionId -> run)
    override def loadForUpdate(sessionId: UUID): Option[FormRun] = this.synchronized(rows.get(sessionId))
    override def save(run: FormRun): Unit = this.synchronized(rows += run.sessionId -> run)
    override def delete(sessionId: UUID): Unit = this.synchronized:
      rows -= sessionId
      ()

  final class InMemoryUnitOfWork extends UnitOfWork:
    val inboundEvents = InMemoryInboundEvents()
    val domainEvents = InMemoryDomainEvents()
    val identities = InMemoryIdentities()
    val audit = InMemoryAudit()
    val renderedMessages = InMemoryRenderedMessages()
    val outbox = InMemoryOutbox()
    val sessions = InMemorySessions()
    val slots = InMemorySlots()
    val formRuns = InMemoryFormRuns()

    private val tx: Tx = new Tx:
      override def sessions: SessionRepository = InMemoryUnitOfWork.this.sessions
      override def outbox: OutboxRepository = InMemoryUnitOfWork.this.outbox
      override def renderedMessages: RenderedMessageRepository = InMemoryUnitOfWork.this.renderedMessages
      override def inboundEvents: InboundEventRepository = InMemoryUnitOfWork.this.inboundEvents
      override def domainEvents: DomainEventRepository = InMemoryUnitOfWork.this.domainEvents
      override def identities: IdentityRepository = InMemoryUnitOfWork.this.identities
      override def audit: AuditRepository = InMemoryUnitOfWork.this.audit
      override def slots: CallbackSlotRepository = InMemoryUnitOfWork.this.slots
      override def formRuns: FormRunRepository = InMemoryUnitOfWork.this.formRuns

    override def transaction[A](f: Tx => A): A = f(tx)

  /** Every transaction fails as it would with the connection pool paused (R68). */
  final class PoolPausedUow extends UnitOfWork:
    override def transaction[A](f: Tx => A): A =
      throw new SQLTransientConnectionException("pool is paused")

  final class RecordingInteractionHandle extends InteractionHandle:
    val calls = new ConcurrentLinkedQueue[String]()
    @volatile private var ackedFlag = false
    override def deferUpdate(): Unit =
      calls.add("deferUpdate")
      ackedFlag = true
    override def deferReply(ephemeral: Boolean): Unit =
      calls.add(s"deferReply($ephemeral)")
      ackedFlag = true
    override def answer(toast: Option[String]): Unit =
      calls.add(s"answer(${toast.getOrElse("")})")
      ackedFlag = true
    override def respond(rendered: RenderedMessage): MessageHandle =
      calls.add(s"respond(${rendered.chunks.mkString(" ")})")
      MessageHandle("fake", "chat-1", "r1")
    override def editSource(rendered: RenderedMessage): Unit =
      calls.add(s"editSource(${rendered.chunks.mkString(" ")})")
    override def openForm(form: RenderedForm): Unit =
      calls.add(s"openForm(${form.id})")
      ackedFlag = true
    override def acked: Boolean = ackedFlag

  final class RecordingHandler(reply: InboundEvent => Reply) extends ChatHandler:
    val received = new ConcurrentLinkedQueue[(InboundEvent, Principal)]()
    private val count = AtomicInteger(0)
    def calls: List[(InboundEvent, Principal)] = received.asScala.toList
    def handled: Int = count.get()
    override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
      received.add((event, principal))
      count.incrementAndGet()
      reply(event)
