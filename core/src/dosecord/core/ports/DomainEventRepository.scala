package dosecord.core.ports

import java.time.Instant
import java.util.UUID

/** One `domain_events` row. `actorJson` and `dataJson` (the CloudEvents envelope) are built by the contracts helper
  * `Event.toEnvelopeJson` so the core stays free of a JSON library.
  */
final case class NewDomainEvent(
    id: UUID,
    eventType: String,
    source: String,
    subject: Option[String],
    accountId: Option[UUID],
    correlationId: Option[String],
    causationId: Option[String],
    actorJson: String,
    occurredAt: Instant,
    dataJson: String
)

/** `domain_events` (ADR-003): the append-only CloudEvents log. Every row carries the resolved account id and a `source`
  * of `dosecord.<module>` (R30, C2, C6).
  */
trait DomainEventRepository:
  def append(event: NewDomainEvent): Unit
