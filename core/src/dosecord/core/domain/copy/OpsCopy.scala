package dosecord.core.domain.copy

/** Ops-alert copy (ROADMAP M1.4a recorded decision: every ops alert is prefixed `Ops:`). Alerts are DMed to the owner
  * silently, rate-limited to one per condition per hour (ROADMAP M2.4); the conditions are the DESIGN.md section 10
  * alert set.
  */
object OpsCopy:

  val Prefix = "Ops:"

  private def alert(text: String): String = s"$Prefix $text"

  def tickAge(ageSeconds: Long): String =
    alert(s"reminder tick is ${ageSeconds}s old (threshold 60s).")

  def outboxDead(count: Long): String =
    alert(s"$count outbox message(s) are dead.")

  def materializedLag(lagSeconds: Long): String =
    alert(s"occurrence materialisation is ${lagSeconds}s behind.")

  def quarantined(count: Long): String =
    alert(s"$count occurrence(s) are quarantined after repeated errors.")

  def adapterDisconnected(vendor: String, minutes: Long): String =
    alert(s"the $vendor adapter has been disconnected for ${minutes}m.")

  val entries: List[CopyEntry] = List(
    CopyEntry("ops.tick_age", tickAge(90)),
    CopyEntry("ops.outbox_dead", outboxDead(2)),
    CopyEntry("ops.materialized_lag", materializedLag(300)),
    CopyEntry("ops.quarantined", quarantined(1)),
    CopyEntry("ops.adapter_disconnected", adapterDisconnected("discord", 7))
  )
end OpsCopy
