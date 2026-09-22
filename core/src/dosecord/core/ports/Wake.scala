package dosecord.core.ports

import java.time.Duration

/** The reminder loop's wake-up (DESIGN.md section 7.4, ADR-003): LISTEN `dosecord_wake` shortens latency, the timeout
  * is the fallback poll. `awaitOrTimeout(Duration.ZERO)` returns immediately (a full batch means more rows are due).
  * Implementations may assume a single blocking caller at a time.
  */
trait Wake:
  def awaitOrTimeout(timeout: Duration): Unit

object Wake:

  /** No signal source: always waits out the timeout. The fallback poll, and the test double. */
  val polling: Wake = timeout => if !timeout.isZero && !timeout.isNegative then Thread.sleep(timeout.toMillis)
