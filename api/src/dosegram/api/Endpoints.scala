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

  private[api] val v1 = endpoint.in("api" / "v1").errorOut(failure).maxRequestBodyLength(MaxBodyBytes)
  // The method is read for the request limits of API-18; it is not part of the documented interface.
  private[api] val secured = v1.securityIn(auth.bearer[String]()).securityIn(extractFromRequest(_.method))

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

  // ---------- insights-and-correlations ----------

  val insightsOverview =
    secured.get
      .in("insights" / "overview")
      .in(query[Int]("range").description("The number of days the statistics cover: 7 or 30"))
      .out(jsonBody[InsightsOverview])
      .name("getInsightsOverview")
      .description("The statistics of a period, one block per domain whose consent is active.")

  val insightMonth =
    secured.get
      .in("insights" / "month")
      .in(query[Option[String]]("month").description("YYYY-MM; absent is the current month"))
      .out(jsonBody[InsightMonth])
      .name("getInsightMonth")
      .description("One calendar month: one block per domain whose consent is active, and each medication's counts.")

  val insightCorrelations =
    secured.get
      .in("insights" / "correlations")
      .out(jsonBody[InsightList])
      .name("listInsightCorrelations")
      .description("The patterns of the last 28 days that passed every check, and those close to their data.")

  // ---------- settings, consents (identity-and-consent, schedule-and-reminders) ----------

  val getSettings = secured.get.in("settings").out(etag).out(jsonBody[SettingsView]).name("getSettings")

  val updateSettings =
    secured.patch
      .in("settings")
      .in(ifMatch)
      .in(jsonBody[SettingsInput])
      .out(etag)
      .out(jsonBody[SettingsView])
      .name("updateSettings")
      .description("Replaces the editable set; a new time zone moves the schedules that follow the account.")

  val listTimeZones = secured.get.in("time-zones").out(jsonBody[TimeZoneList]).name("listTimeZones")

  val previewTimeZone =
    secured.post
      .in("time-zone-previews")
      .in(jsonBody[TimeZonePreviewRequest])
      .out(jsonBody[TimeZonePreview])
      .name("previewTimeZone")
      .description("A pure evaluation of a zone change; nothing is written.")

  val listConsents = secured.get.in("consents").out(jsonBody[ConsentList]).name("listConsents")

  val withdrawConsent =
    secured.post
      .in("consents" / path[String]("purpose") / "withdrawals")
      .out(noContent)
      .name("withdrawConsent")
      .description("Needs a confirmation in the chat (step-up). Until the step-up exists it answers stepup_required.")

  val getNotificationSettings =
    secured.get
      .in("notification-settings")
      .out(etag)
      .out(jsonBody[NotificationSettingsView])
      .name("getNotificationSettings")

  val updateNotificationSettings =
    secured.patch
      .in("notification-settings")
      .in(ifMatch)
      .in(jsonBody[NotificationSettingsInput])
      .out(etag)
      .out(jsonBody[NotificationSettingsView])
      .name("updateNotificationSettings")

  // ---------- hydration ----------

  private val from = query[Option[LocalDate]]("from").description("First local day; with `to`, at most 366 days")
  private val to = query[Option[LocalDate]]("to").description("Last local day")

  val listHydrationEntries =
    secured.get
      .in("hydration" / "entries")
      .in(from)
      .in(to)
      .out(jsonBody[HydrationEntries])
      .name("listHydrationEntries")

  val createHydrationEntry =
    secured.post
      .in("hydration" / "entries")
      .in(idempotencyKey)
      .in(jsonBody[HydrationEntryInput])
      .out(created)
      .out(jsonBody[HydrationLogged])
      .name("createHydrationEntry")

  val deleteHydrationEntry =
    secured.delete
      .in("hydration" / "entries" / id)
      .out(jsonBody[HydrationDayView])
      .name("deleteHydrationEntry")
      .description("Voids the entry and answers with its day; a second delete answers the same.")

  val getHydrationSettings =
    secured.get.in("hydration" / "settings").out(etag).out(jsonBody[HydrationSettings]).name("getHydrationSettings")

  val updateHydrationSettings =
    secured.patch
      .in("hydration" / "settings")
      .in(ifMatch)
      .in(jsonBody[HydrationSettingsInput])
      .out(etag)
      .out(jsonBody[HydrationSettings])
      .name("updateHydrationSettings")

  val getHydrationSummary =
    secured.get
      .in("hydration" / "summary")
      .in(from)
      .in(to)
      .out(jsonBody[HydrationSummary])
      .name("getHydrationSummary")

  // ---------- mood ----------

  val getMoodScale = secured.get.in("mood" / "scale").out(jsonBody[MoodScale]).name("getMoodScale")

  val getMoodBlobStates =
    secured.get
      .in("mood" / "blob-states")
      .in(query[Option[String]]("revision"))
      .out(jsonBody[MoodBlobStates])
      .name("getMoodBlobStates")
      .description("The global geometry of the mood blob; it holds no user data.")

  val listMoodCheckins =
    secured.get.in("mood" / "checkins").in(from).in(to).out(jsonBody[MoodCheckins]).name("listMoodCheckins")

  val createMoodCheckin =
    secured.post
      .in("mood" / "checkins")
      .in(idempotencyKey)
      .in(jsonBody[MoodCheckinInput])
      .out(created)
      .out(jsonBody[MoodRecordedView])
      .name("createMoodCheckin")

  val deleteMoodCheckin =
    secured.delete
      .in("mood" / "checkins" / id)
      .out(jsonBody[MoodDayView])
      .name("deleteMoodCheckin")
      .description("Voids the check-in and answers with its day; a second delete answers the same.")

  val getMoodSummary =
    secured.get.in("mood" / "summary").in(from).in(to).out(jsonBody[MoodSummary]).name("getMoodSummary")

  // ---------- nutrition ----------

  val listMeals = secured.get.in("meals").in(from).in(to).out(jsonBody[Meals]).name("listMeals")

  val createMeal =
    secured.post
      .in("meals")
      .in(idempotencyKey)
      .in(jsonBody[MealEntryInput])
      .out(created)
      .out(jsonBody[MealRecordedView])
      .name("createMeal")
      .description("A meal; with `supersedes`, a change of a recorded meal, which the new one replaces.")

  val listRecentMeals =
    secured.get
      .in("meals" / "recent")
      .in(query[MealKind]("mealType"))
      .out(jsonBody[MealRecent])
      .name("listRecentMeals")

  val getMeal = secured.get.in("meals" / id).out(jsonBody[MealView]).name("getMeal")

  val deleteMeal =
    secured.delete
      .in("meals" / id)
      .out(jsonBody[NutritionDay])
      .name("deleteMeal")
      .description("Voids the meal and answers with its day; a second delete answers the same.")

  val getNutritionSummary =
    secured.get.in("nutrition" / "summary").in(from).in(to).out(jsonBody[NutritionSummary]).name("getNutritionSummary")

  val getNutritionSettings =
    secured.get.in("nutrition" / "settings").out(etag).out(jsonBody[NutritionSettings]).name("getNutritionSettings")

  val updateNutritionSettings =
    secured.patch
      .in("nutrition" / "settings")
      .in(ifMatch)
      .in(jsonBody[NutritionSettingsInput])
      .out(etag)
      .out(jsonBody[NutritionSettings])
      .name("updateNutritionSettings")

  // ---------- sleep ----------

  val listSleepEpisodes =
    secured.get.in("sleep" / "episodes").in(from).in(to).out(jsonBody[SleepEpisodes]).name("listSleepEpisodes")

  val createSleepEpisode =
    secured.post
      .in("sleep" / "episodes")
      .in(idempotencyKey)
      .in(jsonBody[SleepEpisodeInput])
      .out(created)
      .out(jsonBody[SleepEpisodeView])
      .name("createSleepEpisode")
      .description("A night or a nap entered after the fact.")

  val deleteSleepEpisode =
    secured.delete
      .in("sleep" / "episodes" / id)
      .out(jsonBody[SleepStatusView])
      .name("deleteSleepEpisode")
      .description("Voids the episode and answers where sleep stands; a second delete answers the same.")

  val createSleepCheckin =
    secured.post
      .in("sleep" / "checkins")
      .in(idempotencyKey)
      .in(jsonBody[SleepCheckinInput])
      .out(jsonBody[SleepStatusView])
      .name("createSleepCheckin")
      .description("Going to sleep or I'm up, now.")

  val getSleepSettings =
    secured.get.in("sleep" / "settings").out(etag).out(jsonBody[SleepSettings]).name("getSleepSettings")

  val updateSleepSettings =
    secured.patch
      .in("sleep" / "settings")
      .in(ifMatch)
      .in(jsonBody[SleepSettingsInput])
      .out(etag)
      .out(jsonBody[SleepSettings])
      .name("updateSleepSettings")

  val getSleepSummary =
    secured.get.in("sleep" / "summary").in(from).in(to).out(jsonBody[SleepSummary]).name("getSleepSummary")

  // ---------- insights calendar ----------

  private given Codec[String, InsightDomain, CodecFormat.TextPlain] =
    Codec.string
      .mapDecode(name =>
        InsightDomain.values.find(_.wire == name) match
          case Some(domain) => DecodeResult.Value(domain)
          case None         => DecodeResult.Mismatch("a domain", "another value")
      )(_.wire)
      .schema(summon[Schema[InsightDomain]])

  val getInsightCalendar =
    secured.get
      .in("insights" / "calendar")
      .in(query[InsightDomain]("domain"))
      .in(query[Option[String]]("month").description("YYYY-MM; absent is the current month"))
      .out(jsonBody[InsightCalendar])
      .name("getInsightCalendar")

  // ---------- reminders (ADR-023) ----------

  val getReminders = secured.get.in("reminders").out(jsonBody[RemindersView]).name("getReminders")

  val listReminderPresets =
    secured.get
      .in("reminders" / "presets")
      .out(jsonBody[ReminderPresets])
      .name("listReminderPresets")
      .description("Every preset with its next times for the user's current day; reading saves nothing.")

  val createReminderPlan =
    secured.post
      .in("reminders" / "plans")
      .in(idempotencyKey)
      .in(jsonBody[ReminderPlanInput])
      .out(jsonBody[RemindersView])
      .name("createReminderPlan")
      .description("Turns a preset on, replacing the plan of its kind.")

  val deleteReminderPlan =
    secured.delete
      .in("reminders" / "plans" / path[String]("kind"))
      .out(jsonBody[RemindersView])
      .name("deleteReminderPlan")
      .description("Turns the plan of a kind off; a second delete answers the same.")

  val createReminderPause =
    secured.post
      .in("reminders" / "pause")
      .in(idempotencyKey)
      .in(jsonBody[ReminderPauseInput])
      .out(jsonBody[RemindersView])
      .name("createReminderPause")
      .description("Pauses every prompt for 1 to 24 hours; 0 resumes. Medication reminders are not affected.")

  val getDayProfile =
    secured.get.in("reminders" / "day").out(etag).out(jsonBody[DayProfileView]).name("getDayProfile")

  val updateDayProfile =
    secured.patch
      .in("reminders" / "day")
      .in(ifMatch)
      .in(jsonBody[DayProfileInput])
      .out(etag)
      .out(jsonBody[DayProfileView])
      .name("updateDayProfile")

  val getReminderTimeline =
    secured.get
      .in("reminders" / "timeline")
      .in(query[Option[LocalDate]]("date").description("A local day; absent is tomorrow"))
      .out(jsonBody[ReminderTimeline])
      .name("getReminderTimeline")

  /** Any other path under `/api` answers with the problem document for not-found, never with a bare status. */
  val unknown = endpoint.in("api" / paths).errorOut(failure)

  /** Every route of the committed OpenAPI document, in the order of ARCHITECTURE section 9.5. */
  val documented: List[AnyEndpoint] = List(
    createSession,
    deleteSession,
    keepSessionAlive,
    getSettings,
    updateSettings,
    listTimeZones,
    previewTimeZone,
    listConsents,
    withdrawConsent,
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
    changeScheduleState,
    getNotificationSettings,
    updateNotificationSettings,
    listHydrationEntries,
    createHydrationEntry,
    deleteHydrationEntry,
    getHydrationSettings,
    updateHydrationSettings,
    getHydrationSummary,
    getMoodScale,
    getMoodBlobStates,
    listMoodCheckins,
    createMoodCheckin,
    deleteMoodCheckin,
    getMoodSummary,
    listSleepEpisodes,
    createSleepEpisode,
    deleteSleepEpisode,
    createSleepCheckin,
    getSleepSettings,
    updateSleepSettings,
    getSleepSummary,
    getInsightCalendar,
    insightsOverview,
    insightMonth,
    insightCorrelations,
    getReminders,
    listReminderPresets,
    createReminderPlan,
    deleteReminderPlan,
    createReminderPause,
    getDayProfile,
    updateDayProfile,
    getReminderTimeline,
    listMeals,
    createMeal,
    listRecentMeals,
    getMeal,
    deleteMeal,
    getNutritionSummary,
    getNutritionSettings,
    updateNutritionSettings
  ) ++ ExportEndpoints.documented
