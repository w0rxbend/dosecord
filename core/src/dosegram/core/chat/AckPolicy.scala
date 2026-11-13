package dosegram.core.chat

import dosegram.contracts.CapabilityProfile
import dosegram.contracts.InteractionHandle

/** The acknowledgement policy of ADR-013: ack before any database work and outside the per-chat executor. */
object AckPolicy:

  /** A single-shot vendor (`transientAck`) is answered with no toast; the answer is spent at most once. */
  def ackBeforeWork(profile: CapabilityProfile, interaction: InteractionHandle): Unit =
    if profile.transientAck && !interaction.acked then interaction.answer(None)
