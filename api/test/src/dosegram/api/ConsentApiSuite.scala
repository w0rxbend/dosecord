package dosegram.api

import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.ports.ConsentPurpose

import java.time.ZoneId
import java.util.UUID

/** ARCHITECTURE API-19 and PRODUCT_SPEC R-IDC-9 for the medication domain over HTTP: without an active `medication`
  * consent every medication, schedule and day route answers 403 `consent_required` with the purpose, shows no record
  * and stores none. Consent is given in chat only; no route grants one.
  */
final class ConsentApiSuite extends ApiSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val everyDayAtNine = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
  private val medication = ujson.Obj("name" -> "Vitamin D", "doseAmount" -> "1000", "doseUnit" -> "IU")
  private val dailyAtNine = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"))
  private val etag = Map("If-Match" -> "\"v1\"")

  private def assertConsentRequired(reply: Reply)(using munit.Location): Unit =
    assertProblem(reply, 403, "consent_required")
    assertEquals(reply.json("purpose").str, "medication")

  /** One request per medication route, for a medication and a schedule the account owns. */
  private def requests(token: String, medicationId: UUID, scheduleId: UUID): List[(String, Reply)] = List(
    "getToday" -> get("/api/v1/today", token),
    "getDay" -> get("/api/v1/days/2026-11-13", token),
    "getMedicationOptions" -> get("/api/v1/medication-options", token),
    "listMedications" -> get("/api/v1/medications", token),
    "createMedication" -> post("/api/v1/medications", medication, token, newKey()),
    "getMedication" -> get(s"/api/v1/medications/$medicationId", token),
    "updateMedication" -> patch(s"/api/v1/medications/$medicationId", medication, token, etag),
    "changeMedicationState" ->
      post(s"/api/v1/medications/$medicationId/state-changes", ujson.Obj("change" -> "archive"), token, etag),
    "getScheduleOptions" -> get("/api/v1/schedule-options", token),
    "previewSchedule" -> post("/api/v1/schedule-previews", ujson.Obj("draft" -> dailyAtNine), token),
    "listSchedules" -> get(s"/api/v1/medications/$medicationId/schedules", token),
    "createSchedule" -> post(s"/api/v1/medications/$medicationId/schedules", dailyAtNine, token, newKey()),
    "getSchedule" -> get(s"/api/v1/schedules/$scheduleId", token),
    "reviseSchedule" -> post(s"/api/v1/schedules/$scheduleId/revisions", dailyAtNine, token, etag),
    "changeScheduleState" -> post(s"/api/v1/schedules/$scheduleId/state-changes", ujson.Obj("change" -> "pause"), token, etag)
  )

  private def rowsOf(accountId: UUID): (Int, Int, List[String]) =
    uow.transaction: tx =>
      val medications = tx.medications.listAllForAccount(accountId)
      val schedules = medications.flatMap(m => tx.schedules.listForMedication(m.id))
      (medications.size, schedules.size, schedules.map(s => s"${s.status}:${s.currentRevision}"))

  test("an account that never agreed to medication gets 403 consent_required on a read and on a write"):
    val account = accountWithoutConsent()
    val token = login(account)
    assertConsentRequired(get("/api/v1/today", token))
    assertConsentRequired(get("/api/v1/medications", token))
    assertConsentRequired(post("/api/v1/medications", medication, token, newKey()))
    assertEquals(rowsOf(account.accountId), (0, 0, Nil), "the refused create stored nothing")

  test("after a withdrawal every medication route answers 403 consent_required and neither shows nor changes a row"):
    val account = telegramAccount("Europe/Kyiv")
    val created = fixtures.schedule(clock, account.accountId, "Vitamin D", everyDayAtNine, kyiv)
    val token = login(account)
    assertEquals(get("/api/v1/today", token).status, 200)
    val before = rowsOf(account.accountId)

    assert(tracking.consents.withdraw(account.accountId, ConsentPurpose.Medication, clock.now()))
    val replies = requests(token, created.medicationId, created.scheduleId)
    replies.foreach: (route, reply) =>
      assertConsentRequired(reply)
      assert(!reply.body.contains("Vitamin D"), s"$route shows a medication name")
    assertEquals(rowsOf(account.accountId), before, "no route changed a row")

    // Every medication route of the document is covered: a new one needs a case here.
    val gated = Endpoints.documented.flatMap(_.info.name).filterNot(ConsentApiSuite.outsideMedication)
    assertEquals(replies.map(_._1).sorted, gated.sorted)

  test("the consent of another domain does not open the medication routes"):
    val account = accountWithoutConsent()
    fixtures.grant(account.accountId, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    assertConsentRequired(get("/api/v1/today", login(account)))

  test("the session and the insights overview answer without the medication consent"):
    val account = accountWithoutConsent()
    val token = login(account)
    val overview = get("/api/v1/insights/overview?range=7", token)
    assertEquals(overview.status, 200, overview.body)
    assertEquals(overview.json("blocks").arr.toList, Nil, "a domain without consent is absent")

object ConsentApiSuite:

  /** The routes that are not of the medication domain: the session, the overview, which leaves a domain out, the
    * settings and consents of the account, and the water, mood and calendar routes, which check their own domain
    * (TrackingConsentSuite).
    */
  private val outsideMedication: Set[String] =
    Set(
      "createSession",
      "deleteSession",
      "keepSessionAlive",
      "getInsightsOverview",
      "getSettings",
      "updateSettings",
      "listTimeZones",
      "previewTimeZone",
      "listConsents",
      "withdrawConsent",
      "getNotificationSettings",
      "updateNotificationSettings",
      "getInsightCalendar"
    ) ++
      // The reminders routes check the consent of the plan's own domain, and the timeline shows doses only with the
      // medication consent (ReminderApiSuite).
      Set(
        "getReminders",
        "listReminderPresets",
        "createReminderPlan",
        "deleteReminderPlan",
        "createReminderPause",
        "getDayProfile",
        "updateDayProfile",
        "getReminderTimeline"
      ) ++ Endpoints.documented.flatMap(_.info.name).filter(name => name.contains("Hydration") || name.contains("Mood") || name.contains("Sleep"))
