package dosegram.core.application.reminders

import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPresets

import java.util.UUID

/** The offer of water prompts right after the water consent (ADR-023). It is a forced choice with nothing preselected
  * (ROADMAP G5.4): one button per water preset, each opening its preview, and "Not now".
  */
object WaterOffer:

  def message(codec: CallbackCodec, accountId: UUID, key: String): OutboundMessage =
    def token(action: String, value: Long): String =
      val entry = ActionRegistry.byName(action).getOrElse(throw IllegalStateException(s"action '$action' is missing"))
      codec.encode(CallbackMode.Direct, entry, accountId, value).wire
    val presets = PromptPresets
      .forKind(PromptKind.Water)
      .map: preset =>
        List(
          Choice.callback(PromptCopy.presetName(preset.id), token("reminders.preview", PromptPresets.indexOf(preset)))
        )
    val notNow = List(Choice.callback(PromptCopy.NotNow, token("reminders.off", PromptKind.Water.ordinal)))
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(PromptCopy.offerWater)))),
      blocks =
        (presets :+ notNow).zipWithIndex.map((row, i) => Block.Choices(ChoiceSet(s"water.offer.$i", choices = row))),
      dedupeKey = s"water.offer:$key",
      correlationId = key
    )
