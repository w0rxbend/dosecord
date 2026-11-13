package dosegram.app

import dosegram.api.IdempotencyRecord
import dosegram.api.IdempotencyStore
import dosegram.core.ports.Clock
import dosegram.infra.db.IdempotencyOutcome
import dosegram.infra.db.PgIdempotencyKeys

import java.time.Duration
import java.util.UUID
import scala.annotation.tailrec

/** The idempotency store over `idempotency_keys` (ARCHITECTURE API-16): a retry after a restart is answered with the
  * stored response and not executed again.
  *
  * The key is reserved in a statement of its own before `create` runs, and no connection is held while it runs. A
  * second request with the same key waits for the outcome of the first. A reservation older than [[TakeOverAfter]]
  * belongs to a request that never finished (the process died in it), and the next request takes it over.
  */
final class PgIdempotencyStore(keys: PgIdempotencyKeys, clock: Clock) extends IdempotencyStore:
  import PgIdempotencyStore.*

  override def getOrCreate(
      accountId: UUID,
      key: UUID,
      create: () => Option[IdempotencyRecord]
  ): Option[IdempotencyRecord] =
    outcomeOf(Request(accountId, key, create), waitsLeft = MaxWaits)

  @tailrec
  private def outcomeOf(request: Request, waitsLeft: Int): Option[IdempotencyRecord] =
    import request.*
    val now = clock.now()
    if keys.reserve(accountId, key, now) || keys.takeOver(accountId, key, now.minus(TakeOverAfter), now) then
      run(request)
    else
      keys.find(accountId, key).map(row => (row.outcome, row.createdAt)) match
        case Some((Some(outcome), storedAt)) =>
          Some(IdempotencyRecord(outcome.requestHash, outcome.responseJson, storedAt))
        // In flight elsewhere, or released between the two statements: look again.
        case Some((None, _)) | None if waitsLeft > 0 =>
          Thread.sleep(WaitBetweenLooks.toMillis)
          outcomeOf(request, waitsLeft - 1)
        case Some((None, _)) | None =>
          throw IllegalStateException("a request with this idempotency key is still in flight")

  /** Runs the request that holds the reservation. A request that stores nothing, by refusal or by failure, frees it. */
  private def run(request: Request): Option[IdempotencyRecord] =
    import request.*
    val record =
      try create()
      catch
        case failure: Throwable =>
          keys.release(accountId, key)
          throw failure
    record match
      case Some(stored) =>
        keys.complete(accountId, key, IdempotencyOutcome(stored.requestHash, stored.responseJson), stored.storedAt)
      case None => keys.release(accountId, key)
    record

  override def deleteStoredBefore(cutoff: java.time.Instant): Int = keys.deleteCreatedBefore(cutoff)

object PgIdempotencyStore:

  /** A creating request answers within seconds; a reservation this old has no request behind it. */
  val TakeOverAfter: Duration = Duration.ofSeconds(60)

  private val WaitBetweenLooks = Duration.ofMillis(50)

  /** Ten seconds of waiting for a request in flight, then the caller gets the `internal` problem and may retry. */
  private val MaxWaits = 200

  private final case class Request(accountId: UUID, key: UUID, create: () => Option[IdempotencyRecord])
