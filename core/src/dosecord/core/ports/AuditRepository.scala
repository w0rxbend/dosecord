package dosecord.core.ports

import java.util.UUID

/** One append-only audit row. The bad-MAC audit row of DESIGN.md section 4.4 / ADR-006 uses `kind = callback_tampered`.
  */
final case class AuditEntry(
    kind: String,
    outcome: String,
    vendor: String,
    vendorEventId: Option[String] = None,
    chatId: Option[String] = None,
    userId: Option[UUID] = None,
    platformIdentityId: Option[UUID] = None
)

/** `auth_audit_log`: append-only by trigger; writers never update or delete. */
trait AuditRepository:
  def append(entry: AuditEntry): Unit
