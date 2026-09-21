package dosecord.infra.db

import dosecord.core.ports.DomainEventRepository
import dosecord.core.ports.NewDomainEvent

import java.sql.Connection
import scala.language.implicitConversions

final class PgDomainEventRepository(conn: Connection) extends DomainEventRepository:
  private given Connection = conn

  override def append(event: NewDomainEvent): Unit =
    sql"""INSERT INTO domain_events
            (id, type, source, subject, account_id, correlation_id, causation_id, actor, occurred_at, data)
          VALUES (${event.id}, ${event.eventType}, ${event.source}, ${event.subject}, ${event.accountId},
                  ${event.correlationId}, ${event.causationId}, ${Jsonb(event.actorJson)}, ${event.occurredAt},
                  ${Jsonb(event.dataJson)})""".execute()
