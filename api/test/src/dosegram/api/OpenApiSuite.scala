package dosegram.api

/** ROADMAP G3.1: the document is generated from the endpoints, and every path in it is a path of the reconciled
  * resource list of ARCHITECTURE section 9.5 (conflict X-25). The diff against the committed file is the separate
  * check `dosegram.api.openApiCheck`.
  */
final class OpenApiSuite extends munit.FunSuite:

  // ARCHITECTURE section 9.5, written out: a route that is not in that table must not appear in the document.
  private val resourceList = Set(
    "POST /api/v1/session",
    "DELETE /api/v1/session",
    "POST /api/v1/session/keepalive",
    "GET /api/v1/today",
    "GET /api/v1/days/{date}",
    "GET /api/v1/medication-options",
    "GET /api/v1/medications",
    "POST /api/v1/medications",
    "GET /api/v1/medications/{id}",
    "PATCH /api/v1/medications/{id}",
    "POST /api/v1/medications/{id}/state-changes",
    "GET /api/v1/schedule-options",
    "POST /api/v1/schedule-previews",
    "GET /api/v1/medications/{id}/schedules",
    "POST /api/v1/medications/{id}/schedules",
    "GET /api/v1/schedules/{id}",
    "POST /api/v1/schedules/{id}/revisions",
    "POST /api/v1/schedules/{id}/state-changes",
    "GET /api/v1/settings",
    "PATCH /api/v1/settings",
    "GET /api/v1/time-zones",
    "POST /api/v1/time-zone-previews",
    "GET /api/v1/consents",
    "POST /api/v1/consents/{purpose}/withdrawals",
    "GET /api/v1/notification-settings",
    "PATCH /api/v1/notification-settings",
    "GET /api/v1/hydration/entries",
    "POST /api/v1/hydration/entries",
    "DELETE /api/v1/hydration/entries/{id}",
    "GET /api/v1/hydration/settings",
    "PATCH /api/v1/hydration/settings",
    "GET /api/v1/hydration/summary",
    "GET /api/v1/mood/scale",
    "GET /api/v1/mood/blob-states",
    "GET /api/v1/mood/checkins",
    "POST /api/v1/mood/checkins",
    "DELETE /api/v1/mood/checkins/{id}",
    "GET /api/v1/mood/summary",
    "GET /api/v1/sleep/episodes",
    "POST /api/v1/sleep/episodes",
    "DELETE /api/v1/sleep/episodes/{id}",
    "POST /api/v1/sleep/checkins",
    "GET /api/v1/sleep/settings",
    "PATCH /api/v1/sleep/settings",
    "GET /api/v1/sleep/summary",
    "GET /api/v1/insights/calendar",
    "GET /api/v1/insights/overview",
    "GET /api/v1/insights/month",
    "GET /api/v1/insights/correlations",
    "GET /api/v1/reminders",
    "GET /api/v1/reminders/presets",
    "POST /api/v1/reminders/plans",
    "DELETE /api/v1/reminders/plans/{kind}",
    "POST /api/v1/reminders/pause",
    "GET /api/v1/reminders/day",
    "PATCH /api/v1/reminders/day",
    "GET /api/v1/reminders/timeline"
  )

  private val document = ujson.read(OpenApiDocument.json)

  private val operations: List[(String, ujson.Value)] =
    for
      (path, item) <- document("paths").obj.toList
      (method, operation) <- item.obj.toList
    yield s"${method.toUpperCase} $path" -> operation

  test("the document lists exactly the routes of the resource list that exist"):
    assertEquals(operations.map(_._1).toSet, resourceList)

  test("every operation has a unique operation id"):
    val ids = operations.map((_, operation) => operation("operationId").str)
    assertEquals(ids.distinct.size, operations.size)

  test("every operation except the session exchange requires the bearer token"):
    val open = operations.collect { case (route, operation) if !operation.obj.contains("security") => route }
    assertEquals(open, List("POST /api/v1/session"))

  test("every operation documents the problem response and no other error body"):
    operations.foreach: (route, operation) =>
      val responses = operation("responses").obj
      assert(responses("default")("content").obj.contains("application/problem+json"), route)
      assertEquals(responses.keySet.filter(_.startsWith("4")).toSet, Set.empty[String], route)

  test("the wire enumerations are closed sets in the document"):
    val schemas = document("components")("schemas")
    assertEquals(schemas("MedicationState")("enum").arr.map(_.str).toSet, Set("active", "paused", "archived"))
    assertEquals(schemas("SchedulePattern")("enum").arr.map(_.str).toSet, Set("fixed_times", "as_needed"))
    assert(schemas("ErrorCode")("enum").arr.map(_.str).contains("version_conflict"))
