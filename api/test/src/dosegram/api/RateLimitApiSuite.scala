package dosegram.api

import java.time.Duration

/** ARCHITECTURE API-18 (research brief 06 section 12.3), required by checkpoint CP3-a: 120 reads per minute per
  * session and 60 writes per minute per account; a breach answers 429 with `Retry-After`. Every request that is not a
  * `GET` counts as a write.
  */
final class RateLimitApiSuite extends ApiSuite:

  test("the 121st read of one session inside a minute answers 429 with Retry-After; the next minute reads again"):
    val token = login(telegramAccount())
    (1 to 120).foreach(n => assertEquals(get("/api/v1/medication-options", token).status, 200, s"read $n"))
    val refused = get("/api/v1/medication-options", token)
    assertProblem(refused, 429, "rate_limited")
    assertEquals(refused.header("Retry-After"), Some("60"))
    clock.advance(Duration.ofSeconds(60))
    assertEquals(get("/api/v1/medication-options", token).status, 200)

  test("the read limit belongs to the session: another session of the same account still reads"):
    val account = telegramAccount()
    val busy = login(account)
    (1 to 120).foreach(_ => get("/api/v1/medication-options", busy))
    assertProblem(get("/api/v1/medication-options", busy), 429, "rate_limited")
    assertEquals(get("/api/v1/medication-options", login(account)).status, 200)
    clock.advance(Duration.ofSeconds(60))

  test("the 61st write of one account inside a minute answers 429, also from its other session; reads still pass"):
    val account = telegramAccount()
    val first = login(account)
    val second = login(account)
    (1 to 60).foreach: n =>
      val token = if n % 2 == 0 then first else second
      assertEquals(send("POST", "/api/v1/session/keepalive", token, None, Map.empty).status, 204, s"write $n")
    val refused = send("POST", "/api/v1/session/keepalive", first, None, Map.empty)
    assertProblem(refused, 429, "rate_limited")
    assertEquals(refused.header("Retry-After"), Some("60"))
    assertProblem(send("POST", "/api/v1/session/keepalive", second, None, Map.empty), 429, "rate_limited")
    assertEquals(get("/api/v1/medication-options", first).status, 200)
    clock.advance(Duration.ofSeconds(60))
    assertEquals(send("POST", "/api/v1/session/keepalive", first, None, Map.empty).status, 204)

  test("another account's writes are not counted against this one"):
    val busy = login(telegramAccount())
    (1 to 61).foreach(_ => send("POST", "/api/v1/session/keepalive", busy, None, Map.empty))
    val other = login(telegramAccount())
    assertEquals(send("POST", "/api/v1/session/keepalive", other, None, Map.empty).status, 204)
    clock.advance(Duration.ofSeconds(60))

  test("a refused request leaves no log line"):
    assertEquals(logged.toList, Nil)
