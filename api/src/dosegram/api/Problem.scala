package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.core.ports.ConsentPurpose
import sttp.model.StatusCode
import sttp.tapir.Schema

import java.util.UUID

/** The closed error-code list of ARCHITECTURE section 9.4; it grows only by addition. */
enum ErrorCode(val wire: String):
  case ValidationFailed extends ErrorCode("validation_failed")
  case Unauthenticated extends ErrorCode("unauthenticated")
  case SessionExpired extends ErrorCode("session_expired")
  case ClientTooOld extends ErrorCode("client_too_old")
  case ClientOutdated extends ErrorCode("client_outdated")
  case AccountRequired extends ErrorCode("account_required")
  case ConsentRequired extends ErrorCode("consent_required")
  case NotFound extends ErrorCode("not_found")
  case PreconditionRequired extends ErrorCode("precondition_required")
  case VersionConflict extends ErrorCode("version_conflict")
  case IdempotencyKeyReuse extends ErrorCode("idempotency_key_reuse")
  case RateLimited extends ErrorCode("rate_limited")
  case QuotaExceeded extends ErrorCode("quota_exceeded")
  case StepupRequired extends ErrorCode("stepup_required")
  case StepupPending extends ErrorCode("stepup_pending")
  case FeatureDisabled extends ErrorCode("feature_disabled")
  case PayloadTooLarge extends ErrorCode("payload_too_large")
  case UpstreamUnavailable extends ErrorCode("upstream_unavailable")
  case Internal extends ErrorCode("internal")

object ErrorCode:
  given ReadWriter[ErrorCode] = ApiJson.wireEnum(values, _.wire)
  given Schema[ErrorCode] = Schema.derivedEnumeration[ErrorCode](encode = Some(_.wire))

/** One invalid member of a request: its JSON name (or header name) and a code. Never the value. */
final case class FieldError(field: String, code: String) derives ReadWriter, Schema

/** An RFC 9457 problem document (API-14). No member echoes user input. On a version conflict `medication` or `schedule`
  * carries the current representation, so the client can show what changed (API-17). On `consent_required`, `purpose`
  * is the consent purpose of PRODUCT_SPEC R-IDC-6 that is missing (API-19).
  */
final case class Problem(
    `type`: String,
    title: String,
    status: Int,
    code: ErrorCode,
    requestId: String,
    errors: List[FieldError] = Nil,
    medication: Option[Medication] = None,
    schedule: Option[Schedule] = None,
    purpose: Option[String] = None
) derives ReadWriter,
      Schema

/** A refused request: the problem document and, for a rate limit, the seconds to wait. */
final case class ApiFailure(problem: Problem, retryAfterSeconds: Option[Long] = None):
  def status: StatusCode = StatusCode(problem.status)

object ApiFailure:

  private def of(status: StatusCode, code: ErrorCode, title: String): ApiFailure =
    ApiFailure(Problem(s"urn:dosegram:problem:${code.wire}", title, status.code, code, UUID.randomUUID().toString))

  def invalid(errors: List[FieldError]): ApiFailure =
    val failure = of(StatusCode.UnprocessableEntity, ErrorCode.ValidationFailed, "The request is not valid.")
    failure.copy(problem = failure.problem.copy(errors = errors))

  def invalid(field: String, code: String): ApiFailure = invalid(List(FieldError(field, code)))

  /** A request the server could not read at all: broken JSON, an unknown member, a wrong type. */
  def malformed(): ApiFailure =
    of(StatusCode.BadRequest, ErrorCode.ValidationFailed, "The request could not be read.")

  /** ARCHITECTURE section 9.4, body limits. */
  def payloadTooLarge(): ApiFailure =
    of(StatusCode.PayloadTooLarge, ErrorCode.PayloadTooLarge, "The request is too large.")

  def unauthenticated(): ApiFailure =
    of(StatusCode.Unauthorized, ErrorCode.Unauthenticated, "Authentication is required.")

  def sessionExpired(): ApiFailure =
    of(StatusCode.Unauthorized, ErrorCode.SessionExpired, "The session has ended.")

  def clientTooOld(): ApiFailure =
    of(StatusCode.Unauthorized, ErrorCode.ClientTooOld, "This Telegram version is too old.")

  def accountRequired(): ApiFailure =
    of(StatusCode.Forbidden, ErrorCode.AccountRequired, "Start the bot in chat first.")

  /** API-19: the domain has no active consent. Nothing of it is read or written; consent is given in chat only. */
  def consentRequired(purpose: ConsentPurpose): ApiFailure =
    val failure = of(StatusCode.Forbidden, ErrorCode.ConsentRequired, "Turn this on in chat first.")
    failure.copy(problem = failure.problem.copy(purpose = Some(purpose.id)))

  /** An unknown identifier and an identifier of another account answer alike (API-11). */
  def notFound(): ApiFailure = of(StatusCode.NotFound, ErrorCode.NotFound, "Not found.")

  def preconditionRequired(): ApiFailure =
    of(StatusCode.PreconditionRequired, ErrorCode.PreconditionRequired, "If-Match is required.")

  def versionConflict(current: Medication): ApiFailure =
    val failure = conflict()
    failure.copy(problem = failure.problem.copy(medication = Some(current)))

  def versionConflict(current: Schedule): ApiFailure =
    val failure = conflict()
    failure.copy(problem = failure.problem.copy(schedule = Some(current)))

  /** A stale `If-Match` on a resource that the client reads again as a whole (settings). */
  def versionConflict(): ApiFailure = conflict()

  private def conflict(): ApiFailure =
    of(StatusCode.PreconditionFailed, ErrorCode.VersionConflict, "The record changed elsewhere.")

  /** A quota of ARCHITECTURE section 10.1 is used up, for example the entries of one day (PRODUCT_SPEC R-HYD-12). */
  def quotaExceeded(): ApiFailure =
    of(StatusCode.Conflict, ErrorCode.QuotaExceeded, "The limit for this kind of record is reached.")

  /** ARCHITECTURE section 9.3: the action needs a confirmation in the chat, which only the bot can give (MA-STEP-1). */
  def stepupRequired(): ApiFailure =
    of(StatusCode.Forbidden, ErrorCode.StepupRequired, "Confirm this in the chat.")

  /** A step-up that waits for its confirming tap in the chat (an export that is not confirmed yet). */
  def stepupPending(): ApiFailure =
    of(StatusCode.Conflict, ErrorCode.StepupPending, "Confirm this in the chat first.")

  def idempotencyKeyReuse(): ApiFailure =
    of(StatusCode.UnprocessableEntity, ErrorCode.IdempotencyKeyReuse, "The idempotency key was used with another body.")

  def rateLimited(retryAfterSeconds: Long): ApiFailure =
    of(StatusCode.TooManyRequests, ErrorCode.RateLimited, "Too many requests.")
      .copy(retryAfterSeconds = Some(retryAfterSeconds))

  def internal(): ApiFailure = of(StatusCode.InternalServerError, ErrorCode.Internal, "Internal error.")
