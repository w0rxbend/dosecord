package dosegram.api

import dosegram.api.auth.InitDataVerifier
import dosegram.api.auth.Session
import dosegram.api.auth.SessionService
import dosegram.api.auth.SessionStore
import dosegram.core.ports.Clock
import dosegram.core.ports.UnitOfWork
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.decodefailure.DecodeFailureHandler
import sttp.tapir.server.interceptor.decodefailure.DefaultDecodeFailureHandler
import sttp.tapir.server.model.ValuedEndpointOutput

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import scala.util.Try
import scala.util.control.NonFatal

/** The Mini App API as server endpoints (ARCHITECTURE section 9). The composition root mounts [[endpoints]] and
  * installs [[ApiModule.decodeFailureHandler]]; the module never sees the bot token (DEP-05).
  *
  * @param log
  *   receives one line per unexpected failure: the request id and the exception class, never a message, a body or a
  *   path.
  */
final class ApiModule(
    uow: UnitOfWork,
    clock: Clock,
    verifier: InitDataVerifier,
    sessionStore: SessionStore,
    idempotencyStore: IdempotencyStore,
    log: String => Unit
):
  import ApiModule.*

  private val sessions = SessionService(verifier, uow, sessionStore, clock)
  private val idempotency = Idempotency(idempotencyStore, clock)
  private val registry = Registry(uow, clock)
  private val days = DayViews(uow, clock)

  /** Deletes expired sessions and idempotency records older than 24 hours; the caller runs it on a period. */
  def sweep(): Int = sessions.sweep() + idempotency.sweep()

  private def authenticate(bearer: String): Either[ApiFailure, Session] = guarded(sessions.authenticate(bearer))

  /** An unexpected exception becomes the `internal` problem; its class and the request id are the only things logged.
    */
  private def guarded[A](run: => Either[ApiFailure, A]): Either[ApiFailure, A] =
    try run
    catch
      case NonFatal(cause) =>
        val failure = ApiFailure.internal()
        log(s"api: request ${failure.problem.requestId} failed with ${cause.getClass.getName}")
        Left(failure)

  private val sessionEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.createSession.handle: request =>
      guarded:
        sessions
          .exchange(request.initData)
          .map: issued =>
            SessionCreated(
              issued.token,
              issued.expiresAt,
              sessions.idleTimeout.toSeconds,
              Bootstrap(issued.timeZone, RegistryLimits.published)
            )
    ,
    Endpoints.deleteSession.handleSecurity(authenticate).handle(session => _ => guarded(Right(sessions.end(session)))),
    // Authentication already moved the idle timer (API-06).
    Endpoints.keepSessionAlive.handleSecurity(authenticate).handle(_ => _ => Right(()))
  )

  private val dayEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.today.handleSecurity(authenticate).handle(session => _ => guarded(Right(days.today(session.accountId)))),
    Endpoints.day
      .handleSecurity(authenticate)
      .handle(session => date => guarded(Right(days.on(session.accountId, date))))
  )

  private val medicationEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.medicationOptions.handleSecurity(authenticate).handle(_ => _ => Right(registry.options)),
    Endpoints.listMedications
      .handleSecurity(authenticate)
      .handle(session => status => guarded(Right(registry.list(session.accountId, status)))),
    Endpoints.createMedication
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              medication <- idempotency.once(session.accountId, parsedKey, hashOf("medications", input)):
                registry.create(session.accountId, input)
            yield (locationOf("medications", medication.id), Rendering.etag(medication.version), medication)
    ,
    Endpoints.getMedication
      .handleSecurity(authenticate)
      .handle(session => id => guarded(registry.get(session.accountId, id).map(withEtag))),
    Endpoints.updateMedication
      .handleSecurity(authenticate)
      .handle: session =>
        (id, ifMatch, input) =>
          guarded:
            versionOf(ifMatch).flatMap(registry.update(session.accountId, id, _, input)).map(withEtag)
    ,
    Endpoints.changeMedicationState
      .handleSecurity(authenticate)
      .handle: session =>
        (id, ifMatch, body) =>
          guarded:
            versionOf(ifMatch).flatMap(registry.changeState(session.accountId, id, _, body.change)).map(withEtag)
  )

  private val scheduleEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.scheduleOptions
      .handleSecurity(authenticate)
      .handle(session => _ => guarded(Right(registry.scheduleOptions(session.accountId)))),
    Endpoints.previewSchedule
      .handleSecurity(authenticate)
      .handle(session => request => guarded(registry.preview(session.accountId, request.draft))),
    Endpoints.listSchedules
      .handleSecurity(authenticate)
      .handle(session => medicationId => guarded(registry.schedules(session.accountId, medicationId))),
    Endpoints.createSchedule
      .handleSecurity(authenticate)
      .handle: session =>
        (medicationId, key, draft) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              schedule <- idempotency.once(session.accountId, parsedKey, hashOf(s"schedules:$medicationId", draft)):
                registry.createSchedule(session.accountId, medicationId, draft)
            yield (locationOf("schedules", schedule.id), Rendering.etag(schedule.revision.toLong), schedule)
    ,
    Endpoints.getSchedule
      .handleSecurity(authenticate)
      .handle(session => id => guarded(registry.schedule(session.accountId, id).map(withRevisionTag))),
    Endpoints.reviseSchedule
      .handleSecurity(authenticate)
      .handle: session =>
        (id, ifMatch, draft) =>
          guarded:
            versionOf(ifMatch).flatMap(registry.revise(session.accountId, id, _, draft)).map(withRevisionTag)
    ,
    Endpoints.changeScheduleState
      .handleSecurity(authenticate)
      .handle: session =>
        (id, ifMatch, body) =>
          guarded:
            versionOf(ifMatch)
              .flatMap(registry.changeScheduleState(session.accountId, id, _, body.change))
              .map(withRevisionTag)
  )

  /** In mount order: the catch-all for unknown `/api` paths is last. */
  val endpoints: List[ServerEndpoint[Any, Identity]] =
    sessionEndpoints ++ dayEndpoints ++ medicationEndpoints ++ scheduleEndpoints :+
      Endpoints.unknown.handleError(_ => ApiFailure.notFound())

