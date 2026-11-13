package dosegram.api

import dosegram.api.auth.SessionService
import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.infra.db.RowMapper.given
import dosegram.infra.db.sql

import java.sql.Connection
import java.time.Duration
import java.time.ZoneId
import scala.language.implicitConversions

/** ROADMAP G1.12: the session exchange (API-02 to API-07), the bearer check and the Today read. */
final class SessionApiSuite extends ApiSuite:

  private val everyDayAtNine = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))

  private def auditCount(kind: String): Int =
    val conn = dataSource.getConnection
    try
      given Connection = conn
      sql"SELECT count(*)::int FROM auth_audit_log WHERE kind = $kind".queryOne[Int]().getOrElse(0)
    finally conn.close()

  test("valid launch data of a known account answers 201 with a token, a bootstrap document and no cookie"):
    val account = telegramAccount("Europe/Kyiv")
    val reply = exchange(initDataFor(account.telegramUserId))
    assertEquals(reply.status, 201, reply.body)
    assertEquals(reply.header("Content-Type"), Some("application/json"))
    assertEquals(reply.header("Set-Cookie"), None)
    assertEquals(reply.json("bootstrap")("timeZone").str, "Europe/Kyiv")
    assertEquals(reply.json("bootstrap")("limits")("medicationNameMax").num.toInt, 80)
    assertEquals(reply.json("idleTimeoutSeconds").num.toInt, 900)
    assertEquals(reply.json("expiresAt").str, clock.now().plus(Duration.ofMinutes(15)).toString)
    assertEquals(get("/api/v1/today", reply.json("token").str).status, 200)

  test("only the SHA-256 of the token is stored (API-05)"):
    val account = telegramAccount()
    val token = login(account)
    val stored = sessionStore.listForAccount(account.accountId)
    assertEquals(stored.map(_.tokenHash), List(SessionService.sha256Hex(token)))
    assert(!stored.exists(session => session.productIterator.contains(token)))

  test("a Telegram user without an account answers 403 account_required (API-04)"):
    val reply = exchange(initDataFor(telegramUserId = 999000111L))
    assertProblem(reply, 403, "account_required")
    assert(!reply.body.contains("999000111"), "the problem echoes the Telegram user id")

  test("a tampered launch value answers 401 and creates no session"):
    val account = telegramAccount()
    val tampered = initDataFor(account.telegramUserId).replace("first_name", "first_nome")
    assertProblem(exchange(tampered), 401, "unauthenticated")
    assertEquals(sessionStore.listForAccount(account.accountId), Nil)

  test("launch data older than 300 seconds answers 401; one that is 61 seconds ahead answers 401"):
    val account = telegramAccount()
    val stale = initDataFor(account.telegramUserId, clock.now().minusSeconds(301))
    val ahead = initDataFor(account.telegramUserId, clock.now().plusSeconds(61))
    assertProblem(exchange(stale), 401, "unauthenticated")
    assertProblem(exchange(ahead), 401, "unauthenticated")
    assertEquals(exchange(initDataFor(account.telegramUserId, clock.now().minusSeconds(300))).status, 201)

  test("launch data without a hash answers 401 client_too_old (API-03)"):
    val account = telegramAccount()
    val unsigned = initDataFor(account.telegramUserId).replaceAll("&?hash=[0-9a-f]+", "")
    assertProblem(exchange(unsigned), 401, "client_too_old")

  test("the same launch value presented again issues a new session and ends the earlier one (API-07)"):
    val account = telegramAccount()
    val initData = initDataFor(account.telegramUserId)
    val before = auditCount("initdata.reused")
    val first = exchange(initData).json("token").str
    val second = exchange(initData).json("token").str
    assertNotEquals(first, second)
    assertProblem(get("/api/v1/today", first), 401, "unauthenticated")
    assertEquals(get("/api/v1/today", second).status, 200)
    assertEquals(auditCount("initdata.reused"), before + 1)

  test("the seventh exchange of one Telegram user inside a minute answers 429 with Retry-After"):
    val account = telegramAccount()
    (1 to 6).foreach(_ => assertEquals(exchange(initDataFor(account.telegramUserId)).status, 201))
    val refused = exchange(initDataFor(account.telegramUserId))
    assertProblem(refused, 429, "rate_limited")
    assertEquals(refused.header("Retry-After"), Some("60"))
    clock.advance(Duration.ofSeconds(60))
    assertEquals(exchange(initDataFor(account.telegramUserId)).status, 201)

  test("a sixth session of one account evicts the oldest"):
    val account = telegramAccount()
    val tokens = (1 to 6).map: _ =>
      clock.advance(Duration.ofSeconds(11))
      login(account)
    assertProblem(get("/api/v1/today", tokens.head), 401, "unauthenticated")
    tokens.tail.foreach(token => assertEquals(get("/api/v1/today", token).status, 200))

  test("a request without a bearer token and one with an unknown token answer 401"):
    assertProblem(get("/api/v1/today"), 401, "unauthenticated")
    assertProblem(get("/api/v1/today", "not-a-token"), 401, "unauthenticated")

  test("a session idle for 15 minutes answers 401 session_expired; use keeps it alive up to 12 hours"):
    val account = telegramAccount()
    val idle = login(account)
    val used = login(account)
    clock.advance(Duration.ofMinutes(10))
    assertEquals(send("POST", "/api/v1/session/keepalive", used, None, Map.empty).status, 204)
    clock.advance(Duration.ofMinutes(10))
    assertProblem(get("/api/v1/today", idle), 401, "session_expired")
    assertEquals(get("/api/v1/today", used).status, 200)
    // 20 minutes are gone; 69 more steps of use reach 11 h 50 min, the next step is the absolute limit.
    (1 to 69).foreach: _ =>
      clock.advance(Duration.ofMinutes(10))
      assertEquals(get("/api/v1/today", used).status, 200)
    clock.advance(Duration.ofMinutes(10))
    assertProblem(get("/api/v1/today", used), 401, "session_expired")

  test("the sweep deletes expired sessions and keeps live ones"):
    val account = telegramAccount()
    login(account)
    clock.advance(Duration.ofMinutes(16))
    val live = login(account)
    assert(sweep() >= 1)
    assertEquals(sessionStore.listForAccount(account.accountId).map(_.tokenHash), List(SessionService.sha256Hex(live)))

  test("DELETE /session ends the session"):
    val token = login(telegramAccount())
    assertEquals(delete("/api/v1/session", token).status, 204)
    assertProblem(get("/api/v1/today", token), 401, "unauthenticated")

  test("a body with an unknown member, broken JSON or a missing member answers 400 without echoing the input"):
    val unknownMember = post("/api/v1/session", ujson.Obj("initData" -> "x", "canary-member" -> "canary-value"))
    assertProblem(unknownMember, 400, "validation_failed")
    assert(!unknownMember.body.contains("canary"), unknownMember.body)
    val broken = send("POST", "/api/v1/session", "", Some("{canary-broken"), Map.empty)
    assertProblem(broken, 400, "validation_failed")
    assert(!broken.body.contains("canary"), broken.body)
    assertProblem(post("/api/v1/session", ujson.Obj()), 400, "validation_failed")

  test("an unknown path under /api answers the not_found problem for every method"):
    val token = login(telegramAccount())
    assertProblem(get("/api/v1/nothing-here", token), 404, "not_found")
    assertProblem(get("/api/v2/today", token), 404, "not_found")
    assertProblem(delete("/api/v1/today", token), 404, "not_found")

  test("an identifier that is not a UUID answers 400"):
    assertProblem(get("/api/v1/medications/not-a-uuid", login(telegramAccount())), 400, "validation_failed")

  test("GET /today lists the account's occurrences of its local day and never another account's"):
    startNewDay()
    val mine = telegramAccount("Europe/Kyiv")
    val theirs = telegramAccount("Europe/Kyiv")
    val kyiv = ZoneId.of("Europe/Kyiv")
    val created = fixtures.schedule(clock, mine.accountId, "Mine D3", everyDayAtNine, kyiv, doseAmount = Some(BigDecimal(1000)), doseUnit = Some("IU"))
    fixtures.schedule(clock, theirs.accountId, "Theirs B12", everyDayAtNine, kyiv)
    val reply = get("/api/v1/today", login(mine))
    assertEquals(reply.status, 200, reply.body)
    val today = clock.now().atZone(kyiv).toLocalDate
    assertEquals(reply.json("date").str, today.toString)
    assertEquals(reply.json("timeZone").str, "Europe/Kyiv")
    assertEquals(reply.json("isToday").bool, true)
    val names = reply.json("occurrences").arr.map(_("medicationName").str).toList
    assertEquals(names.distinct, List("Mine D3"))
    assert(!reply.body.contains("Theirs"), reply.body)
    val first = reply.json("occurrences").arr.head
    assertEquals(first("medicationId").str, created.medicationId.toString)
    assertEquals(first("doseText").str, "1000 IU")
    assertEquals(first("state").str, "pending")
    assertEquals(first("scheduledTime").str, "09:00")

  test("GET /days/{date} shows another local day without the clock position"):
    startNewDay()
    val account = telegramAccount()
    fixtures.schedule(clock, account.accountId, "Daily", everyDayAtNine, ZoneId.of("UTC"))
    val tomorrow = clock.now().atZone(ZoneId.of("UTC")).toLocalDate.plusDays(1)
    val reply = get(s"/api/v1/days/$tomorrow", login(account))
    assertEquals(reply.status, 200, reply.body)
    assertEquals(reply.json("isToday").bool, false)
    assertEquals(reply.json("previousDate").str, tomorrow.minusDays(1).toString)
    assertEquals(reply.json("occurrences").arr.map(_("scheduledTime").str).toList, List("09:00"))
    assert(!reply.json.obj.contains("nowMinute"))

  test("no request leaves a log line when nothing failed"):
    assertEquals(logged.toList, Nil)
