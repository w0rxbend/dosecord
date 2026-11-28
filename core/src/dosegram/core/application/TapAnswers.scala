package dosegram.core.application

import dosegram.contracts.InboundEvent
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.Tx

/** Every tap is answered with words (supervisor decision, 2026-11-28; BOT_UX section 4.4). In the rehearsal of that day
  * "7 days" on an unchanged card and "Snooze 10m" on a closed dose stopped the progress indicator on nothing. A tap
  * whose handler gives no toast gets one that says what its answer does. The toast rides the tap's answer only: it
  * never becomes a message, and a typed reply that a choice map resolves to a tap has no answer to ride.
  */
final class TapAnswers(inner: ChatHandler) extends ChatHandler:
  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val reply = inner.handle(event, principal, tx)
    if event.interaction.isEmpty || reply.toast.isDefined then reply
    else reply.copy(toast = Some(TapAnswers.toastOf(reply)), toastOnAnswerOnly = true)

object TapAnswers:

  /** New messages are named as such; an edit of the tapped card leaves it current; an answer with neither changed
    * nothing the person can see.
    */
  def toastOf(reply: Reply): String =
    if reply.followUps.nonEmpty || reply.replace.exists(isNewMessage) then ReminderCopy.tapSentBelow
    else if reply.replace.isDefined then ReminderCopy.tapUpToDate
    else ReminderCopy.tapNothingChanged

  // A message with an attachment or an album never edits the card it replaces (ADR-015).
  private def isNewMessage(message: OutboundMessage): Boolean = message.attachment.isDefined || message.album.nonEmpty
