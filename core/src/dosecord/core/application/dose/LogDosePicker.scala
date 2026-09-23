package dosecord.core.application.dose

import dosecord.contracts.*
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackMode
import dosecord.core.domain.copy.MenuCopy
import dosecord.core.domain.copy.ReminderCopy
import dosecord.core.ports.Tx

/** The manual-logging picker shared by Medications -> Log dose (M1.10) and a bare `/log`: one [Log <name>] control per
  * active medication, each a direct `dose.log` token on the medication id (medication ids never expire, so the picker
  * keeps working on an old message). The tap itself records through [[DoseIntakeHandler]].
  */
private[application] object LogDosePicker:

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
                    Choice(
                      MenuCopy.logDoseLabel(medication.name),
                      codec
                        .encode(
                          CallbackMode.Direct,
                          dosecord.core.chat.ActionRegistry.byName("dose.log").get,
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
