package dosegram.core.ports

import java.time.Instant
import java.util.UUID
import java.util.random.RandomGenerator

/** Identifiers for new rows of the tracking tables (ARCHITECTURE section 10.1): time-ordered, generated in the
  * application from the instant the caller took from its injected clock.
  */
trait IdSource:
  def next(at: Instant): UUID

object IdSource:

  /** UUID version 7 (RFC 9562): 48 bits of Unix milliseconds, the version and variant bits, 74 random bits. The source
    * is shared by parallel events, so `random` must be thread-safe (`SecureRandom` is).
    */
  def uuidV7(random: RandomGenerator): IdSource = at =>
    val timeAndVersion = (at.toEpochMilli << 16) | 0x7000L | (random.nextLong() & 0x0fffL)
    val variantAndRandom = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L
    UUID(timeAndVersion, variantAndRandom)
