package dosegram.api.auth

import dosegram.api.ApiFailure
import dosegram.contracts.PlatformIdentity
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.AuditEntry
import dosegram.core.ports.AuditEvent
import dosegram.core.ports.AuditEventType
import dosegram.core.ports.Clock
import dosegram.core.ports.UnitOfWork

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import scala.collection.mutable

/** One Mini App session as stored: the SHA-256 of its token, never the token (ARCHITECTURE API-05). */
final case class Session(
    tokenHash: String,
    accountId: UUID,
    initDataDigest: String,
    createdAt: Instant,
    lastUsedAt: Instant
)

/** Session lifetimes of ARCHITECTURE API-06. */
final case class SessionPolicy(idle: Duration, absolute: Duration, maxPerAccount: Int):

  def expiresAt(session: Session): Instant =
    val idleEnd = session.lastUsedAt.plus(idle)
    val absoluteEnd = session.createdAt.plus(absolute)
    if idleEnd.isBefore(absoluteEnd) then idleEnd else absoluteEnd

  def isLive(session: Session, now: Instant): Boolean = now.isBefore(expiresAt(session))

object SessionPolicy:
  val Default: SessionPolicy = SessionPolicy(Duration.ofMinutes(15), Duration.ofHours(12), maxPerAccount = 5)

/** Where sessions live: the table `miniapp_sessions` in production, [[InMemorySessionStore]] in a suite. The
  * composition root implements the port over the table, so this module never sees the storage (DEP-05).
  */
trait SessionStore:
  def insert(session: Session): Unit
  def find(tokenHash: String): Option[Session]
  def touch(tokenHash: String, at: Instant): Unit
  def delete(tokenHash: String): Unit
  def listForAccount(accountId: UUID): List[Session]

  /** Removes every session created from this launch value and returns how many there were (API-07). */
  def deleteByInitData(digest: String): Int

  /** Removes the sessions that are over and returns how many there were (the deletion job of section 15.6): one last
    * used at or before `idleBefore`, or created at or before `createdBefore`.
    */
  def deleteEnded(idleBefore: Instant, createdBefore: Instant): Int

/** Sessions of one process. They do not survive a restart: the client then meets "This session has ended" and
  * relaunches (MINI_APP gate G-4).
  */
final class InMemorySessionStore extends SessionStore:
  private val sessions = mutable.Map.empty[String, Session]

  override def insert(session: Session): Unit = synchronized(sessions.update(session.tokenHash, session))
  override def find(tokenHash: String): Option[Session] = synchronized(sessions.get(tokenHash))
  override def touch(tokenHash: String, at: Instant): Unit = synchronized:
    sessions.updateWith(tokenHash)(_.map(_.copy(lastUsedAt = at)))
    ()
  override def delete(tokenHash: String): Unit = synchronized:
    sessions.remove(tokenHash)
    ()
  override def listForAccount(accountId: UUID): List[Session] =
    synchronized(sessions.values.filter(_.accountId == accountId).toList)
  override def deleteByInitData(digest: String): Int = deleteWhere(_.initDataDigest == digest)
  override def deleteEnded(idleBefore: Instant, createdBefore: Instant): Int =
    deleteWhere(session => !session.lastUsedAt.isAfter(idleBefore) || !session.createdAt.isAfter(createdBefore))
  private def deleteWhere(dead: Session => Boolean): Int = synchronized:
    val doomed = sessions.values.filter(dead).map(_.tokenHash).toList
    doomed.foreach(sessions.remove)
    doomed.size

/** A fixed-window attempt counter per key (API-18: `POST /session` 6 per minute per Telegram user). In memory, so it is
  * valid while one process serves the API.
  */
final class AttemptLimiter(limit: Int, window: Duration):
  private final case class Window(startedAt: Instant, attempts: Int)
  private val windows = mutable.Map.empty[String, Window]

  /** Counts one attempt; `Some(wait)` when the key has spent its window. */
  def attempt(key: String, now: Instant): Option[Duration] = synchronized:
    if windows.size > AttemptLimiter.PruneAbove then windows.filterInPlace((_, w) => isOpen(w, now))
    val current = windows.get(key).filter(isOpen(_, now)).getOrElse(Window(now, 0))
    if current.attempts >= limit then Some(Duration.between(now, current.startedAt.plus(window)))
    else
      windows.update(key, current.copy(attempts = current.attempts + 1))
      None

  private def isOpen(w: Window, now: Instant): Boolean = now.isBefore(w.startedAt.plus(window))

object AttemptLimiter:
  private val PruneAbove = 10000

/** A new session: the token in the clear, exactly once. */
final case class IssuedSession(token: String, session: Session, expiresAt: Instant, timeZone: String)

