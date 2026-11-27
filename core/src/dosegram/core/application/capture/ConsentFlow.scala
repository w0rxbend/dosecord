package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Reply
import dosegram.core.application.privacy.PrivacyMessages
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DataDomain
import dosegram.core.ports.NewOutboxMessage
import dosegram.core.ports.OutboxOp

import java.time.Duration
import java.util.UUID

/** The two-stage consent of BOT_UX section 5.5 and the `/privacy` card of section 20.1. A consent is recorded only on
  * the "Confirm" tap; the card then becomes the receipt and is not edited again, and the input that opened the card
  * runs as a new message. Nothing is kept in a session: the pending input travels in the signed buttons. The ledger is
  * the one on the transaction of the event, so a consent and what it allows commit together.
  */
private[capture] final class ConsentFlow(tokens: Tokens, clock: Clock, privacyButtons: PrivacyMessages):

  /** Runs `granted` when the purpose is on; otherwise shows stage 1 with `pending` in its buttons. */
  def guarded(turn: Turn, purpose: ConsentPurpose, pending: Pending)(granted: => Reply): Reply =
    if turn.tx.consents.isGranted(turn.accountId, purpose) then granted
    else
      val card = stageOne(turn, ConsentRequest.asking(purpose, pending))
        .copy(dedupeKey = s"${cardPrefix(purpose)}${turn.event.vendor}:${turn.event.vendorEventId}")
      removeEarlierCards(turn, purpose, card.dedupeKey)
      turn.show(card)

  /** One consent card per purpose (finding 11 of the review of 2026-11-27; supervisor decision, BOT_UX section 5.5): a
    * card of the last day for the same purpose is deleted, or cancelled while still queued, so the new card with the
    * newest pending input is the only one. An older card is left alone: the chat may no longer delete it.
    */
  private def removeEarlierCards(turn: Turn, purpose: ConsentPurpose, current: String): Unit =
    val now = clock.now()
    val vendor = turn.event.vendor
    turn.tx.outbox
      .recentSendKeys(turn.accountId, cardPrefix(purpose), now.minus(ConsentFlow.OpenCardWindow))
      .filterNot(_ == current)
      .foreach: key =>
        turn.tx.outbox.recordedHandle(vendor, key) match
          case Some(handle) =>
            turn.tx.outbox.enqueue(
              NewOutboxMessage(
                id = UUID.randomUUID(),
                sendKey = s"$key:delete",
                op = OutboxOp.Delete,
                kind = ConsentFlow.CardDeleteKind,
                vendor = vendor,
                accountId = Some(turn.accountId),
                payload = "{}",
                target = Some(handle),
                importance = "interaction_reply",
                nextAttemptAt = now
              )
            )
          case None => turn.tx.outbox.cancelQueued(key)

  private def cardPrefix(purpose: ConsentPurpose): String = s"consent.card.${ConsentRequest.codeOf(purpose)}:"

  /** One tap on a consent card. `resume` runs the pending input once the consent is recorded. */
  def step(turn: Turn, request: ConsentRequest)(resume: Pending => Reply): Reply =
    request.stage match
      case ConsentStage.Ask     => turn.show(stageOne(turn, request))
      case ConsentStage.Agree   => turn.show(stageTwo(turn, request.agreedAt(clock.now())))
      case ConsentStage.Decline =>
        turn.show(turn.message("consent.declined", List(ConsentCopy.declined(request.purpose)), Nil))
      case ConsentStage.Confirm => confirm(turn, request, resume)

  private def confirm(turn: Turn, request: ConsentRequest, resume: Pending => Reply): Reply =
    val now = clock.now()
    val notice = ConsentNotice(ConsentCopy.NoticeVersion, ConsentCopy.statement(request.purpose), ConsentCopy.Locale)
    // A second tap on an old card records nothing and must not run the pending input a second time, also when the
    // purpose was granted under an earlier notice version, which stays in force (PRODUCT_SPEC R-IDC-11).
    if turn.tx.consents.isGranted(turn.accountId, request.purpose) ||
      !turn.tx.consents.grant(turn.accountId, request.purpose, notice, request.taps(now))
    then turn.toast(ReminderCopy.staleControlToast)
    else
      val receipt = List(ConsentCopy.receipt(request.purpose, turn.localDate(now)))
      val resumed = resume(request.pending)
      val receiptReply = turn.show(turn.message("consent.receipt", receipt, Nil))
      receiptReply.copy(
        followUps = receiptReply.followUps ++ resumed.replace.map(asNewMessage).toList ++ resumed.followUps,
        toast = resumed.toast
      )

  private def asNewMessage(message: OutboundMessage): OutboundMessage = message.copy(replaces = None)

  private def stageOne(turn: Turn, request: ConsentRequest): OutboundMessage =
    val buttons = List(
      List(button(turn, ConsentCopy.Agree, request.at(ConsentStage.Agree))),
      List(button(turn, ConsentCopy.NotNow, request.at(ConsentStage.Decline)))
    )
    turn.message("consent.stage1", List(ConsentCopy.statement(request.purpose)), buttons)

  private def stageTwo(turn: Turn, request: ConsentRequest): OutboundMessage =
    val buttons = List(
      List(button(turn, ConsentCopy.Confirm, request.at(ConsentStage.Confirm))),
      List(button(turn, ConsentCopy.GoBack, ConsentRequest.asking(request.purpose, request.pending)))
    )
    turn.message("consent.stage2", List(ConsentCopy.confirmation(request.purpose)), buttons)

  private def button(turn: Turn, label: String, request: ConsentRequest): Choice =
    Choice.callback(label, tokens.direct(CaptureActions.ConsentStep, turn.accountId, request.value))

  /** `/privacy`: every granted consent with its date and a "Withdraw" button (BUX-CONS-06), then "Delete data"
    * (BUX-DEL-01).
    */
  def privacy(turn: Turn): Reply =
    val granted = turn.tx.consents.granted(turn.accountId)
    val lines =
      if granted.isEmpty then List(ConsentCopy.privacyNone)
      else
        ConsentCopy.privacyTitle +:
          granted.map(consent => ConsentCopy.privacyLine(consent.purpose, turn.localDate(consent.grantedAt)))
    val buttons = granted.map: consent =>
      val code = ConsentRequest.codeOf(consent.purpose).toLong
      List(
        Choice.callback(
          ConsentCopy.withdrawLabel(consent.purpose),
          tokens.direct(CaptureActions.ConsentWithdraw, turn.accountId, code)
        )
      )
    val actions =
      List(List(privacyButtons.exportButton(turn.accountId)), List(privacyButtons.menuButton(turn.accountId)))
    turn.show(turn.message("privacy.card", lines, buttons ++ actions))

  /** Withdrawal takes effect at once, in the transaction of the tap: the domain's commands return to the consent
    * prompt, and `stop` ends whatever the purpose kept running (PRODUCT_SPEC R-IDC-10). A domain's withdrawal offers
    * "Keep the data" and "Delete this data" (BUX-CONS-06).
    */
  def withdraw(turn: Turn, purposeCode: Long)(stop: ConsentPurpose => Unit): Reply =
    ConsentRequest.purposeOf(purposeCode) match
      case Some(purpose) if turn.tx.consents.withdraw(turn.accountId, purpose, clock.now()) =>
        stop(purpose)
        val choices = DataDomain.ofPurpose(purpose).toList.flatMap(privacyButtons.afterWithdrawal(turn.accountId, _))
        turn.show(turn.message("privacy.withdrawn", List(ConsentCopy.withdrawn(purpose)), choices))
      case _ => turn.toast(ReminderCopy.staleControlToast)

private[capture] object ConsentFlow:

  /** How far back a new consent card looks for an earlier one of its purpose: within a day the chat can still delete
    * it.
    */
  val OpenCardWindow: Duration = Duration.ofHours(24)

  /** The outbox kind of the delete of an earlier consent card. */
  val CardDeleteKind = "consent_card_delete"
