package dosegram.infra.db

import dosegram.infra.crypto.DataKeys

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.language.implicitConversions

/** The outcome of a creating request as `idempotency_keys` holds it; without `outcome` the request is still in flight.
  */
final case class IdempotencyRow(outcome: Option[IdempotencyOutcome], createdAt: Instant)

/** The hash of the request and the response that answered it. */
final case class IdempotencyOutcome(requestHash: String, responseJson: String)

/** `idempotency_keys` (ARCHITECTURE API-16). A key is reserved before its request runs, so of two simultaneous requests
  * with one key only one runs; the other finds the reservation and, later, the outcome. The stored response holds what
  * the request returned, names included, so it is sealed with the account's data key (V10).
  */
final class PgIdempotencyKeys(dataSource: DataSource, keys: DataKeys):
  import SealedColumns.IdempotencyResponse

  private val pooled = Pooled(dataSource)

  /** Takes the key for a request that starts now. `false` when the key is already there, in flight or answered. */
  def reserve(accountId: UUID, key: UUID, now: Instant): Boolean =
    pooled:
      sql"""INSERT INTO idempotency_keys (account_id, key, created_at) VALUES ($accountId, $key, $now)
            ON CONFLICT (account_id, key) DO NOTHING""".execute() == 1

  /** Takes over a reservation made before `staleBefore`: its request never finished. `false` when there is none. */
  def takeOver(accountId: UUID, key: UUID, staleBefore: Instant, now: Instant): Boolean =
    pooled:
      sql"""UPDATE idempotency_keys SET created_at = $now
            WHERE account_id = $accountId AND key = $key AND request_hash IS NULL AND created_at < $staleBefore"""
        .execute() == 1

  def complete(accountId: UUID, key: UUID, outcome: IdempotencyOutcome, now: Instant): Unit =
    pooled: conn ?=>
      val response = keys.on(conn).sealText(IdempotencyResponse.forAccount(accountId, key), outcome.responseJson)
      sql"""UPDATE idempotency_keys
            SET request_hash = ${outcome.requestHash}, response_enc = $response, created_at = $now
            WHERE account_id = $accountId AND key = $key AND request_hash IS NULL""".execute()
    ()

  /** Frees a reserved key whose request stored nothing; an answered key stays. */
  def release(accountId: UUID, key: UUID): Unit =
    pooled:
      sql"""DELETE FROM idempotency_keys
            WHERE account_id = $accountId AND key = $key AND request_hash IS NULL""".execute()
    ()

  def find(accountId: UUID, key: UUID): Option[IdempotencyRow] =
    pooled: conn ?=>
      given RowMapper[IdempotencyRow] = rs =>
        val outcome =
          for
            hash <- rs.optString("request_hash")
            response <- Option(rs.getBytes("response_enc"))
          yield IdempotencyOutcome(
            hash,
            keys.on(conn).openText(IdempotencyResponse.forAccount(accountId, key), response)
          )
        IdempotencyRow(outcome, rs.instant("created_at"))
      sql"SELECT request_hash, response_enc, created_at FROM idempotency_keys WHERE account_id = $accountId AND key = $key"
        .queryOne[IdempotencyRow]()

  /** The deleting job: removes the rows created before `cutoff` (24 hours, API-16). */
  def deleteCreatedBefore(cutoff: Instant): Int =
    pooled(sql"DELETE FROM idempotency_keys WHERE created_at < $cutoff".execute())
