package dosegram.infra.db

import dosegram.core.ports.DomainEventRepository
import dosegram.core.ports.KeyService
import dosegram.core.ports.NewDomainEvent

import java.sql.Connection
import scala.language.implicitConversions

/** `domain_events`. The envelope and the actor are sealed (V21, ROADMAP G2.4b) with the event's account key, or the
  * transport service key for an event without an account: an event carries the names and words of the change.
  */
final class PgDomainEventRepository(conn: Connection, keys: KeyService) extends DomainEventRepository:
  private given Connection = conn
  import SealedColumns.EventActor
  import SealedColumns.EventData

  override def append(event: NewDomainEvent): Unit =
    val sealedActor = keys.sealText(EventActor.forRow(event.accountId, event.id), event.actorJson)
    val sealedData = keys.sealText(EventData.forRow(event.accountId, event.id), event.dataJson)
    sql"""INSERT INTO domain_events
            (id, type, source, subject, account_id, correlation_id, causation_id, actor_enc, occurred_at, data_enc)
          VALUES (${event.id}, ${event.eventType}, ${event.source}, ${event.subject}, ${event.accountId},
                  ${event.correlationId}, ${event.causationId}, $sealedActor, ${event.occurredAt},
                  $sealedData)""".execute()
