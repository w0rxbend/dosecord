package dosegram.api

import dosegram.core.ports.ConsentPurpose

import java.util.UUID

/** ARCHITECTURE API-19 and PRODUCT_SPEC R-IDC-9 for the water and mood domains and the history calendar: without the
  * consent of its domain every route answers 403 `consent_required` with the purpose, shows nothing and stores nothing.
  * The consent of one domain opens no other. The registry test fails when a tracking route is added without a case.
  */
final class TrackingConsentSuite extends ApiSuite:

  private val id = UUID.randomUUID()
  private val anyBody = ujson.Obj("presetsMl" -> ujson.Arr(250))

  private val hydrationRoutes: List[(String, String => Reply)] = List(
    "listHydrationEntries" -> (token => get("/api/v1/hydration/entries", token)),
    "createHydrationEntry" -> (token => post("/api/v1/hydration/entries", ujson.Obj("amountMl" -> 250), token, newKey())),
    "deleteHydrationEntry" -> (token => delete(s"/api/v1/hydration/entries/$id", token)),
    "getHydrationSettings" -> (token => get("/api/v1/hydration/settings", token)),
    "updateHydrationSettings" -> (token =>
      patch("/api/v1/hydration/settings", anyBody, token, Map("If-Match" -> "\"v1\""))
    ),
    "getHydrationSummary" -> (token => get("/api/v1/hydration/summary", token))
  )

  private val moodRoutes: List[(String, String => Reply)] = List(
    "getMoodScale" -> (token => get("/api/v1/mood/scale", token)),
    "getMoodBlobStates" -> (token => get("/api/v1/mood/blob-states", token)),
    "listMoodCheckins" -> (token => get("/api/v1/mood/checkins", token)),
    "createMoodCheckin" -> (token => post("/api/v1/mood/checkins", ujson.Obj("valence" -> 3), token, newKey())),
    "deleteMoodCheckin" -> (token => delete(s"/api/v1/mood/checkins/$id", token)),
    "getMoodSummary" -> (token => get("/api/v1/mood/summary", token))
  )

  private def assertRefused(replies: List[(String, Reply)], purpose: String)(using munit.Location): Unit =
    replies.foreach: (route, reply) =>
      assertEquals((route, reply.status, reply.code), (route, 403, "consent_required"), reply.body)
      assertEquals(reply.json("purpose").str, purpose, route)

  test("without the hydration consent every water route is refused, even with the mood and medication consents"):
    val account = accountWithoutConsent()
    fixtures.grant(account.accountId, ConsentPurpose.Mood, ConsentPurpose.Medication)
    val token = login(account)
    assertRefused(hydrationRoutes.map((route, call) => route -> call(token)), "hydration")
    assertRefused(List("calendar" -> get("/api/v1/insights/calendar?domain=hydration", token)), "hydration")
    assertEquals(tracking.water.day(account.accountId, dosegram.core.domain.EntryDay.Today).isLeft, true)

  test("without the mood consent every mood route is refused, even with the hydration consent"):
    val account = accountWithoutConsent()
    fixtures.grant(account.accountId, ConsentPurpose.Hydration)
    val token = login(account)
    assertRefused(moodRoutes.map((route, call) => route -> call(token)), "mood")
    assertRefused(List("calendar" -> get("/api/v1/insights/calendar?domain=mood", token)), "mood")
    assertRefused(List("calendar" -> get("/api/v1/insights/calendar?domain=medication", token)), "medication")

  test("after a withdrawal the entries recorded before are no longer shown"):
    val account = accountWithoutConsent()
    fixtures.grant(account.accountId, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    val token = login(account)
    assertEquals(post("/api/v1/hydration/entries", ujson.Obj("amountMl" -> 333), token, newKey()).status, 201)
    assertEquals(post("/api/v1/mood/checkins", ujson.Obj("valence" -> 2), token, newKey()).status, 201)
    assert(tracking.consents.withdraw(account.accountId, ConsentPurpose.Hydration, clock.now()))
    assert(tracking.consents.withdraw(account.accountId, ConsentPurpose.Mood, clock.now()))
    val replies = (hydrationRoutes ++ moodRoutes).map((route, call) => route -> call(token))
    replies.foreach((route, reply) => assert(!reply.body.contains("333"), s"$route shows the entry"))
    assertRefused(replies.take(hydrationRoutes.size), "hydration")
    assertRefused(replies.drop(hydrationRoutes.size), "mood")

  test("every documented water and mood route has a consent case"):
    val tracked = Endpoints.documented
      .flatMap(_.info.name)
      .filter(name => name.contains("Hydration") || name.contains("Mood"))
    assertEquals((hydrationRoutes ++ moodRoutes).map(_._1).sorted, tracked.sorted)
end TrackingConsentSuite
