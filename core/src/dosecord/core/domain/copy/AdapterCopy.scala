package dosecord.core.domain.copy

/** Adapter-level copy (M2.1): the refusal an adapter answers with when an interaction arrives from a context the
  * registration was supposed to make unreachable (ROADMAP M2.1: defence in depth, R45/R47). The reply is ephemeral —
  * visible only to the sender (owner directive, docs/spikes/jda-dm.md "Inputs for M2").
  */
object AdapterCopy:

  /** A guild- or group-DM-context interaction: registration already makes these unreachable; this is the last-resort
    * notice shown only to the invoker.
    */
  val dmOnlyRedirect = "Dosecord works in direct messages only — please open Dosecord's DM."

  val entries: List[CopyEntry] = List(
    CopyEntry("adapter.dm_only_redirect", dmOnlyRedirect)
  )
end AdapterCopy
