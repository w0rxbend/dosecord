package dosegram.core.application

import dosegram.core.domain.VoidReason
import dosegram.core.domain.hydration.GoalChange
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DoseRecord
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupKey
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.domain.mood.MoodCheckin
import dosegram.contracts.AccountId
import dosegram.contracts.IdentityId
import dosegram.core.ports.*

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import scala.collection.mutable

/** A clock the tests move by hand. */
final class ManualClock(start: Instant) extends Clock:
  private var at: Instant = start
  override def now(): Instant = at
  def advance(by: Duration): Unit = at = at.plus(by)
  def set(to: Instant): Unit = at = to

/** Consents as a set the tests edit. */
final class FakeConsent(initial: Set[(UUID, ConsentPurpose)]) extends ConsentLedger:
  private val current = mutable.LinkedHashSet.from(initial)
  override def isGranted(accountId: UUID, purpose: ConsentPurpose): Boolean = current((accountId, purpose))
  override def grant(accountId: UUID, purpose: ConsentPurpose, notice: ConsentNotice, taps: ConsentTaps): Boolean =
    current.add((accountId, purpose))
  override def withdraw(accountId: UUID, purpose: ConsentPurpose, at: Instant): Boolean =
    current.remove((accountId, purpose))
  override def granted(accountId: UUID): List[GrantedConsent] =
    current.toList.collect { case (`accountId`, purpose) => GrantedConsent(purpose, Instant.EPOCH, 1) }
  def withdraw(accountId: UUID, purpose: ConsentPurpose): Unit = current -= ((accountId, purpose))

object FakeConsent:
  def all(accounts: UUID*): FakeConsent =
    FakeConsent(accounts.flatMap(account => ConsentPurpose.values.map(account -> _)).toSet)

/** The consent ledger in memory: the current state per purpose and the events in order. */
final class InMemoryConsentLedger extends ConsentLedger:
  private val state = mutable.LinkedHashMap.empty[(UUID, ConsentPurpose), GrantedConsent]
  val events: mutable.ListBuffer[String] = mutable.ListBuffer.empty

  /** Every recorded grant with what the ledger row holds: the notice and both taps. */
  val grants: mutable.ListBuffer[(ConsentPurpose, ConsentNotice, ConsentTaps)] = mutable.ListBuffer.empty

  override def isGranted(accountId: UUID, purpose: ConsentPurpose): Boolean = state.contains((accountId, purpose))

  override def grant(accountId: UUID, purpose: ConsentPurpose, notice: ConsentNotice, taps: ConsentTaps): Boolean =
    val changed = !state.get((accountId, purpose)).exists(_.policyVersion == notice.policyVersion)
    if changed then
      state((accountId, purpose)) = GrantedConsent(purpose, taps.confirmedAt, notice.policyVersion)
      events += s"${purpose.id}:granted"
      grants += ((purpose, notice, taps))
    changed

  override def withdraw(accountId: UUID, purpose: ConsentPurpose, at: Instant): Boolean =
    val removed = state.remove((accountId, purpose)).isDefined
    if removed then events += s"${purpose.id}:withdrawn"
    removed

  override def granted(accountId: UUID): List[GrantedConsent] =
    state.collect { case ((owner, _), consent) if owner == accountId => consent }.toList

/** Identifiers in creation order, so tests can tell rows apart without randomness. */
final class SequentialIds extends IdSource:
  private var counter = 0L
  override def next(at: Instant): UUID =
    counter += 1
    UUID(at.toEpochMilli, counter)

/** The tracking tables in memory. It keeps the two behaviours of the database that the use cases rely on: a client key
  * is unique per account, and every write to a source table queues the affected rollup day (the triggers of the
  * baseline schema).
  */
