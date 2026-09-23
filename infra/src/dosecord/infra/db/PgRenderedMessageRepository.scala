package dosecord.infra.db

import dosecord.contracts.ChoiceMapEntry
import dosecord.contracts.MessageHandle
import dosecord.core.ports.RenderedChoiceMap
import dosecord.core.ports.RenderedMessageRepository

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

final class PgRenderedMessageRepository(conn: Connection) extends RenderedMessageRepository:
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
    val choiceMapJson = if choiceMap.isEmpty then None else Some(Jsonb(upickle.default.write(choiceMap)))
    sql"""INSERT INTO rendered_messages
            (id, account_id, vendor, chat_id, message_id, kind, subject_type, subject_id, epoch, choice_map, sent_at)
          VALUES (${UUID.randomUUID()}, $accountId, ${handle.vendor}, ${handle.chatId}, ${handle.messageId},
                  $kind, $subjectType, $subjectId, $epoch, $choiceMapJson, $now)
          ON CONFLICT (vendor, chat_id, message_id) DO NOTHING""".execute() == 1

  override def choiceMapFor(handle: MessageHandle): Option[RenderedChoiceMap] =
    sql"""SELECT revision, choice_map, controls_removed_at
          FROM rendered_messages
          WHERE vendor = ${handle.vendor} AND chat_id = ${handle.chatId} AND message_id = ${handle.messageId}"""
      .queryOne[(Int, Option[String], Option[Instant])]()
      .map: (revision, choiceMapJson, controlsRemovedAt) =>
        RenderedChoiceMap(handle, revision, readChoiceMap(choiceMapJson), controlsRemovedAt.isDefined)

  override def latestPendingPrompt(vendor: String, chatId: String): Option[RenderedChoiceMap] =
    sql"""SELECT message_id, revision, choice_map
          FROM rendered_messages
          WHERE vendor = $vendor AND chat_id = $chatId
            AND choice_map IS NOT NULL AND controls_removed_at IS NULL
          ORDER BY sent_at DESC, message_id DESC
          LIMIT 1"""
      .queryOne[(String, Int, String)]()
      .map: (messageId, revision, choiceMapJson) =>
        RenderedChoiceMap(
          MessageHandle(vendor, chatId, messageId, revision),
          revision,
          readChoiceMap(Some(choiceMapJson)),
          controlsRemoved = false
        )

  override def handlesForSubject(subjectType: String, subjectId: UUID): List[MessageHandle] =
    sql"""SELECT vendor, chat_id, message_id, revision FROM rendered_messages
          WHERE subject_type = $subjectType AND subject_id = $subjectId
          ORDER BY sent_at, message_id"""
      .query[MessageHandle]()

  private given RowMapper[MessageHandle] = rs =>
    MessageHandle(rs.getString("vendor"), rs.getString("chat_id"), rs.getString("message_id"), rs.getInt("revision"))

  private given RowMapper[(Int, Option[String], Option[Instant])] = rs =>
    (rs.getInt("revision"), rs.optString("choice_map"), rs.optInstant("controls_removed_at"))

  private given RowMapper[(String, Int, String)] = rs =>
    (rs.getString("message_id"), rs.getInt("revision"), rs.getString("choice_map"))

  private def readChoiceMap(json: Option[String]): List[ChoiceMapEntry] =
    json match
      case Some(document) => upickle.default.read[List[ChoiceMapEntry]](document)
      case None           => Nil
