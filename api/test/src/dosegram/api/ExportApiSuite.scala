package dosegram.api

import dosegram.core.application.exports.ExportMessages
import dosegram.core.application.exports.ExportWorker
import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.insights.RollupService
import dosegram.core.ports.IdSource
import dosegram.core.ports.RecordSummaryRenderer
import dosegram.infra.db.sql

import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** ROADMAP G3.10 through the routes (ARCHITECTURE sections 9.5 and 9.7): a request waits for the confirming tap in the
  * chat; the file is fetched with a ticket that is bound to one export of the account, five requests and one address
  * prefix, and the ticket route alone carries `Content-Disposition` and the Telegram Web origin; 3 requests per 24
  * hours.
  */
class ExportApiSuite extends ApiSuite:

  private lazy val worker = ExportWorker(
    uow,
    clock,
    IdSource.uuidV7(RandomGenerator.getDefault),
    RecordSummaries(RollupService(uow, clock)),
    RecordSummaryRenderer.unavailable,
    ExportMessages(ApiSuite.Codec),
    _ => ()
  )

  /** An account with a chat to confirm in. */
  private def accountWithChat(): (ApiSuite.TelegramAccount, String) =
    val account = telegramAccount()
    fixtures.deliveryChannel(account.accountId, "fake", s"chat-${account.accountId}", clock.now())
    (account, login(account))

  private def outboxKinds(account: UUID): List[String] =
    withConnection: conn =>
      given Connection = conn
      sql"SELECT kind FROM outbox_messages WHERE account_id = $account ORDER BY created_at".query[String]()

  /** The confirming tap in the chat, as the chat flow records it. */
  private def confirm(account: UUID, id: String): Unit =
    assert(uow.transaction(_.exports.confirm(account, UUID.fromString(id), clock.now())))

  private def ready(account: UUID, token: String): String =
    val created = post("/api/v1/exports", ujson.Obj("kind" -> "full_zip"), token, newKey())
    assertEquals(created.status, 202, created.body)
    val id = created.json("id").str
    confirm(account, id)
    assert(worker.runOnce())
    id

  private def fetch(path: String, forwardedFor: String): Reply =
    send("GET", path, "", None, Map("X-Forwarded-For" -> forwardedFor))

  test("a request from the app waits for the confirmation in the chat, which is sent there; nothing is made before it"):
    val (account, token) = accountWithChat()
    val created = post("/api/v1/exports", ujson.Obj("kind" -> "full_zip"), token, newKey())
    assertEquals(created.status, 202, created.body)
    assertEquals(created.json("state").str, "awaiting_confirmation")
    assertEquals(outboxKinds(account.accountId), List("export"))
    assert(!worker.runOnce(), "no export is made without the confirming tap (AC-IMX-4)")
    val id = created.json("id").str
    assertProblem(post(s"/api/v1/exports/$id/download-tickets", ujson.Obj(), token), 409, "stepup_pending")
    confirm(account.accountId, id)
    assertEquals(get(s"/api/v1/exports/$id", token).json("state").str, "preparing")
    assert(worker.runOnce())
    val view = get(s"/api/v1/exports/$id", token).json
    assertEquals(view("state").str, "ready")
    assert(view("fileName").str.startsWith("dosegram-export-"))
    assertEquals(outboxKinds(account.accountId), List("export", "export"), "the ready message follows")

  test("a ticket gives the file with the download headers, five times from one address prefix, then nothing"):
    val (account, token) = accountWithChat()
    val id = ready(account.accountId, token)
    val ticket = post(s"/api/v1/exports/$id/download-tickets", ujson.Obj(), token)
    assertEquals(ticket.status, 201, ticket.body)
    val url = ticket.json("url").str
    assert(url.startsWith("/api/v1/downloads/"), url)
    val first = fetch(url, "203.0.113.7")
    assertEquals(first.status, 200)
    assertEquals(first.header("Access-Control-Allow-Origin"), Some("https://web.telegram.org"))
    assert(first.header("Content-Disposition").exists(_.startsWith("attachment; filename=\"dosegram-export-")))
    assertEquals(first.header("Content-Type"), Some("application/zip"))
    assert(first.body.startsWith("PK"), "a ZIP")
    assertEquals(fetch(url, "198.51.100.7").status, 404, "another address prefix")
    (2 to 5).foreach(_ => assertEquals(fetch(url, "203.0.113.99").status, 200, "the same /24"))
    assertEquals(fetch(url, "203.0.113.7").status, 404, "five requests at most")
    assertEquals(get(s"/api/v1/exports/$id", token).json("state").str, "downloaded")
    assertProblem(post(s"/api/v1/exports/$id/download-tickets", ujson.Obj(), token), 404, "not_found")

  test("a ticket is useless after 15 minutes, and an unknown ticket answers like an expired one"):
    val (account, token) = accountWithChat()
    val id = ready(account.accountId, token)
    val url = post(s"/api/v1/exports/$id/download-tickets", ujson.Obj(), token).json("url").str
    assertEquals(fetch("/api/v1/downloads/" + "A" * 43, "203.0.113.7").status, 404)
    clock.advance(Duration.ofMinutes(15))
    assertEquals(fetch(url, "203.0.113.7").status, 404)

  test("another account sees no export of this one and gets no ticket for it"):
    val (account, token) = accountWithChat()
    val id = ready(account.accountId, token)
    val (_, otherToken) = accountWithChat()
    assertProblem(get(s"/api/v1/exports/$id", otherToken), 404, "not_found")
    assertProblem(post(s"/api/v1/exports/$id/download-tickets", ujson.Obj(), otherToken), 404, "not_found")
    assertEquals(get("/api/v1/exports", otherToken).json("items").arr.size, 0)

  test("a fourth request within 24 hours is refused with the wait until the next one (AC-IMX-3)"):
    val (_, token) = accountWithChat()
    (1 to 3).foreach: _ =>
      assertEquals(post("/api/v1/exports", ujson.Obj("kind" -> "full_zip"), token, newKey()).status, 202)
      clock.advance(Duration.ofMinutes(1))
    val refused = post("/api/v1/exports", ujson.Obj("kind" -> "full_zip"), token, newKey())
    assertProblem(refused, 429, "rate_limited")
    assertEquals(refused.header("Retry-After").map(_.toLong), Some(Duration.ofHours(24).minusMinutes(3).toSeconds))

  test("a record summary takes a period of up to 366 days that has begun"):
    val (_, token) = accountWithChat()
    val today = clock.now().atZone(java.time.ZoneOffset.UTC).toLocalDate
    val summary = post(
      "/api/v1/exports",
      ujson.Obj("kind" -> "record_summary_pdf", "from" -> today.minusDays(29).toString, "to" -> today.toString),
      token,
      newKey()
    )
    assertEquals(summary.status, 202, summary.body)
    assertEquals(summary.json("from").str, today.minusDays(29).toString)
    val tooLong = post(
      "/api/v1/exports",
      ujson.Obj("kind" -> "record_summary_pdf", "from" -> today.minusDays(400).toString, "to" -> today.toString),
      token,
      newKey()
    )
    assertEquals(tooLong.status, 422, tooLong.body)
    val reversed = post(
      "/api/v1/exports",
      ujson.Obj("kind" -> "record_summary_pdf", "from" -> today.toString, "to" -> today.minusDays(1).toString),
      token,
      newKey()
    )
    assertEquals(reversed.fieldErrors, List("to" -> "before_from"))
    val lastYear = post("/api/v1/exports", ujson.Obj("kind" -> "record_summary_pdf", "days" -> 365), token, newKey())
    assertEquals(lastYear.status, 202, lastYear.body)
    assertEquals(lastYear.json("from").str, today.minusDays(364).toString)
end ExportApiSuite
