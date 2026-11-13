package dosegram.core.application

import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.sleep.CheckinKind
import dosegram.core.domain.sleep.SleepCheckin
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepGoalChange
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.ports.SleepRepository

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.collection.mutable

/** The sleep tables in memory. It keeps the two rules of the schema the use cases rely on: a client key is unique per
  * account in each table, and a sleep day has at most one live night.
  */
final class InMemorySleep extends SleepRepository:
  val checkins: mutable.LinkedHashMap[UUID, SleepCheckin] = mutable.LinkedHashMap.empty
  val episodes: mutable.LinkedHashMap[UUID, SleepEpisode] = mutable.LinkedHashMap.empty
  private val goalChanges: mutable.Map[UUID, Vector[SleepGoalChange]] = mutable.Map.empty

  def live: List[SleepEpisode] = episodes.values.filter(_.isLive).toList

  override def insertCheckin(checkin: SleepCheckin): Boolean =
    val taken = checkin.clientKey.exists(key => checkinByClientKey(checkin.accountId, key).isDefined)
    if !taken then checkins(checkin.id) = checkin
    !taken

  override def checkin(accountId: UUID, id: UUID): Option[SleepCheckin] =
    checkins.get(id).filter(_.accountId == accountId)

  override def checkinByClientKey(accountId: UUID, clientKey: String): Option[SleepCheckin] =
    checkins.values.find(checkin => checkin.accountId == accountId && checkin.clientKey.contains(clientKey))

  override def latestLiveCheckin(accountId: UUID, kind: CheckinKind): Option[SleepCheckin] =
    checkins.values
      .filter(checkin => checkin.accountId == accountId && checkin.kind == kind && checkin.isLive)
      .maxByOption(_.occurredAt)

  override def voidCheckin(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    checkin(accountId, id).filter(_.isLive) match
      case Some(found) =>
        checkins(id) = found.copy(voidedAt = Some(now))
        true
      case None => false

  override def insertEpisode(episode: SleepEpisode): Boolean =
    val taken = episode.clientKey.exists(key => episodeByClientKey(episode.accountId, key).isDefined)
    val secondNight = episode.kind == SleepKind.Main && live.exists: other =>
      other.accountId == episode.accountId && other.kind == SleepKind.Main && other.sleepDay == episode.sleepDay
    require(!secondNight, s"a second live night on ${episode.sleepDay} violates uq_sleep_episodes_main_per_day")
    if !taken then episodes(episode.id) = episode
    !taken

  override def episode(accountId: UUID, id: UUID): Option[SleepEpisode] =
    episodes.get(id).filter(_.accountId == accountId)

  override def episodeByClientKey(accountId: UUID, clientKey: String): Option[SleepEpisode] =
    episodes.values.find(episode => episode.accountId == accountId && episode.clientKey.contains(clientKey))

  override def voidEpisode(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    episode(accountId, id).filter(_.isLive) match
      case Some(found) =>
        episodes(id) = found.copy(voidedAt = Some(now))
        true
      case None => false

  override def liveEpisodes(accountId: UUID, range: DateRange): List[SleepEpisode] =
    ofAccount(accountId).filter(episode => range.contains(episode.sleepDay))

  override def overlapping(accountId: UUID, interval: SleepInterval): List[SleepEpisode] =
    ofAccount(accountId).filter(_.interval.overlaps(interval))

  override def latestLiveEpisode(accountId: UUID): Option[SleepEpisode] = ofAccount(accountId).lastOption

  override def setGoal(accountId: UUID, change: SleepGoalChange, now: Instant): Unit =
    val kept = goalChanges.getOrElse(accountId, Vector.empty).filterNot(_.effectiveFrom == change.effectiveFrom)
    goalChanges(accountId) = (kept :+ change).sortBy(_.effectiveFrom.toEpochDay)

  override def goals(accountId: UUID, upTo: LocalDate): List[SleepGoalChange] =
    goalChanges.getOrElse(accountId, Vector.empty).filterNot(_.effectiveFrom.isAfter(upTo)).toList

  private def ofAccount(accountId: UUID): List[SleepEpisode] =
    live.filter(_.accountId == accountId).sortBy(_.interval.wake.at)
