package dosegram.core.application.menu

import dosegram.contracts.Block
import dosegram.contracts.ChatRef
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.WizardDocument
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.ports.CallbackSlot
import dosegram.core.ports.Tx

import java.util.UUID

/** Menu screens as buttons outside the menu: slots that the menu handler opens as its own buttons do. */
final class MenuButtons(codec: CallbackCodec):

  /** The end of onboarding with medication (BOT_UX step R): "Add medication" and "Today", one tap to the first action
    * instead of a pointer to /menu (walk-through of 2026-11-27).
    */
  def firstMedication(tx: Tx, chat: ChatRef, accountId: UUID): Block =
    Block.Choices(
      ChoiceSet(
        id = "onboarding.ready",
        choices = List(
          Choice.callback(
            WizardCopy.addMedicationTitle,
            slot(tx, chat, accountId, MenuHandler.KeyAdd),
            ChoiceStyle.Primary
          ),
          Choice.callback(MenuCopy.topLevel.head, slot(tx, chat, accountId, MenuHandler.KeyToday))
        )
      )
    )

  private def slot(tx: Tx, chat: ChatRef, accountId: UUID, key: String): String =
    val slotId = UUID.randomUUID()
    tx.slots.insert(
      CallbackSlot(
        id = slotId,
        accountId = Some(accountId),
        chat = WizardDocument.chatToJson(chat),
        payload = WizardDocument.slotPayloadToJson(key),
        sessionId = None,
        stepSeq = None,
        expiresAt = None
      )
    )
    codec.encode(CallbackMode.Slot, MenuHandler.MenuOpenAction, slotId, 0).wire