final class InMemoryTracking(zones: Map[UUID, ZoneId]) extends TrackingRepositories:
  val entries: mutable.LinkedHashMap[UUID, HydrationEntry] = mutable.LinkedHashMap.empty
  val checkins: mutable.LinkedHashMap[UUID, MoodCheckin] = mutable.LinkedHashMap.empty
  val doses: mutable.ListBuffer[(UUID, DoseRecord)] = mutable.ListBuffer.empty
  val stored: mutable.Map[RollupKey, RollupMetrics] = mutable.Map.empty
  val dirty: mutable.LinkedHashSet[RollupKey] = mutable.LinkedHashSet.empty
  private val goalChanges: mutable.Map[UUID, Vector[GoalChange]] = mutable.Map.empty
  private val presetAmounts: mutable.Map[UUID, List[Int]] = mutable.Map.empty
  private val zoneOf: mutable.Map[UUID, ZoneId] = mutable.Map.from(zones)
  override val sleep: InMemorySleep = InMemorySleep()
  override val meals: InMemoryMeals = InMemoryMeals(dirty)

  /** The zone of an account, as `users.timezone` holds it. */
  def zoneIdOf(accountId: UUID): Option[String] = zoneOf.get(accountId).map(_.getId)

  /** Adds a dose occurrence and queues the days it could count toward, as the occurrence trigger does. */
  def addDose(accountId: UUID, record: DoseRecord): Unit =
    doses += (accountId -> record)
    markDoseDays(accountId, record)

  /** Moves the account to another zone and queues every medication day, as the account trigger does. */
  def setZone(accountId: UUID, zone: ZoneId): Unit =
    zoneOf(accountId) = zone
    stored.keys.filter(key => key.accountId == accountId && key.domain == RollupDomain.Medication).foreach(dirty += _)
    doses.filter(_._1 == accountId).foreach((_, record) => markDoseDays(accountId, record))

  private def markDoseDays(accountId: UUID, record: DoseRecord): Unit =
    val input = record.input
    for
      instant <- List(Some(input.scheduledFor), input.effectiveAt, input.resolvedAt).flatten
      shift <- List(-1L, 0L, 1L)
    do dirty += RollupKey(accountId, LocalDate.ofInstant(instant, ZoneId.of("UTC")).plusDays(shift), RollupDomain.Medication)

  override val hydration: HydrationRepository = new HydrationRepository:
    override def insert(entry: HydrationEntry): Boolean =
      val taken = entry.clientKey.exists(key => findByClientKey(entry.accountId, key).isDefined)
      if !taken then
        entries(entry.id) = entry
        dirty += RollupKey(entry.accountId, entry.localDate, RollupDomain.Hydration)
      !taken

    override def get(accountId: UUID, id: UUID): Option[HydrationEntry] =
      entries.get(id).filter(_.accountId == accountId)

    override def findByClientKey(accountId: UUID, clientKey: String): Option[HydrationEntry] =
      entries.values.find(entry => entry.accountId == accountId && entry.clientKey.contains(clientKey))

    override def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
      get(accountId, id).filter(_.isLive) match
        case Some(entry) =>
          entries(id) = entry.copy(voidedAt = Some(now))
          dirty += RollupKey(accountId, entry.localDate, RollupDomain.Hydration)
          true
        case None => false

    override def listLive(accountId: UUID, range: DateRange): List[HydrationEntry] =
      entries.values.filter(e => e.accountId == accountId && e.isLive && range.contains(e.localDate)).toList

    override def setGoal(accountId: UUID, change: GoalChange, now: Instant): Unit =
      val others = goalChanges.getOrElse(accountId, Vector.empty).filterNot(_.effectiveFrom == change.effectiveFrom)
      goalChanges(accountId) = (others :+ change).sortBy(_.effectiveFrom.toEpochDay)
      entries.values
        .filter(e => e.accountId == accountId && !e.localDate.isBefore(change.effectiveFrom))
        .foreach(e => dirty += RollupKey(accountId, e.localDate, RollupDomain.Hydration))

    override def goals(accountId: UUID, upTo: LocalDate): List[GoalChange] =
      goalChanges.getOrElse(accountId, Vector.empty).filterNot(_.effectiveFrom.isAfter(upTo)).toList

    override def presets(accountId: UUID): List[Int] = presetAmounts.getOrElse(accountId, Nil)

    override def replacePresets(accountId: UUID, amountsMl: List[Int]): Unit = presetAmounts(accountId) = amountsMl

  override val mood: MoodRepository = new MoodRepository:
    override def insert(checkin: MoodCheckin): Boolean =
      val taken = checkin.clientKey.exists(key => findByClientKey(checkin.accountId, key).isDefined)
      if !taken then
        checkins(checkin.id) = checkin
        dirty += RollupKey(checkin.accountId, checkin.localDate, RollupDomain.Mood)
      !taken

    override def get(accountId: UUID, id: UUID): Option[MoodCheckin] =
      checkins.get(id).filter(_.accountId == accountId)

    override def findByClientKey(accountId: UUID, clientKey: String): Option[MoodCheckin] =
      checkins.values.find(checkin => checkin.accountId == accountId && checkin.clientKey.contains(clientKey))

    override def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
      get(accountId, id).filter(_.isLive) match
        case Some(checkin) =>
          checkins(id) = checkin.copy(voidedAt = Some(now))
          dirty += RollupKey(accountId, checkin.localDate, RollupDomain.Mood)
          true
        case None => false

    override def listLive(accountId: UUID, range: DateRange): List[MoodCheckin] =
      checkins.values.filter(c => c.accountId == accountId && c.isLive && range.contains(c.localDate)).toList

  override val rollups: RollupRepository = new RollupRepository:
    override def claimDirty(limit: Int): List[RollupKey] =
      val claimed = dirty.take(limit).toList
      dirty --= claimed
      claimed

    override def claimDirtyIn(accountId: UUID, domain: RollupDomain, range: DateRange): List[LocalDate] =
      val claimed = dirty
        .filter(key => key.accountId == accountId && key.domain == domain && range.contains(key.localDate))
        .toList
      dirty --= claimed
      claimed.map(_.localDate)

    override def put(key: RollupKey, metrics: RollupMetrics, computedAt: Instant): Unit = stored(key) = metrics

    override def remove(key: RollupKey): Unit = stored -= key

    override def list(accountId: UUID, domain: RollupDomain, range: DateRange): Map[LocalDate, RollupMetrics] =
      stored.collect {
        case (key, metrics) if key.accountId == accountId && key.domain == domain && range.contains(key.localDate) =>
          key.localDate -> metrics
      }.toMap

    override def markDirty(accountId: UUID, domain: RollupDomain, range: DateRange, now: Instant): Unit =
      range.dates.foreach(date => dirty += RollupKey(accountId, date, domain))

  override val doseHistory: DoseHistoryRepository = (accountId, from, until) =>
    def inside(instant: Instant): Boolean = !instant.isBefore(from) && instant.isBefore(until)
    doses.collect {
      case (owner, record)
          if owner == accountId &&
            (inside(record.input.scheduledFor) || record.input.effectiveAt.exists(inside) ||
              record.input.resolvedAt.exists(inside)) =>
        record
    }.toList
