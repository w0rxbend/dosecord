package dosegram.api

import java.time.Instant

/** ROADMAP G9.10 and G9.12 over HTTP: the inventory of a medication (versioned, idempotent, days of supply by the
  * schedule), a pause with a resume date and "course ends on".
  */
final class MedicationDepthApiSuite extends ApiSuite:

  private val utcMorning = Instant.parse("2027-02-01T08:00:00Z") // a Monday, before 09:00

  private def medication(token: String, unit: String = "tablet"): String =
    val body = ujson.Obj("name" -> "Iron", "doseAmount" -> "1", "doseUnit" -> unit)
    val created = post("/api/v1/medications", body, token, newKey())
    assertEquals(created.status, 201, created.body)
    created.json("id").str

  private def everyOtherDay(token: String, id: String): Reply =
    val draft = ujson.Obj("pattern" -> "every_n_days", "times" -> ujson.Arr("09:00"), "everyDays" -> 2)
    val reply = post(s"/api/v1/medications/$id/schedules", draft, token, newKey())
    assertEquals(reply.status, 201, reply.body)
    reply

  private def put(path: String, body: ujson.Value, token: String, headers: Map[String, String]): Reply =
    send("PUT", path, token, Some(ujson.write(body)), headers)

  test("AC-REG-7 over HTTP: a first count needs no If-Match and shows 10 days of supply on every other day"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val id = medication(token)
    everyOtherDay(token, id)
    val none = get(s"/api/v1/medications/$id/inventory", token)
    assertEquals(none.status, 200)
    assert(!none.json.obj.contains("count"), none.body)
    val saved = put(s"/api/v1/medications/$id/inventory", ujson.Obj("onHand" -> "5", "perIntake" -> "1"), token, newKey())
    assertEquals(saved.status, 200, saved.body)
    assertEquals(saved.header("ETag"), Some("\"v1\""))
    assertEquals(saved.json("daysOfSupply").num.toInt, 10)
    assertEquals(saved.json("coversUntil").str, "2027-02-09")
    assertEquals(saved.json("thresholdDays").num.toInt, 7, "the default threshold of R-REG-11")
    assertEquals(saved.json("belowThreshold").bool, false)
    assertEquals(saved.json("summary").str, "About 5 tablets of Iron left by your count. At your current schedule that covers until about Tue 9 Feb.")
    assertEquals(get(s"/api/v1/medications/$id/inventory", token).json("count"), saved.json)

  test("a count is replaced on the version read; a stale version is 412, a repeated key changes nothing"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val id = medication(token)
    val path = s"/api/v1/medications/$id/inventory"
    val first = put(path, ujson.Obj("onHand" -> "20", "perIntake" -> "0.5", "thresholdUnits" -> "4"), token, newKey())
    val key = newKey()
    val second = put(path, ujson.Obj("onHand" -> "30", "perIntake" -> "0.5"), token, key ++ ifMatch(first))
    assertEquals(second.json("onHand").str, "30")
    assertEquals(second.json("version").num.toInt, 2)
    val repeat = put(path, ujson.Obj("onHand" -> "30", "perIntake" -> "0.5"), token, key ++ Map("If-Match" -> "\"v2\""))
    assertEquals(repeat.json("version").num.toInt, 2, "the same key is the same request")
    assertProblem(put(path, ujson.Obj("onHand" -> "9", "perIntake" -> "1"), token, newKey() ++ ifMatch(first)), 412, "version_conflict")
    assertProblem(put(path, ujson.Obj("onHand" -> "9", "perIntake" -> "1"), token, newKey()), 428, "precondition_required")

  test("a repeated threshold-only edit answers with the first answer, not 412 (API-16)"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val id = medication(token)
    val path = s"/api/v1/medications/$id/inventory"
    val first = put(path, ujson.Obj("onHand" -> "20", "perIntake" -> "1"), token, newKey())
    val key = newKey()
    val edit = put(path, ujson.Obj("onHand" -> "20", "perIntake" -> "1", "thresholdDays" -> 3), token, key ++ ifMatch(first))
    assertEquals(edit.status, 200, edit.body)
    val repeat = put(path, ujson.Obj("onHand" -> "20", "perIntake" -> "1", "thresholdDays" -> 3), token, key ++ ifMatch(first))
    assertEquals(repeat.status, 200, repeat.body)
    assertEquals(repeat.json, edit.json)
    assertEquals(repeat.header("ETag"), edit.header("ETag"))
    val other = put(path, ujson.Obj("onHand" -> "20", "perIntake" -> "1", "thresholdDays" -> 5), token, key ++ ifMatch(edit))
    assertProblem(other, 422, "idempotency_key_reuse")

  test("a repeated refill answers with the first answer and adds once"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val id = medication(token)
    put(s"/api/v1/medications/$id/inventory", ujson.Obj("onHand" -> "2", "perIntake" -> "1"), token, newKey())
    val key = newKey()
    val first = post(s"/api/v1/medications/$id/refills", ujson.Obj("quantity" -> "30"), token, key)
    val again = post(s"/api/v1/medications/$id/refills", ujson.Obj("quantity" -> "30"), token, key)
    assertEquals((first.status, again.status), (201, 201))
    assertEquals(again.json, first.json)
    assertEquals(again.json("onHand").str, "32")

  test("a Ukrainian account reads the count's summary with the unit in Ukrainian"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val read = get("/api/v1/settings", token)
    val body = ujson.Obj("language" -> "uk", "timeZone" -> "UTC", "imageTheme" -> "auto")
    assertEquals(patch("/api/v1/settings", body, token, ifMatch(read)).status, 200)
    val id = medication(token)
    val saved = put(s"/api/v1/medications/$id/inventory", ujson.Obj("onHand" -> "21", "perIntake" -> "1"), token, newKey())
    assert(saved.json("summary").str.startsWith("Iron: за вашим підрахунком лишилося близько 21 таблетка."), saved.body)

  test("invalid members answer 422 per member; a stranger's medication is 404"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val id = medication(token)
    val path = s"/api/v1/medications/$id/inventory"
    val bad = put(path, ujson.Obj("onHand" -> "-1", "perIntake" -> "0", "thresholdDays" -> 0, "thresholdUnits" -> "x"), token, newKey())
    assertEquals(
      bad.fieldErrors,
      List(
        "onHand" -> "invalid",
        "perIntake" -> "invalid",
        "thresholdDays" -> "out_of_range",
        "thresholdUnits" -> "invalid",
        "thresholdUnits" -> "not_allowed"
      )
    )
    val stranger = login(telegramAccount("UTC"))
    assertProblem(get(path, stranger), 404, "not_found")
    assertProblem(put(path, ujson.Obj("onHand" -> "5", "perIntake" -> "1"), stranger, newKey()), 404, "not_found")

  test("POST /refills adds the quantity once per key and remembers it as the last refill"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val id = medication(token)
    put(s"/api/v1/medications/$id/inventory", ujson.Obj("onHand" -> "2", "perIntake" -> "1"), token, newKey())
    val key = newKey()
    val refilled = post(s"/api/v1/medications/$id/refills", ujson.Obj("quantity" -> "30"), token, key)
    assertEquals(refilled.status, 201, refilled.body)
    assertEquals(refilled.json("onHand").str, "32")
    assertEquals(refilled.json("lastRefill").str, "30")
    assertEquals(post(s"/api/v1/medications/$id/refills", ujson.Obj("quantity" -> "30"), token, key).json("onHand").str, "32")
    assertEquals(post(s"/api/v1/medications/$id/refills", ujson.Obj("quantity" -> "0"), token, newKey()).fieldErrors, List("quantity" -> "invalid"))

  test("a pause may carry a resume date after today; the schedule shows it and a resume forgets it"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val id = medication(token)
    everyOtherDay(token, id)
    val path = s"/api/v1/medications/$id"
    val tooSoon = post(s"$path/state-changes", ujson.Obj("change" -> "pause", "resumeOn" -> "2027-02-01"), token, ifMatch(get(path, token)))
    assertEquals(tooSoon.fieldErrors, List("resumeOn" -> "out_of_range"))
    val paused = post(s"$path/state-changes", ujson.Obj("change" -> "pause", "resumeOn" -> "2027-02-08"), token, ifMatch(get(path, token)))
    assertEquals(paused.json("status").str, "paused", paused.body)
    assertEquals(paused.json("schedules")(0)("resumeOn").str, "2027-02-08")
    val resumed = post(s"$path/state-changes", ujson.Obj("change" -> "resume"), token, ifMatch(paused))
    assert(!resumed.json("schedules")(0).obj.contains("resumeOn"), resumed.body)
    val notWithResume = post(s"$path/state-changes", ujson.Obj("change" -> "resume", "resumeOn" -> "2027-02-08"), token, ifMatch(resumed))
    assertEquals(notWithResume.fieldErrors, List("resumeOn" -> "not_allowed"))

  test("course ends on: end with a date keeps the schedule active, drops doses after it, and refuses a past date"):
    clock.at = utcMorning
    val token = login(telegramAccount("UTC"))
    val id = medication(token)
    val daily = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"))
    val created = post(s"/api/v1/medications/$id/schedules", daily, token, newKey())
    val path = s"/api/v1/schedules/${created.json("id").str}"
    val past = post(s"$path/state-changes", ujson.Obj("change" -> "end", "endDate" -> "2027-01-31"), token, ifMatch(created))
    assertEquals(past.fieldErrors, List("endDate" -> "out_of_range"))
    val ending = post(s"$path/state-changes", ujson.Obj("change" -> "end", "endDate" -> "2027-02-01"), token, ifMatch(created))
    assertEquals(ending.status, 200, ending.body)
    assertEquals(ending.json("status").str, "active")
    assertEquals(ending.json("endDate").str, "2027-02-01")
    val pending = uow.transaction(_.occurrences.listBySchedule(java.util.UUID.fromString(created.json("id").str)))
      .filter(_.status == dosegram.core.domain.OccurrenceStatus.Pending)
      .map(_.scheduledFor.toString)
    assertEquals(pending, List("2027-02-01T09:00:00Z"), "tomorrow's dose is gone; today's stays")
