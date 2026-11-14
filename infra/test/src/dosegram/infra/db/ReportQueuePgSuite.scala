package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.contracts.ChatRef
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.ports.CardPage
import dosegram.core.ports.NewReportRequest
import dosegram.core.ports.ReportPage
import dosegram.core.ports.ReportStatus

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** `report_requests` (ROADMAP G4.3, V7) on Testcontainers Postgres 18: the pages survive the round trip, a claim holds
  * for its lease and is taken once by two workers, the counts the limits read, and the purge.
  */
class ReportQueuePgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-11-14T10:00:00Z")
  private val lease = Duration.ofSeconds(30)
  private lazy val fixtures = Fixtures(dataSource)
  private val chat = ChatRef("telegram", "7001")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection(_.createStatement().execute("TRUNCATE report_requests"))
    ()

  private def uow = PgUnitOfWork(dataSource, () => t0, DataKeys.development)

  private def page(n: Int, shared: Option[String] = None): ReportPage =
    ReportPage(
      CardPage("water", s"<svg id='$n'>&amp;</svg>", s"Water today: $n", "water.png", hasSpoiler = n % 2 == 0, shared),
      OutboundMessage(body = List(Node.Paragraph(List(Inline.Text(s"caption $n")))), dedupeKey = s"image:$n", correlationId = "c")
    )

  private def request(account: UUID, at: Instant, pages: ReportPage*): NewReportRequest =
    NewReportRequest(UUID.randomUUID(), account, chat, pages.toList, at, at.plusSeconds(5))

  test("the application role may write, claim and purge requests"):
    withFreshConnection: conn =>
      val statement = conn.createStatement()
      statement.execute("SET ROLE dosegram_app")
      statement.execute("UPDATE report_requests SET attempts = attempts WHERE false")
      statement.execute("DELETE FROM report_requests WHERE false")

  test("a claimed request carries the pages, the chat and the times of its event, in order"):
    val account = fixtures.account()
    val queued = request(account, t0, page(1, Some("mood.p4.plain.dark")), page(2))
    uow.transaction(_.reports.enqueue(queued))
    val claimed = uow.transaction(_.reports.claim(t0, lease)).getOrElse(fail("nothing claimed"))
    assertEquals(claimed.id, queued.id)
    assertEquals(claimed.accountId, account)
    assertEquals(claimed.chat, chat)
    assertEquals(claimed.pages, queued.pages)
    assertEquals(claimed.createdAt, t0)
    assertEquals(claimed.deadlineAt, t0.plusSeconds(5))
    assertEquals(claimed.attempts, 1)

  test("a claim holds until its lease ends; then the next worker takes it, and a finished request is not claimed"):
    val account = fixtures.account()
    uow.transaction(_.reports.enqueue(request(account, t0, page(1))))
    val first = uow.transaction(_.reports.claim(t0, lease)).getOrElse(fail("nothing claimed"))
    assertEquals(uow.transaction(_.reports.claim(t0.plus(lease).minusMillis(1), lease)), None)
    val again = uow.transaction(_.reports.claim(t0.plus(lease), lease)).getOrElse(fail("the lapsed claim is not taken"))
    assertEquals((again.id, again.attempts), (first.id, 2))
    uow.transaction(_.reports.finish(again.id, ReportStatus.TextFallback, Some("Late"), None, t0.plus(lease)))
    assertEquals(uow.transaction(_.reports.claim(t0.plus(Duration.ofMinutes(5)), lease)), None)
    withConnection: conn =>
      val rs = conn.createStatement().executeQuery("SELECT status, error_class, finished_at IS NOT NULL FROM report_requests")
      rs.next()
      assertEquals((rs.getString(1), rs.getString(2), rs.getBoolean(3)), ("text_fallback", "Late", true))

  test("two workers claiming at once take different requests, oldest first"):
    val account = fixtures.account()
    val older = request(account, t0, page(1))
    val newer = request(fixtures.account(), t0.plusSeconds(1), page(2))
    uow.transaction(_.reports.enqueue(newer))
    uow.transaction(_.reports.enqueue(older))
    val pair = uow.transaction: tx =>
      val one = tx.reports.claim(t0.plusSeconds(2), lease)
      val two = PgUnitOfWork(dataSource, () => t0, DataKeys.development).transaction(_.reports.claim(t0.plusSeconds(2), lease))
      (one.map(_.id), two.map(_.id))
    assertEquals(pair, (Some(older.id), Some(newer.id)))

  test("waiting, busy and the requests of the last day are what the limits read"):
    val account = fixtures.account()
    val other = fixtures.account()
    uow.transaction(_.reports.enqueue(request(account, t0, page(1))))
    uow.transaction(_.reports.enqueue(request(other, t0.plusSeconds(1), page(2))))
    assertEquals(uow.transaction(_.reports.waiting()), 2)
    assert(uow.transaction(_.reports.isBusy(account)))
    val claimed = uow.transaction(_.reports.claim(t0.plusSeconds(2), lease)).getOrElse(fail("nothing claimed"))
    assertEquals(uow.transaction(_.reports.waiting()), 2, "a request being drawn still waits")
    uow.transaction(_.reports.finish(claimed.id, ReportStatus.Sent, None, Some(120L), t0.plusSeconds(3)))
    assert(!uow.transaction(_.reports.isBusy(account)), "a finished request leaves the account free")
    assertEquals(uow.transaction(_.reports.waiting()), 1)
    assertEquals(uow.transaction(_.reports.requestedSince(account, t0)), List(t0), "a request made at the edge counts")
    assertEquals(uow.transaction(_.reports.requestedSince(account, t0.plusMillis(1))), Nil)

  test("the purge deletes the unfinished requests made before its second instant"):
    val account = fixtures.account()
    uow.transaction(_.reports.enqueue(request(account, t0, page(1))))
    uow.transaction(_.reports.enqueue(request(account, t0.plusSeconds(60), page(2))))
    assertEquals(uow.transaction(_.reports.purge(t0, t0)), 0)
    assertEquals(uow.transaction(_.reports.purge(t0, t0.plusSeconds(1))), 1)
    assertEquals(uow.transaction(_.reports.waiting()), 1)

  test("a finished request keeps no pages and is deleted only after the first instant, for the daily limit"):
    val account = fixtures.account()
    uow.transaction(_.reports.enqueue(request(account, t0, page(1))))
    val claimed = uow.transaction(_.reports.claim(t0, lease)).getOrElse(fail("nothing claimed"))
    uow.transaction(_.reports.finish(claimed.id, ReportStatus.Sent, None, Some(80L), t0.plusSeconds(1)))
    withConnection: conn =>
      val rs = conn.createStatement().executeQuery("SELECT params::text FROM report_requests")
      rs.next()
      assertEquals(rs.getString(1), "{}", "the SVG and the captions go when the request is finished")
    assertEquals(uow.transaction(_.reports.purge(t0, t0.plusSeconds(60))), 0, "a finished request outlives the hour")
    assertEquals(uow.transaction(_.reports.requestedSince(account, t0)), List(t0))
    assertEquals(uow.transaction(_.reports.purge(t0.plusSeconds(1), t0.plusSeconds(60))), 1)
