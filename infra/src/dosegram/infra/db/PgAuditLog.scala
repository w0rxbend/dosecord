package dosegram.infra.db

import dosegram.core.ports.AuditEvent
import dosegram.core.ports.AuditLog
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource

import java.sql.Connection
import scala.language.implicitConversions

/** `audit_log` on the connection of the surrounding transaction (ROADMAP G2.16): one INSERT per event, with a
  * time-ordered id. The table's checks refuse anything that is not a code, so no free text reaches it.
  */
final class PgAuditLog(conn: Connection, clock: Clock, ids: IdSource) extends AuditLog:
  private given Connection = conn

  override def record(event: AuditEvent): Unit =
    val at = clock.now()
    sql"""INSERT INTO audit_log (id, occurred_at, event_type, actor_type, actor_ref, subject_ref, object_type, object_id,
                                 action, outcome, reason_code, channel, policy_version, changed_fields)
          VALUES (${ids.next(at)}, $at, ${event.eventType.code}, ${event.actor.actorType}, ${event.actor.ref},
                  ${event.subject}, ${event.objectType}, ${event.objectId}, ${event.action}, ${event.outcome},
                  ${event.reasonCode}, ${event.channel.code}, ${event.policyVersion}, ${event.changedFields.toArray})"""
      .execute()
    ()
