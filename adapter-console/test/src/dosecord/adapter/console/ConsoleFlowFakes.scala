package dosecord.adapter.console

import dosecord.contracts.*
import dosecord.core.ports.*

import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ListBuffer

/** Working in-memory port fakes for the console first-flows golden (M0.12d): the wizard needs real sessions, slots and
  * form runs, and the flows need accounts and mood check-ins. Mirrors core's `MediatorFakes` (test sources are not
  * shared across modules).
  */
private object ConsoleFlowFakes:

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

  final class InMemoryDomainEvents extends DomainEventRepository:
    private val appended = ListBuffer.empty[NewDomainEvent]
    def all: List[NewDomainEvent] = this.synchronized(appended.toList)
    override def append(event: NewDomainEvent): Unit = this.synchronized(appended += event)

  final class InMemoryIdentities extends IdentityRepository:
    private val principals = scala.collection.mutable.Map.empty[(String, String), Principal]
    def linkIdentity(identityId: IdentityId, accountId: UUID): Unit = this.synchronized:
      principals.collectFirst { case (key, p) if p.identityId == identityId => key }.foreach: key =>
        principals.update(key, principals(key).copy(accountId = Some(AccountId(accountId)), linked = true))
    override def resolve(actor: PlatformIdentity, now: Instant): Principal = this.synchronized:
      principals.getOrElseUpdate(
        (actor.vendor, actor.vendorUserId),
        Principal(IdentityId(UUID.randomUUID()), None, linked = false)
      )
    override def find(actor: PlatformIdentity): Option[Principal] =
      this.synchronized(principals.get((actor.vendor, actor.vendorUserId)))

  final class CreatedAccount(val accountId: UUID, val identityId: UUID, val timezone: String):
    val displayName: Option[String] = None

  final class InMemoryAccounts(identities: InMemoryIdentities) extends AccountRepository:
    private val rows = ListBuffer.empty[CreatedAccount]
    def all: List[CreatedAccount] = this.synchronized(rows.toList)
    override def createAccount(identityId: IdentityId, timezone: String, now: Instant): AccountId =
      this.synchronized:
        val accountId = UUID.randomUUID()
        rows += CreatedAccount(accountId, identityId.uuid, timezone)
        identities.linkIdentity(identityId, accountId)
        AccountId(accountId)

  final class InMemoryMoodCheckins extends MoodCheckinRepository:
    private val rows = ListBuffer.empty[NewMoodCheckin]
    def all: List[NewMoodCheckin] = this.synchronized(rows.toList)
    override def insert(row: NewMoodCheckin): Unit = this.synchronized(rows += row)

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

  /** The console flows never touch the medication repositories. */
  private object UnusedPorts:
    val medications: MedicationRepository = ???
    val schedules: ScheduleRepository = ???
    val revisions: ScheduleRevisionRepository = ???
    val occurrences: OccurrenceRepository = ???
    val doseActions: DoseActionRepository = ???

  final class InMemoryUnitOfWork extends UnitOfWork:
    val inboundEvents = InMemoryInboundEvents()
    val domainEvents = InMemoryDomainEvents()
    val identities = InMemoryIdentities()
    val accounts = InMemoryAccounts(identities)
    val moodCheckins = InMemoryMoodCheckins()
    val renderedMessages = InMemoryRenderedMessages()
    val sessions = InMemorySessions()
    private val slots = InMemorySlots()
    private val formRuns = InMemoryFormRuns()
    private val outbox = InMemoryOutbox()

    private val tx: Tx = new Tx:
      override def sessions: SessionRepository = InMemoryUnitOfWork.this.sessions
      override def outbox: OutboxRepository = InMemoryUnitOfWork.this.outbox
      override def renderedMessages: RenderedMessageRepository = InMemoryUnitOfWork.this.renderedMessages
      override def inboundEvents: InboundEventRepository = InMemoryUnitOfWork.this.inboundEvents
      override def domainEvents: DomainEventRepository = InMemoryUnitOfWork.this.domainEvents
      override def identities: IdentityRepository = InMemoryUnitOfWork.this.identities
      override def accounts: AccountRepository = InMemoryUnitOfWork.this.accounts
      override def moodCheckins: MoodCheckinRepository = InMemoryUnitOfWork.this.moodCheckins
      override def audit: AuditRepository = _ => ()
      override def slots: CallbackSlotRepository = InMemoryUnitOfWork.this.slots
      override def formRuns: FormRunRepository = InMemoryUnitOfWork.this.formRuns
      override def medications: MedicationRepository = UnusedPorts.medications
      override def schedules: ScheduleRepository = UnusedPorts.schedules
      override def revisions: ScheduleRevisionRepository = UnusedPorts.revisions
      override def occurrences: OccurrenceRepository = UnusedPorts.occurrences
      override def doseActions: DoseActionRepository = UnusedPorts.doseActions

    override def transaction[A](f: Tx => A): A = f(tx)
