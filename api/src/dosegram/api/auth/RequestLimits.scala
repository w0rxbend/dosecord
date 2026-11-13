package dosegram.api.auth

import dosegram.api.ApiFailure
import sttp.model.Method

import java.time.Duration
import java.time.Instant

/** The request limits of ARCHITECTURE API-18 after authentication: reads are counted per session, writes per account,
  * so a second session cannot double an account's write budget. A `GET` or `HEAD` is a read; every other method is a
  * write. The counters are in memory, valid while one process serves the API (API-18).
  */
final class RequestLimits(reads: AttemptLimiter, writes: AttemptLimiter):

  /** Counts the request; `Left` with the 429 problem when its window is already spent. */
  def admit(session: Session, method: Method, now: Instant): Either[ApiFailure, Unit] =
    val spent =
      if RequestLimits.isRead(method) then reads.attempt(session.tokenHash, now)
      else writes.attempt(session.accountId.toString, now)
    spent.map(wait => ApiFailure.rateLimited(math.max(1L, wait.toSeconds))).toLeft(())

object RequestLimits:
  private val Window = Duration.ofMinutes(1)

  /** 120 reads per minute per session, 60 writes per minute per account (research brief 06 section 12.3). */
  def default(): RequestLimits =
    RequestLimits(AttemptLimiter(limit = 120, window = Window), AttemptLimiter(limit = 60, window = Window))

  private def isRead(method: Method): Boolean = method == Method.GET || method == Method.HEAD
