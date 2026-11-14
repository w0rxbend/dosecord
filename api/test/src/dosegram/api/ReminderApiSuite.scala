package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.ports.ConsentPurpose

import java.time.Duration
import java.time.Instant

/** The reminders routes of ADR-023 over HTTP: the hub, the presets with their previews, plans on and off with an
  * idempotency key and the domain's consent, the day profile on the version the client read, the pause, the timeline,
  * and the scope of the session's account.
  */
final class ReminderApiSuite extends ApiSuite:

  /** A fresh account at 08:00 in Kyiv of a new day: the suite shares one clock. */
  private def user(purposes: ConsentPurpose*): (TelegramAccount, String) =
    startNewDay()
    clock.at = clock.now().plus(Duration.ofHours(1))
    val account = accountWithoutConsent("Europe/Kyiv")
    if purposes.nonEmpty then fixtures.grant(account.accountId, purposes*)
    (account, login(account))

  private def kind(view: ujson.Value, id: String): ujson.Value = view("kinds").arr.find(_("kind").str == id).get

  private def turnOn(token: String, preset: String, key: Map[String, String] = newKey()): Reply =
    post("/api/v1/reminders/plans", ujson.Obj("presetId" -> preset), token, key)

  test("the hub lists every kind, off, with the day profile and its defaults"):
    val (_, token) = user()
    val hub = get("/api/v1/reminders", token)
    assertEquals(hub.status, 200, hub.body)
    assertEquals(hub.json("timeZone").str, "Europe/Kyiv")
    assertEquals(kind(hub.json, "water")("available").bool, true)
    assert(!kind(hub.json, "water").obj.contains("presetId"))
    assertEquals(kind(hub.json, "meal")("available").bool, true)
    assertEquals(kind(hub.json, "report_weekly")("available").bool, true)
    assertEquals(hub.json("day")("workStart").str, "09:00")
    assertEquals(hub.json("day")("workDays").arr.map(_.num.toInt).toList, List(1, 2, 3, 4, 5))
    assertEquals(hub.json("day")("quietStart").str, "22:00")

  test("presets come with their next times and save nothing; medication presets are drafts for the editor"):
    val (_, token) = user()
    val presets = get("/api/v1/reminders/presets", token)
    assertEquals(presets.status, 200, presets.body)
    val work = presets.json("prompts").arr.find(_("id").str == "water.work_hours_30").get
    assertEquals(work("name").str, "Work hours, every 30 min")
    assertEquals(work("preview").arr.size, 5)
    val twice = presets.json("medication").arr.find(_("id").str == "med.twice_daily").get
    assertEquals(twice("draft")("times").arr.map(_.str).toList, List("07:00", "23:00"))
    val cycle = presets.json("medication").arr.find(_("id").str == "med.cycle").get
    assertEquals(cycle("available").bool, true)
    assertEquals(cycle("draft")("pattern").str, "cycle")
    assertEquals((cycle("draft")("onDays").num.toInt, cycle("draft")("offDays").num.toInt), (21, 7))
    val everyOther = presets.json("medication").arr.find(_("id").str == "med.every_other_day").get
    assertEquals(everyOther("draft")("everyDays").num.toInt, 2)
    assert(!kind(get("/api/v1/reminders", token).json, "water").obj.contains("presetId"))

  test("a plan needs the domain's consent, is recorded once per key, and is turned off by kind"):
    val (_, refused) = user()
    assertProblem(turnOn(refused, "water.work_hours_30"), 403, "consent_required")
    val (_, token) = user(ConsentPurpose.Hydration)
    val key = newKey()
    val on = turnOn(token, "water.work_hours_30", key)
    assertEquals(on.status, 200, on.body)
    val water = kind(on.json, "water")
    assertEquals(water("presetName").str, "Work hours, every 30 min")
    assertEquals(Instant.parse(water("nextAt").str).atZone(java.time.ZoneId.of("Europe/Kyiv")).toLocalTime.toString, "09:30")
    assertEquals(turnOn(token, "water.work_hours_30", key).body, on.body, "a repeat answers the same")
    assertProblem(turnOn(token, "water.hourly", key), 422, "idempotency_key_reuse")
    assertEquals(turnOn(token, "nope").fieldErrors, List("presetId" -> "unknown"))
    assertEquals(turnOn(token, "report.weekly").status, 200, "a report needs no domain consent")
    assertProblem(turnOn(token, "meal.anchors"), 403, "consent_required")
    val off = delete("/api/v1/reminders/plans/water", token)
    assertEquals(off.status, 200, off.body)
    assert(!kind(off.json, "water").obj.contains("presetId"))
    assertEquals(delete("/api/v1/reminders/plans/nope", token).status, 404)

  test("a custom water interval is validated field by field"):
    val (_, token) = user(ConsentPurpose.Hydration)
    val bad = ujson.Obj("presetId" -> "water.custom", "everyMinutes" -> 5, "from" -> "9", "until" -> "17:00")
    assertEquals(
      post("/api/v1/reminders/plans", bad, token, newKey()).fieldErrors.toSet,
      Set("from" -> "invalid", "everyMinutes" -> "out_of_range")
    )
    val good = ujson.Obj("presetId" -> "water.custom", "everyMinutes" -> 45, "from" -> "09:00", "until" -> "17:00")
    val on = post("/api/v1/reminders/plans", good, token, newKey())
    assertEquals(kind(on.json, "water")("presetName").str, "Custom interval")

  test("the day profile is edited on the version the client read, and moves the plans that use it"):
    val (_, token) = user(ConsentPurpose.Hydration)
    turnOn(token, "water.work_hours_30")
    val read = get("/api/v1/reminders/day", token)
    assertEquals(read.status, 200, read.body)
    val edited = read.json.obj.clone()
    edited.remove("version")
    edited("workStart") = "10:00"
    edited("workEnd") = "19:00"
    val saved = patch("/api/v1/reminders/day", ujson.Obj.from(edited), token, ifMatch(read))
    assertEquals(saved.status, 200, saved.body)
    assertEquals(saved.json("workStart").str, "10:00")
    assertProblem(patch("/api/v1/reminders/day", ujson.Obj.from(edited), token, ifMatch(read)), 412, "version_conflict")
    val next = Instant.parse(kind(get("/api/v1/reminders", token).json, "water")("nextAt").str)
    assertEquals(next.atZone(java.time.ZoneId.of("Europe/Kyiv")).toLocalTime.toString, "10:30")
    edited("workEnd") = "08:00"
    edited("wake") = "7am"
    assertEquals(
      patch("/api/v1/reminders/day", ujson.Obj.from(edited), token, ifMatch(saved)).fieldErrors,
      List("wake" -> "invalid")
    )

  test("pause and resume every prompt; the timeline of tomorrow lists the plans outside quiet hours"):
    val (_, token) = user(ConsentPurpose.Hydration)
    turnOn(token, "water.hourly")
    val paused = post("/api/v1/reminders/pause", ujson.Obj("hours" -> 3), token, newKey())
    assertEquals(paused.status, 200, paused.body)
    assert(paused.json.obj.contains("pausedUntil"))
    assertEquals(post("/api/v1/reminders/pause", ujson.Obj("hours" -> 30), token, newKey()).fieldErrors, List("hours" -> "out_of_range"))
    assert(!post("/api/v1/reminders/pause", ujson.Obj("hours" -> 0), token, newKey()).json.obj.contains("pausedUntil"))
    val timeline = get("/api/v1/reminders/timeline", token)
    assertEquals(timeline.status, 200, timeline.body)
    val entries = timeline.json("entries").arr
    assertEquals(entries.size, 12, "every hour from 09:00 to 20:00")
    assert(entries.forall(_("kind").str == "water"))

  test("one account's plans are never another's"):
    val (_, mine) = user(ConsentPurpose.Hydration)
    val (_, theirs) = user(ConsentPurpose.Hydration)
    turnOn(mine, "water.work_hours_30")
    assert(!kind(get("/api/v1/reminders", theirs).json, "water").obj.contains("presetId"))
    assertEquals(get("/api/v1/reminders", "").status, 401)
