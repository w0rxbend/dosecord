package dosecord.core.chat

import dosecord.contracts.CapabilityProfile
import dosecord.contracts.CommandSpec
import dosecord.contracts.InteractionHandle
import dosecord.contracts.Visibility

/** The acknowledgement policy scaffold of ADR-013: ack before any database work and outside the per-chat executor,
  * driven by the static declarations of the action registry and the command registry. Vendor adapters wire their
  * deadline watchdogs later (M2.1); the mediator applies the declared ack here.
  */
object AckPolicy:

  enum Ack:
    case NoAck
    case DeferUpdate
    case DeferReply(ephemeral: Boolean)
    case Answer(toast: Option[String])

  /** Component actions: `opensForm` entries are never deferred (the modal is the response); deferrable vendors defer
    * with an update; single-shot vendors (Telegram: `transientAck`, not `deferrable`) answer with no toast.
    */
  def forComponent(entry: ActionEntry, profile: CapabilityProfile): Ack =
    if entry.opensForm then Ack.NoAck
    else if profile.deferrable then Ack.DeferUpdate
    else if profile.transientAck then Ack.Answer(None)
    else Ack.NoAck

  /** Commands defer a reply with the visibility declared on the CommandSpec (Discord fixes the ephemeral flag at defer
    * time); single-shot vendors answer instead.
    */
  def forCommand(spec: CommandSpec, profile: CapabilityProfile): Ack =
    if profile.deferrable then Ack.DeferReply(spec.visibility == Visibility.Ephemeral)
    else if profile.transientAck then Ack.Answer(None)
    else Ack.NoAck

  def apply(ack: Ack, interaction: InteractionHandle): Unit =
    ack match
      case Ack.NoAck                 => ()
      case Ack.DeferUpdate           => interaction.deferUpdate()
      case Ack.DeferReply(ephemeral) => interaction.deferReply(ephemeral)
      case Ack.Answer(toast)         => interaction.answer(toast)
