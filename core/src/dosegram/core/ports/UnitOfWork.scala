package dosegram.core.ports

/** One database transaction; exposes the repositories bound to it (DESIGN.md section 7.4:
  * `uow.transaction: tx => ...`).
  */
trait Tx:
  def sessions: SessionRepository
  def outbox: OutboxRepository
  def renderedMessages: RenderedMessageRepository
  def inboundEvents: InboundEventRepository
  def domainEvents: DomainEventRepository
  def identities: IdentityRepository
  def accounts: AccountRepository
  def audit: AuditRepository
  def slots: CallbackSlotRepository
  def formRuns: FormRunRepository
  def medications: MedicationRepository
  def schedules: ScheduleRepository
  def revisions: ScheduleRevisionRepository
  def occurrences: OccurrenceRepository
  def doseActions: DoseActionRepository
  def policies: PolicyRepository
  def channels: DeliveryChannelRepository
  def heartbeat: WorkerHeartbeatRepository

  /** The consent ledger on this transaction: a consent check, a grant and a withdrawal commit or roll back with the
    * records they gate (PRODUCT_SPEC R-IDC-9, R-IDC-10).
    */
  def consents: ConsentLedger

  /** Hydration, mood, the daily rollups and the dose history, on this transaction: an entry is atomic with the inbound
    * event that recorded it, and a capture event holds one connection.
    */
  def tracking: TrackingRepositories

  /** Runs `f` under a savepoint: on exception the transaction is rolled back to the savepoint and the exception
    * rethrown, so one failing unit of work cannot poison the surrounding transaction (ADR-004: per-row savepoints in
    * the reminder loop).
    */
  def savepoint[A](f: => A): A

trait UnitOfWork:
  def transaction[A](f: Tx => A): A
