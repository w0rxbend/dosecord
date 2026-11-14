package dosegram.api

import dosegram.api.auth.InitDataVerifier
import dosegram.api.auth.RequestLimits
import dosegram.api.auth.Session
import dosegram.api.auth.SessionService
import dosegram.api.auth.SessionStore
import dosegram.core.application.Application
import dosegram.core.application.exports.ExportRequests
import dosegram.core.domain.copy.Lang
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.UnitOfWork
import sttp.model.Method
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
  * @param blobStates
  *   the drawn mood states: the renderer's geometry, which the composition root converts (MINI_APP section 7.9).
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
    tracking: TrackingUseCases,
    blobStates: MoodBlobStates,
    log: String => Unit,
    exportRequests: ExportRequests
):
  import ApiModule.*

  private val sessions = SessionService(verifier, uow, sessionStore, clock)
  private val limits = RequestLimits.default()
  private val idempotency = Idempotency(idempotencyStore, clock)
  private val registry = Registry(uow, clock)
  private val days = DayViews(uow, clock)
  private val insights = Insights(uow, tracking, clock)
  private val insightReports = InsightReports(uow, tracking, clock)
  private val history = History(uow, tracking, clock)
  private val hydration = Hydration(uow, tracking)
  private val mood = Mood(uow, tracking, blobStates)
  private val sleep = Sleep(uow, tracking, clock)
  private val nutrition = Nutrition(uow, tracking)
  private val reminders = Reminders(uow, clock, Application.reminderSettings(clock))
  private val accountSettings = AccountSettingsApi(uow, clock)
  private val exports = Exports(uow, clock, exportRequests)

  /** Deletes expired sessions and idempotency records older than 24 hours; the caller runs it on a period. */
  def sweep(): Int = sessions.sweep() + idempotency.sweep()

  /** The bearer check, then the request limits of API-18: an unknown token is 401 before it can spend a budget. */
  private def authenticate(credentials: (String, Method)): Either[ApiFailure, Session] =
    val (bearer, method) = credentials
    guarded:
      for
        session <- sessions.authenticate(bearer)
        _ <- limits.admit(session, method, clock.now())
      yield session

  /** The medication routes (ARCHITECTURE API-19, PRODUCT_SPEC R-IDC-9): without an active `medication` consent a read
    * shows nothing and a write stores nothing; the answer is 403 `consent_required`.
    */
  private def medication[A](session: Session)(run: => Either[ApiFailure, A]): Either[ApiFailure, A] =
    guarded:
      if uow.transaction(_.consents.isGranted(session.accountId, ConsentPurpose.Medication)) then run
      else Left(ApiFailure.consentRequired(ConsentPurpose.Medication))

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
              Bootstrap(
                issued.timeZone,
                RegistryLimits.published,
                grantedPurposes(issued.session.accountId),
                language = Some(languageOf(issued.session).code)
              )
            )
    ,
    Endpoints.deleteSession.handleSecurity(authenticate).handle(session => _ => guarded(Right(sessions.end(session)))),
    // Authentication already moved the idle timer (API-06).
    Endpoints.keepSessionAlive.handleSecurity(authenticate).handle(_ => _ => Right(()))
  )

  private val dayEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.today
      .handleSecurity(authenticate)
      .handle(session => _ => medication(session)(Right(days.today(session.accountId)))),
    Endpoints.day
      .handleSecurity(authenticate)
      .handle(session => date => medication(session)(Right(days.on(session.accountId, date))))
  )

  private val medicationEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.medicationOptions
      .handleSecurity(authenticate)
      .handle(session => _ => medication(session)(Right(registry.options))),
    Endpoints.listMedications
      .handleSecurity(authenticate)
      .handle(session => status => medication(session)(Right(registry.list(session.accountId, status)))),
    Endpoints.createMedication
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          medication(session):
            for
              parsedKey <- idempotencyKeyOf(key)
              medication <- idempotency.once(session.accountId, parsedKey, hashOf("medications", input)):
                registry.create(session.accountId, input)
            yield (locationOf("medications", medication.id), Rendering.etag(medication.version), medication)
    ,
    Endpoints.getMedication
      .handleSecurity(authenticate)
      .handle(session => id => medication(session)(registry.get(session.accountId, id).map(withEtag))),
    Endpoints.updateMedication
      .handleSecurity(authenticate)
      .handle: session =>
        (id, ifMatch, input) =>
          medication(session):
            versionOf(ifMatch).flatMap(registry.update(session.accountId, id, _, input)).map(withEtag)
    ,
    Endpoints.changeMedicationState
      .handleSecurity(authenticate)
      .handle: session =>
        (id, ifMatch, body) =>
          medication(session):
            versionOf(ifMatch).flatMap(registry.changeState(session.accountId, id, _, body.change)).map(withEtag)
  )

  private val scheduleEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.scheduleOptions
      .handleSecurity(authenticate)
      .handle(session => _ => medication(session)(Right(registry.scheduleOptions(session.accountId)))),
    Endpoints.previewSchedule
      .handleSecurity(authenticate)
      .handle(session => request => medication(session)(registry.preview(session.accountId, request.draft))),
    Endpoints.listSchedules
      .handleSecurity(authenticate)
      .handle(session => medicationId => medication(session)(registry.schedules(session.accountId, medicationId))),
    Endpoints.createSchedule
      .handleSecurity(authenticate)
      .handle: session =>
        (medicationId, key, draft) =>
          medication(session):
            for
              parsedKey <- idempotencyKeyOf(key)
              schedule <- idempotency.once(session.accountId, parsedKey, hashOf(s"schedules:$medicationId", draft)):
                registry.createSchedule(session.accountId, medicationId, draft)
            yield (locationOf("schedules", schedule.id), Rendering.etag(schedule.revision.toLong), schedule)
    ,
    Endpoints.getSchedule
      .handleSecurity(authenticate)
      .handle(session => id => medication(session)(registry.schedule(session.accountId, id).map(withRevisionTag))),
    Endpoints.reviseSchedule
      .handleSecurity(authenticate)
      .handle: session =>
        (id, ifMatch, draft) =>
          medication(session):
            versionOf(ifMatch).flatMap(registry.revise(session.accountId, id, _, draft)).map(withRevisionTag)
    ,
    Endpoints.changeScheduleState
      .handleSecurity(authenticate)
      .handle: session =>
        (id, ifMatch, body) =>
          medication(session):
            versionOf(ifMatch)
              .flatMap(registry.changeScheduleState(session.accountId, id, _, body.change))
              .map(withRevisionTag)
  )

  private val insightEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.insightsOverview
      .handleSecurity(authenticate)
      .handle(session => rangeDays => guarded(insights.overview(session.accountId, rangeDays))),
    Endpoints.insightMonth
      .handleSecurity(authenticate)
      .handle(session => month => guarded(insightReports.month(session.accountId, month))),
    Endpoints.insightCorrelations
      .handleSecurity(authenticate)
      .handle(session => _ => guarded(insightReports.correlations(session.accountId)))
  )

  private def languageOf(session: Session): Lang = uow.transaction(_.accounts.languageOf(session.accountId))

  /** Every text a route of a session writes is in the account's language (ROADMAP G2.22, MINI_APP 5.7 rule 8). */
  private def localised(endpoint: ServerEndpoint[Any, Identity]): ServerEndpoint[Any, Identity] =
    ServerEndpoint[
      endpoint.SECURITY_INPUT,
      endpoint.PRINCIPAL,
      endpoint.INPUT,
      endpoint.ERROR_OUTPUT,
      endpoint.OUTPUT,
      Any,
      Identity
    ](
      endpoint.endpoint,
      endpoint.securityLogic,
      monad =>
        principal =>
          input =>
            principal match
              case session: Session => Lang.within(languageOf(session))(endpoint.logic(monad)(principal)(input))
              case _                => endpoint.logic(monad)(principal)(input)
    )

  private def grantedPurposes(accountId: UUID): List[String] =
    uow.transaction(_.consents.granted(accountId)).map(_.purpose.id)

  private val settingsEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.getSettings
      .handleSecurity(authenticate)
      .handle(session =>
        _ => guarded(accountSettings.settings(session.accountId).map(view => (Rendering.etag(view.version), view)))
      ),
    Endpoints.updateSettings
      .handleSecurity(authenticate)
      .handle: session =>
        (ifMatch, input) =>
          guarded:
            versionOf(ifMatch)
              .flatMap(accountSettings.saveSettings(session.accountId, _, input))
              .map(view => (Rendering.etag(view.version), view))
    ,
    Endpoints.listTimeZones.handleSecurity(authenticate).handle(_ => _ => guarded(Right(accountSettings.timeZones))),
    Endpoints.previewTimeZone
      .handleSecurity(authenticate)
      .handle(session => request => guarded(accountSettings.preview(session.accountId, request))),
    Endpoints.listConsents
      .handleSecurity(authenticate)
      .handle(session => _ => guarded(accountSettings.consents(session.accountId))),
    // A withdrawal needs the confirmation in the chat (ARCHITECTURE section 9.3), which no route can give (MA-STEP-1).
    Endpoints.withdrawConsent
      .handleSecurity(authenticate)
      .handle: _ =>
        purpose =>
          if ConsentPurpose.ofId(purpose).isEmpty then Left(ApiFailure.notFound())
          else Left(ApiFailure.stepupRequired()),
    Endpoints.getNotificationSettings
      .handleSecurity(authenticate)
      .handle(session =>
        _ => guarded(accountSettings.notifications(session.accountId).map(view => (Rendering.etag(view.version), view)))
      ),
    Endpoints.updateNotificationSettings
      .handleSecurity(authenticate)
      .handle: session =>
        (ifMatch, input) =>
          guarded:
            versionOf(ifMatch)
              .flatMap(accountSettings.saveNotifications(session.accountId, _, input))
              .map(view => (Rendering.etag(view.version), view))
  )

  // The tracking routes check the consent of their domain in the use case, inside the same transaction (API-19).
  private val hydrationEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.listHydrationEntries
      .handleSecurity(authenticate)
      .handle(session => (from, to) => guarded(hydration.entries(session.accountId, from, to))),
    Endpoints.createHydrationEntry
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              logged <- idempotency.once(session.accountId, parsedKey, hashOf("hydration/entries", input)):
                hydration.log(session.accountId, parsedKey, input)
            yield logged
    ,
    Endpoints.deleteHydrationEntry
      .handleSecurity(authenticate)
      .handle(session => id => guarded(hydration.remove(session.accountId, id))),
    Endpoints.getHydrationSettings
      .handleSecurity(authenticate)
      .handle(session =>
        _ => guarded(hydration.settings(session.accountId).map(view => (Rendering.etag(view.version), view)))
      ),
    Endpoints.updateHydrationSettings
      .handleSecurity(authenticate)
      .handle: session =>
        (ifMatch, input) =>
          guarded:
            versionOf(ifMatch)
              .flatMap(hydration.saveSettings(session.accountId, _, input))
              .map(view => (Rendering.etag(view.version), view))
    ,
    Endpoints.getHydrationSummary
      .handleSecurity(authenticate)
      .handle(session => (from, to) => guarded(hydration.summary(session.accountId, from, to)))
  )

  private val moodEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.getMoodScale.handleSecurity(authenticate).handle(session => _ => guarded(mood.scale(session.accountId))),
    Endpoints.getMoodBlobStates
      .handleSecurity(authenticate)
      .handle(session => revision => guarded(mood.blobStates(session.accountId, revision))),
    Endpoints.listMoodCheckins
      .handleSecurity(authenticate)
      .handle(session => (from, to) => guarded(mood.checkins(session.accountId, from, to))),
    Endpoints.createMoodCheckin
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              recorded <- idempotency.once(session.accountId, parsedKey, hashOf("mood/checkins", input)):
                mood.checkIn(session.accountId, parsedKey, input)
            yield recorded
    ,
    Endpoints.deleteMoodCheckin
      .handleSecurity(authenticate)
      .handle(session => id => guarded(mood.remove(session.accountId, id))),
    Endpoints.getMoodSummary
      .handleSecurity(authenticate)
      .handle(session => (from, to) => guarded(mood.summary(session.accountId, from, to)))
  )

  private val sleepEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.listSleepEpisodes
      .handleSecurity(authenticate)
      .handle(session => (from, to) => guarded(sleep.episodes(session.accountId, from, to))),
    Endpoints.createSleepEpisode
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              recorded <- idempotency.once(session.accountId, parsedKey, hashOf("sleep/episodes", input)):
                sleep.enter(session.accountId, parsedKey, input)
            yield recorded
    ,
    Endpoints.deleteSleepEpisode
      .handleSecurity(authenticate)
      .handle(session => id => guarded(sleep.remove(session.accountId, id))),
    Endpoints.createSleepCheckin
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              status <- idempotency.once(session.accountId, parsedKey, hashOf("sleep/checkins", input)):
                sleep.checkIn(session.accountId, parsedKey, input)
            yield status
    ,
    Endpoints.getSleepSettings
      .handleSecurity(authenticate)
      .handle(session =>
        _ => guarded(sleep.settings(session.accountId).map(view => (Rendering.etag(view.version), view)))
      ),
    Endpoints.updateSleepSettings
      .handleSecurity(authenticate)
      .handle: session =>
        (ifMatch, input) =>
          guarded:
            versionOf(ifMatch)
              .flatMap(sleep.saveSettings(session.accountId, _, input))
              .map(view => (Rendering.etag(view.version), view))
    ,
    Endpoints.getSleepSummary
      .handleSecurity(authenticate)
      .handle(session => (from, to) => guarded(sleep.summary(session.accountId, from, to)))
  )

  private val reminderEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.getReminders
      .handleSecurity(authenticate)
      .handle(session => _ => guarded(Right(reminders.view(session.accountId)))),
    Endpoints.listReminderPresets
      .handleSecurity(authenticate)
      .handle(session => _ => guarded(Right(reminders.presets(session.accountId)))),
    Endpoints.createReminderPlan
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              view <- idempotency.once(session.accountId, parsedKey, hashOf("reminders/plans", input)):
                reminders.apply(session.accountId, input)
            yield view
    ,
    Endpoints.deleteReminderPlan
      .handleSecurity(authenticate)
      .handle(session => kind => guarded(reminders.turnOff(session.accountId, kind))),
    Endpoints.createReminderPause
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              view <- idempotency.once(session.accountId, parsedKey, hashOf("reminders/pause", input)):
                reminders.pause(session.accountId, input)
            yield view
    ,
    Endpoints.getDayProfile
      .handleSecurity(authenticate)
      .handle(session =>
        _ => guarded(Right(reminders.day(session.accountId)).map(view => (Rendering.etag(view.version), view)))
      ),
    Endpoints.updateDayProfile
      .handleSecurity(authenticate)
      .handle: session =>
        (ifMatch, input) =>
          guarded:
            versionOf(ifMatch)
              .flatMap(reminders.saveDay(session.accountId, _, input))
              .map(view => (Rendering.etag(view.version), view))
    ,
    Endpoints.getReminderTimeline
      .handleSecurity(authenticate)
      .handle(session => date => guarded(Right(reminders.timeline(session.accountId, date))))
  )

  // `GET /meals/recent` is mounted before `GET /meals/{id}`, so "recent" is never read as an id.
  private val nutritionEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.listMeals
      .handleSecurity(authenticate)
      .handle(session => (from, to) => guarded(nutrition.meals(session.accountId, from, to))),
    Endpoints.createMeal
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              recorded <- idempotency.once(session.accountId, parsedKey, hashOf("meals", input)):
                nutrition.record(session.accountId, parsedKey, input)
            yield recorded
    ,
    Endpoints.listRecentMeals
      .handleSecurity(authenticate)
      .handle(session => mealType => guarded(nutrition.recent(session.accountId, mealType))),
    Endpoints.getMeal
      .handleSecurity(authenticate)
      .handle(session => id => guarded(nutrition.meal(session.accountId, id))),
    Endpoints.deleteMeal
      .handleSecurity(authenticate)
      .handle(session => id => guarded(nutrition.remove(session.accountId, id))),
    Endpoints.getNutritionSummary
      .handleSecurity(authenticate)
      .handle(session => (from, to) => guarded(nutrition.summary(session.accountId, from, to))),
    Endpoints.getNutritionSettings
      .handleSecurity(authenticate)
      .handle(session =>
        _ => guarded(nutrition.settings(session.accountId).map(view => (Rendering.etag(view.version), view)))
      ),
    Endpoints.updateNutritionSettings
      .handleSecurity(authenticate)
      .handle: session =>
        (ifMatch, input) =>
          guarded:
            versionOf(ifMatch)
              .flatMap(nutrition.saveSettings(session.accountId, _, input))
              .map(view => (Rendering.etag(view.version), view))
  )

  private val calendarEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    Endpoints.getInsightCalendar
      .handleSecurity(authenticate)
      .handle(session => (domain, month) => guarded(history.calendar(session.accountId, domain, month)))
  )

  // The export routes (ROADMAP G3.10): no consent gate, because the export is the right of access to every record.
  private val exportEndpoints: List[ServerEndpoint[Any, Identity]] = List(
    ExportEndpoints.createExport
      .handleSecurity(authenticate)
      .handle: session =>
        (key, input) =>
          guarded:
            for
              parsedKey <- idempotencyKeyOf(key)
              view <- idempotency.once(session.accountId, parsedKey, hashOf("exports", input)):
                exports.request(session.accountId, input)
            yield view
    ,
    ExportEndpoints.listExports
      .handleSecurity(authenticate)
      .handle(session => _ => guarded(Right(exports.list(session.accountId)))),
    ExportEndpoints.getExport
      .handleSecurity(authenticate)
      .handle(session => id => guarded(exports.get(session.accountId, id))),
    ExportEndpoints.createDownloadTicket
      .handleSecurity(authenticate)
      .handle(session => id => guarded(exports.ticket(session.accountId, id))),
    ExportEndpoints.download.handle: (ticket, forwardedFor, remote) =>
      guarded:
        exports
          .download(ticket, Exports.clientAddress(forwardedFor, remote))
          .map(file => (file.contentDisposition, file.contentType, file.bytes))
  )

  /** In mount order: the catch-all for unknown `/api` paths is last. */
  val endpoints: List[ServerEndpoint[Any, Identity]] =
    (sessionEndpoints ++ settingsEndpoints ++ dayEndpoints ++ medicationEndpoints ++ scheduleEndpoints ++
      hydrationEndpoints ++ moodEndpoints ++ sleepEndpoints ++ reminderEndpoints ++ calendarEndpoints ++ insightEndpoints ++
      nutritionEndpoints ++ exportEndpoints).map(localised) :+
      Endpoints.unknown.handleError(_ => ApiFailure.notFound())

object ApiModule:
  private val VersionTag = """^"v(\d{1,18})"$""".r

  /** A request the endpoints could not decode: a missing or malformed bearer is 401, a body over the limit is 413,
    * anything else is the problem document for an unreadable request. The failure text of the decoder is dropped: it
    * can quote the input.
    */
  val decodeFailureHandler: DecodeFailureHandler[Identity] = DecodeFailureHandler.pure: context =>
    DefaultDecodeFailureHandler
      .respond(context)
      .map: (status, _) =>
        val failure = status match
          case StatusCode.Unauthorized    => ApiFailure.unauthenticated()
          case StatusCode.PayloadTooLarge => ApiFailure.payloadTooLarge()
          case _                          => ApiFailure.malformed()
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
