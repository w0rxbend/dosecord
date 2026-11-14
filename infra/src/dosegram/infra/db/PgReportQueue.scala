package dosegram.infra.db

import dosegram.contracts.ChatRef
import dosegram.contracts.OutboundMessage
import dosegram.core.ports.CardPage
import dosegram.core.ports.NewReportRequest
import dosegram.core.ports.ReportPage
import dosegram.core.ports.ReportQueue
import dosegram.core.ports.ReportRequest
import dosegram.core.ports.ReportStatus

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** `report_requests` (ROADMAP G4.3, migration V7) on the connection of the surrounding transaction. `params` holds the
  * chat and the pages until the request is finished, then `{}`; nothing in it is read by SQL.
  */
final class PgReportQueue(conn: Connection) extends ReportQueue:
  private given Connection = conn

  private given RowMapper[ReportRequest] = rs =>
    val (chat, pages) = PgReportQueue.decode(rs.getString("params"))
    ReportRequest(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      chat = chat,
      pages = pages,
      createdAt = rs.instant("created_at"),
      deadlineAt = rs.instant("deadline_at"),
      attempts = rs.getInt("attempts")
    )

  override def enqueue(request: NewReportRequest): Unit =
    val params = Jsonb(PgReportQueue.encode(request.chat, request.pages))
    sql"""INSERT INTO report_requests (id, account_id, card_kind, params, status, deadline_at, created_at)
          VALUES (${request.id}, ${request.accountId}, ${request.pages.head.card.templateId}, $params, 'queued',
                  ${request.deadlineAt}, ${request.createdAt})""".execute()
    ()

  override def waiting(): Int =
    sql"SELECT count(*)::int FROM report_requests WHERE status IN ('queued', 'rendering')".queryOne[Int]().getOrElse(0)

  override def isBusy(accountId: UUID): Boolean =
    sql"""SELECT EXISTS (SELECT 1 FROM report_requests
          WHERE account_id = $accountId AND status IN ('queued', 'rendering'))""".queryOne[Boolean]().contains(true)

  override def requestedSince(accountId: UUID, since: Instant): List[Instant] =
    sql"""SELECT created_at FROM report_requests WHERE account_id = $accountId AND created_at >= $since
          ORDER BY created_at""".query[Instant]()

  override def claim(now: Instant, lease: Duration): Option[ReportRequest] =
    sql"""UPDATE report_requests SET status = 'rendering', lease_until = ${now.plus(lease)}, attempts = attempts + 1
          WHERE id = (SELECT id FROM report_requests
                      WHERE status = 'queued' OR (status = 'rendering' AND lease_until <= $now)
                      ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
          RETURNING id, account_id, params, created_at, deadline_at, attempts""".queryOne[ReportRequest]()

  override def finish(
      id: UUID,
      status: ReportStatus,
      errorClass: Option[String],
      renderMillis: Option[Long],
      now: Instant
  ): Unit =
    val millis = renderMillis.map(value => math.min(value, Int.MaxValue.toLong).toInt)
    sql"""UPDATE report_requests SET status = ${status.db}, lease_until = NULL, error_class = $errorClass,
                 render_ms = $millis, finished_at = $now, params = '{}'::jsonb
          WHERE id = $id AND status = 'rendering'""".execute()
    ()

  override def purge(before: Instant, unfinishedBefore: Instant): Int =
    sql"""DELETE FROM report_requests
          WHERE created_at < (CASE WHEN status IN ('queued', 'rendering') THEN $unfinishedBefore ELSE $before END)"""
      .execute()

object PgReportQueue:

  private[db] def encode(chat: ChatRef, pages: List[ReportPage]): String =
    ujson.write(
      ujson.Obj(
        "chat" -> upickle.default.writeJs(chat),
        "pages" -> ujson.Arr.from(pages.map: page =>
          ujson.Obj(
            "template" -> page.card.templateId,
            "svg" -> page.card.svg,
            "alt" -> page.card.altText,
            "file" -> page.card.fileName,
            "spoiler" -> page.card.hasSpoiler,
            "shared" -> page.card.sharedKey.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
            "message" -> ujson.read(OutboundMessage.toJson(page.message))
          ))
      )
    )

  private[db] def decode(params: String): (ChatRef, List[ReportPage]) =
    val json = ujson.read(params)
    val pages = json("pages").arr.toList.map: page =>
      val card = CardPage(
        templateId = page("template").str,
        svg = page("svg").str,
        altText = page("alt").str,
        fileName = page("file").str,
        hasSpoiler = page("spoiler").bool,
        sharedKey = page("shared").strOpt
      )
      ReportPage(card, OutboundMessage.fromJson(ujson.write(page("message"))))
    (upickle.default.read[ChatRef](json("chat")), pages)
