package dosegram.core.application.privacy

import dosegram.contracts.CallbackRef
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.MessageHandle
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.PrivacyCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.AuditEvent
import dosegram.core.ports.AuditEventType
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DataDomain
import dosegram.core.ports.Tx

import java.util.UUID

/** Deletion in the chat (BOT_UX section 20.3, BUX-CONS-06; ROADMAP G2.17, G5.7). "Delete data" on `/privacy` offers one
  * button per domain and "Delete my account". A domain is deleted after its confirmation; the account after two
  * confirmations, the second of which deletes it in the transaction of the tap and answers with the last message of the
  * bot. A confirmation counts for ten minutes. Every button names the account it was made for: a tap from anyone else,
  * or from a deleted account, is answered with the neutral stale notice. Anything else goes to `inner`.
  *
  * `stop` ends what a consent kept running when the domain deletion withdraws it (the medication reminders).
  */
final class PrivacyChat(
    clock: Clock,
    deletions: Deletions,
    messages: PrivacyMessages,
    stop: (Tx, UUID, ConsentPurpose) => Unit,
    inner: ChatHandler
) extends ChatHandler:
  import PrivacyActions.*

  private final case class Turn(event: InboundEvent, accountId: UUID, source: Option[MessageHandle], tx: Tx):
    def key: String = s"${event.vendor}:${event.vendorEventId}"

    def show(message: OutboundMessage): Reply =
      source match
        case Some(handle) => Reply(replace = Some(message.copy(replaces = Some(handle))))
        case None         => Reply(followUps = List(message))

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    event.body match
      case Inbound.InteractionSubmitted(ref, source) if ActionRegistry.byId(ref.actionId).exists(all) =>
        principal.accountId match
          case Some(account) if ref.subject == account.uuid => tap(Turn(event, account.uuid, source, tx), ref)
          case _                                            => stale
      case _ => inner.handle(event, principal, tx)

  private def tap(turn: Turn, ref: CallbackRef): Reply =
    ActionRegistry.byId(ref.actionId) match
      case Some(DeleteMenu)       => turn.show(messages.menu(turn.accountId, turn.key))
      case Some(Keep)             => turn.show(messages.text("privacy.delete.kept", turn.key, PrivacyCopy.kept))
      case Some(DeleteDomainAsk)  => askDomain(turn, ref.value)
      case Some(DeleteDomain)     => confirmed(turn, ref.value)(deleteDomain(turn, _))
      case Some(DeleteAccountAsk) =>
        turn.show(messages.accountConfirm(turn.accountId, shown(fromApp = false, None), turn.key))
      case Some(DeleteAccountConfirm) =>
        confirmed(turn, ref.value): _ =>
          // The app's step-up is the first confirmation; the second is the chat's own.
          turn.show(messages.accountFinal(turn.accountId, shown(fromApp = false, None), turn.key))
      case Some(DeleteAccount) => confirmed(turn, ref.value)(deleteAccount(turn, _))
      case _                   => stale

  private def askDomain(turn: Turn, value: Long): Reply =
    Option
      .when(value >= 0 && value < DataDomain.values.length)(DataDomain.fromOrdinal(value.toInt))
      .fold(stale): domain =>
        turn.show(messages.domainConfirm(turn.accountId, shown(fromApp = false, Some(domain)), domain, turn.key))

  /** Runs `run` for a confirmation that is still fresh; an old one deletes nothing and says so. */
  private def confirmed(turn: Turn, value: Long)(run: Confirmation => Reply): Reply =
    Confirmation.of(value) match
      case None                                                     => stale
      case Some(confirmation) if !confirmation.isFresh(clock.now()) =>
        turn.show(messages.text("privacy.delete.expired", turn.key, PrivacyCopy.expired))
      case Some(confirmation) =>
        if confirmation.fromApp then turn.tx.auditLog.record(stepUpConfirmed(turn.accountId, confirmation))
        run(confirmation)

  private def deleteDomain(turn: Turn, confirmation: Confirmation): Reply =
    confirmation.domain.fold(stale): target =>
      deletions.domain(turn.tx, turn.accountId, target, AuditChannel.Bot)(stop(turn.tx, turn.accountId, _))
      turn.show(messages.text("privacy.delete.domain.done", turn.key, PrivacyCopy.domainDeleted(target)))

  private def deleteAccount(turn: Turn, confirmation: Confirmation): Reply =
    if confirmation.domain.isDefined then stale
    else
      deletions.account(turn.tx, turn.accountId, AuditChannel.Bot)
      turn.show(messages.text("privacy.deleted", turn.key, PrivacyCopy.deleted))

  private def stepUpConfirmed(accountId: UUID, confirmation: Confirmation): AuditEvent =
    AuditEvent
      .byUser(
        AuditEventType.StepUpConfirmed,
        accountId,
        confirmation.domain.fold("account")(_ => "domain"),
        None,
        "delete",
        AuditChannel.Bot
      )
      .copy(reasonCode = confirmation.domain.map(_.code))

  private def shown(fromApp: Boolean, domain: Option[DataDomain]): Confirmation =
    Confirmation.shown(clock.now(), fromApp, domain)

  private def stale: Reply = Reply(toast = Some(ReminderCopy.staleControlToast))
end PrivacyChat
