package dosegram.infra.db

import dosegram.infra.crypto.DataKeys

import java.sql.Connection
import java.util.UUID

/** The payloads of queued messages as the dispatcher reads them: sealed since V13, so a test opens them with the
  * development keys instead of reading `payload::text`.
  */
object OutboxPayloads:

  /** The opened payloads of the rows `query` selects; it projects `id`, `account_id`, `payload` and `payload_enc`. */
  def open(query: SqlQuery)(using conn: Connection): List[String] =
    val keys = DataKeys.development.on(conn)
    given RowMapper[String] = rs =>
      val id = rs.getObject("id", classOf[UUID])
      PgOutboxRepository.payloadOf(keys, rs, id, Option(rs.getObject("account_id", classOf[UUID])))
    query.query[String]()
