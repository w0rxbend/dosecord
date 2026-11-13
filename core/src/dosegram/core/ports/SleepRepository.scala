package dosegram.core.ports

import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.sleep.CheckinKind
import dosegram.core.domain.sleep.SleepCheckin
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepGoalChange
import dosegram.core.domain.sleep.SleepInterval

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `sleep_checkins`, `sleep_episodes` and `sleep_goals` (ARCHITECTURE section 10.9). Taps and episodes are immutable:
  * the only change to a stored row is its void, and a correction is a new episode that names the one it supersedes.
  * Every method is scoped to one account.
  */
trait SleepRepository:

  /** Inserts the tap unless the account already has one with the same client key; returns whether a row was written. */
  def insertCheckin(checkin: SleepCheckin): Boolean

  def checkin(accountId: UUID, id: UUID): Option[SleepCheckin]
  def checkinByClientKey(accountId: UUID, clientKey: String): Option[SleepCheckin]

  /** The most recent tap of a kind that is not voided. */
  def latestLiveCheckin(accountId: UUID, kind: CheckinKind): Option[SleepCheckin]

  /** Voids a live tap; returns false when it is unknown or already voided. */
  def voidCheckin(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean

  /** Inserts the episode unless the account already has one with the same client key; returns whether a row was
    * written.
    */
  def insertEpisode(episode: SleepEpisode): Boolean

  def episode(accountId: UUID, id: UUID): Option[SleepEpisode]
  def episodeByClientKey(accountId: UUID, clientKey: String): Option[SleepEpisode]

  /** Voids a live episode; returns false when it is unknown or already voided. */
  def voidEpisode(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean

  /** The episodes that count, with a sleep day inside the range, ordered by wake instant. */
  def liveEpisodes(accountId: UUID, range: DateRange): List[SleepEpisode]

  /** The live episodes that share at least one instant with the interval, ordered by wake instant. */
  def overlapping(accountId: UUID, interval: SleepInterval): List[SleepEpisode]

  /** The live episode with the latest wake instant. */
  def latestLiveEpisode(accountId: UUID): Option[SleepEpisode]

  /** Records the goal in force from `change.effectiveFrom`; a second change on the same day replaces the first. */
  def setGoal(accountId: UUID, change: SleepGoalChange, now: Instant): Unit

  /** Every goal change that took effect on or before `upTo`, oldest first. */
  def goals(accountId: UUID, upTo: LocalDate): List[SleepGoalChange]
