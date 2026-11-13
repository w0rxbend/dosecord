package dosegram.api

import dosegram.api.ApiJson.jsonBody
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.server.model.EndpointExtensions.*

import java.time.LocalDate
import java.util.UUID

/** The Tapir definitions of `/api/v1` (ARCHITECTURE section 9.5). They are descriptions only: [[ApiModule]] attaches
  * the logic, and the committed OpenAPI document is generated from [[Endpoints.documented]].
  */
object Endpoints:

  /** ARCHITECTURE section 9.4, body limits: 64 kB for JSON. */
  val MaxBodyBytes: Long = 64L * 1024L

  val failure: EndpointOutput[ApiFailure] =
    statusCode
      .and(header[Option[Long]]("Retry-After"))
      .and(ApiJson.problemBody[Problem])
      .map((_, retryAfter, problem) => ApiFailure(problem, retryAfter))(f => (f.status, f.retryAfterSeconds, f.problem))

  private val v1 = endpoint.in("api" / "v1").errorOut(failure).maxRequestBodyLength(MaxBodyBytes)
  private val secured = v1.securityIn(auth.bearer[String]())

  private val created = statusCode(StatusCode.Created)
  private val noContent = statusCode(StatusCode.NoContent)
  private val etag = header[String]("ETag").description("\"v<version>\" of the representation (API-17)")
  private val location = header[String]("Location")
  private val ifMatch = header[Option[String]]("If-Match").description("The ETag the edit is based on; required")
  private val idempotencyKey =
    header[Option[String]]("Idempotency-Key").description("A UUID per user action; required (API-16)")
  private val id = path[UUID]("id")

  private given Codec[String, MedicationState, CodecFormat.TextPlain] =
    Codec.string
      .mapDecode(name =>
        MedicationState.values.find(_.wire == name) match
          case Some(state) => DecodeResult.Value(state)
          case None        => DecodeResult.Mismatch("a medication status", "another value")
      )(_.wire)
      .schema(summon[Schema[MedicationState]])

  // ---------- identity-and-consent ----------

  val createSession =
    v1.post
      .in("session")
      .in(jsonBody[SessionRequest])
      .out(created)
      .out(jsonBody[SessionCreated])
      .name("createSession")
      .description("Exchanges Telegram launch data for a short-lived session. No cookie is set.")

  val deleteSession = secured.delete.in("session").out(noContent).name("deleteSession")

  val keepSessionAlive = secured.post.in("session" / "keepalive").out(noContent).name("keepSessionAlive")

  // ---------- daily-overview ----------

  val today = secured.get.in("today").out(jsonBody[DayView]).name("getToday")

  val day = secured.get.in("days" / path[LocalDate]("date")).out(jsonBody[DayView]).name("getDay")

  // ---------- medication-registry ----------

  val medicationOptions =
    secured.get.in("medication-options").out(jsonBody[MedicationOptions]).name("getMedicationOptions")

  val listMedications =
    secured.get
      .in("medications")
      .in(query[Option[MedicationState]]("status"))
      .out(jsonBody[MedicationList])
      .name("listMedications")

  val createMedication =
    secured.post
      .in("medications")
      .in(idempotencyKey)
      .in(jsonBody[MedicationInput])
      .out(created)
      .out(location)
      .out(etag)
      .out(jsonBody[Medication])
      .name("createMedication")

  val getMedication = secured.get.in("medications" / id).out(etag).out(jsonBody[Medication]).name("getMedication")

  val updateMedication =
    secured.patch
      .in("medications" / id)
      .in(ifMatch)
      .in(jsonBody[MedicationInput])
      .out(etag)
      .out(jsonBody[Medication])
      .name("updateMedication")
      .description("Replaces the editable set; an absent optional member clears the value.")

  val changeMedicationState =
    secured.post
      .in("medications" / id / "state-changes")
      .in(ifMatch)
      .in(jsonBody[MedicationStateChange])
      .out(etag)
      .out(jsonBody[Medication])
      .name("changeMedicationState")

  // ---------- schedule-and-reminders ----------

  val scheduleOptions = secured.get.in("schedule-options").out(jsonBody[ScheduleOptions]).name("getScheduleOptions")

  val previewSchedule =
    secured.post
      .in("schedule-previews")
      .in(jsonBody[SchedulePreviewRequest])
      .out(jsonBody[SchedulePreview])
      .name("previewSchedule")
      .description("A pure evaluation of an unsaved draft over the next seven days; nothing is written.")

  val listSchedules =
    secured.get.in("medications" / id / "schedules").out(jsonBody[ScheduleList]).name("listSchedules")

  val createSchedule =
    secured.post
      .in("medications" / id / "schedules")
      .in(idempotencyKey)
      .in(jsonBody[ScheduleDraft])
      .out(created)
      .out(location)
      .out(etag)
      .out(jsonBody[Schedule])
      .name("createSchedule")

  val getSchedule = secured.get.in("schedules" / id).out(etag).out(jsonBody[Schedule]).name("getSchedule")

  val reviseSchedule =
    secured.post
      .in("schedules" / id / "revisions")
      .in(ifMatch)
      .in(jsonBody[ScheduleDraft])
      .out(created)
      .out(etag)
      .out(jsonBody[Schedule])
      .name("reviseSchedule")
      .description("An edit is a new revision on the revision named by If-Match.")

  val changeScheduleState =
    secured.post
      .in("schedules" / id / "state-changes")
      .in(ifMatch)
      .in(jsonBody[ScheduleStateChange])
      .out(etag)
      .out(jsonBody[Schedule])
      .name("changeScheduleState")

  /** Any other path under `/api` answers with the problem document for not-found, never with a bare status. */
  val unknown = endpoint.in("api" / paths).errorOut(failure)

  /** Every route of the committed OpenAPI document, in the order of ARCHITECTURE section 9.5. */
  val documented: List[AnyEndpoint] = List(
    createSession,
    deleteSession,
    keepSessionAlive,
    today,
    day,
    medicationOptions,
    listMedications,
    createMedication,
    getMedication,
    updateMedication,
    changeMedicationState,
    scheduleOptions,
    previewSchedule,
    listSchedules,
    createSchedule,
    getSchedule,
    reviseSchedule,
    changeScheduleState
  )
