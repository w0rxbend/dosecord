package dosecord.core.ports

import java.time.Instant

/** Time source. Every service and query takes `now` from here, never from SQL `now()` (ADR-011).
  */
trait Clock:
  def now(): Instant

object Clock:
  val system: Clock = () => Instant.now()
