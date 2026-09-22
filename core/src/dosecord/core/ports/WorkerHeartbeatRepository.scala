package dosecord.core.ports

import java.time.Instant

/** `worker_heartbeat` (DESIGN.md section 7.4): one row per process instance, written in its own short transaction after
  * each batch (never inside it, so a long batch cannot hold the row). The max over all instances is the loop's
  * `lastHealthyTick` — the `unknown(outage | undelivered)` evidence of DESIGN.md section 7.3.
  */
trait WorkerHeartbeatRepository:

  /** Upserts this instance's row. */
  def touch(instance: String, role: String, now: Instant): Unit

  /** The max `last_tick_at` over all workers, or None when no worker ever ticked. */
  def maxLastTick(): Option[Instant]
