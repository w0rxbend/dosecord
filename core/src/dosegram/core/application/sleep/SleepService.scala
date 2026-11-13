package dosegram.core.application.sleep

import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.sleep.CheckinKind
import dosegram.core.domain.sleep.ClockNights
import dosegram.core.domain.sleep.Confidence
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepBounds
import dosegram.core.domain.sleep.SleepCheckin
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepGoalChange
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepInvalid
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepOrigin
import dosegram.core.domain.sleep.SleepRules
import dosegram.core.domain.sleep.SleepTier
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.Tx

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Who records, from which surface, and the idempotency key of the input: the same tap or request records at most once.
  */
final case class SleepInput(accountId: UUID, source: EntrySource, clientKey: String)

/** A night or a nap entered after the fact, as two instants. */
final case class EnterSleep(input: SleepInput, kind: SleepKind, onsetAt: Instant, wakeAt: Instant)

/** The answer to "When did you go to sleep?" after an "I'm up" that had no open night: the bedtime as a clock time. */
final case class CompleteNight(input: SleepInput, wakeCheckinId: UUID, bedtime: LocalTime)

/** "Adjust times": each edge moved by a signed amount; zero leaves the edge and its source as they are. */
final case class AdjustEpisode(input: SleepInput, episodeId: UUID, onsetShift: Duration, wakeShift: Duration)

final case class RateEpisode(input: SleepInput, episodeId: UUID, quality: Quality)

/** Why a sleep request changed nothing. No refusal depends on how long or how well the user slept. */
enum SleepRefusal:
  /** No active `sleep` consent: nothing is stored and nothing is shown (PRODUCT_SPEC R-IDC-9). */
  case ConsentMissing
  case UnknownAccount
  case UnknownEpisode
  case UnknownCheckin
  case UndoWindowPassed

  /** The two instants are not an episode (R-SLP-2). */
  case Invalid(reason: SleepInvalid)

  /** Another episode of the account shares time with this one (R-SLP-2). */
  case Overlaps(existing: SleepEpisode)

  /** The sleep day already has a night: the user replaces it knowingly, it is never overwritten. */
  case NightExists(existing: SleepEpisode)
  case GoalOutOfBounds

/** "Going to sleep" was recorded. `replacedOpen` says that an earlier open tap gave way to this one (R-SLP-4). */
final case class NightStarted(checkin: SleepCheckin, replacedOpen: Boolean, replayed: Boolean)

/** What "I'm up" led to. Without an open night nothing is guessed: the bedtime is asked (R-SLP-4). */
enum WokeUp:
  case Closed(episode: SleepEpisode, replayed: Boolean)
  case NeedsBedtime(checkin: SleepCheckin)

final case class SleepRecorded(episode: SleepEpisode, replayed: Boolean)

/** What the `/sleep` card shows: the open night if there is one, the latest night and the user's own goal. */
final case class SleepStatus(
    zone: ZoneId,
    open: Option[SleepCheckin],
    latestNight: Option[SleepEpisode],
    goalMinutes: Option[Int]
)

/** The sleep use cases (PRODUCT_SPEC section 8.11, tier 2; ROADMAP G7.1). Sleep is what the user says it was: two taps,
  * or times entered afterwards. Nothing here reads Telegram presence, and nothing infers an episode from the absence of
  * activity. Every write and every read needs the `sleep` consent and runs on the caller's transaction.
  */
