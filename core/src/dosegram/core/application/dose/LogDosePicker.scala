package dosegram.core.application.dose

import dosegram.contracts.*
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.CallbackSlot
import dosegram.core.ports.Tx

import java.util.UUID

/** The manual-logging picker shared by Medications -> Log dose (M1.10) and a bare `/log`: one [Log <name>] control per
  * active medication, each a direct `dose.log` token on the medication id (medication ids never expire, so the picker
  * keeps working on an old message). The tap itself records through [[DoseIntakeHandler]].
  */
private[application] object LogDosePicker:

  /** The menu slot key that opens the picker (Medications -> Log dose, and "Log another"). */
  val MenuKey = "menu:logdose"

  /** "Log another" (BUX-TAKE-01, BUX-TAKE-02): a menu slot that sends a fresh picker, a new message and a new intent.
    */
  def anotherChoice(codec: CallbackCodec, chat: ChatRef, accountId: UUID, tx: Tx): Choice =
    val slotId = UUID.randomUUID()
    tx.slots.insert(
      CallbackSlot(
        id = slotId,
        accountId = Some(accountId),
        chat = WizardDocument.chatToJson(chat),
        payload = WizardDocument.slotPayloadToJson(MenuKey),
        sessionId = None,
        stepSeq = None,
        expiresAt = None
      )
    )
    val menuOpen = dosegram.core.chat.ActionRegistry.byName("menu.open").get
    Choice.callback(ReminderCopy.logAnother, codec.encode(CallbackMode.Slot, menuOpen, slotId, 0).wire)

  def reply(codec: CallbackCodec, event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val medications = tx.medications.listForAccount(principal.accountId.get.uuid)
    if medications.isEmpty then Reply(toast = Some(ReminderCopy.logEmpty))
    else
      Reply(followUps =
        List(
          OutboundMessage(
            body = List(Node.Paragraph(List(Inline.Text(ReminderCopy.logPickerPrompt)))),
            blocks = List(
              Block.Choices(
                ChoiceSet(
                  id = "log.picker",
                  choices = medications.map(medication =>
                    Choice.callback(
                      ControlLimits.fitLabel(MenuCopy.logDoseLabel(medication.name)),
                      codec
                        .encode(
                          CallbackMode.Direct,
                          dosegram.core.chat.ActionRegistry.byName("dose.log").get,
                          medication.id,
                          0
                        )
                        .wire
                    )
                  )
                )
              )
            ),
            dedupeKey = s"log:picker:${event.vendor}:${event.vendorEventId}",
            correlationId = s"${event.vendor}:${event.vendorEventId}"
          )
        )
      )
end LogDosePicker