/** The session exchange and the bearer check of ARCHITECTURE section 9.2 (decision D6). */
final class SessionService(
    verifier: InitDataVerifier,
    uow: UnitOfWork,
    store: SessionStore,
    clock: Clock,
    policy: SessionPolicy = SessionPolicy.Default,
    exchangeLimiter: AttemptLimiter = AttemptLimiter(limit = 6, window = Duration.ofMinutes(1))
):
  import SessionService.*

  private val random = SecureRandom()

  /** Verifies the launch value, finds the account that the chat created, and issues a session. Presenting the same
    * value again inside its freshness window issues a new session and ends the earlier one (API-07).
    */
  def exchange(rawInitData: String): Either[ApiFailure, IssuedSession] =
    val now = clock.now()
    for
      verified <- verifier.verify(rawInitData, now).left.map(rejected)
      _ <- exchangeLimiter.attempt(verified.telegramUserId.toString, now).map(tooMany).toLeft(())
      account <- accountOf(verified.telegramUserId).toRight(ApiFailure.accountRequired())
    yield issue(account, verified, now)

  /** The session behind a bearer token; an unknown token and an expired session are both 401. */
  def authenticate(bearer: String): Either[ApiFailure, Session] =
    val now = clock.now()
    val tokenHash = sha256Hex(bearer)
    store.find(tokenHash) match
      case None                                          => Left(ApiFailure.unauthenticated())
      case Some(session) if !policy.isLive(session, now) =>
        store.delete(tokenHash)
        Left(ApiFailure.sessionExpired())
      // API-06: the idle timer is written at most once per minute, so a screen that loads does not write per request.
      case Some(session) if now.isBefore(session.lastUsedAt.plus(SessionService.TouchInterval)) => Right(session)
      case Some(session)                                                                        =>
        store.touch(tokenHash, now)
        Right(session.copy(lastUsedAt = now))

  def end(session: Session): Unit =
    store.delete(session.tokenHash)
    audited(AuditEventType.SessionRevoked, session.accountId, "revoke")

  def idleTimeout: Duration = policy.idle

  /** Deletes expired sessions; the caller runs it on a period. */
  def sweep(): Int =
    val now = clock.now()
    store.deleteEnded(idleBefore = now.minus(policy.idle), createdBefore = now.minus(policy.absolute))

  private def accountOf(telegramUserId: Long): Option[Account] =
    uow.transaction: tx =>
      for
        principal <- tx.identities.find(PlatformIdentity(Vendor, telegramUserId.toString))
        accountId <- principal.accountId
      yield Account(accountId.uuid, tx.accounts.timezoneOf(accountId.uuid).getOrElse("UTC"))

  private def issue(account: Account, verified: VerifiedInitData, now: Instant): IssuedSession =
    if store.deleteByInitData(verified.digest) > 0 then recordReuse(account.id)
    val bytes = new Array[Byte](TokenBytes)
    random.nextBytes(bytes)
    val token = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val session = Session(sha256Hex(token), account.id, verified.digest, createdAt = now, lastUsedAt = now)
    store.insert(session)
    audited(AuditEventType.SessionCreated, account.id, "login")
    evictBeyondLimit(account.id)
    IssuedSession(token, session, policy.expiresAt(session), account.timeZone)

  private def evictBeyondLimit(accountId: UUID): Unit =
    store
      .listForAccount(accountId)
      .sortBy(_.createdAt)
      .dropRight(policy.maxPerAccount)
      .foreach(oldest => store.delete(oldest.tokenHash))

  private def recordReuse(accountId: UUID): Unit =
    uow.transaction(_.audit.append(AuditEntry("initdata.reused", "session_replaced", Vendor, userId = Some(accountId))))

  // The session store is not on the transaction, so the row follows the session write it records.
  private def audited(eventType: AuditEventType, accountId: UUID, action: String): Unit =
    uow.transaction(
      _.auditLog.record(AuditEvent.byUser(eventType, accountId, "session", None, action, AuditChannel.MiniApp))
    )

  private def tooMany(wait: Duration): ApiFailure = ApiFailure.rateLimited(math.max(1L, wait.toSeconds))

object SessionService:
  private val Vendor = "telegram"
  private val TokenBytes = 32
  private val TouchInterval = Duration.ofMinutes(1)

  private final case class Account(id: UUID, timeZone: String)

  private def rejected(rejection: InitDataRejection): ApiFailure = rejection match
    case InitDataRejection.MissingSignature => ApiFailure.clientTooOld()
    case InitDataRejection.TooLarge | InitDataRejection.Malformed | InitDataRejection.DuplicateKey |
        InitDataRejection.BadSignature | InitDataRejection.Stale | InitDataRejection.FromFuture |
        InitDataRejection.MissingUser =>
      ApiFailure.unauthenticated()

  def sha256Hex(value: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))
