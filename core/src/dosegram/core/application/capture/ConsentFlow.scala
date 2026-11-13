package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Reply
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose

/** The two-stage consent of BOT_UX section 5.5 and the `/privacy` card of section 20.1. A consent is recorded only on
  * the "Confirm" tap; the card then becomes the receipt and is not edited again, and the input that opened the card
  * runs as a new message. Nothing is kept in a session: the pending input travels in the signed buttons. The ledger is
  * the one on the transaction of the event, so a consent and what it allows commit together.
  */
private[capture] final class ConsentFlow(tokens: Tokens, clock: Clock):

  /** Runs `granted` when the purpose is on; otherwise shows stage 1 with `pending` in its buttons. */
  def guarded(turn: Turn, purpose: ConsentPurpose, pending: Pending)(granted: => Reply): Reply =
    if turn.tx.consents.isGranted(turn.accountId, purpose) then granted
    else turn.show(stageOne(turn, ConsentRequest(ConsentStage.Ask, purpose, pending)))

  /** One tap on a consent card. `resume` runs the pending input once the consent is recorded. */
  def step(turn: Turn, request: ConsentRequest)(resume: Pending => Reply): Reply =
    request.stage match
      case ConsentStage.Ask     => turn.show(stageOne(turn, request))
      case ConsentStage.Agree   => turn.show(stageTwo(turn, request))
      case ConsentStage.Decline =>
        turn.show(turn.message("consent.declined", List(ConsentCopy.declined(request.purpose)), Nil))
      case ConsentStage.Confirm => confirm(turn, request, resume)

  private def confirm(turn: Turn, request: ConsentRequest, resume: Pending => Reply): Reply =
    val now = clock.now()
    val notice = ConsentNotice(ConsentCopy.NoticeVersion, ConsentCopy.statement(request.purpose))
    // A second tap on an old card records nothing and must not run the pending input a second time.
    if !turn.tx.consents.grant(turn.accountId, request.purpose, notice, now) then
      turn.toast(ReminderCopy.staleControlToast)
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
      List(button(turn, ConsentCopy.GoBack, request.at(ConsentStage.Ask)))
    )
    turn.message("consent.stage2", List(ConsentCopy.confirmation(request.purpose)), buttons)

  private def button(turn: Turn, label: String, request: ConsentRequest): Choice =
    Choice.callback(label, tokens.direct(CaptureActions.ConsentStep, turn.accountId, request.value))

  /** `/privacy`: every granted consent with its date and a "Withdraw" button (BUX-CONS-06). */
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
    turn.show(turn.message("privacy.card", lines, buttons))

  /** Withdrawal takes effect at once: the domain's commands return to the consent prompt. */
  def withdraw(turn: Turn, purposeCode: Long): Reply =
    ConsentRequest.purposeOf(purposeCode) match
      case Some(purpose) if turn.tx.consents.withdraw(turn.accountId, purpose, clock.now()) =>
        turn.show(turn.message("privacy.withdrawn", List(ConsentCopy.withdrawn(purpose)), Nil))
      case _ => turn.toast(ReminderCopy.staleControlToast)
