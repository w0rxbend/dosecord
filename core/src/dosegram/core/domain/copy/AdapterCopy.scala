package dosegram.core.domain.copy

/** Adapter-level copy (M2.1): the refusal an adapter answers with when an interaction arrives from a context the
  * registration was supposed to make unreachable (ROADMAP M2.1: defence in depth, R45/R47). The reply is ephemeral —
  * visible only to the sender (owner directive, docs/spikes/jda-dm.md "Inputs for M2").
  */
object AdapterCopy:

  /** A guild- or group-DM-context interaction: registration already makes these unreachable; this is the last-resort
    * notice shown only to the invoker.
    */
  val dmOnlyRedirect = "Dosegram works in direct messages only — please open Dosegram's DM."

  /** The one fixed reply to a sender who is not on the allowlist (ROADMAP G1.6): nothing is stored for them. */
  val notAvailable = "Dosegram is not open to new users yet."

  val entries: List[CopyEntry] = List(
    CopyEntry("adapter.dm_only_redirect", dmOnlyRedirect),
    CopyEntry("adapter.not_available", notAvailable)
  )
end AdapterCopy
