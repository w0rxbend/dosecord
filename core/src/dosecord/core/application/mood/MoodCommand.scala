package dosecord.core.application.mood

import dosecord.contracts.*
import dosecord.core.chat.ChatHandler
import dosecord.core.domain.copy.IdentityCopy
import dosecord.core.domain.copy.MoodCopy
import dosecord.core.ports.Clock
import dosecord.core.ports.NewMoodCheckin
import dosecord.core.ports.Tx

import java.util.UUID

/** `/mood <1-10|word> [note] [#tag ...]` (ROADMAP M0.12d, R35/R37/R38): stores one `mood_checkins` row (note only when
  * supplied, trailing `#tags` into `tags text[]`) and appends `dosecord.mood.checkin_recorded.v1` on the reply. An
  * unlinked principal is sent to `/start` (DESIGN.md section 6); an unparsable level gets the usage error and writes
  * nothing (C18).
  */
final class MoodCommand(clock: Clock) extends ChatHandler:

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    event.body match
      case Inbound.CommandInvoked(_, _, raw) =>
        principal.accountId match
          case None          => reply(event, IdentityCopy.createFirst)
          case Some(account) =>
            MoodParsing.parse(dropCommandName(raw)) match
              case Left(error)   => reply(event, error)
              case Right(parsed) =>
                tx.moodCheckins.insert(
                  NewMoodCheckin(
                    id = UUID.randomUUID(),
                    accountId = account.uuid,
                    moodLevel = parsed.level,
                    note = parsed.note,
                    tags = parsed.tags,
                    platformIdentityId = Some(principal.identityId.uuid),
                    recordedAt = clock.now()
                  )
                )
                Reply(
                  followUps = List(outbound(event, MoodCopy.recorded(parsed.level.value))),
                  domainEvents = List(Event.MoodCheckinRecorded(account, parsed.level, parsed.note, parsed.tags))
                )
      case _ => Reply.empty

  /** `/mood 8 ...` -> `8 ...` (the console carries the raw line; vendor adapters populate structured args in M2.1). */
  private def dropCommandName(raw: String): String =
    raw.trim.drop(1).dropWhile(c => !c.isWhitespace)

  private def reply(event: InboundEvent, text: String): Reply =
    Reply(followUps = List(outbound(event, text)))

  private def outbound(event: InboundEvent, text: String): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(text)))),
      dedupeKey = s"mood:${event.vendor}:${event.vendorEventId}",
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )
end MoodCommand
