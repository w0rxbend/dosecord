package dosecord.core.ports

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
  def audit: AuditRepository
  def slots: CallbackSlotRepository
  def formRuns: FormRunRepository

trait UnitOfWork:
  def transaction[A](f: Tx => A): A
