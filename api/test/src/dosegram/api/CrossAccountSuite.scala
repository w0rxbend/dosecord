package dosegram.api

import dosegram.core.ports.ConsentPurpose
import sttp.tapir.AnyEndpoint

/** ARCHITECTURE API-10 and API-11, ROADMAP G3.1: every route that names a record is called as account B with an
  * identifier of account A and must answer exactly like an unknown identifier, and A's data must be unchanged. The
  * registry test at the end fails when a route with an identifier is added without a case here.
  */
final class CrossAccountSuite extends ApiSuite:

  private val dailyAtNine = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"))
  private val validMedication = ujson.Obj("name" -> "Taken over")

  /** Account A with one medication, one schedule, one water entry and one check-in, and a session of account B. */
  private final case class World(
      ownerToken: String,
      strangerToken: String,
      medication: Reply,
      schedule: Reply,
      water: Reply,
      checkin: Reply,
      night: Reply
  ):
    val nightId: String = night.json("id").str
    val waterId: String = water.json("entry")("id").str
    val checkinId: String = checkin.json("checkin")("id").str
    val medicationId: String = medication.json("id").str
    val scheduleId: String = schedule.json("id").str
    val medicationTag: Map[String, String] = Map("If-Match" -> medication.header("ETag").get)
    val scheduleTag: Map[String, String] = Map("If-Match" -> schedule.header("ETag").get)

  private lazy val world: World =
    startNewDay()
    val ownerToken = login(trackingAccount())
    val medication = post("/api/v1/medications", ujson.Obj("name" -> "Private", "doseAmount" -> "5", "doseUnit" -> "mg"), ownerToken, newKey())
    val schedule = post(s"/api/v1/medications/${medication.json("id").str}/schedules", dailyAtNine, ownerToken, newKey())
    assertEquals(schedule.status, 201, schedule.body)
    val water = post("/api/v1/hydration/entries", ujson.Obj("amountMl" -> 321), ownerToken, newKey())
    val checkin = post("/api/v1/mood/checkins", ujson.Obj("valence" -> 2), ownerToken, newKey())
    val onset = clock.now().minus(java.time.Duration.ofHours(9))
    val nightBody = ujson.Obj("kind" -> "night", "onsetAt" -> onset.toString, "wakeAt" -> clock.now().minusSeconds(3600).toString)
    val night = post("/api/v1/sleep/episodes", nightBody, ownerToken, newKey())
    assertEquals((water.status, checkin.status, night.status), (201, 201, 201), water.body + checkin.body + night.body)
    World(
      ownerToken,
      login(trackingAccount()),
      get(s"/api/v1/medications/${medication.json("id").str}", ownerToken),
      schedule,
      water,
      checkin,
      night
    )

  /** An account with every consent of the routes under test, so a refusal can only come from the identifier. */
  private def trackingAccount(): ApiSuite.TelegramAccount =
    val account = telegramAccount()
    fixtures.grant(
      account.accountId,
      ConsentPurpose.Hydration,
      ConsentPurpose.Mood,
      ConsentPurpose.Sleep,
      ConsentPurpose.Nutrition
    )
    account

  /** A meal of account A, with words that must not reach account B. */
  private lazy val mealId: String =
    val meal = post("/api/v1/meals", ujson.Obj("mealType" -> "lunch", "description" -> "Private"), world.ownerToken, newKey())
    assertEquals(meal.status, 201, meal.body)
    meal.json("meal")("id").str

  private def ownerView(): (ujson.Value, ujson.Value, ujson.Value, ujson.Value, ujson.Value) =
    (
      get(s"/api/v1/medications/${world.medicationId}", world.ownerToken).json,
      get(s"/api/v1/schedules/${world.scheduleId}", world.ownerToken).json,
      get("/api/v1/hydration/entries", world.ownerToken).json,
      get("/api/v1/mood/checkins", world.ownerToken).json,
      get("/api/v1/sleep/episodes", world.ownerToken).json
    )

  /** One case per route with an identifier: the route name and the request account B sends. */
  private val cases: List[(String, World => Reply)] = List(
    "getMedication" -> (w => get(s"/api/v1/medications/${w.medicationId}", w.strangerToken)),
    "updateMedication" -> (w =>
      patch(s"/api/v1/medications/${w.medicationId}", validMedication, w.strangerToken, w.medicationTag)
    ),
    "changeMedicationState" -> (w =>
      post(s"/api/v1/medications/${w.medicationId}/state-changes", ujson.Obj("change" -> "archive"), w.strangerToken, w.medicationTag)
    ),
    "listSchedules" -> (w => get(s"/api/v1/medications/${w.medicationId}/schedules", w.strangerToken)),
    "createSchedule" -> (w =>
      post(s"/api/v1/medications/${w.medicationId}/schedules", dailyAtNine, w.strangerToken, newKey())
    ),
    "getSchedule" -> (w => get(s"/api/v1/schedules/${w.scheduleId}", w.strangerToken)),
    "reviseSchedule" -> (w =>
      post(s"/api/v1/schedules/${w.scheduleId}/revisions", dailyAtNine, w.strangerToken, w.scheduleTag)
    ),
    "changeScheduleState" -> (w =>
      post(s"/api/v1/schedules/${w.scheduleId}/state-changes", ujson.Obj("change" -> "end"), w.strangerToken, w.scheduleTag)
    ),
    "deleteHydrationEntry" -> (w => delete(s"/api/v1/hydration/entries/${w.waterId}", w.strangerToken)),
    "deleteMoodCheckin" -> (w => delete(s"/api/v1/mood/checkins/${w.checkinId}", w.strangerToken)),
    "deleteSleepEpisode" -> (w => delete(s"/api/v1/sleep/episodes/${w.nightId}", w.strangerToken)),
    "getMeal" -> (w => get(s"/api/v1/meals/$mealId", w.strangerToken)),
    "deleteMeal" -> (w => delete(s"/api/v1/meals/$mealId", w.strangerToken))
  )

  cases.foreach: (route, request) =>
    test(s"$route: an identifier of another account answers 404 not_found and changes nothing"):
      // Each case starts a fresh minute of the read budget (API-18): the number of cases must not decide which one
      // is answered 429.
      clock.at = clock.now().plusSeconds(61)
      val before = ownerView()
      val reply = request(world)
      assertProblem(reply, 404, "not_found")
      assert(!reply.body.contains("Private") && !reply.bodyWithoutRequestId.contains("321"), reply.body)
      assertEquals(ownerView(), before)

  test("the answer for a foreign identifier equals the answer for an unknown one, apart from the request id"):
    def shape(reply: Reply): ujson.Value =
      val json = reply.json
      json.obj.remove("requestId")
      json
    val foreign = get(s"/api/v1/medications/${world.medicationId}", world.strangerToken)
    val unknown = get(s"/api/v1/medications/${java.util.UUID.randomUUID()}", world.strangerToken)
    assertEquals(shape(foreign), shape(unknown))
    assertEquals(foreign.status, unknown.status)

  test("lists and day views of account B hold nothing of account A"):
    assertEquals(get("/api/v1/medications", world.strangerToken).json("items").arr.toList, Nil)
    assertEquals(get("/api/v1/hydration/entries", world.strangerToken).json("items").arr.toList, Nil)
    assertEquals(get("/api/v1/mood/checkins", world.strangerToken).json("items").arr.toList, Nil)
    assertEquals(get("/api/v1/sleep/episodes", world.strangerToken).json("items").arr.toList, Nil)
    assertEquals(get("/api/v1/hydration/summary", world.strangerToken).json("today")("totalMl").num.toInt, 0)
    assertEquals(get("/api/v1/mood/summary", world.strangerToken).json("today")("checkIns").num.toInt, 0)
    val calendar = get("/api/v1/insights/calendar?domain=hydration", world.strangerToken).json("days").arr
    assertEquals(calendar.count(_("swatch")("state").str == "value"), 0)
    assertEquals(get("/api/v1/today", world.strangerToken).json("occurrences").arr.toList, Nil)
    assertEquals(get("/api/v1/today", world.ownerToken).json("occurrences").arr.size, 1)

  test("an idempotency key of account A is not a key of account B"):
    val key = newKey()
    val mine = post("/api/v1/medications", ujson.Obj("name" -> "Keyed"), world.ownerToken, key)
    val theirs = post("/api/v1/medications", ujson.Obj("name" -> "Keyed"), world.strangerToken, key)
    assertEquals(mine.status, 201)
    assertEquals(theirs.status, 201)
    assertNotEquals(mine.json("id").str, theirs.json("id").str)

  test("every documented route that takes an identifier has a cross-account case"):
    def takesIdentifier(endpoint: AnyEndpoint): Boolean = endpoint.showPathTemplate().contains("{id}")
    val withIdentifier = Endpoints.documented.filter(takesIdentifier).flatMap(_.info.name).toSet
    assertEquals(cases.map(_._1).toSet, withIdentifier)
    assertEquals(Endpoints.documented.count(takesIdentifier), withIdentifier.size, "a route without a name")