end InMemoryTracking

/** A transaction for the use-case suites: the zones of the accounts, a consent ledger and the tracking tables. A call
  * on any other repository group fails and names the group (ROADMAP G2.1).
  */
final class TrackingTxDouble(tables: InMemoryTracking, ledger: ConsentLedger) extends Tx, UnitOfWork:
  override def transaction[A](f: Tx => A): A = f(this)
  override def consents: ConsentLedger = ledger
  override def tracking: TrackingRepositories = tables
  override val accounts: AccountRepository = new AccountRepository:
    override def createAccount(identityId: IdentityId, timezone: String, now: Instant): AccountId = absent("accounts")
    override def timezoneOf(accountId: UUID): Option[String] = tables.zoneIdOf(accountId)
    override def setTimezone(accountId: UUID, timezone: String, now: Instant): Unit = absent("accounts")
    override def settingsOf(accountId: UUID): Option[AccountSettings] = absent("accounts")
    override def saveSettings(accountId: UUID, settings: AccountSettings, now: Instant): Unit = absent("accounts")
  override def sessions: SessionRepository = absent("sessions")
  override def outbox: OutboxRepository = absent("outbox")
  override def renderedMessages: RenderedMessageRepository = absent("renderedMessages")
  override def inboundEvents: InboundEventRepository = absent("inboundEvents")
  override def domainEvents: DomainEventRepository = absent("domainEvents")
  override def identities: IdentityRepository = absent("identities")
  override def audit: AuditRepository = absent("audit")
  override def slots: CallbackSlotRepository = absent("slots")
  override def formRuns: FormRunRepository = absent("formRuns")
  override def medications: MedicationRepository = absent("medications")
  override def schedules: ScheduleRepository = absent("schedules")
  override def revisions: ScheduleRevisionRepository = absent("revisions")
  override def occurrences: OccurrenceRepository = absent("occurrences")
  override def doseActions: DoseActionRepository = absent("doseActions")
  override def policies: PolicyRepository = absent("policies")
  override def channels: DeliveryChannelRepository = absent("channels")
  override def heartbeat: WorkerHeartbeatRepository = absent("heartbeat")
  override def savepoint[A](f: => A): A = f

  private def absent(group: String): Nothing =
    throw UnsupportedOperationException(s"this double has no repository group '$group'")
