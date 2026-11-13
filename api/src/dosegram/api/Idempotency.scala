package dosegram.api

import dosegram.core.ports.Clock

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The stored outcome of a creating request (ARCHITECTURE API-16): a hash of the request and the response body. */
final case class IdempotencyRecord(requestHash: String, responseJson: String, storedAt: Instant)

/** Where idempotency records live, per account and key. A table with a deletion job replaces
  * [[InMemoryIdempotencyStore]] once the schema has one (ROADMAP G3.5).
  */
trait IdempotencyStore:

  /** The record under the key, or the one `create` returns, stored before any other caller of the same key sees it.
    * `create` runs at most once per key; when it returns `None` nothing is stored and the key stays free.
    */
  def getOrCreate(accountId: UUID, key: UUID, create: () => Option[IdempotencyRecord]): Option[IdempotencyRecord]

  /** Removes the records stored before `cutoff` and returns how many there were. */
  def deleteStoredBefore(cutoff: Instant): Int

/** Records of one process: a retry after a restart is executed again. */
final class InMemoryIdempotencyStore extends IdempotencyStore:
  private val records = ConcurrentHashMap[(UUID, UUID), IdempotencyRecord]()

  override def getOrCreate(
      accountId: UUID,
      key: UUID,
      create: () => Option[IdempotencyRecord]
  ): Option[IdempotencyRecord] =
    // computeIfAbsent holds the key's bin while `create` runs, so a concurrent retry waits for the first outcome.
    Option(records.computeIfAbsent((accountId, key), _ => create().orNull))

  override def deleteStoredBefore(cutoff: Instant): Int =
    val before = records.size
    records.values.removeIf(_.storedAt.isBefore(cutoff))
    before - records.size

/** Runs a creating request once per `Idempotency-Key` (API-16): a repeat with the same body answers with the stored
  * response, a repeat with another body is refused. Only a success is stored; a refused request may be sent again.
  */
final class Idempotency(store: IdempotencyStore, clock: Clock):
  import ApiJson.ReadWriter

  def once[R: ReadWriter](accountId: UUID, key: UUID, requestHash: String)(
      run: => Either[ApiFailure, R]
  ): Either[ApiFailure, R] =
    var refusal = Option.empty[ApiFailure]
    val record = store.getOrCreate(
      accountId,
      key,
      () =>
        run match
          case Right(response) => Some(IdempotencyRecord(requestHash, ApiJson.write(response), clock.now()))
          case Left(failure)   => { refusal = Some(failure); None }
    )
    record match
      case Some(stored) if stored.requestHash == requestHash => Right(ApiJson.read[R](stored.responseJson))
      case Some(_)                                           => Left(ApiFailure.idempotencyKeyReuse())
      case None                                              =>
        Left(refusal.getOrElse(throw IllegalStateException("idempotent run stored nothing and refused nothing")))

  /** Deletes records older than the retention of API-16. */
  def sweep(): Int = store.deleteStoredBefore(clock.now().minus(Idempotency.Retention))

object Idempotency:
  val Retention: Duration = Duration.ofHours(24)
