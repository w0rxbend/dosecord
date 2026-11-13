package dosegram.infra.db

import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.sleep.CheckinKind
import dosegram.core.domain.sleep.Confidence
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepCheckin
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepGoalChange
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepOrigin
import dosegram.core.domain.sleep.SleepTier
import dosegram.core.ports.SleepRepository

import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `sleep_checkins`, `sleep_episodes` and `sleep_goals`. `occurred_at` of an episode is its wake instant and
  * `local_date` its sleep day, so the entry indexes serve the reads by day.
  */
final class PgSleepRepository(conn: Connection) extends SleepRepository:
  import PgSleepRepository.*

  private given Connection = conn

  private given RowMapper[SleepCheckin] = rs =>
    SleepCheckin(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      kind = stored(rs, "kind", CheckinKind.values)(_.dbValue),
      occurredAt = rs.instant("occurred_at"),
      zone = ZoneId.of(rs.getString("tz")),
      source = EntrySource.fromDbValue(rs.getString("source")),
      clientKey = rs.optString("client_key"),
      createdAt = rs.instant("created_at"),
      voidedAt = rs.optInstant("voided_at")
    )

  private given RowMapper[SleepEpisode] = rs =>
    val onsetSource = stored(rs, "onset_source", EdgeSource.values)(_.dbValue)
    val wakeSource = stored(rs, "wake_source", EdgeSource.values)(_.dbValue)
    SleepEpisode(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      kind = stored(rs, "kind", SleepKind.values)(_.dbValue),
      interval = SleepInterval(
        SleepEdge(rs.instant("onset_at"), ZoneId.of(rs.getString("tz_onset")), onsetSource),
        SleepEdge(rs.instant("wake_at"), ZoneId.of(rs.getString("tz")), wakeSource)
      ),
      origin = stored(rs, "origin", SleepOrigin.values)(_.dbValue),
      tier = stored(rs, "tier", SleepTier.values)(_.number.toString),
      confidence = confidenceOf(rs),
      quality = rs.optInt("quality").map(step => Quality.of(step).getOrElse(corrupt("quality", step.toString))),
      source = EntrySource.fromDbValue(rs.getString("source")),
      clientKey = rs.optString("client_key"),
      supersedesId = rs.optUuid("supersedes_id"),
      createdAt = rs.instant("created_at"),
      voidedAt = rs.optInstant("voided_at")
    )

  private given RowMapper[SleepGoalChange] = rs =>
    SleepGoalChange(rs.localDate("effective_from"), rs.optInt("target_minutes"))

  override def insertCheckin(checkin: SleepCheckin): Boolean =
    sql"""INSERT INTO sleep_checkins (id, account_id, occurred_at, local_date, tz, source, client_key, kind, created_at)
          VALUES (${checkin.id}, ${checkin.accountId}, ${checkin.occurredAt}, ${checkin.localDate},
                  ${checkin.zone.getId}, ${checkin.source.dbValue}, ${checkin.clientKey}, ${checkin.kind.dbValue},
                  ${checkin.createdAt})
          ON CONFLICT (account_id, client_key) DO NOTHING""".execute() == 1

  override def checkin(accountId: UUID, id: UUID): Option[SleepCheckin] =
    sql"""SELECT id, account_id, occurred_at, tz, source, client_key, kind, created_at, voided_at
          FROM sleep_checkins WHERE id = $id AND account_id = $accountId""".queryOne[SleepCheckin]()

  override def checkinByClientKey(accountId: UUID, clientKey: String): Option[SleepCheckin] =
    sql"""SELECT id, account_id, occurred_at, tz, source, client_key, kind, created_at, voided_at
          FROM sleep_checkins WHERE account_id = $accountId AND client_key = $clientKey""".queryOne[SleepCheckin]()

  override def latestLiveCheckin(accountId: UUID, kind: CheckinKind): Option[SleepCheckin] =
    sql"""SELECT id, account_id, occurred_at, tz, source, client_key, kind, created_at, voided_at
          FROM sleep_checkins
          WHERE account_id = $accountId AND kind = ${kind.dbValue} AND voided_at IS NULL
          ORDER BY occurred_at DESC, id DESC LIMIT 1""".queryOne[SleepCheckin]()

  override def voidCheckin(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    sql"""UPDATE sleep_checkins SET voided_at = $now, void_reason = ${reason.dbValue}
          WHERE id = $id AND account_id = $accountId AND voided_at IS NULL""".execute() == 1

  override def insertEpisode(episode: SleepEpisode): Boolean =
    val onset = episode.interval.onset
    val wake = episode.interval.wake
    sql"""INSERT INTO sleep_episodes
            (id, account_id, occurred_at, local_date, tz, source, client_key, supersedes_id, kind, onset_at, wake_at,
             tz_onset, tier, origin, onset_source, wake_source, confidence, quality, created_at)
          VALUES (${episode.id}, ${episode.accountId}, ${wake.at}, ${episode.sleepDay}, ${wake.zone.getId},
                  ${episode.source.dbValue}, ${episode.clientKey}, ${episode.supersedesId}, ${episode.kind.dbValue},
                  ${onset.at}, ${wake.at}, ${onset.zone.getId}, ${episode.tier.number}, ${episode.origin.dbValue},
                  ${onset.source.dbValue}, ${wake.source.dbValue},
                  ${BigDecimal(episode.confidence.hundredths) / Hundred}, ${episode.quality.map(_.step)},
                  ${episode.createdAt})
          ON CONFLICT (account_id, client_key) DO NOTHING""".execute() == 1

  override def episode(accountId: UUID, id: UUID): Option[SleepEpisode] =
    sql"""SELECT id, account_id, tz, source, client_key, supersedes_id, kind, onset_at, wake_at, tz_onset, tier,
                 origin, onset_source, wake_source, confidence, quality, created_at, voided_at
          FROM sleep_episodes WHERE id = $id AND account_id = $accountId""".queryOne[SleepEpisode]()

  override def episodeByClientKey(accountId: UUID, clientKey: String): Option[SleepEpisode] =
    sql"""SELECT id, account_id, tz, source, client_key, supersedes_id, kind, onset_at, wake_at, tz_onset, tier,
                 origin, onset_source, wake_source, confidence, quality, created_at, voided_at
          FROM sleep_episodes WHERE account_id = $accountId AND client_key = $clientKey""".queryOne[SleepEpisode]()

  override def voidEpisode(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    sql"""UPDATE sleep_episodes SET voided_at = $now, void_reason = ${reason.dbValue}
          WHERE id = $id AND account_id = $accountId AND voided_at IS NULL""".execute() == 1

  override def liveEpisodes(accountId: UUID, range: DateRange): List[SleepEpisode] =
    sql"""SELECT id, account_id, tz, source, client_key, supersedes_id, kind, onset_at, wake_at, tz_onset, tier,
                 origin, onset_source, wake_source, confidence, quality, created_at, voided_at
          FROM sleep_episodes
          WHERE account_id = $accountId AND local_date >= ${range.start} AND local_date <= ${range.end}
            AND voided_at IS NULL
          ORDER BY wake_at, id""".query[SleepEpisode]()

  override def overlapping(accountId: UUID, interval: SleepInterval): List[SleepEpisode] =
    sql"""SELECT id, account_id, tz, source, client_key, supersedes_id, kind, onset_at, wake_at, tz_onset, tier,
                 origin, onset_source, wake_source, confidence, quality, created_at, voided_at
          FROM sleep_episodes
          WHERE account_id = $accountId AND voided_at IS NULL
            AND onset_at < ${interval.wake.at} AND wake_at > ${interval.onset.at}
          ORDER BY wake_at, id""".query[SleepEpisode]()

  override def latestLiveEpisode(accountId: UUID): Option[SleepEpisode] =
    sql"""SELECT id, account_id, tz, source, client_key, supersedes_id, kind, onset_at, wake_at, tz_onset, tier,
                 origin, onset_source, wake_source, confidence, quality, created_at, voided_at
          FROM sleep_episodes
          WHERE account_id = $accountId AND voided_at IS NULL
          ORDER BY wake_at DESC, id DESC LIMIT 1""".queryOne[SleepEpisode]()

  override def setGoal(accountId: UUID, change: SleepGoalChange, now: Instant): Unit =
    sql"""INSERT INTO sleep_goals (account_id, effective_from, target_minutes, created_at)
          VALUES ($accountId, ${change.effectiveFrom}, ${change.targetMinutes}, $now)
          ON CONFLICT (account_id, effective_from) DO UPDATE SET target_minutes = EXCLUDED.target_minutes""".execute()
    ()

  override def goals(accountId: UUID, upTo: LocalDate): List[SleepGoalChange] =
    sql"""SELECT effective_from, target_minutes FROM sleep_goals
          WHERE account_id = $accountId AND effective_from <= $upTo
          ORDER BY effective_from""".query[SleepGoalChange]()

private object PgSleepRepository:
  private val Hundred = BigDecimal(100)

  /** A stored word is inside its column CHECK; anything else is a corrupt row. */
  private def stored[A](rs: ResultSet, column: String, values: Array[A])(word: A => String): A =
    val text = rs.getString(column)
    values.find(value => word(value) == text).getOrElse(corrupt(column, text))

  private def confidenceOf(rs: ResultSet): Confidence =
    val hundredths = rs.getBigDecimal("confidence").movePointRight(2).intValueExact
    Confidence.ofHundredths(hundredths).getOrElse(corrupt("confidence", hundredths.toString))

  private def corrupt(column: String, value: String): Nothing =
    throw IllegalStateException(s"a sleep table holds '$value' in $column, outside its CHECK")
