package dosegram.api

import sttp.tapir.AnyEndpoint

/** ARCHITECTURE API-10 and API-11, ROADMAP G3.1: every route that names a record is called as account B with an
  * identifier of account A and must answer exactly like an unknown identifier, and A's data must be unchanged. The
  * registry test at the end fails when a route with an identifier is added without a case here.
  */
final class CrossAccountSuite extends ApiSuite:

  private val dailyAtNine = ujson.Obj("pattern" -> "fixed_times", "times" -> ujson.Arr("09:00"))
  private val validMedication = ujson.Obj("name" -> "Taken over")

  /** Account A with one medication and one schedule, and a session of account B. */
  private final case class World(ownerToken: String, strangerToken: String, medication: Reply, schedule: Reply):
    val medicationId: String = medication.json("id").str
    val scheduleId: String = schedule.json("id").str
    val medicationTag: Map[String, String] = Map("If-Match" -> medication.header("ETag").get)
    val scheduleTag: Map[String, String] = Map("If-Match" -> schedule.header("ETag").get)

  private lazy val world: World =
    startNewDay()
    val ownerToken = login(telegramAccount())
    val medication = post("/api/v1/medications", ujson.Obj("name" -> "Private", "doseAmount" -> "5", "doseUnit" -> "mg"), ownerToken, newKey())
    val schedule = post(s"/api/v1/medications/${medication.json("id").str}/schedules", dailyAtNine, ownerToken, newKey())
    assertEquals(schedule.status, 201, schedule.body)
    World(ownerToken, login(telegramAccount()), get(s"/api/v1/medications/${medication.json("id").str}", ownerToken), schedule)

  private def ownerView(): (ujson.Value, ujson.Value) =
    (
      get(s"/api/v1/medications/${world.medicationId}", world.ownerToken).json,
      get(s"/api/v1/schedules/${world.scheduleId}", world.ownerToken).json
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
    )
  )

  cases.foreach: (route, request) =>
    test(s"$route: an identifier of another account answers 404 not_found and changes nothing"):
      val before = ownerView()
      val reply = request(world)
      assertProblem(reply, 404, "not_found")
      assert(!reply.body.contains("Private"), reply.body)
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
