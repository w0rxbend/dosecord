package dosegram.core.application

import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.Inline
import dosegram.contracts.MessageHandle
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.WhatsNewCopy
import dosegram.core.ports.Clock
import dosegram.core.ports.Tx

import java.util.UUID

/** The "What's new" note (supervisor decision, 2026-11-28; BOT_UX section 6.5). After a release that changes what the
  * user sees ([[WhatsNewCopy.Release]]), the answer to the account's next command carries one more message: the
  * commands the release added, with "Don't show these". It comes once per release and never on its own: no prompt, no
  * schedule, nothing in quiet hours, nothing for an account that turned it off, and nothing for an account created
  * after the notes began (`seen` absent), which only records the release. `/whatsnew` shows the latest note with the
  * switch. The state is written on the event's transaction, so a redelivered update shows nothing twice.
  */
final class WhatsNewChat(codec: CallbackCodec, clock: Clock, inner: ChatHandler) extends ChatHandler:

  import WhatsNewChat.*

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val account = principal.accountId.map(_.uuid)
    event.body match
      case Inbound.CommandInvoked(name, _, _) if name == CommandRegistry.WhatsNew.name =>
        account.fold(Reply(toast = Some(IdentityCopy.createFirst)))(card(event, _, None, tx))
      case Inbound.InteractionSubmitted(ref, source) if ref.actionId == Switch.id =>
        account.filter(_ == ref.subject).fold(stale)(switch(event, _, ref.value == On, source, tx))
      case Inbound.CommandInvoked(name, _, _) if name != CommandRegistry.Start.name =>
        val reply = inner.handle(event, principal, tx)
        account.fold(reply)(after(event, _, reply, tx))
      case _ => inner.handle(event, principal, tx)

  /** The note after the answer to a command, when the account has not been told of the current release. */
  private def after(event: InboundEvent, account: UUID, reply: Reply, tx: Tx): Reply =
    val state = tx.accounts.whatsNewOf(account)
    val answered = reply.followUps.nonEmpty || reply.replace.nonEmpty
    if !answered || state.seen.exists(_ >= WhatsNewCopy.Release) then reply
    else
      tx.accounts.setWhatsNewSeen(account, WhatsNewCopy.Release, clock.now())
      if state.seen.isEmpty || !state.enabled then reply
      else reply.copy(followUps = reply.followUps :+ note(event, account, enabled = true))

  private def card(event: InboundEvent, account: UUID, source: Option[MessageHandle], tx: Tx): Reply =
    show(source, note(event, account, tx.accounts.whatsNewOf(account).enabled))

  private def switch(event: InboundEvent, account: UUID, enabled: Boolean, source: Option[MessageHandle], tx: Tx) =
    tx.accounts.setWhatsNewEnabled(account, enabled, clock.now())
    show(source, message(event, List(if enabled then WhatsNewCopy.on else WhatsNewCopy.off), button(account, enabled)))

  private def note(event: InboundEvent, account: UUID, enabled: Boolean): OutboundMessage =
    message(event, WhatsNewCopy.lines, button(account, enabled))

  /** "Don't show these" while the notes are on, "Show after updates" while they are off. */
  private def button(account: UUID, enabled: Boolean): Choice =
    if enabled then Choice.callback(WhatsNewCopy.StopThese, token(account, Off))
    else Choice.callback(WhatsNewCopy.TurnOn, token(account, On))

  private def token(account: UUID, value: Long): String = codec.encode(CallbackMode.Direct, Switch, account, value).wire

  private def message(event: InboundEvent, lines: List[String], choice: Choice): OutboundMessage =
    OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks = List(Block.Choices(ChoiceSet(Kind, choices = List(choice)))),
      dedupeKey = s"$Kind:${event.vendor}:${event.vendorEventId}",
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )

  private def show(source: Option[MessageHandle], message: OutboundMessage): Reply =
    source match
      case Some(handle) => Reply(replace = Some(message.copy(replaces = Some(handle))))
      case None         => Reply(followUps = List(message))

  private def stale: Reply = Reply(toast = Some(ReminderCopy.staleControlToast))

object WhatsNewChat:
  val Switch: ActionEntry =
    ActionRegistry.byName("whatsnew.switch").getOrElse(throw IllegalStateException("whatsnew.switch is not registered"))

  /** The values of [[Switch]]: 0 turns the notes off, 1 back on. */
  val Off = 0L
  val On = 1L

  private val Kind = "whatsnew"
