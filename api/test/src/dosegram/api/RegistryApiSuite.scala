package dosegram.api

import dosegram.core.domain.OccurrenceStatus
import dosegram.core.ports.ScheduleStatus

import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** ROADMAP G3.4 to G3.6, server side: the medication registry and its schedules over HTTP, with the conventions of
  * ARCHITECTURE section 9.4 that these routes need first (API-14, API-16, API-17).
  */
final class RegistryApiSuite extends ApiSuite:

  private def medicationBody(name: String): ujson.Obj =
    ujson.Obj("name" -> name, "doseAmount" -> "1000", "doseUnit" -> "IU", "instructions" -> "with breakfast")

  private def createMedication(token: String, name: String): Reply =
    val reply = post("/api/v1/medications", medicationBody(name), token, newKey())
    assertEquals(reply.status, 201, reply.body)
    reply

  private val dailyAtNine = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"))

  private def createSchedule(token: String, medicationId: String, draft: ujson.Value = dailyAtNine): Reply =
    val reply = post(s"/api/v1/medications/$medicationId/schedules", draft, token, newKey())
    assertEquals(reply.status, 201, reply.body)
    reply

  private def pendingRows(scheduleId: String) =
    uow.transaction(_.occurrences.listBySchedule(UUID.fromString(scheduleId))).filter(_.status == OccurrenceStatus.Pending)

  // ---------- medications ----------

  test("POST /medications creates the medication and answers 201 with Location, ETag and the representation"):
    val token = login(telegramAccount())
    val created = createMedication(token, "Vitamin D")
    val id = created.json("id").str
    assertEquals(created.header("Location"), Some(s"/api/v1/medications/$id"))
    assertEquals(created.header("ETag"), Some(s"\"v${created.json("version").num.toLong}\""))
    assertEquals(created.json("name").str, "Vitamin D")
    assertEquals(created.json("doseAmount").str, "1000")
    assertEquals(created.json("status").str, "active")
    assertEquals(created.json("schedules").arr.toList, Nil)
    val read = get(s"/api/v1/medications/$id", token)
    assertEquals(read.status, 200)
    assertEquals(read.json, created.json)
    assertEquals(read.header("ETag"), created.header("ETag"))

  test("GET /medications lists every medication of the account, archived ones under their status"):
    val token = login(telegramAccount())
    val kept = createMedication(token, "Kept")
    // The list is in creation order.
    clock.advance(Duration.ofSeconds(1))
    val gone = createMedication(token, "Gone")
    val archived = post(s"/api/v1/medications/${gone.json("id").str}/state-changes", ujson.Obj("change" -> "archive"), token, ifMatch(gone))
    assertEquals(archived.status, 200, archived.body)
    val all = get("/api/v1/medications", token).json("items").arr.toList
    assertEquals(all.map(_("name").str), List("Kept", "Gone"))
    assertEquals(all.map(_("status").str), List("active", "archived"))
    assertEquals(all.head("doseText").str, "1000 IU")
    assertEquals(all.head("scheduleSummary").str, "No schedule")
    assertEquals(all.head("id").str, kept.json("id").str)
    val onlyArchived = get("/api/v1/medications?status=archived", token).json("items").arr.toList
    assertEquals(onlyArchived.map(_("name").str), List("Gone"))
    assertProblem(get("/api/v1/medications?status=deleted", token), 400, "validation_failed")

  test("a create without an Idempotency-Key, or with one that is not a UUID, is refused and writes nothing"):
    val account = telegramAccount()
    val token = login(account)
    val missing = post("/api/v1/medications", medicationBody("No key"), token)
    assertProblem(missing, 422, "validation_failed")
    assertEquals(missing.fieldErrors, List("Idempotency-Key" -> "required"))
    val malformed = post("/api/v1/medications", medicationBody("Bad key"), token, Map("Idempotency-Key" -> "abc"))
    assertEquals(malformed.fieldErrors, List("Idempotency-Key" -> "invalid"))
    assertEquals(get("/api/v1/medications", token).json("items").arr.toList, Nil)

  test("a repeat with the same key and body answers with the stored response and records once (API-16)"):
    val token = login(telegramAccount())
    val key = newKey()
    val first = post("/api/v1/medications", medicationBody("Once"), token, key)
    val repeat = post("/api/v1/medications", medicationBody("Once"), token, key)
    assertEquals(first.status, 201, first.body)
    assertEquals(repeat.status, 201, repeat.body)
    assertEquals(repeat.json, first.json)
    assertEquals(repeat.header("Location"), first.header("Location"))
    assertEquals(get("/api/v1/medications", token).json("items").arr.size, 1)

  test("the same key with another body answers 422 idempotency_key_reuse"):
    val token = login(telegramAccount())
    val key = newKey()
    post("/api/v1/medications", medicationBody("First body"), token, key)
    assertProblem(post("/api/v1/medications", medicationBody("Second body"), token, key), 422, "idempotency_key_reuse")
    assertEquals(get("/api/v1/medications", token).json("items").arr.size, 1)

  test("a refused create is not stored under its key: the corrected request with the same key succeeds"):
    val token = login(telegramAccount())
    val key = newKey()
    assertProblem(post("/api/v1/medications", ujson.Obj("name" -> " "), token, key), 422, "validation_failed")
    assertEquals(post("/api/v1/medications", medicationBody("Corrected"), token, key).status, 201)

  test("an idempotency record is deleted after 24 hours"):
    val account = telegramAccount()
    val key = newKey()
    post("/api/v1/medications", medicationBody("Old key"), login(account), key)
    clock.advance(Duration.ofHours(24).plusSeconds(1))
    assert(sweep() >= 1)
    // The key is free again, so the same key with another body is a new request.
    assertEquals(post("/api/v1/medications", medicationBody("New body"), login(account), key).status, 201)

  test("invalid members answer 422 with one entry per member and no echo of the input"):
    val token = login(telegramAccount())
    val body = ujson.Obj(
      "name" -> ("canary" * 20),
      "doseAmount" -> "canary-amount",
      "doseUnit" -> ("u" * 17),
      "instructions" -> ("i" * 501)
    )
    val reply = post("/api/v1/medications", body, token, newKey())
    assertProblem(reply, 422, "validation_failed")
    assertEquals(
      reply.fieldErrors,
      List("name" -> "too_long", "doseAmount" -> "invalid", "doseUnit" -> "too_long", "instructions" -> "too_long")
    )
    assert(!reply.body.contains("canary"), reply.body)
    List("0", "-1", "1.2345", "1,5", "1e3").foreach: amount =>
      val refused = post("/api/v1/medications", ujson.Obj("name" -> "Amount", "doseAmount" -> amount), token, newKey())
      assertEquals(refused.fieldErrors, List("doseAmount" -> "invalid"), amount)

  test("a second medication with the same name, in any case, answers 422 duplicate"):
    val token = login(telegramAccount())
    createMedication(token, "Omega 3")
    val reply = post("/api/v1/medications", medicationBody("  omega 3 "), token, newKey())
    assertEquals(reply.fieldErrors, List("name" -> "duplicate"))

  test("a duplicate name is found under a default locale with other case rules"):
    val token = login(telegramAccount())
    createMedication(token, "MAGNESIUM CITRATE")
    val previous = Locale.getDefault
    // In Turkish an upper-case I lower-cases to a dotless one, which the stored normal form does not hold.
    Locale.setDefault(Locale.forLanguageTag("tr"))
    val reply =
      try post("/api/v1/medications", medicationBody("MAGNESIUM CITRATE"), token, newKey())
      finally Locale.setDefault(previous)
    assertEquals(reply.fieldErrors, List("name" -> "duplicate"))

  test("PATCH needs If-Match: absent answers 428, malformed 422 (API-17)"):
    val token = login(telegramAccount())
    val created = createMedication(token, "Needs a version")
    val path = s"/api/v1/medications/${created.json("id").str}"
    assertProblem(patch(path, medicationBody("Renamed"), token), 428, "precondition_required")
    val malformed = patch(path, medicationBody("Renamed"), token, Map("If-Match" -> "*"))
    assertEquals(malformed.fieldErrors, List("If-Match" -> "invalid"))
    assertEquals(get(path, token).json("name").str, "Needs a version")

  test("PATCH with the current version replaces the editable set and moves the version"):
    val token = login(telegramAccount())
    val created = createMedication(token, "Before")
    val path = s"/api/v1/medications/${created.json("id").str}"
    val updated = patch(path, ujson.Obj("name" -> "After", "doseAmount" -> "2.5", "doseUnit" -> "ml"), token, ifMatch(created))
    assertEquals(updated.status, 200, updated.body)
    assertEquals(updated.json("name").str, "After")
    assertEquals(updated.json("doseAmount").str, "2.5")
    assert(!updated.json.obj.contains("instructions"), "an absent optional member clears the value")
    assert(updated.json("version").num > created.json("version").num)
    assertEquals(get(path, token).json, updated.json)

  test("a stale If-Match answers 412 with the current representation and applies nothing"):
    val token = login(telegramAccount())
    val created = createMedication(token, "Edited twice")
    val path = s"/api/v1/medications/${created.json("id").str}"
    val winner = patch(path, medicationBody("Winner"), token, ifMatch(created))
    val stale = patch(path, medicationBody("Lost update"), token, ifMatch(created))
    assertProblem(stale, 412, "version_conflict")
    assertEquals(stale.json("medication"), winner.json)
    assertEquals(get(path, token).json("name").str, "Winner")
    val staleArchive = post(s"$path/state-changes", ujson.Obj("change" -> "archive"), token, ifMatch(created))
    assertProblem(staleArchive, 412, "version_conflict")
    assertEquals(get(path, token).json("status").str, "active")

  test("an edit of the dose reaches the pending reminders through a new schedule revision"):
    startNewDay()
    val token = login(telegramAccount())
    val created = createMedication(token, "Old name")
    val id = created.json("id").str
    val schedule = createSchedule(token, id)
    val current = get(s"/api/v1/medications/$id", token)
    val updated = patch(s"/api/v1/medications/$id", ujson.Obj("name" -> "New name", "doseAmount" -> "5", "doseUnit" -> "mg"), token, ifMatch(current))
    assertEquals(updated.status, 200, updated.body)
    assertEquals(updated.json("schedules")(0)("revision").num.toInt, 2)
    val pending = pendingRows(schedule.json("id").str)
    assert(pending.nonEmpty)
    assertEquals(pending.flatMap(_.doseSnapshot).map(_.medicationName).distinct, List("New name"))
    val today = get("/api/v1/today", token).json("occurrences").arr.toList
    assertEquals(today.map(o => o("medicationName").str -> o("doseText").str), List("New name" -> "5 mg"))

  test("an edit that leaves the dose snapshot alone adds no revision"):
    val token = login(telegramAccount())
    val created = createMedication(token, "Stable")
    val id = created.json("id").str
    createSchedule(token, id)
    val current = get(s"/api/v1/medications/$id", token)
    val same = patch(s"/api/v1/medications/$id", medicationBody("Stable"), token, ifMatch(current))
    assertEquals(same.json("schedules")(0)("revision").num.toInt, 1)

  test("archive ends the schedules and cancels pending doses; a later change of an archived medication is refused"):
    startNewDay()
    val token = login(telegramAccount())
    val created = createMedication(token, "To archive")
    val id = created.json("id").str
    val schedule = createSchedule(token, id)
    assert(pendingRows(schedule.json("id").str).nonEmpty)
    val archived = post(s"/api/v1/medications/$id/state-changes", ujson.Obj("change" -> "archive"), token, ifMatch(get(s"/api/v1/medications/$id", token)))
    assertEquals(archived.status, 200, archived.body)
    assertEquals(archived.json("status").str, "archived")
    assertEquals(archived.json("schedules")(0)("status").str, "ended")
    assertEquals(pendingRows(schedule.json("id").str), Nil)
    assertEquals(get("/api/v1/today", token).json("occurrences").arr.toList, Nil)
    val resume = post(s"/api/v1/medications/$id/state-changes", ujson.Obj("change" -> "resume"), token, ifMatch(archived))
    assertEquals(resume.fieldErrors, List("change" -> "not_allowed"))

  test("pause and resume move the medication and its schedule together"):
    startNewDay()
    val token = login(telegramAccount())
    val id = createMedication(token, "Pausable").json("id").str
    val schedule = createSchedule(token, id)
    val path = s"/api/v1/medications/$id"
    val paused = post(s"$path/state-changes", ujson.Obj("change" -> "pause"), token, ifMatch(get(path, token)))
    assertEquals(paused.json("status").str, "paused")
    assertEquals(paused.json("schedules")(0)("status").str, "paused")
    assertEquals(paused.json("schedules")(0)("summary").str, "paused: every day 09:00")
    assertEquals(pendingRows(schedule.json("id").str), Nil)
    val resumed = post(s"$path/state-changes", ujson.Obj("change" -> "resume"), token, ifMatch(paused))
    assertEquals(resumed.json("status").str, "active")
    assertEquals(resumed.json("schedules")(0)("times").arr.map(_.str).toList, List("09:00"))
    assert(pendingRows(schedule.json("id").str).nonEmpty)

  test("a schedule paused in the chat shows the medication as paused, and resume from the app restores it"):
    val account = telegramAccount()
    val token = login(account)
    val id = createMedication(token, "Paused in chat").json("id").str
    val scheduleId = UUID.fromString(createSchedule(token, id).json("id").str)
    fixtures.lifecycle(clock).pause(scheduleId)
    val seen = get(s"/api/v1/medications/$id", token)
    assertEquals(seen.json("status").str, "paused")
    assertEquals(get("/api/v1/medications?status=paused", token).json("items").arr.map(_("id").str).toList, List(id))
    val resumed = post(s"/api/v1/medications/$id/state-changes", ujson.Obj("change" -> "resume"), token, ifMatch(seen))
    assertEquals(resumed.json("status").str, "active")
    assertEquals(uow.transaction(_.schedules.get(scheduleId)).map(_.status), Some(ScheduleStatus.Active))

  test("GET /medication-options publishes the limits and the dose units"):
    val reply = get("/api/v1/medication-options", login(telegramAccount()))
    assertEquals(reply.json("limits")("medicationNameMax").num.toInt, 80)
    assertEquals(reply.json("limits")("instructionsMax").num.toInt, 500)
    assert(reply.json("doseUnits").arr.map(_.str).contains("mg"))

  // ---------- schedules ----------

  test("POST /schedule-previews lists the next seven days from the engine's evaluator and writes nothing"):
    clock.at = Instant.parse("2027-01-11T08:00:00Z")
    val account = telegramAccount("UTC")
    val draft = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("21:30", "09:00"), "weekdays" -> ujson.Arr(1, 3, 5))
    val reply = post("/api/v1/schedule-previews", ujson.Obj("draft" -> draft), login(account))
    assertEquals(reply.status, 200, reply.body)
    assertEquals(reply.json("summary").str, "on Mon, Wed, Fri 09:00, 21:30")
    assertEquals(reply.json("timeZone").str, "UTC")
    // 2027-01-11 is a Monday; the window is seven days from 08:00.
    val days = reply.json("days").arr.toList
    // The window ends at 08:00 on Monday the 18th, before that day's first time.
    assertEquals(days.map(_("date").str), List("2027-01-11", "2027-01-13", "2027-01-15"))
    assertEquals(days.map(_("label").str).head, "Mon 11 Jan")
    assertEquals(days.head("occurrences").arr.map(_("time").str).toList, List("09:00", "21:30"))
    assertEquals(days.head("occurrences")(0)("at").str, "2027-01-11T09:00:00Z")
    assertEquals(get("/api/v1/medications", login(account)).json("items").arr.toList, Nil)

  test("the preview names a clock change: 03:30 does not exist in Kyiv on 2027-03-28"):
    clock.at = Instant.parse("2027-03-27T10:00:00Z")
    val account = telegramAccount("Europe/Kyiv")
    val draft = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("03:30"))
    val days = post("/api/v1/schedule-previews", ujson.Obj("draft" -> draft), login(account)).json("days").arr.toList
    val changeDay = days.find(_("date").str == "2027-03-28").get("occurrences")(0)
    // 03:00 to 04:00 is skipped; the engine moves the slot forward by the gap.
    assertEquals(changeDay("at").str, "2027-03-28T01:30:00Z")
    assertEquals(
      changeDay("note").str,
      "Clock change: 03:30 does not exist on this day. The reminder comes at 04:30."
    )
    val ordinary = days.find(_("date").str == "2027-03-29").get("occurrences")(0)
    assertEquals(ordinary("at").str, "2027-03-29T00:30:00Z")
    assert(!ordinary.obj.contains("note"))

  test("a preview of an as-needed schedule has a summary and no occurrences"):
    val reply = post("/api/v1/schedule-previews", ujson.Obj("draft" -> ujson.Obj("pattern" -> "as_needed")), login(telegramAccount()))
    assertEquals(reply.json("summary").str, "as needed")
    assertEquals(reply.json("days").arr.toList, Nil)

  test("an invalid draft answers 422 per member"):
    val token = login(telegramAccount())
    def errors(draft: ujson.Obj) = post("/api/v1/schedule-previews", ujson.Obj("draft" -> draft), token).fieldErrors
    assertEquals(errors(ujson.Obj("pattern" -> "fixed_times")), List("times" -> "required"))
    assertEquals(errors(ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("9:00"))), List("times" -> "invalid"))
    assertEquals(errors(ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00", "09:00"))), List("times" -> "duplicate"))
    assertEquals(errors(ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"), "weekdays" -> ujson.Arr(0, 8))), List("weekdays" -> "invalid"))
    assertEquals(errors(ujson.Obj("pattern" -> "as_needed", "times" -> ujson.Arr("09:00"), "maxPerDay" -> 0)), List("times" -> "not_allowed", "maxPerDay" -> "out_of_range"))
    assertEquals(errors(ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"), "startDate" -> "2027-02-02", "endDate" -> "2027-02-01")), List("endDate" -> "before_start"))
    assertProblem(post("/api/v1/schedule-previews", ujson.Obj("draft" -> ujson.Obj("pattern" -> "hourly")), token), 400, "validation_failed")

  test("POST /medications/{id}/schedules starts the schedule; the engine materialises what the preview listed"):
    clock.at = Instant.parse("2027-02-01T08:00:00Z")
    val token = login(telegramAccount("UTC"))
    val id = createMedication(token, "Scheduled").json("id").str
    val draft = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00", "21:00"))
    val preview = post("/api/v1/schedule-previews", ujson.Obj("draft" -> draft), token)
    val created = createSchedule(token, id, draft)
    val scheduleId = created.json("id").str
    assertEquals(created.header("Location"), Some(s"/api/v1/schedules/$scheduleId"))
    assertEquals(created.header("ETag"), Some("\"v1\""))
    assertEquals(created.json("status").str, "active")
    assertEquals(created.json("weekdays").arr.map(_.num.toInt).toList, List(1, 2, 3, 4, 5, 6, 7))
    assertEquals(created.json("summary").str, "every day 09:00, 21:00")
    assertEquals(created.json("startDate").str, "2027-02-01")
    // The engine materialises 48 hours; those rows are the first rows of the preview.
    val materialised = pendingRows(scheduleId).map(_.scheduledFor.toString).sorted
    val previewed = preview.json("days").arr.toList.flatMap(_("occurrences").arr.map(_("at").str))
    assertEquals(materialised, previewed.take(materialised.size))
    assertEquals(materialised.size, 4)
    assertEquals(get(s"/api/v1/schedules/$scheduleId", token).json, created.json)
    assertEquals(get(s"/api/v1/medications/$id/schedules", token).json("items").arr.toList, List(created.json))
    assertEquals(get("/api/v1/medications", token).json("items")(0)("scheduleSummary").str, "every day 09:00, 21:00")

  test("a schedule create is idempotent per key, and a medication takes one schedule that has not ended"):
    val token = login(telegramAccount())
    val id = createMedication(token, "One schedule").json("id").str
    val key = newKey()
    val first = post(s"/api/v1/medications/$id/schedules", dailyAtNine, token, key)
    val repeat = post(s"/api/v1/medications/$id/schedules", dailyAtNine, token, key)
    assertEquals(repeat.json, first.json)
    assertEquals(get(s"/api/v1/medications/$id/schedules", token).json("items").arr.size, 1)
    val second = post(s"/api/v1/medications/$id/schedules", dailyAtNine, token, newKey())
    assertEquals(second.fieldErrors, List("medication" -> "schedule_exists"))
    assertProblem(post(s"/api/v1/medications/$id/schedules", dailyAtNine, token), 422, "validation_failed")

  test("start and end dates bound the schedule"):
    clock.at = Instant.parse("2027-04-05T08:00:00Z")
    val token = login(telegramAccount("UTC"))
    val id = createMedication(token, "Course").json("id").str
    val draft = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"), "startDate" -> "2027-04-06", "endDate" -> "2027-04-06")
    val created = createSchedule(token, id, draft)
    assertEquals(created.json("endDate").str, "2027-04-06")
    assertEquals(pendingRows(created.json("id").str).map(_.scheduledFor.toString), List("2027-04-06T09:00:00Z"))
    val preview = post("/api/v1/schedule-previews", ujson.Obj("draft" -> draft), token).json("days").arr.toList
    assertEquals(preview.map(_("date").str), List("2027-04-06"))

  test("POST /schedules/{id}/revisions edits as a new revision on the base revision"):
    startNewDay()
    val token = login(telegramAccount())
    val id = createMedication(token, "Revised").json("id").str
    val created = createSchedule(token, id)
    val path = s"/api/v1/schedules/${created.json("id").str}"
    val evening = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("20:00"), "weekdays" -> ujson.Arr(6, 7))
    assertProblem(post(s"$path/revisions", evening, token), 428, "precondition_required")
    val revised = post(s"$path/revisions", evening, token, ifMatch(created))
    assertEquals(revised.status, 201, revised.body)
    assertEquals(revised.header("ETag"), Some("\"v2\""))
    assertEquals(revised.json("times").arr.map(_.str).toList, List("20:00"))
    assertEquals(revised.json("summary").str, "on Sat, Sun 20:00")
    val stale = post(s"$path/revisions", dailyAtNine, token, ifMatch(created))
    assertProblem(stale, 412, "version_conflict")
    assertEquals(stale.json("schedule"), revised.json)
    assertEquals(get(path, token).json("revision").num.toInt, 2)

  test("a revision cannot change the pattern or the dates"):
    val token = login(telegramAccount())
    val id = createMedication(token, "Fixed pattern").json("id").str
    val created = createSchedule(token, id)
    val path = s"/api/v1/schedules/${created.json("id").str}/revisions"
    val asNeeded = post(path, ujson.Obj("pattern" -> "as_needed"), token, ifMatch(created))
    assertEquals(asNeeded.fieldErrors, List("pattern" -> "not_editable"))
    val movedDraft = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"), "startDate" -> "2030-01-01")
    val moved = post(path, movedDraft, token, ifMatch(created))
    assertEquals(moved.fieldErrors, List("startDate" -> "not_editable"))
    assertEquals(get(s"/api/v1/schedules/${created.json("id").str}", token).json("revision").num.toInt, 1)

  test("schedule state changes: pause, resume, end; a change the state does not allow is refused"):
    startNewDay()
    val token = login(telegramAccount())
    val id = createMedication(token, "States").json("id").str
    val created = createSchedule(token, id)
    val path = s"/api/v1/schedules/${created.json("id").str}/state-changes"
    assertEquals(post(path, ujson.Obj("change" -> "resume"), token, ifMatch(created)).fieldErrors, List("change" -> "not_allowed"))
    val paused = post(path, ujson.Obj("change" -> "pause"), token, ifMatch(created))
    assertEquals(paused.json("status").str, "paused")
    assertEquals(get(s"/api/v1/medications/$id", token).json("status").str, "paused")
    val resumed = post(path, ujson.Obj("change" -> "resume"), token, ifMatch(paused))
    assertEquals(resumed.json("status").str, "active")
    assert(pendingRows(created.json("id").str).nonEmpty)
    val ended = post(path, ujson.Obj("change" -> "end"), token, ifMatch(resumed))
    assertEquals(ended.json("status").str, "ended")
    assertEquals(ended.json("summary").str, "ended: every day 09:00")
    assertEquals(pendingRows(created.json("id").str), Nil)
    // The medication stays, and takes a new schedule.
    assertEquals(get(s"/api/v1/medications/$id", token).json("status").str, "active")
    assertEquals(post(s"/api/v1/medications/$id/schedules", dailyAtNine, token, newKey()).status, 201)

  test("GET /schedule-options publishes the patterns, the limits and the account's day"):
    clock.at = Instant.parse("2027-05-01T22:30:00Z")
    val reply = get("/api/v1/schedule-options", login(telegramAccount("Europe/Kyiv")))
    assertEquals(reply.json("patterns").arr.map(_.str).toList, List("fixed_times", "as_needed"))
    assertEquals(reply.json("previewDays").num.toInt, 7)
    assertEquals(reply.json("timeZone").str, "Europe/Kyiv")
    assertEquals(reply.json("today").str, "2027-05-02")

  test("no request of this suite failed unexpectedly"):
    assertEquals(logged.toList, Nil)
