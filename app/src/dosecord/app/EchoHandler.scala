package dosecord.app

import dosecord.contracts.*
import dosecord.core.chat.ChatHandler
import dosecord.core.ports.Tx

/** Minimal stand-in handler (ROADMAP M0.12c): the real flow handlers land in `core/application` with M0.12d; until then
  * this echoes what the mediator delivered so the console vertical runs end to end. It never logs — the text goes only
  * to the user's own console.
  */
final class EchoHandler extends ChatHandler:
  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val text = event.body match
      case Inbound.MessageReceived(body, replyTo, _) =>
        replyTo match
          case Some(handle) => s"echo (reply to #${handle.messageId}): $body"
          case None         => s"echo: $body"
      case Inbound.CommandInvoked(name, _, raw)         => s"echo: command /$name ($raw)"
      case Inbound.InteractionSubmitted(callback, _, _) => s"echo: control action ${callback.actionId} resolved"
      case other                                        => s"echo: ${other.productPrefix}"
    Reply(
      followUps = List(
        OutboundMessage(
          body = List(Node.Paragraph(List(Inline.Text(text)))),
          dedupeKey = s"echo:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}"
        )
      )
    )
