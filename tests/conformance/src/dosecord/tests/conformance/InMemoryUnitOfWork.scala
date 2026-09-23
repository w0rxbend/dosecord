package dosecord.tests.conformance

import dosecord.contracts.*
import dosecord.core.ports.*

import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ListBuffer

/** In-memory port fakes for suite B2 (the mediator-resolution harness). Mirrors core's test `MediatorFakes` — test
  * sources are not shared across modules — trimmed to the ports the B2 scenarios touch: inbound events, identities,
  * rendered messages (choice maps), outbox, sessions, slots, form runs and audit.
  */
final class InMemoryUnitOfWork extends UnitOfWork:
  val inboundEvents = InMemoryInboundEvents()
  val identities = InMemoryIdentities()
  val renderedMessages = InMemoryRenderedMessages()
  val sessions = InMemorySessions()
  val slots = InMemorySlots()
  val formRuns = InMemoryFormRuns()
  val audit = InMemoryAudit()

  private object UnusedPorts:
    val accounts: AccountRepository = ???
    val moodCheckins: MoodCheckinRepository = ???
    val medications: MedicationRepository = ???
    val schedules: ScheduleRepository = ???
    val revisions: ScheduleRevisionRepository = ???
    val occurrences: OccurrenceRepository = ???
    val doseActions: DoseActionRepository = ???
    val channels: DeliveryChannelRepository = new DeliveryChannelRepository:
      override def activePrimaryChannels(accountId: UUID): List[DeliveryTarget] = Nil
      override def markDead(channelId: UUID, error: String, now: Instant): Unit = ()
      override def fallbackChannel(accountId: UUID, excludeChannelId: UUID): Option[DeliveryTarget] = None
      override def byId(channelId: UUID): Option[DeliveryTarget] = None
    val heartbeat: WorkerHeartbeatRepository = new WorkerHeartbeatRepository:
      override def touch(instance: String, role: String, now: Instant): Unit = ()
      override def maxLastTick(): Option[Instant] = None

  private val outbox = new OutboxRepository:
    override def enqueue(msg: NewOutboxMessage): Boolean = true
    override def enqueueDigest(msg: NewOutboxMessage): Boolean = true
    override def claim(now: Instant, vendors: Seq[String], limit: Int, lease: Duration): List[OutboxMessage] = Nil
    override def recordHandle(id: UUID, encodedHandle: String): Unit = ()
    override def markOpDone(id: UUID, opIndex: Int): Unit = ()
    override def markSent(id: UUID, encodedHandle: String, now: Instant, possibleDuplicate: Boolean): Unit = ()
    override def retry(id: UUID, at: Instant, possibleDuplicate: Boolean, error: String): Unit = ()
    override def failPermanently(id: UUID, error: String): Unit = ()
    override def cancel(id: UUID): Unit = ()
    override def dead(id: UUID, error: String): Unit = ()
    override def deliveredFor(occurrenceId: UUID): Boolean = false
    override def cancelOlderQueued(occurrenceId: UUID, epoch: Int): Int = 0

  private val tx: Tx = new Tx:
    override def sessions: SessionRepository = InMemoryUnitOfWork.this.sessions
    override def outbox: OutboxRepository = InMemoryUnitOfWork.this.outbox
    override def renderedMessages: RenderedMessageRepository = InMemoryUnitOfWork.this.renderedMessages
    override def inboundEvents: InboundEventRepository = InMemoryUnitOfWork.this.inboundEvents
    override def domainEvents: DomainEventRepository = _ => ()
    override def identities: IdentityRepository = InMemoryUnitOfWork.this.identities
    override def accounts: AccountRepository = UnusedPorts.accounts
    override def moodCheckins: MoodCheckinRepository = UnusedPorts.moodCheckins
    override def audit: AuditRepository = InMemoryUnitOfWork.this.audit
    override def slots: CallbackSlotRepository = InMemoryUnitOfWork.this.slots
    override def formRuns: FormRunRepository = InMemoryUnitOfWork.this.formRuns
    override def medications: MedicationRepository = UnusedPorts.medications
    override def schedules: ScheduleRepository = UnusedPorts.schedules
    override def revisions: ScheduleRevisionRepository = UnusedPorts.revisions
    override def occurrences: OccurrenceRepository = UnusedPorts.occurrences
    override def doseActions: DoseActionRepository = UnusedPorts.doseActions
    override def policies: PolicyRepository = _ => ???
    override def channels: DeliveryChannelRepository = UnusedPorts.channels
    override def heartbeat: WorkerHeartbeatRepository = UnusedPorts.heartbeat
    override def savepoint[A](f: => A): A = f

  override def transaction[A](f: Tx => A): A = f(tx)

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
  private val subjects = ListBuffer.empty[(MessageHandle, String, UUID)]
  def all: List[(MessageHandle, List[ChoiceMapEntry], Boolean)] = this.synchronized(rows.toList)

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
    for
      st <- subjectType
      sid <- subjectId
    do subjects += ((handle, st, sid))
    true

  /** Test hook: finalize a message (controls removed), as the mediator does after a finalize. */
  def removeControls(handle: MessageHandle): Unit = this.synchronized:
    rows.mapInPlace((h, map, removed) => (h, map, removed || h == handle))

  override def choiceMapFor(handle: MessageHandle): Option[RenderedChoiceMap] = this.synchronized:
    rows.collectFirst {
      case (h, map, removed) if h == handle =>
        RenderedChoiceMap(h, h.revision, map, removed)
    }
  override def latestPendingPrompt(vendor: String, chatId: String): Option[RenderedChoiceMap] = this.synchronized:
    rows.toList.reverse.collectFirst {
      case (h, map, removed) if h.vendor == vendor && h.chatId == chatId && map.nonEmpty && !removed =>
        RenderedChoiceMap(h, h.revision, map, controlsRemoved = false)
    }
  override def handlesForSubject(subjectType: String, subjectId: UUID): List[MessageHandle] = this.synchronized:
    subjects.toList.collect { case (h, st, sid) if st == subjectType && sid == subjectId => h }