final class SleepService(clock: Clock, ids: IdSource):

  /** "Going to sleep": opens a night with the onset as now. A second tap replaces the open onset (R-SLP-4). */
  def goingToSleep(tx: Tx, input: SleepInput): Either[SleepRefusal, NightStarted] =
    val accountId = input.accountId
    consented(tx, accountId): zone =>
      tx.tracking.sleep.checkinByClientKey(accountId, input.clientKey) match
        case Some(existing) => Right(NightStarted(existing, replacedOpen = false, replayed = true))
        case None           =>
          val now = clock.now()
          val replaced = openNight(tx, accountId, now)
            .exists(open => tx.tracking.sleep.voidCheckin(accountId, open.id, VoidReason.Superseded, now))
          val checkin = tap(input, CheckinKind.GoingToSleep, zone, now)
          tx.tracking.sleep.insertCheckin(checkin)
          Right(NightStarted(checkin, replaced, replayed = false))

  /** "I'm up": closes the open night with the wake as now, or records the tap and reports that the bedtime is missing.
    */
  def wakeUp(tx: Tx, input: SleepInput): Either[SleepRefusal, WokeUp] =
    val accountId = input.accountId
    consented(tx, accountId): zone =>
      tx.tracking.sleep.checkinByClientKey(accountId, input.clientKey) match
        case Some(existing) => Right(replayOf(tx, existing))
        case None           =>
          val now = clock.now()
          val wake = tap(input, CheckinKind.ImUp, zone, now)
          openNight(tx, accountId, now) match
            case None =>
              tx.tracking.sleep.insertCheckin(wake)
              Right(WokeUp.NeedsBedtime(wake))
            case Some(open) =>
              val onset = SleepEdge(open.occurredAt, open.zone, EdgeSource.CheckIn)
              val interval = SleepInterval(onset, SleepEdge(now, zone, EdgeSource.CheckIn))
              val draft = Draft(input, SleepKind.Main, interval, SleepOrigin.CheckIn, None)
              record(tx, draft, replaces = None).map: recorded =>
                tx.tracking.sleep.insertCheckin(wake)
                WokeUp.Closed(recorded.episode, recorded.replayed)

  /** The bedtime for an "I'm up" that had no open night: the latest occurrence of that clock time before the tap. */
  def completeNight(tx: Tx, command: CompleteNight): Either[SleepRefusal, SleepRecorded] =
    consented(tx, command.input.accountId): _ =>
      tx.tracking.sleep.checkin(command.input.accountId, command.wakeCheckinId).filter(isOpenWake) match
        case None       => Left(SleepRefusal.UnknownCheckin)
        case Some(wake) =>
          val onsetAt = ClockNights.latestAt(command.bedtime, wake.occurredAt.minusSeconds(1), wake.zone)
          val interval = SleepInterval(
            SleepEdge(onsetAt, wake.zone, EdgeSource.Retrospective),
            SleepEdge(wake.occurredAt, wake.zone, EdgeSource.CheckIn)
          )
          record(tx, Draft(command.input, SleepKind.Main, interval, SleepOrigin.Retrospective, None), replaces = None)

  /** A night or a nap entered after the fact (R-SLP-6, R-SLP-7). A sleep day that already has a night is refused with
    * that night, so the caller can offer to replace it.
    */
  def enter(tx: Tx, command: EnterSleep): Either[SleepRefusal, SleepRecorded] =
    consented(tx, command.input.accountId)(zone => record(tx, retrospective(command, zone), replaces = None))

  /** The same entry as a correction of `replacedId`: the old night is kept as a superseded row (R-SLP-6). */
  def replace(tx: Tx, command: EnterSleep, replacedId: UUID): Either[SleepRefusal, SleepRecorded] =
    consented(tx, command.input.accountId): zone =>
      live(tx, command.input.accountId, replacedId).flatMap: original =>
        record(tx, retrospective(command, zone).copy(quality = original.quality), replaces = Some(original))

  /** "Adjust times" and "Save": a moved edge becomes an entered time; the unmoved edge keeps its source. */
  def adjust(tx: Tx, command: AdjustEpisode): Either[SleepRefusal, SleepRecorded] =
    consented(tx, command.input.accountId): _ =>
      live(tx, command.input.accountId, command.episodeId).flatMap: original =>
        if command.onsetShift.isZero && command.wakeShift.isZero then Right(SleepRecorded(original, replayed = true))
        else
          val interval = SleepInterval(
            moved(original.interval.onset, command.onsetShift),
            moved(original.interval.wake, command.wakeShift)
          )
          val draft = Draft(command.input, original.kind, interval, SleepOrigin.Retrospective, original.quality)
          record(tx, draft, replaces = Some(original))

  /** The one-tap quality rating: the episode is replaced by a copy that carries the rating. */
  def rate(tx: Tx, command: RateEpisode): Either[SleepRefusal, SleepRecorded] =
    consented(tx, command.input.accountId): _ =>
      live(tx, command.input.accountId, command.episodeId).flatMap: original =>
        val draft = Draft(command.input, original.kind, original.interval, original.origin, Some(command.quality))
        record(tx, draft, replaces = Some(original))

  /** Undo on the night card: removes the open "Going to sleep"; `false` when it was already gone. */
  def undoCheckin(tx: Tx, accountId: UUID, checkinId: UUID): Either[SleepRefusal, Boolean] =
    consented(tx, accountId): _ =>
      tx.tracking.sleep.checkin(accountId, checkinId) match
        case None          => Left(SleepRefusal.UnknownCheckin)
        case Some(checkin) =>
          Right(checkin.isLive && tx.tracking.sleep.voidCheckin(accountId, checkinId, VoidReason.Undone, clock.now()))

  /** Undo on an outcome message: voids the episode while it is younger than the undo window. */
  def undoEpisode(tx: Tx, accountId: UUID, episodeId: UUID): Either[SleepRefusal, Boolean] =
    consented(tx, accountId): _ =>
      val now = clock.now()
      tx.tracking.sleep.episode(accountId, episodeId) match
        case None                             => Left(SleepRefusal.UnknownEpisode)
        case Some(episode) if !episode.isLive => Right(false)
        case Some(episode) if Duration.between(episode.createdAt, now).compareTo(SleepBounds.UndoWindow) > 0 =>
          Left(SleepRefusal.UndoWindowPassed)
        case Some(_) => Right(tx.tracking.sleep.voidEpisode(accountId, episodeId, VoidReason.Undone, now))

  def status(tx: Tx, accountId: UUID): Either[SleepRefusal, SleepStatus] =
    consented(tx, accountId): zone =>
      val now = clock.now()
      val latest = tx.tracking.sleep.latestLiveEpisode(accountId)
      Right(SleepStatus(zone, openNight(tx, accountId, now), latest, goalOn(tx, accountId, now, zone)))

  /** The user's own goal from today on; `None` removes it. No reference duration exists (R-SLP-19). */
  def setGoal(tx: Tx, accountId: UUID, targetMinutes: Option[Int]): Either[SleepRefusal, Option[Int]] =
    consented(tx, accountId): zone =>
      if targetMinutes.exists(!SleepBounds.isGoal(_)) then Left(SleepRefusal.GoalOutOfBounds)
      else
        val now = clock.now()
        tx.tracking.sleep.setGoal(accountId, SleepGoalChange(LocalDays.dateOf(now, zone), targetMinutes), now)
        Right(targetMinutes)

  private def goalOn(tx: Tx, accountId: UUID, now: Instant, zone: ZoneId): Option[Int] =
    tx.tracking.sleep.goals(accountId, LocalDays.dateOf(now, zone)).lastOption.flatMap(_.targetMinutes)

  /** The "Going to sleep" that "I'm up" would close: live, not older than the open limit, and later than the wake of
    * the latest night.
    */
  private def openNight(tx: Tx, accountId: UUID, now: Instant): Option[SleepCheckin] =
    val closedUntil = tx.tracking.sleep.latestLiveEpisode(accountId).map(_.interval.wake.at)
    tx.tracking.sleep
      .latestLiveCheckin(accountId, CheckinKind.GoingToSleep)
      .filter(checkin => Duration.between(checkin.occurredAt, now).compareTo(SleepBounds.OpenLimit) <= 0)
      .filter(checkin => closedUntil.forall(_.isBefore(checkin.occurredAt)))

  private def isOpenWake(checkin: SleepCheckin): Boolean = checkin.isLive && checkin.kind == CheckinKind.ImUp

  private def replayOf(tx: Tx, checkin: SleepCheckin): WokeUp =
    checkin.clientKey.flatMap(tx.tracking.sleep.episodeByClientKey(checkin.accountId, _)) match
      case Some(episode) => WokeUp.Closed(episode, replayed = true)
      case None          => WokeUp.NeedsBedtime(checkin)

  private def tap(input: SleepInput, kind: CheckinKind, zone: ZoneId, now: Instant): SleepCheckin =
    SleepCheckin(
      id = ids.next(now),
      accountId = input.accountId,
      kind = kind,
      occurredAt = now,
      zone = zone,
      source = input.source,
      clientKey = Some(input.clientKey),
      createdAt = now,
      voidedAt = None
    )

  private def retrospective(command: EnterSleep, zone: ZoneId): Draft =
    val interval = SleepInterval(
      SleepEdge(command.onsetAt, zone, EdgeSource.Retrospective),
      SleepEdge(command.wakeAt, zone, EdgeSource.Retrospective)
    )
    Draft(command.input, command.kind, interval, SleepOrigin.Retrospective, None)

  private def moved(edge: SleepEdge, shift: Duration): SleepEdge =
    if shift.isZero then edge else edge.copy(at = edge.at.plus(shift), source = EdgeSource.Retrospective)

  private def live(tx: Tx, accountId: UUID, episodeId: UUID): Either[SleepRefusal, SleepEpisode] =
    tx.tracking.sleep.episode(accountId, episodeId).filter(_.isLive).toRight(SleepRefusal.UnknownEpisode)

  /** The one write path of an episode: the guards of R-SLP-2, then the void of the row it replaces, then the insert. */
  private def record(tx: Tx, draft: Draft, replaces: Option[SleepEpisode]): Either[SleepRefusal, SleepRecorded] =
    tx.tracking.sleep.episodeByClientKey(draft.input.accountId, draft.input.clientKey) match
      case Some(existing) => Right(SleepRecorded(existing, replayed = true))
      case None           =>
        val now = clock.now()
        for
          _ <- SleepRules.check(draft.kind, draft.interval, now).left.map(SleepRefusal.Invalid(_))
          _ <- free(tx, draft, replaces)
          _ <- Either.cond(supersede(tx, replaces, now), (), SleepRefusal.UnknownEpisode)
        yield store(tx, draft.episode(ids.next(now), replaces.map(_.id), now))

  /** No other live episode stands in the way: not the night of the same sleep day, not any episode sharing time. */
  private def free(tx: Tx, draft: Draft, replaces: Option[SleepEpisode]): Either[SleepRefusal, Unit] =
    def other(episode: SleepEpisode): Boolean = !replaces.exists(_.id == episode.id)
    val day = DateRange.single(draft.interval.sleepDay)
    val nights =
      if draft.kind == SleepKind.Main then tx.tracking.sleep.liveEpisodes(draft.input.accountId, day) else Nil
    nights.find(episode => episode.kind == SleepKind.Main && other(episode)) match
      case Some(night) => Left(SleepRefusal.NightExists(night))
      case None        =>
        tx.tracking.sleep.overlapping(draft.input.accountId, draft.interval).find(other) match
          case Some(overlapped) => Left(SleepRefusal.Overlaps(overlapped))
          case None             => Right(())

  /** The void is the fence: of two concurrent corrections of one episode, only the one that voids it may replace it. */
  private def supersede(tx: Tx, replaces: Option[SleepEpisode], now: Instant): Boolean =
    replaces.forall(original =>
      tx.tracking.sleep.voidEpisode(original.accountId, original.id, VoidReason.Superseded, now)
    )

  private def store(tx: Tx, episode: SleepEpisode): SleepRecorded =
    if tx.tracking.sleep.insertEpisode(episode) then SleepRecorded(episode, replayed = false)
    else
      // A concurrent request with the same client key won the insert: answer with its episode.
      val winner = episode.clientKey
        .flatMap(tx.tracking.sleep.episodeByClientKey(episode.accountId, _))
        .getOrElse(throw IllegalStateException(s"sleep episode ${episode.id} conflicted without a client-key twin"))
      SleepRecorded(winner, replayed = true)

  private def consented[A](tx: Tx, accountId: UUID)(f: ZoneId => Either[SleepRefusal, A]): Either[SleepRefusal, A] =
    if !tx.consents.isGranted(accountId, ConsentPurpose.Sleep) then Left(SleepRefusal.ConsentMissing)
    else
      tx.accounts.timezoneOf(accountId) match
        case Some(zone) => f(ZoneId.of(zone))
        case None       => Left(SleepRefusal.UnknownAccount)
end SleepService

/** An episode before it has an id: what the user gave, and how. Both edges come from the user, so the tier is the
  * explicit one and the confidence that of two explicit edges (R-SLP-12).
  */
private final case class Draft(
    input: SleepInput,
    kind: SleepKind,
    interval: SleepInterval,
    origin: SleepOrigin,
    quality: Option[Quality]
):
  def episode(id: UUID, supersedesId: Option[UUID], now: Instant): SleepEpisode =
    SleepEpisode(
      id = id,
      accountId = input.accountId,
      kind = kind,
      interval = interval,
      origin = origin,
      tier = SleepTier.Explicit,
      confidence = Confidence.BothExplicit,
      quality = quality,
      source = input.source,
      clientKey = Some(input.clientKey),
      supersedesId = supersedesId,
      createdAt = now,
      voidedAt = None
    )
