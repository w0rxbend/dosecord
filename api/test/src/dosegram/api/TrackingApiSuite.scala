package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.ports.ConsentPurpose

import java.time.Duration

/** The hydration and mood routes of ARCHITECTURE section 9.5 over HTTP, the history calendar and the chart series of
  * the insights overview. Writes carry an `Idempotency-Key` (API-16): a retry records once, two taps record twice
  * (MINI_APP MA-HYD-1).
  */
final class TrackingApiSuite extends ApiSuite:

  private def tracker(purposes: ConsentPurpose*): (TelegramAccount, String) =
    val account = accountWithoutConsent("Europe/Kyiv")
    fixtures.grant(account.accountId, purposes*)
    (account, login(account))

  private def drink(token: String, millilitres: Int, key: Map[String, String] = newKey()): Reply =
    post("/api/v1/hydration/entries", ujson.Obj("amountMl" -> millilitres), token, key)

  private def feel(token: String, valence: Int, energy: Option[Int] = None): Reply =
    val body = ujson.Obj("valence" -> valence)
    energy.foreach(step => body("energy") = step)
    post("/api/v1/mood/checkins", body, token, newKey())

  // ---------- hydration ----------

  test("a drink is recorded once per idempotency key, and two taps are two entries"):
    val (_, token) = tracker(ConsentPurpose.Hydration)
    val key = newKey()
    val first = drink(token, 250, key)
    assertEquals(first.status, 201, first.body)
    assertEquals(first.json("entry")("text").str, "250 ml")
    assertEquals(first.json("day")("totalMl").num.toInt, 250)
    val retried = drink(token, 250, key)
    assertEquals(retried.status, 201)
    assertEquals(retried.json("entry")("id").str, first.json("entry")("id").str, "the retry answers the first entry")
    assertEquals(drink(token, 250).json("day")("totalMl").num.toInt, 500, "a second tap is a second entry")
    assertEquals(get("/api/v1/hydration/entries", token).json("items").arr.size, 2)

    val reused = drink(token, 500, key)
    assertProblem(reused, 422, "idempotency_key_reuse")
    assertProblem(post("/api/v1/hydration/entries", ujson.Obj("amountMl" -> 250), token), 422, "validation_failed")

  test("an amount outside 1 to 5000 ml is refused and names the member"):
    val (_, token) = tracker(ConsentPurpose.Hydration)
    val refused = drink(token, 6000)
    assertProblem(refused, 422, "validation_failed")
    assertEquals(refused.fieldErrors, List("amountMl" -> "out_of_range"))
    assertEquals(get("/api/v1/hydration/entries", token).json("items").arr.toList, Nil)

  test("a delete voids the entry and answers its day; a second delete answers the same"):
    val (_, token) = tracker(ConsentPurpose.Hydration)
    drink(token, 300)
    val entry = drink(token, 200).json("entry")("id").str
    val removed = delete(s"/api/v1/hydration/entries/$entry", token)
    assertEquals(removed.status, 200, removed.body)
    assertEquals(removed.json("totalMl").num.toInt, 300)
    assertEquals(delete(s"/api/v1/hydration/entries/$entry", token).json("totalMl").num.toInt, 300)
    assertEquals(get("/api/v1/hydration/entries", token).json("items").arr.map(_("amountMl").num.toInt).toList, List(300))
    assertProblem(delete(s"/api/v1/hydration/entries/${java.util.UUID.randomUUID()}", token), 404, "not_found")

  test("the goal and the quick-add amounts are edited on the version the client read"):
    val (_, token) = tracker(ConsentPurpose.Hydration)
    val read = get("/api/v1/hydration/settings", token)
    assertEquals(read.status, 200, read.body)
    assertEquals(read.json("presetsMl").arr.map(_.num.toInt).toList, List(200, 300, 500))
    assert(!read.json.obj.contains("goalMl"), "no goal until the user sets one")
    assertEquals(read.json("limits")("goalMaxMl").num.toInt, 10000)

    val body = ujson.Obj("presetsMl" -> ujson.Arr(150, 250, 500), "goalMl" -> 2000)
    val saved = patch("/api/v1/hydration/settings", body, token, ifMatch(read))
    assertEquals(saved.status, 200, saved.body)
    assertEquals(saved.json("goalMl").num.toInt, 2000)
    assertNotEquals(saved.header("ETag"), read.header("ETag"))

    assertProblem(patch("/api/v1/hydration/settings", body, token, ifMatch(read)), 412, "version_conflict")
    assertProblem(patch("/api/v1/hydration/settings", body, token), 428, "precondition_required")
    val invalid = ujson.Obj("presetsMl" -> ujson.Arr(250, 250), "goalMl" -> 50)
    val refused = patch("/api/v1/hydration/settings", invalid, token, ifMatch(saved))
    assertProblem(refused, 422, "validation_failed")
    assertEquals(refused.fieldErrors.toSet, Set("goalMl" -> "out_of_range", "presetsMl" -> "duplicate"))

  test("the vessel names the value, the goal and the percent; past the goal the figure is true"):
    val (account, token) = tracker(ConsentPurpose.Hydration)
    assert(tracking.water.setGoal(account.accountId, Some(2000)).isRight)
    val day = drink(token, 1200).json("day")
    assertEquals(day("vesselLabel").str, "Water: 1.2 of 2.0 litres, 60 percent")
    assertEquals(day("percent").num.toInt, 60)
    val reached = drink(token, 1100)
    assert(reached.json("goalReachedNow").bool, "the entry that first reaches the goal says so")
    assertEquals(reached.json("day")("percent").num.toInt, 115)
    assert(!drink(token, 100).json("goalReachedNow").bool, "only once a day")

  test("the summary holds today, the amounts and a chart of the days, with a gap for a day without water"):
    val (_, token) = tracker(ConsentPurpose.Hydration)
    val empty = get("/api/v1/hydration/summary", token)
    assertEquals(empty.status, 200, empty.body)
    assert(!empty.json.obj.contains("chart"), "no chart from an empty week")
    assertEquals(empty.json("noDataText").str, "No data in this period")
    assertEquals(empty.json("today")("text").str, "Nothing logged yet")

    drink(token, 400)
    val summary = get("/api/v1/hydration/summary", token)
    val chart = summary.json("chart")
    assertEquals(chart("table")("rows").arr.size, 7)
    assertEquals(chart("table")("rows").arr.count(_(1).str == "No entry"), 6)
    assertEquals(chart("marks").arr.last.str, "Fri 13 Nov: 0.4 l")
    assertEquals(
      chart("scene")("nodes").arr.count(node => node("shape").str == "rect" && node("tone").str == "domain-hydration"),
      1,
      "one bar: the empty days are gaps"
    )
    assert(chart("scene")("nodes").arr.size <= 400)
    assertProblem(get("/api/v1/hydration/summary?from=2026-11-10", token), 422, "validation_failed")
    assertProblem(get("/api/v1/hydration/summary?from=2025-01-01&to=2026-11-13", token), 422, "validation_failed")

  // ---------- mood ----------

  test("the scale is data: five stops each with their labels, the blob revision and the standing sentence"):
    val (_, token) = tracker(ConsentPurpose.Mood)
    val scale = get("/api/v1/mood/scale", token)
    assertEquals(scale.status, 200, scale.body)
    assertEquals(scale.json("valence").arr.map(_("step").num.toInt).toList, List(1, 2, 3, 4, 5))
    assertEquals(scale.json("energy").arr.size, 5)
    assertEquals(scale.json("blobRevision").str, "test-1")
    assertEquals(scale.json("statement").str, "Dosegram records how you feel. It does not assess it.")
    assertEquals(get("/api/v1/mood/blob-states", token).json("states").arr.size, 1)
    assertEquals(get("/api/v1/mood/blob-states?revision=test-1", token).status, 200)
    assertProblem(get("/api/v1/mood/blob-states?revision=old", token), 404, "not_found")

  test("a check-in is recorded once per key and answered with its label and drawn state"):
    val (_, token) = tracker(ConsentPurpose.Mood)
    val key = newKey()
    val body = ujson.Obj("valence" -> 4, "energy" -> 2)
    val recorded = post("/api/v1/mood/checkins", body, token, key)
    assertEquals(recorded.status, 201, recorded.body)
    assertEquals(recorded.json("checkin")("blobState").str, "blob.v6.e2")
    assertEquals(recorded.json("day")("checkIns").num.toInt, 1)
    assertEquals(post("/api/v1/mood/checkins", body, token, key).json("checkin")("id"), recorded.json("checkin")("id"))
    assertEquals(get("/api/v1/mood/checkins", token).json("items").arr.size, 1)

    val refused = feel(token, 6)
    assertProblem(refused, 422, "validation_failed")
    assertEquals(refused.fieldErrors, List("valence" -> "out_of_range"))
    assertEquals(feel(token, 3, Some(0)).fieldErrors, List("energy" -> "out_of_range"))

  test("every check-in answer has the same shape for every value: nothing reads the value to choose the words"):
    val (_, token) = tracker(ConsentPurpose.Mood)
    val shapes = (1 to 5).map(step => feel(token, step).json("checkin").obj.keySet.toSet).toSet
    assertEquals(shapes.size, 1)
    val words = get("/api/v1/mood/checkins", token).json("items").arr.map(_("label").str).toList
    assertEquals(words.flatMap(InsightPhrases.violations), Nil)

  test("a check-in of any age is deleted; the day then has no check-in"):
    val (account, first) = tracker(ConsentPurpose.Mood)
    val id = feel(first, 2).json("checkin")("id").str
    clock.advance(Duration.ofHours(1))
    val token = login(account)
    val removed = delete(s"/api/v1/mood/checkins/$id", token)
    assertEquals(removed.status, 200, removed.body)
    assertEquals((removed.json("checkIns").num.toInt, removed.json("blobState").str), (0, "blob.none"))
    assertEquals(delete(s"/api/v1/mood/checkins/$id", token).status, 200)

  test("the summary has one drawn state per day, blob.none on a day without a check-in"):
    val (_, token) = tracker(ConsentPurpose.Mood)
    feel(token, 5)
    clock.advance(Duration.ofMinutes(1))
    feel(token, 3)
    val summary = get("/api/v1/mood/summary", token)
    assertEquals(summary.status, 200, summary.body)
    val days = summary.json("days").arr.toList
    assertEquals(days.size, 7)
    assertEquals(days.take(6).map(_("blobState").str).distinct, List("blob.none"))
    assertEquals(days.last("checkIns").num.toInt, 2)
    assertEquals(days.last("name").str, "Fri 13 Nov: 4.0 (pleasant), 2 check-ins")
    assertEquals(summary.json("today")("latest")("valence").num.toInt, 3, "the latest check-in of the day")

  // ---------- calendar ----------

  test("the water calendar has a cell per day, a step on the own goal, no entry as its own state and blank future"):
    val (account, token) = tracker(ConsentPurpose.Hydration, ConsentPurpose.Mood)
    assert(tracking.water.setGoal(account.accountId, Some(2000)).isRight)
    drink(token, 1500)
    val calendar = get("/api/v1/insights/calendar?domain=hydration", token)
    assertEquals(calendar.status, 200, calendar.body)
    assertEquals(calendar.json("month").str, "2026-11")
    assertEquals(calendar.json("monthLabel").str, "November 2026")
    assertEquals(calendar.json("previousMonth").str, "2026-10")
    assert(!calendar.json.obj.contains("nextMonth"), "the current month has no next month")
    assertEquals(calendar.json("domains").arr.map(_.str).toList, List("hydration", "mood"))
    val days = calendar.json("days").arr.toList
    assertEquals(days.size, 30)
    val today = days(12)
    assertEquals(today("swatch")("state").str, "value")
    assertEquals(today("swatch")("step").num.toInt, 3)
    assertEquals(today("name").str, "Friday 13 November, 1.5 of 2.0 l (75%)")
    assertEquals(today("value").str, "1.5")
    assertEquals(days(11)("swatch")("state").str, "empty")
    assertEquals(days(11)("name").str, "Thursday 12 November, no entry")
    assertEquals(days(13)("swatch")("state").str, "future")
    assertEquals(calendar.json("summary").str, "November so far: water on 1 day. The goal was reached on 0 of them.")

    val october = get("/api/v1/insights/calendar?domain=hydration&month=2026-10", token)
    assertEquals(october.json("nextMonth").str, "2026-11")
    assertEquals(october.json("days").arr.count(_("swatch")("state").str == "empty"), 31)
    assertProblem(get("/api/v1/insights/calendar?domain=hydration&month=2026-12", token), 422, "validation_failed")
    assertProblem(get("/api/v1/insights/calendar?domain=hydration&month=soon", token), 422, "validation_failed")
    assertProblem(get("/api/v1/insights/calendar?domain=sleep", token), 400, "validation_failed")

  test("the mood calendar steps by the rounded daily mean with the colour of its drawn state"):
    val (_, token) = tracker(ConsentPurpose.Mood)
    feel(token, 4)
    feel(token, 5)
    val day = get("/api/v1/insights/calendar?domain=mood", token).json("days")(12)
    assertEquals(day("swatch")("step").num.toInt, 5)
    assertEquals(day("swatch")("blobState").str, "blob.v7.e3", "a mean of 4.5 is level V7")
    assertEquals(day("name").str, "Friday 13 November, 4.5 (very pleasant)")

  // ---------- overview charts ----------

  test("the overview carries a chart of water and of mood by day, each with its table and a mark per day"):
    val (_, token) = tracker(ConsentPurpose.Hydration, ConsentPurpose.Mood)
    drink(token, 800)
    feel(token, 4)
    val blocks = get("/api/v1/insights/overview?range=7", token).json("blocks").arr.toList
    val charts = blocks.map(block => block("id").str -> block("charts").arr.map(_("id").str).toList)
    assertEquals(charts, List("hydration" -> List("water-by-day"), "mood" -> List("mood-by-day")))
    val mood = blocks(1)("charts")(0)
    assertEquals(mood("marks").arr.size, 7)
    assertEquals(mood("summary").str, "Daily mood, 7 Nov to 13 Nov. Check-ins on 1 of 7 days. A day without a check-in is a gap.")
    assertEquals(mood("table")("columns").arr.map(_.str).toList, List("Day", "Daily mood", "Check-ins"))

  test("a block without a record has no chart"):
    val (_, token) = tracker(ConsentPurpose.Hydration)
    val block = get("/api/v1/insights/overview?range=30", token).json("blocks")(0)
    assert(!block.obj.contains("charts"), block.toString)
end TrackingApiSuite
