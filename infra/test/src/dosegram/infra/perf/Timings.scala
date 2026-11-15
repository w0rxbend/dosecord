package dosegram.infra.perf

/** Wall-clock samples of one probe of docs/PERFORMANCE.md, in milliseconds, and their summary. A probe is a tool run
  * by hand (`runMain`), not a test: its numbers depend on the host, so nothing asserts on them.
  */
final case class Timings(label: String, millis: Vector[Double]):
  private lazy val sorted = millis.sorted

  /** The nearest-rank percentile `p` (0 to 100) of the samples. */
  def percentile(p: Double): Double =
    if sorted.isEmpty then 0.0
    else sorted((math.ceil(p / 100.0 * sorted.size).toInt - 1).max(0).min(sorted.size - 1))

  def p50: Double = percentile(50)
  def p95: Double = percentile(95)
  def max: Double = sorted.lastOption.getOrElse(0.0)

  /** One row of the tables of docs/PERFORMANCE.md. */
  def row: String = f"| $label | ${millis.size} | $p50%.1f | $p95%.1f | $max%.1f |"

object Timings:

  val Header: String = "| Probe | Samples | p50 ms | p95 ms | max ms |\n|---|---|---|---|---|"

  /** Runs `f` `warmUp` times unmeasured, then `samples` times measured. */
  def of(label: String, warmUp: Int, samples: Int)(f: => Unit): Timings =
    (1 to warmUp).foreach(_ => f)
    Timings(label, Vector.fill(samples)(time(f)))

  def time(f: => Unit): Double =
    val start = System.nanoTime()
    f
    (System.nanoTime() - start) / 1e6
