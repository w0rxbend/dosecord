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

  /** Message identity is the (vendor, chat, message) triple; `revision` is the rendered-message edit counter, not
    * part of the identity.
    */
  private def sameMessage(a: MessageHandle, b: MessageHandle): Boolean =
    a.vendor == b.vendor && a.chatId == b.chatId && a.messageId == b.messageId

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

  final class CreatedAccount(val accountId: UUID, val identityId: UUID, timezone: String):
    val displayName: Option[String] = None
    @volatile var tz: String = timezone
    def currentTimezone: String = tz

  final class InMemoryAccounts(identities: InMemoryIdentities) extends AccountRepository:
    private val rows = ListBuffer.empty[CreatedAccount]
    def all: List[CreatedAccount] = this.synchronized(rows.toList)
    override def createAccount(identityId: IdentityId, timezone: String, now: Instant): AccountId =
      this.synchronized:
        val accountId = UUID.randomUUID()
        rows += CreatedAccount(accountId, identityId.uuid, timezone)
        identities.linkIdentity(identityId, accountId)
        AccountId(accountId)
    override def timezoneOf(accountId: UUID): Option[String] =
      this.synchronized(rows.find(_.accountId == accountId).map(_.tz))
    override def setTimezone(accountId: UUID, timezone: String, now: Instant): Unit =
      this.synchronized(rows.find(_.accountId == accountId).foreach(_.tz = timezone))

  final class InMemoryMoodCheckins extends MoodCheckinRepository:
    private val rows = ListBuffer.empty[NewMoodCheckin]
    def all: List[NewMoodCheckin] = this.synchronized(rows.toList)
    override def insert(row: NewMoodCheckin): Unit = this.synchronized(rows += row)

  final class InMemoryRenderedMessages extends RenderedMessageRepository:
    private val rows = ListBuffer.empty[(MessageHandle, List[ChoiceMapEntry], Boolean, Option[String], Option[UUID])]
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
      rows += ((handle, choiceMap, false, subjectType, subjectId))
      true
    override def recordEdit(handle: MessageHandle, choiceMap: List[ChoiceMapEntry], now: Instant): Unit =
      this.synchronized:
        if choiceMap.nonEmpty then
          rows.mapInPlace((h, map, removed, st, sid) =>
            if sameMessage(h, handle) then (h.copy(revision = h.revision + 1), choiceMap, removed, st, sid)
            else (h, map, removed, st, sid)
          )
    override def choiceMapFor(handle: MessageHandle): Option[RenderedChoiceMap] = this.synchronized:
      rows.collectFirst { case (h, map, removed, _, _) if sameMessage(h, handle) =>
        RenderedChoiceMap(h, h.revision, map, removed)
      }
    override def latestPendingPrompt(vendor: String, chatId: String): Option[RenderedChoiceMap] = this.synchronized:
      rows.toList.reverse.collectFirst {
        case (h, map, removed, _, _) if h.vendor == vendor && h.chatId == chatId && map.nonEmpty && !removed =>
          RenderedChoiceMap(h, h.revision, map, controlsRemoved = false)
      }
    override def handlesForSubject(subjectType: String, subjectId: UUID): List[MessageHandle] = this.synchronized:
      rows.toList.collect { case (h, _, _, Some(st), Some(sid)) if st == subjectType && sid == subjectId => h }

  final class InMemoryOutbox extends OutboxRepository:
    private val enqueued = ListBuffer.empty[NewOutboxMessage]
    private val sent = ListBuffer.empty[UUID]
    private final case class State(
        status: OutboxStatus = OutboxStatus.Queued,
        attempts: Int = 0,
        nextAttemptAt: Option[Instant] = None,
        attemptedAt: Option[Instant] = None,
        opsDone: Int = 0,
        platformMessageId: Option[String] = None,
        possibleDuplicate: Boolean = false,
        sentAt: Option[Instant] = None
    )
    private val states = scala.collection.mutable.Map.empty[UUID, State].withDefaultValue(State())
    def allEnqueued: List[NewOutboxMessage] = this.synchronized(enqueued.toList)
    def allSent: List[UUID] = this.synchronized(sent.toList)
    override def enqueue(msg: NewOutboxMessage): Boolean = this.synchronized:
      if enqueued.exists(_.sendKey == msg.sendKey) then false
      else
        enqueued += msg
        states(msg.id) = State(nextAttemptAt = Some(msg.nextAttemptAt))
        true
    override def enqueueDigest(msg: NewOutboxMessage): Boolean = enqueue(msg)
    override def claim(now: Instant, vendors: Seq[String], limit: Int, lease: Duration): List[OutboxMessage] =
      this.synchronized:
        enqueued
          .filter: msg =>
            val s = states(msg.id)
            vendors.contains(msg.vendor) &&
            (s.status == OutboxStatus.Queued || s.status == OutboxStatus.FailedRetry) &&
            s.nextAttemptAt.exists(!_.isAfter(now))
          .sortBy(msg => states(msg.id).nextAttemptAt)
          .take(limit)
          .map: msg =>
            val s = states(msg.id)
            states(msg.id) = s.copy(
              status = OutboxStatus.Sending,
              attempts = s.attempts + 1,
              attemptedAt = Some(now)
            )
            toOutboxMessage(msg, states(msg.id), previousAttemptedAt = s.attemptedAt)
          .toList
    override def recordHandle(id: UUID, encodedHandle: String): Unit = this.synchronized:
      states(id) = states(id).copy(opsDone = states(id).opsDone | 1, platformMessageId = Some(encodedHandle))
    override def markOpDone(id: UUID, opIndex: Int): Unit = this.synchronized:
      states(id) = states(id).copy(opsDone = states(id).opsDone | (1 << opIndex))
    override def markSent(id: UUID, encodedHandle: String, now: Instant, possibleDuplicate: Boolean): Unit =
      this.synchronized:
        sent += id
        states(id) = states(id).copy(
          status = OutboxStatus.Sent,
          platformMessageId = Some(encodedHandle),
          possibleDuplicate = possibleDuplicate,
          sentAt = Some(now)
        )
    override def retry(id: UUID, at: Instant, possibleDuplicate: Boolean, error: String): Unit =
      this.synchronized:
        states(id) = states(id).copy(status = OutboxStatus.FailedRetry, nextAttemptAt = Some(at),
          possibleDuplicate = possibleDuplicate)
    override def dead(id: UUID, error: String): Unit = this.synchronized:
      states(id) = states(id).copy(status = OutboxStatus.Dead)
    override def failPermanently(id: UUID, error: String): Unit = this.synchronized:
      states(id) = states(id).copy(status = OutboxStatus.FailedPermanent)
    override def cancel(id: UUID): Unit = this.synchronized:
      states(id) = states(id).copy(status = OutboxStatus.Cancelled)
    override def deliveredFor(occurrenceId: UUID): Boolean = this.synchronized:
      enqueued.exists(msg => msg.occurrenceId.contains(occurrenceId) && states(msg.id).sentAt.isDefined)
    override def cancelOlderQueued(occurrenceId: UUID, epoch: Int): Int = this.synchronized:
      val doomed = enqueued.filter(msg =>
        msg.occurrenceId.contains(occurrenceId) && msg.epoch.exists(_ < epoch) &&
          Set(OutboxStatus.Queued, OutboxStatus.FailedRetry, OutboxStatus.Sending).contains(states(msg.id).status)
      )
      doomed.foreach(msg => states(msg.id) = states(msg.id).copy(status = OutboxStatus.Cancelled))
      doomed.size

    private def toOutboxMessage(msg: NewOutboxMessage, s: State, previousAttemptedAt: Option[Instant]): OutboxMessage =
      OutboxMessage(
        id = msg.id,
        sendKey = msg.sendKey,
        op = msg.op,
        kind = msg.kind,
        vendor = msg.vendor,
        accountId = msg.accountId,
        occurrenceId = msg.occurrenceId,
        channelId = msg.channelId,
        epoch = msg.epoch,
        payload = msg.payload,
        target = msg.target,
        importance = msg.importance,
        status = s.status,
        attempts = s.attempts,
        nextAttemptAt = s.nextAttemptAt.getOrElse(msg.nextAttemptAt),
        leaseUntil = None,
        attemptedAt = s.attemptedAt,
        previousAttemptedAt = previousAttemptedAt,
        opsDone = s.opsDone,
        possibleDuplicate = s.possibleDuplicate,
        platformMessageId = s.platformMessageId,
        lastError = None,
        createdAt = msg.nextAttemptAt,
        sentAt = s.sentAt
      )

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

  final class InMemoryMedications extends MedicationRepository:
    private val rows = ListBuffer.empty[StoredMedication]
    def all: List[StoredMedication] = this.synchronized(rows.toList)
    override def insert(row: NewMedication, now: Instant): Unit = this.synchronized:
      rows += StoredMedication(row.id, row.accountId, row.name, row.name.trim.toLowerCase, row.doseAmount,
        row.doseUnit, row.instructions, MedicationStatus.Active, now)
    override def get(id: UUID): Option[StoredMedication] = this.synchronized(rows.find(_.id == id))
    override def findByNameNorm(accountId: UUID, nameNorm: String): Option[StoredMedication] =
      this.synchronized(
        rows.find(r => r.accountId == accountId && r.nameNorm == nameNorm && r.status != MedicationStatus.Archived)
      )
    override def listForAccount(accountId: UUID): List[StoredMedication] =
      this.synchronized(
        rows.filter(r => r.accountId == accountId && r.status != MedicationStatus.Archived).toList
      )
    override def searchByNameNormPrefix(accountId: UUID, prefix: String, limit: Int): List[StoredMedication] =
      this.synchronized(
        rows
          .filter(r =>
            r.accountId == accountId && r.status != MedicationStatus.Archived &&
              r.nameNorm.startsWith(prefix.trim.toLowerCase)
          )
          .take(limit)
          .toList
      )

  final class InMemorySchedules extends ScheduleRepository:
    private val rows = ListBuffer.empty[StoredSchedule]
    def all: List[StoredSchedule] = this.synchronized(rows.toList)
    override def insert(row: NewSchedule, now: Instant): Unit = this.synchronized:
      rows += StoredSchedule(row.id, row.medicationId, row.accountId, row.kind, ScheduleStatus.Active, 1, row.tz,
        row.tzFollowsUser, row.startDate, row.endDate, None)
    override def get(id: UUID): Option[StoredSchedule] = this.synchronized(rows.find(_.id == id))
    override def getForUpdate(id: UUID): Option[StoredSchedule] = get(id)
    override def setStatus(id: UUID, status: ScheduleStatus, now: Instant): Unit = update(id)(_.copy(status = status))
    override def setTimezone(id: UUID, tz: java.time.ZoneId, now: Instant): Unit = update(id)(_.copy(tz = tz))
    override def setCurrentRevision(id: UUID, revision: Int, now: Instant): Unit =
      update(id)(_.copy(currentRevision = revision))
    override def advanceMaterializedThrough(id: UUID, through: Instant, now: Instant): Unit =
      update(id)(s =>
        if s.materializedThrough.exists(!_.isBefore(through)) then s
        else s.copy(materializedThrough = Some(through))
      )
    override def listFollowingForTzChange(accountId: UUID): List[StoredSchedule] =
      this.synchronized(
        rows.filter(r => r.accountId == accountId && r.tzFollowsUser && r.status != ScheduleStatus.Archived).toList
      )
    override def claimForMaterialisation(horizonEnd: Instant, limit: Int): List[StoredSchedule] =
      this.synchronized(
        rows
          .filter(s => s.status == ScheduleStatus.Active && s.materializedThrough.forall(_.isBefore(horizonEnd)))
          .take(limit)
          .toList
      )
    override def listForMedication(medicationId: UUID): List[StoredSchedule] =
      this.synchronized(rows.filter(_.medicationId == medicationId).toList)
    private def update(id: UUID)(f: StoredSchedule => StoredSchedule): Unit = this.synchronized:
      rows.mapInPlace(s => if s.id == id then f(s) else s)

  final class InMemoryRevisions extends ScheduleRevisionRepository:
    private val rows = ListBuffer.empty[StoredScheduleRevision]
    def all: List[StoredScheduleRevision] = this.synchronized(rows.toList)
    override def append(row: NewScheduleRevision, now: Instant): Unit = this.synchronized:
      rows += StoredScheduleRevision(row.id, row.scheduleId, row.revision, row.effectiveFrom, row.tz, row.rule,
        row.policy, row.doseSnapshot, row.createdBy, row.reason, now)
    override def latest(scheduleId: UUID): Option[StoredScheduleRevision] =
      this.synchronized(rows.filter(_.scheduleId == scheduleId).maxByOption(_.revision))
    override def get(scheduleId: UUID, revision: Int): Option[StoredScheduleRevision] =
      this.synchronized(rows.find(r => r.scheduleId == scheduleId && r.revision == revision))
    override def list(scheduleId: UUID): List[StoredScheduleRevision] =
      this.synchronized(rows.filter(_.scheduleId == scheduleId).sortBy(_.revision).toList)

  final class InMemoryOccurrences extends OccurrenceRepository:
    private val rows = ListBuffer.empty[StoredOccurrence]
    def all: List[StoredOccurrence] = this.synchronized(rows.toList)
    override def insertAll(newRows: List[NewOccurrence]): Int = this.synchronized:
      var inserted = 0
      newRows.foreach { row =>
        val duplicate = rows.exists(r =>
          r.scheduleId == row.scheduleId && r.localDate == row.localDate && r.slotKey == row.slotKey &&
            (r.revision == row.revision || r.status != dosecord.core.domain.OccurrenceStatus.Cancelled)
        )
        if !duplicate then
          rows += StoredOccurrence(row.id, row.accountId, row.medicationId, row.scheduleId, row.revision, row.origin,
            row.localDate, row.localTime.map(t => java.time.LocalTime.of(t.hour, t.minute)), row.slotKey, row.tz,
            row.dstKind, None, version = 1, row.state, Some(row.doseSnapshot))
          inserted += 1
      }
      inserted
    override def get(id: UUID): Option[StoredOccurrence] = this.synchronized(rows.find(_.id == id))
    override def lockById(id: UUID): Option[StoredOccurrence] = get(id)
    override def latestOpenForAccount(accountId: UUID, medicationId: Option[UUID], now: Instant)
        : Option[StoredOccurrence] =
      this.synchronized:
        rows
          .filter(r =>
            r.accountId == accountId && r.status.isOpen && medicationId.forall(_ == r.medicationId) &&
              !r.state.scheduledFor.isAfter(now)
          )
          .maxByOption(_.state.scheduledFor)
    override def listBySchedule(scheduleId: UUID): List[StoredOccurrence] =
      this.synchronized(
        rows
          .filter(_.scheduleId.contains(scheduleId))
          .sortBy(r => (r.state.scheduledFor, r.localDate.toEpochDay, r.slotKey))
          .toList
      )
    override def lockOpenRows(scheduleId: UUID): List[StoredOccurrence] =
      this.synchronized(rows.filter(r => r.scheduleId.contains(scheduleId) && r.status.isOpen).toList)
    override def cancel(ids: List[UUID], reason: dosecord.core.domain.CancelReason, now: Instant): Int =
      this.synchronized:
        var cancelled = 0
        rows.mapInPlace { r =>
          if ids.contains(r.id) && r.status.isOpen then
            cancelled += 1
            r.copy(
              cancelReason = Some(reason),
              version = r.version + 1,
              state = r.state.copy(status = dosecord.core.domain.OccurrenceStatus.Cancelled,
                nextActionAt = None, epoch = r.state.epoch + 1)
            )
          else r
        }
        cancelled
    override def hasResolvedOrDueOn(scheduleId: UUID, localDate: java.time.LocalDate, now: Instant): Boolean =
      this.synchronized:
        rows.exists(r =>
          r.scheduleId.contains(scheduleId) && r.localDate == localDate &&
            ((r.status != dosecord.core.domain.OccurrenceStatus.Pending &&
              r.status != dosecord.core.domain.OccurrenceStatus.Cancelled) ||
              (r.status == dosecord.core.domain.OccurrenceStatus.Pending && !r.state.dueWindowStart.isAfter(now)))
        )
    override def claimDue(now: Instant, limit: Int): List[StoredOccurrence] =
      this.synchronized(
        rows
          .filter(r =>
            r.status.isOpen && r.state.nextActionAt.exists(!_.isAfter(now)) &&
              errorCounts(r.id) < OccurrenceRepository.QuarantineErrorThreshold
          )
          .toList
          .sortBy(r => (r.state.nextActionAt, r.id))
          .take(limit)
      )
    override def applyTransition(
        id: UUID,
        expectedVersion: Int,
        expectedEpoch: Int,
        newState: dosecord.core.domain.Occurrence,
        now: Instant
    ): Boolean = this.synchronized:
      rows.find(_.id == id) match
        case Some(r) if r.version == expectedVersion && r.state.epoch == expectedEpoch =>
          rows.mapInPlace(x => if x.id == id then r.copy(version = r.version + 1, state = newState) else x)
          true
        case _ => false
    override def quarantine(id: UUID, retryAt: Instant, now: Instant): Unit = this.synchronized:
      errorCounts(id) = errorCounts(id) + 1
      rows.mapInPlace(r =>
        if r.id == id then r.copy(state = r.state.copy(nextActionAt = Some(retryAt))) else r
      )
    override def nextScheduledAfter(scheduleId: UUID, after: Instant): Option[Instant] =
      this.synchronized(
        rows
          .filter(r => r.scheduleId.contains(scheduleId) && r.status.isOpen && r.state.scheduledFor.isAfter(after))
          .map(_.state.scheduledFor)
          .toList
          .minOption
      )
    override def epochIsStale(occurrenceId: UUID, epoch: Int): Boolean =
      this.synchronized(rows.find(_.id == occurrenceId).forall(_.state.epoch > epoch))
    override def listForAccountBetween(accountId: UUID, from: java.time.LocalDate, to: java.time.LocalDate)
        : List[StoredOccurrence] =
      this.synchronized(
        rows
          .filter(r => r.accountId == accountId && !r.localDate.isBefore(from) && !r.localDate.isAfter(to))
          .sortBy(r => (r.state.scheduledFor, r.id))
          .toList
      )
    private val errorCounts = scala.collection.mutable.Map.empty[UUID, Int].withDefaultValue(0)

  final class InMemoryDoseActions extends DoseActionRepository:
    private val rows = ListBuffer.empty[StoredDoseAction]
    def all: List[StoredDoseAction] = this.synchronized(rows.toList)
    override def append(row: NewDoseAction): Unit = this.synchronized:
      val seq = rows.count(_.occurrenceId == row.occurrenceId) + 1
      rows += StoredDoseAction(row.id, row.occurrenceId, row.accountId, seq, row.action, row.actor, row.occurredAt,
        row.occurredAt, row.priorStatus, row.newStatus, row.effectiveAt, row.reasonCode, row.note, row.undoesSeq,
        row.catchUp, row.correlationId, row.idempotencyKey, row.metadata, row.vendor, row.platformIdentityId,
        row.platformMessageId)
    override def listForOccurrence(occurrenceId: UUID): List[StoredDoseAction] =
      this.synchronized(rows.filter(_.occurrenceId == occurrenceId).sortBy(_.seq).toList)

  /** Policies from the in-memory revisions; a scheduled row with a missing revision throws (the poison-row path). */
  final class InMemoryPolicies(revisions: InMemoryRevisions) extends PolicyRepository:
    @volatile var quiet: Option[dosecord.core.domain.QuietHours] = None
    override def forOccurrence(occ: StoredOccurrence)
        : (dosecord.core.domain.ReminderPolicy, dosecord.core.domain.QuietHoursContext) =
      val policy = (occ.scheduleId, occ.revision) match
        case (Some(scheduleId), Some(revision)) =>
          revisions
            .get(scheduleId, revision)
            .map(_.policy)
            .getOrElse(
              throw new NoSuchElementException(s"occurrence ${occ.id} references missing revision $revision")
            )
        case _ => dosecord.core.domain.ReminderPolicy.Default
      (policy, dosecord.core.domain.QuietHoursContext(quiet, occ.tz))

  final class InMemoryChannels extends DeliveryChannelRepository:
    private val targets = scala.collection.mutable.Map.empty[UUID, List[DeliveryTarget]].withDefaultValue(Nil)
    private val dead = scala.collection.mutable.Set.empty[UUID]
    def register(accountId: UUID, target: DeliveryTarget): Unit = this.synchronized:
      targets(accountId) = targets(accountId) :+ target
    override def activePrimaryChannels(accountId: UUID): List[DeliveryTarget] =
      this.synchronized(targets(accountId).filterNot(t => dead.contains(t.channelId)))
    override def markDead(channelId: UUID, error: String, now: Instant): Unit = this.synchronized:
      dead += channelId
      ()
    override def fallbackChannel(accountId: UUID, excludeChannelId: UUID): Option[DeliveryTarget] =
      this.synchronized:
        targets(accountId).find(t => t.channelId != excludeChannelId && !dead.contains(t.channelId))
    override def byId(channelId: UUID): Option[DeliveryTarget] =
      this.synchronized:
        targets.values.flatten.find(_.channelId == channelId)

  final class InMemoryHeartbeat extends WorkerHeartbeatRepository:
    private val rows = scala.collection.mutable.Map.empty[String, Instant]
    override def touch(instance: String, role: String, now: Instant): Unit =
      this.synchronized:
        rows(instance) = now
    override def maxLastTick(): Option[Instant] = this.synchronized(rows.values.toList.maxOption)

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
    val outbox = InMemoryOutbox()
    val medications = InMemoryMedications()
    val schedules = InMemorySchedules()
    val revisions = InMemoryRevisions()
    val occurrences = InMemoryOccurrences()
    val doseActions = InMemoryDoseActions()
    val policies = InMemoryPolicies(revisions)
    val channels = InMemoryChannels()
    val heartbeat = InMemoryHeartbeat()

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
      override def medications: MedicationRepository = InMemoryUnitOfWork.this.medications
      override def schedules: ScheduleRepository = InMemoryUnitOfWork.this.schedules
      override def revisions: ScheduleRevisionRepository = InMemoryUnitOfWork.this.revisions
      override def occurrences: OccurrenceRepository = InMemoryUnitOfWork.this.occurrences
      override def doseActions: DoseActionRepository = InMemoryUnitOfWork.this.doseActions
      override def savepoint[A](f: => A): A = f
      override def policies: PolicyRepository = InMemoryUnitOfWork.this.policies
      override def channels: DeliveryChannelRepository = InMemoryUnitOfWork.this.channels
      override def heartbeat: WorkerHeartbeatRepository = InMemoryUnitOfWork.this.heartbeat

    override def transaction[A](f: Tx => A): A = f(tx)
