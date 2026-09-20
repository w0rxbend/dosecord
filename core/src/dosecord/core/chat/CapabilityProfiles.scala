package dosecord.core.chat

import dosecord.contracts.CapabilityProfile
import dosecord.contracts.DeleteCapability
import dosecord.contracts.DmInitiation
import dosecord.contracts.EditCapability
import dosecord.contracts.Markup

import java.time.Duration

/** The five static capability profiles of DESIGN.md section 4.3. A profile is a declaration of what the vendor
  * supports; the conformance suite (M0.13) checks adapters stay honest about it.
  */
object CapabilityProfiles:

  val Discord: CapabilityProfile = CapabilityProfile(
    nativeCommands = true,
    buttons = true,
    select = true,
    modal = true,
    ephemeral = true,
    editOwn = EditCapability.AnyAge,
    deleteOwn = DeleteCapability.AnyAge,
    botReactions = true,
    reactionEvents = true,
    transientAck = true,
    deferrable = true,
    ackDeadline = Some(Duration.ofSeconds(3)),
    polls = true,
    silentDelivery = true,
    canInitiateDm = DmInitiation.SharedGuildOrInstall,
    maxText = 2000,
    maxChoicesPerRow = 5,
    maxRows = 5,
    callbackBudgetBytes = Some(100),
    eventsPerMessageBudget = 10,
    markup = Markup.DiscordMd
  )

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

  val Zulip: CapabilityProfile = CapabilityProfile(
    nativeCommands = false,
    buttons = false,
    select = false,
    modal = false,
    ephemeral = false,
    // Realm edit window probed at start (M6.2); 10 min is the conservative default.
    editOwn = EditCapability.Window(Duration.ofMinutes(10)),
    deleteOwn = DeleteCapability.NoDelete,
    botReactions = true,
    reactionEvents = true,
    transientAck = false,
    deferrable = false,
    ackDeadline = None,
    polls = false,
    silentDelivery = false,
    canInitiateDm = DmInitiation.Always,
    maxText = 10000,
    maxChoicesPerRow = 0,
    maxRows = 0,
    callbackBudgetBytes = None,
    eventsPerMessageBudget = 4,
    markup = Markup.ZulipMd
  )

  val Matrix: CapabilityProfile = CapabilityProfile(
    nativeCommands = false,
    buttons = false,
    select = false,
    modal = false,
    ephemeral = false,
    editOwn = EditCapability.AnyAge,
    deleteOwn = DeleteCapability.AnyAge,
    botReactions = true,
    reactionEvents = true,
    transientAck = false,
    deferrable = false,
    ackDeadline = None,
    polls = false,
    silentDelivery = false,
    canInitiateDm = DmInitiation.Invite,
    maxText = 10000,
    maxChoicesPerRow = 0,
    maxRows = 0,
    callbackBudgetBytes = None,
    eventsPerMessageBudget = 4,
    markup = Markup.MatrixHtml
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
    "discord" -> Discord,
    "telegram" -> Telegram,
    "zulip" -> Zulip,
    "matrix" -> Matrix,
    "console" -> Console
  )
