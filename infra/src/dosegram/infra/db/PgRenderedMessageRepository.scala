package dosegram.infra.db

import dosegram.contracts.ChoiceMapEntry
import dosegram.contracts.MessageHandle
import dosegram.core.ports.KeyService
import dosegram.core.ports.RenderedChoiceMap
import dosegram.core.ports.RenderedMessageRepository
import dosegram.infra.db.SealedColumns.ChoiceMap

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** The handles of sent messages with their controls. The controls are sealed (V27): a label can name a medication, so
  * `choice_map_enc` holds them under the row's account key, or the transport key before the account existed.
  */
final class PgRenderedMessageRepository(conn: Connection, keys: KeyService) extends RenderedMessageRepository:
  private given Connection = conn

  override def record(
      handle: MessageHandle,
      accountId: Option[UUID],
      kind: String,
      subjectType: Option[String],
      subjectId: Option[UUID],
      epoch: Option[Int],
      choiceMap: List[ChoiceMapEntry],
      now: Instant
  ): Boolean =
    val id = UUID.randomUUID()
    val sealedChoiceMap = Option.when(choiceMap.nonEmpty)(sealChoiceMap(accountId, id, choiceMap))
    sql"""INSERT INTO rendered_messages
            (id, account_id, vendor, chat_id, message_id, kind, subject_type, subject_id, epoch, choice_map_enc, sent_at)
          VALUES ($id, $accountId, ${handle.vendor}, ${handle.chatId}, ${handle.messageId},
                  $kind, $subjectType, $subjectId, $epoch, $sealedChoiceMap, $now)
          ON CONFLICT (vendor, chat_id, message_id) DO NOTHING""".execute() == 1

  override def recordEdit(handle: MessageHandle, choiceMap: List[ChoiceMapEntry], now: Instant): Unit =
    if choiceMap.nonEmpty then
      sql"""SELECT id, account_id FROM rendered_messages
            WHERE vendor = ${handle.vendor} AND chat_id = ${handle.chatId} AND message_id = ${handle.messageId}"""
        .queryOne[(UUID, Option[UUID])]()
        .foreach: (id, accountId) =>
          sql"""UPDATE rendered_messages
                SET choice_map = NULL, choice_map_enc = ${sealChoiceMap(accountId, id, choiceMap)},
                    revision = revision + 1, last_edited_at = $now
                WHERE id = $id""".execute()
          ()

  override def choiceMapFor(handle: MessageHandle): Option[RenderedChoiceMap] =
    sql"""SELECT id, account_id, revision, choice_map_enc, controls_removed_at
          FROM rendered_messages
          WHERE vendor = ${handle.vendor} AND chat_id = ${handle.chatId} AND message_id = ${handle.messageId}"""
      .queryOne[StoredChoiceMap]()
      .map: row =>
        RenderedChoiceMap(handle, row.revision, openChoiceMap(row), row.controlsRemoved)

  override def latestPendingPrompt(vendor: String, chatId: String): Option[RenderedChoiceMap] =
    sql"""SELECT message_id, id, account_id, revision, choice_map_enc, controls_removed_at
          FROM rendered_messages
          WHERE vendor = $vendor AND chat_id = $chatId
            AND choice_map_enc IS NOT NULL AND controls_removed_at IS NULL
          ORDER BY sent_at DESC, message_id DESC
          LIMIT 1"""
      .queryOne[(String, StoredChoiceMap)]()
      .map: (messageId, row) =>
        RenderedChoiceMap(
          MessageHandle(vendor, chatId, messageId, row.revision),
          row.revision,
          openChoiceMap(row),
          controlsRemoved = false
        )

  override def detachFromSubject(handle: MessageHandle): Unit =
    sql"""UPDATE rendered_messages SET subject_type = NULL, subject_id = NULL
          WHERE vendor = ${handle.vendor} AND chat_id = ${handle.chatId} AND message_id = ${handle.messageId}"""
      .execute()
    ()

  override def handlesForSubject(subjectType: String, subjectId: UUID): List[MessageHandle] =
    sql"""SELECT vendor, chat_id, message_id, revision FROM rendered_messages
          WHERE subject_type = $subjectType AND subject_id = $subjectId
          ORDER BY sent_at, message_id"""
      .query[MessageHandle]()

  private given RowMapper[MessageHandle] = rs =>
    MessageHandle(rs.getString("vendor"), rs.getString("chat_id"), rs.getString("message_id"), rs.getInt("revision"))

  /** A row's controls as stored: sealed, bound to the row's id and owned by its account (or the transport key). */
  private final case class StoredChoiceMap(
      id: UUID,
      accountId: Option[UUID],
      revision: Int,
      sealedValue: Option[Array[Byte]],
      controlsRemoved: Boolean
  )

  private def storedChoiceMap(rs: java.sql.ResultSet): StoredChoiceMap =
    StoredChoiceMap(
      rs.uuid("id"),
      rs.optUuid("account_id"),
      rs.getInt("revision"),
      Option(rs.getBytes("choice_map_enc")),
      rs.optInstant("controls_removed_at").isDefined
    )

  private given RowMapper[StoredChoiceMap] = rs => storedChoiceMap(rs)

  private given RowMapper[(String, StoredChoiceMap)] = rs => (rs.getString("message_id"), storedChoiceMap(rs))

  private given RowMapper[(UUID, Option[UUID])] = rs => (rs.uuid("id"), rs.optUuid("account_id"))

  private def sealChoiceMap(accountId: Option[UUID], id: UUID, choiceMap: List[ChoiceMapEntry]): Array[Byte] =
    keys.sealText(ChoiceMap.forRow(accountId, id), upickle.default.write(choiceMap))

  private def openChoiceMap(row: StoredChoiceMap): List[ChoiceMapEntry] =
    row.sealedValue match
      case Some(value) =>
        upickle.default.read[List[ChoiceMapEntry]](keys.openText(ChoiceMap.forRow(row.accountId, row.id), value))
      case None => Nil
