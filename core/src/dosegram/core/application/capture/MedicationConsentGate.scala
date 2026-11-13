package dosegram.core.application.capture

import dosegram.contracts.InboundEvent
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.chat.CallbackCodec
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.Tx

import java.time.ZoneId
import java.util.UUID

/** PRODUCT_SPEC R-IDC-9 for the medication domain in chat: without an active `medication` consent a medication command
  * or button answers with the consent prompt, records nothing and shows no record (BOT_UX BUX-CMD-03, BUX-CONS-04). The
  * handlers of the medication surface put every entry point behind [[guarded]]; the taps on the prompt are the capture
  * handler's, which opens the screen after the confirming tap.
  */
final class MedicationConsentGate(codec: CallbackCodec, clock: Clock):

  private val consent = ConsentFlow(Tokens(codec), clock)

  /** Runs `granted` for an account whose consent is on. An account without it gets stage 1 of the consent as a message
    * of its own, so the card the request came from stays as it is. An event without an account passes: the handler
    * answers that one itself.
    */
  def guarded(event: InboundEvent, principal: Principal, tx: Tx)(screen: MedicationScreen)(granted: => Reply): Reply =
    principal.accountId match
      case None          => granted
      case Some(account) =>
        val zone = ZoneId.of(tx.accounts.timezoneOf(account.uuid).getOrElse("UTC"))
        val turn = Turn(event, principal, account.uuid, zone, source = None, tx)
        consent.guarded(turn, ConsentPurpose.Medication, Pending.Medication(screen))(granted)

object MedicationConsentGate:

  /** Whether the medication records of the account may be written and shown now. */
  def isOpen(tx: Tx, accountId: UUID): Boolean = tx.consents.isGranted(accountId, ConsentPurpose.Medication)
