package dosecord.infra.db

import dosecord.contracts.ChoiceMapEntry
import dosecord.contracts.MessageHandle
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