object ApiModule:
  private val VersionTag = """^"v(\d{1,18})"$""".r

  /** A request the endpoints could not decode: a missing or malformed bearer is 401, anything else is the problem
    * document for an unreadable request. The failure text of the decoder is dropped: it can quote the input.
    */
  val decodeFailureHandler: DecodeFailureHandler[Identity] = DecodeFailureHandler.pure: context =>
    DefaultDecodeFailureHandler
      .respond(context)
      .map: (status, _) =>
        val failure = if status == StatusCode.Unauthorized then ApiFailure.unauthenticated() else ApiFailure.malformed()
        ValuedEndpointOutput(Endpoints.failure, failure)

  private def withEtag(medication: Medication): (String, Medication) = (Rendering.etag(medication.version), medication)

  private def withRevisionTag(schedule: Schedule): (String, Schedule) =
    (Rendering.etag(schedule.revision.toLong), schedule)

  private def locationOf(collection: String, id: UUID): String = s"/api/v1/$collection/$id"

  /** `If-Match` (API-17): absent is 428, anything but one `"v<number>"` is a validation error. */
  private def versionOf(ifMatch: Option[String]): Either[ApiFailure, Long] = ifMatch match
    case None                      => Left(ApiFailure.preconditionRequired())
    case Some(VersionTag(version)) => Right(version.toLong)
    case Some(_)                   => Left(ApiFailure.invalid("If-Match", "invalid"))

  private def idempotencyKeyOf(header: Option[String]): Either[ApiFailure, UUID] = header match
    case None      => Left(ApiFailure.invalid("Idempotency-Key", "required"))
    case Some(key) => Try(UUID.fromString(key)).toOption.toRight(ApiFailure.invalid("Idempotency-Key", "invalid"))

  /** The hash that tells a repeat from a reuse of the key: the route and the body as the server read it. */
  private def hashOf[B: ApiJson.ReadWriter](route: String, body: B): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(s"$route\n${ApiJson.write(body)}".getBytes(UTF_8))
    HexFormat.of().formatHex(digest)
