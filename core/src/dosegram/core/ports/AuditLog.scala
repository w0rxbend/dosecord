package dosegram.core.ports

import java.util.UUID

/** The event catalogue of research brief 06 section 10.3 (ROADMAP G2.16). `written` says whether a use case of this
  * version produces the event; an event no use case can produce yet names the slice that brings it. The registry test
  * of `AuditPgSuite` drives every written event through its use case and fails on a written event that no row shows.
  */
enum AuditEventType(val code: String, val written: Boolean, val pending: String = ""):
  // Authentication
  case SessionCreated extends AuditEventType("session.created", written = true)
  case SessionRejected extends AuditEventType("session.rejected", false, "a refused sign-in writes no row yet")
  case SessionRevoked extends AuditEventType("session.revoked", written = true)
  case InitDataReused extends AuditEventType("initdata.reused", false, "kept in auth_audit_log until G11.6b")
  case StepUpRequested extends AuditEventType("stepup.requested", written = true)
  case StepUpConfirmed extends AuditEventType("stepup.confirmed", written = true)
  case StepUpExpired extends AuditEventType("stepup.expired", false, "no expiry job for pending actions yet (G3.8)")
  // Authorisation
  case AccessDenied extends AuditEventType("access.denied", false, "caregiver access, G11.13")
  case GrantCreated extends AuditEventType("grant.created", false, "caregiver access, G11.13")
  case GrantAccepted extends AuditEventType("grant.accepted", false, "caregiver access, G11.13")
  case GrantRevoked extends AuditEventType("grant.revoked", false, "caregiver access, G11.13")
  case GrantExpired extends AuditEventType("grant.expired", false, "caregiver access, G11.13")
  // Data access
  case CaregiverRead extends AuditEventType("data.read.caregiver", false, "caregiver access, G11.13")
  case ExportRequested extends AuditEventType("data.export.requested", written = true)
  case ExportCompleted extends AuditEventType("data.export.completed", written = true)
  case ExportDownloaded extends AuditEventType("data.export.downloaded", written = true)
  case OperatorRead extends AuditEventType("data.read.operator", false, "break-glass, G11.14")
  // Data change
  case RecordUpdated extends AuditEventType("record.updated", written = true)
  case AccountDeleteRequested extends AuditEventType("account.delete.requested", written = true)
  case AccountDeleteCompleted extends AuditEventType("account.delete.completed", written = true)
  case DomainDeleteCompleted extends AuditEventType("domain.delete.completed", written = true)
  // Consent
  case ConsentGranted extends AuditEventType("consent.granted", written = true)
  case ConsentWithdrawn extends AuditEventType("consent.withdrawn", written = true)
  case ConsentReprompted extends AuditEventType("consent.reprompted", false, "no notice change exists yet")
  case AgeAttested extends AuditEventType("age.attested", false, "adult attestation, G2.8")
  // Security
  case WebhookRejected extends AuditEventType("webhook.rejected", false, "G11.6b, with the chain")
  case CallbackRejected extends AuditEventType("callback.rejected", false, "kept in auth_audit_log until G11.6b")
  // Keys and secrets
  case DekCreated extends AuditEventType("key.dek.created", written = true)
  case DekRotated extends AuditEventType("key.dek.rotated", written = true)
  case DekDestroyed extends AuditEventType("key.dek.destroyed", written = true)
  case KekRotated extends AuditEventType("key.kek.rotated", written = true)
  // Not in brief 06: the sealing backfill of the plaintext stores (supervisor decision, 2026-11-14).
  case BackfillCompleted extends AuditEventType("key.backfill.completed", written = true)
  // Administrative
  case AdminCommand extends AuditEventType("admin.command", false, "the operator CLI, G2.18")

  require(written == pending.isEmpty, s"$code: a written event names no slice; a pending one names its slice")

/** Who acted (brief 06 section 10.2): the account itself, a named service, or an operator. Never a vendor id. */
enum AuditActor:
  case User(accountId: UUID)
  case System(service: String)

  def actorType: String = this match
    case _: User   => "user"
    case _: System => "system"

  def ref: String = this match
    case User(id)        => id.toString
    case System(service) => service

enum AuditChannel(val code: String):
  case Bot extends AuditChannel("bot")
  case MiniApp extends AuditChannel("miniapp")
  case AdminCli extends AuditChannel("admin_cli")
  case Scheduler extends AuditChannel("scheduler")

/** One audit row: identifiers, codes and outcomes only, never health content (brief 06 section 10.1). `changedFields`
  * names fields, never values.
  */
final case class AuditEvent(
    eventType: AuditEventType,
    actor: AuditActor,
    subject: Option[UUID],
    objectType: String,
    objectId: Option[UUID],
    action: String,
    channel: AuditChannel,
    outcome: String = "success",
    reasonCode: Option[String] = None,
    policyVersion: Option[String] = None,
    changedFields: List[String] = Nil
)

object AuditEvent:

  /** The account acting on its own data. */
  def byUser(
      eventType: AuditEventType,
      accountId: UUID,
      objectType: String,
      objectId: Option[UUID],
      action: String,
      channel: AuditChannel,
      changedFields: List[String] = Nil
  ): AuditEvent =
    AuditEvent(
      eventType,
      AuditActor.User(accountId),
      Some(accountId),
      objectType,
      objectId,
      action,
      channel,
      changedFields = changedFields
    )

/** `record.updated` on the account settings: the names of the fields that changed, never their values. */
object SettingsAudit:
  def changed(accountId: UUID, channel: AuditChannel, fields: List[String]): AuditEvent =
    AuditEvent.byUser(AuditEventType.RecordUpdated, accountId, "settings", Some(accountId), "update", channel, fields)

/** `audit_log` (ROADMAP G2.16): append-only; a row is written on the transaction of the action it records. */
trait AuditLog:
  def record(event: AuditEvent): Unit
