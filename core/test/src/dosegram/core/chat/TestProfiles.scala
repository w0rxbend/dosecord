package dosegram.core.chat

import dosegram.contracts.CapabilityProfile
import dosegram.contracts.DeleteCapability
import dosegram.contracts.EditCapability

import java.time.Duration

/** Capability profiles no shipped vendor declares. They keep the renderer, mediator and wizard rungs that outlived
  * their vendor under test (native select, modal, ephemeral, deferred ack, the reaction tier) until roadmap slice G0.6
  * retires those rungs, and these fixtures with them.
  */
object TestProfiles:

  /** Every native control: select, modal, ephemeral replies and a deferrable ack, on a 5 x 5 component grid. */
  val Native: CapabilityProfile = CapabilityProfiles.Telegram.copy(
    select = true,
    modal = true,
    ephemeral = true,
    deleteOwn = DeleteCapability.AnyAge,
    deferrable = true,
    ackDeadline = Some(Duration.ofSeconds(3)),
    polls = true,
    maxText = 2000,
    maxChoicesPerRow = 5,
    maxRows = 5,
    callbackBudgetBytes = Some(100)
  )

  /** The reaction tier: no native controls, numbered choices answered by reaction, a short edit window. */
  val Reactions: CapabilityProfile = CapabilityProfiles.Console.copy(
    editOwn = EditCapability.Window(Duration.ofMinutes(10)),
    botReactions = true,
    reactionEvents = true,
    maxText = 10000,
    eventsPerMessageBudget = 4
  )
