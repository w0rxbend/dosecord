package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.ports.ConsentPurpose

import java.time.Duration
import java.time.Instant

/** The sleep routes of ARCHITECTURE section 9.5 and MINI_APP section 7.10 over HTTP: a night entered after the fact,
  * the two check-ins, the user's own goal, the summary with its chart, the overview block and the calendar. Every value
  * names its source (MA-SLEEP-1) and no reference duration is given (MA-SLEEP-4).
  */
final class SleepApiSuite extends ApiSuite:

  /** A fresh account at 10:00 in Kyiv of a new day: the suite shares one clock. */
  private def sleeper(purposes: ConsentPurpose*): (TelegramAccount, String) =
    startNewDay()
    clock.at = clock.now().plus(Duration.ofHours(3))
    val account = accountWithoutConsent("Europe/Kyiv")
    fixtures.grant(account.accountId, (ConsentPurpose.Sleep +: purposes)*)
    (account, login(account))

  private def night(token: String, onset: Instant, wake: Instant, key: Map[String, String] = newKey()): Reply =
    val body = ujson.Obj("kind" -> "night", "onsetAt" -> onset.toString, "wakeAt" -> wake.toString)
    post("/api/v1/sleep/episodes", body, token, key)

  private def hoursAgo(hours: Double): Instant = clock.now().minus(Duration.ofMinutes((hours * 60).toLong))

  test("a night entered after the fact is recorded once per key, with its times, its duration and its source"):
    val (_, token) = sleeper()
    val key = newKey()
    val first = night(token, hoursAgo(10.5), hoursAgo(3), key)
    assertEquals(first.status, 201, first.body)
    assertEquals(first.json("kind").str, "night")
    assertEquals(first.json("minutes").num.toInt, 450)
    assertEquals(first.json("duration").str, "7 h 30 min")
    assertEquals(first.json("source").str, "From your check-ins")
    assert(first.json("text").str.matches("Night of \\w{3} \\d+ Nov: 23:30 to 07:00, 7 h 30 min"), first.body)
    assertEquals(night(token, hoursAgo(10.5), hoursAgo(3), key).json("id").str, first.json("id").str)
    assertEquals(get("/api/v1/sleep/episodes", token).json("items").arr.size, 1)
    assertProblem(night(token, hoursAgo(9), hoursAgo(10)), 422, "validation_failed")
    assertEquals(night(token, hoursAgo(9), hoursAgo(10)).fieldErrors, List("wakeAt" -> "wake_before_sleep"))
    assertEquals(night(token, hoursAgo(26), hoursAgo(20)).status, 201, "a night of another sleep day")
    assertEquals(night(token, hoursAgo(9), hoursAgo(5)).fieldErrors, List("wakeAt" -> "night_exists"))
    val nap = ujson.Obj("kind" -> "nap", "onsetAt" -> hoursAgo(4).toString, "wakeAt" -> hoursAgo(2).toString)
    assertEquals(post("/api/v1/sleep/episodes", nap, token, newKey()).fieldErrors, List("onsetAt" -> "overlap"))

  test("going to sleep and I'm up close a night; I'm up without an open night asks for the night"):
    val (account, token) = sleeper()
    val asleep = post("/api/v1/sleep/checkins", ujson.Obj("kind" -> "going_to_sleep"), token, newKey())
    assertEquals(asleep.status, 200, asleep.body)
    assert(asleep.json("openSince").str.nonEmpty)
    clock.at = clock.now().plus(Duration.ofHours(7))
    val morning = login(account)
    val up = post("/api/v1/sleep/checkins", ujson.Obj("kind" -> "awake"), morning, newKey())
    assertEquals(up.status, 200, up.body)
    assertEquals(up.json("lastNight")("duration").str, "7 h 00 min")
    assert(!up.json.obj.contains("needsBedtime") && !up.json.obj.contains("openSince"), up.body)
    clock.at = clock.now().plus(Duration.ofHours(20))
    val unmatched = post("/api/v1/sleep/checkins", ujson.Obj("kind" -> "awake"), login(account), newKey())
    assertEquals(unmatched.json("needsBedtime").bool, true, unmatched.body)

  test("the goal is the user's own number, edited on the version the client read"):
    val (_, token) = sleeper()
    val read = get("/api/v1/sleep/settings", token)
    assertEquals(read.status, 200, read.body)
    assert(!read.json.obj.contains("goalMinutes"), "no goal until the user sets one")
    assertEquals(read.json("limits")("goalMaxMinutes").num.toInt, 1080)
    val saved = patch("/api/v1/sleep/settings", ujson.Obj("goalMinutes" -> 450), token, ifMatch(read))
    assertEquals(saved.status, 200, saved.body)
    assertEquals(saved.json("goalMinutes").num.toInt, 450)
    assertProblem(patch("/api/v1/sleep/settings", ujson.Obj("goalMinutes" -> 420), token, ifMatch(read)), 412, "version_conflict")
    val refused = patch("/api/v1/sleep/settings", ujson.Obj("goalMinutes" -> 20), token, ifMatch(saved))
    assertEquals(refused.fieldErrors, List("goalMinutes" -> "out_of_range"))
    assertEquals(get("/api/v1/sleep/summary", token).json("status")("goalText").str, "Your goal: 7 h 30 min")

  test("the summary states the nights in words and charts them, with a gap for a day without a night"):
    val (_, token) = sleeper()
    val empty = get("/api/v1/sleep/summary", token)
    assertEquals(empty.status, 200, empty.body)
    assertEquals(empty.json("status")("text").str, "No night logged yet")
    assertEquals(empty.json("noDataText").str, "No data in this period")
    assert(!empty.json.obj.contains("chart"), empty.body)
    night(token, hoursAgo(10.5), hoursAgo(3))
    val summary = get("/api/v1/sleep/summary", token).json
    assertEquals(summary("days").arr.size, 7)
    assertEquals(summary("lines").arr.map(_.str).toList.head, "1 night logged")
    assert(summary("lines").arr.exists(_.str == "your check-ins"), summary.toString)
    assertEquals(summary("chart")("id").str, "sleep-by-day")
    assertEquals(summary("chart")("marks").arr.count(_.str.endsWith("no night logged")), 6)
    assertEquals(summary("status")("text").str, "Last: 23:30 to 07:00, 7 h 30 min.")

  test("a delete voids a night of any age; a second delete answers the same"):
    val (account, first) = sleeper()
    val id = night(first, hoursAgo(10.5), hoursAgo(3)).json("id").str
    clock.at = clock.now().plus(Duration.ofDays(2))
    val token = login(account)
    val removed = delete(s"/api/v1/sleep/episodes/$id", token)
    assertEquals(removed.status, 200, removed.body)
    assertEquals(removed.json("text").str, "No night logged yet")
    assertEquals(delete(s"/api/v1/sleep/episodes/$id", token).status, 200)
    assertProblem(delete(s"/api/v1/sleep/episodes/${java.util.UUID.randomUUID()}", token), 404, "not_found")

  test("the overview has a sleep block with its chart, and the calendar a sleep month"):
    val (_, token) = sleeper()
    night(token, hoursAgo(10.5), hoursAgo(3))
    val block = get("/api/v1/insights/overview?range=7", token).json("blocks").arr.find(_("id").str == "sleep").get
    assertEquals(block("title").str, "Sleep")
    assertEquals(block("charts").arr.map(_("id").str).toList, List("sleep-by-day", "sleep-times"))
    assertEquals(block("details").arr.map(_.str).toList, List("4 more nights for bedtime regularity"))
    val calendar = get("/api/v1/insights/calendar?domain=sleep", token)
    assertEquals(calendar.status, 200, calendar.body)
    assertEquals(calendar.json("domains").arr.map(_.str).toList, List("sleep"))
    assertEquals(calendar.json("days").arr.count(_("swatch")("state").str == "value"), 1)
    assertEquals(calendar.json("summary").str, "November so far: 1 night logged, from your check-ins.")
end SleepApiSuite
