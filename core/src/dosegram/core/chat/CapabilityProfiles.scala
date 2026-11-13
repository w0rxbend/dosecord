package dosegram.core.chat

import dosegram.contracts.CapabilityProfile
import dosegram.contracts.DeleteCapability
import dosegram.contracts.DmInitiation
import dosegram.contracts.EditCapability
import dosegram.contracts.Markup

import java.time.Duration

/** The static capability profiles of DESIGN.md section 4.3. A profile is a declaration of what the vendor supports; the
  * conformance suite (M0.13) checks adapters stay honest about it.
  */
object CapabilityProfiles:

  val Telegram: CapabilityProfile = CapabilityProfile(
    nativeCommands = true,
    buttons = true,
    select = false,
    modal = false,
    ephemeral = false,
    editOwn = EditCapability.AnyAge,
    deleteOwn = DeleteCapability.Window(Duration.ofHours(48)),
    botReactions = true,
    reactionEvents = true,
    transientAck = true,
    deferrable = false,
    ackDeadline = Some(Duration.ofSeconds(2)),
    polls = false,
    silentDelivery = true,
    canInitiateDm = DmInitiation.AfterUserStart,
    maxText = 4096,
    maxChoicesPerRow = 4,
    maxRows = 12,
    callbackBudgetBytes = Some(64),
    eventsPerMessageBudget = 10,
    markup = Markup.TelegramHtml
  )

  val Console: CapabilityProfile = CapabilityProfile(
    nativeCommands = false,
    buttons = false,
    select = false,
    modal = false,
    ephemeral = false,
    editOwn = EditCapability.NoEdit,
    deleteOwn = DeleteCapability.NoDelete,
    botReactions = false,
    reactionEvents = false,
    transientAck = false,
    deferrable = false,
    ackDeadline = None,
    polls = false,
    silentDelivery = false,
    canInitiateDm = DmInitiation.Always,
    maxText = 4000,
    maxChoicesPerRow = 0,
    maxRows = 0,
    callbackBudgetBytes = None,
    eventsPerMessageBudget = 1,
    markup = Markup.Plain
  )

  /** Suite B1 runs every profile; the name is the golden directory. */
  val all: List[(String, CapabilityProfile)] = List(
    "telegram" -> Telegram,
    "console" -> Console
  )
