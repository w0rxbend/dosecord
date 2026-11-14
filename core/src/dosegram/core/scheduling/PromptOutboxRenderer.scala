package dosegram.core.scheduling

import dosegram.contracts.CapabilityProfile
import dosegram.contracts.ChatRef
import dosegram.contracts.OutboundMessage
import dosegram.contracts.RenderedMessage
import dosegram.contracts.VendorOp
import dosegram.core.chat.Renderer
import dosegram.core.ports.OutboxMessage
import dosegram.core.ports.UnitOfWork

/** Renders the outbox rows of the prompt loop ([[PromptLoop.OutboxKind]]), and of any other producer in `kinds` that
  * sends a finished message to a channel (the export messages): the payload is the message itself, built when it was
  * decided, and the chat is the row's delivery channel, or the chat of the message an edit targets. Every other kind
  * goes to `next`.
  */
final class PromptOutboxRenderer(uow: UnitOfWork, next: OutboxRenderer, kinds: Set[String]) extends OutboxRenderer:

  def this(uow: UnitOfWork, next: OutboxRenderer) = this(uow, next, Set(PromptLoop.OutboxKind))

  override def render(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
    if !kinds(row.kind) then next.render(row, capabilities)
    else
      val chat = ChatRef(row.vendor, chatIdOf(row))
      val (ops, _) = Renderer.render(OutboundMessage.fromJson(row.payload), chat, capabilities, row.sendKey)
      val rendered = ops
        .collectFirst { case VendorOp.Send(_, sent, _, _) => sent }
        .getOrElse(throw IllegalStateException(s"outbox row ${row.id}: renderer produced no send op"))
      (chat, rendered)

  private def chatIdOf(row: OutboxMessage): String =
    row.target match
      case Some(handle) => OutboxDispatcher.decodeHandle(row.vendor, handle).chatId
      case None         =>
        row.channelId
          .flatMap(id => uow.transaction(_.channels.byId(id)).flatMap(_.chatId))
          .getOrElse(throw IllegalStateException(s"outbox row ${row.id}: no channel chat for a prompt"))