final class InMemorySessions extends SessionRepository:
  private val rows = scala.collection.mutable.LinkedHashMap.empty[UUID, ConversationSession]
  def all: List[ConversationSession] = this.synchronized(rows.values.toList)
  override def loadForUpdate(principalKey: String, vendor: String, chatId: String): Option[ConversationSession] =
    this.synchronized(rows.values.find(s => s.principalKey == principalKey && s.vendor == vendor && s.chatId == chatId))
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
    rows.values.filter(s => s.lastPrompt.isDefined && s.expiresAt.isAfter(now)).toList.sortBy(_.updatedAt).take(limit)

final class InMemorySlots extends CallbackSlotRepository:
  private val rows = scala.collection.mutable.LinkedHashMap.empty[UUID, CallbackSlot]
  override def insert(slot: CallbackSlot): Unit = this.synchronized(rows += slot.id -> slot)
  override def loadForUpdate(id: UUID): Option[CallbackSlot] = this.synchronized(rows.get(id))
  override def deleteForSession(sessionId: UUID): Int = this.synchronized:
    val doomed = rows.values.filter(_.sessionId.contains(sessionId)).map(_.id).toList
    doomed.foreach(rows -= _)
    doomed.size

final class InMemoryFormRuns extends FormRunRepository:
  private val rows = scala.collection.mutable.LinkedHashMap.empty[UUID, FormRun]
  override def insert(run: FormRun): Unit = this.synchronized(rows += run.sessionId -> run)
  override def loadForUpdate(sessionId: UUID): Option[FormRun] = this.synchronized(rows.get(sessionId))
  override def save(run: FormRun): Unit = this.synchronized(rows += run.sessionId -> run)
  override def delete(sessionId: UUID): Unit = this.synchronized:
    rows -= sessionId
    ()

final class InMemoryAudit extends AuditRepository:
  private val entries = ListBuffer.empty[AuditEntry]
  def all: List[AuditEntry] = this.synchronized(entries.toList)
  override def append(entry: AuditEntry): Unit = this.synchronized(entries += entry)
