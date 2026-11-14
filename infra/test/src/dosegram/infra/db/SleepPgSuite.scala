package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.core.application.sleep.AdjustEpisode
import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.RateEpisode
import dosegram.core.application.sleep.SleepInput
import dosegram.core.application.sleep.SleepRefusal
import dosegram.core.application.sleep.SleepService
import dosegram.core.application.sleep.WokeUp
import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.sleep.CheckinKind
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepGoalChange
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.ports.IdSource

import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** ROADMAP G7.1 on Testcontainers Postgres 18: the sleep use cases on the real tables, the rules the schema keeps by
  * itself, and the privileges of the application and erasure roles.
  */
class SleepPgSuite extends PgSuite:

  // 23:40 local on 11 November 2026 in Berlin, and 07:10 the next morning.
  private val bedtime = Instant.parse("2026-11-11T22:40:00Z")
  private val morning = Instant.parse("2026-11-12T06:10:00Z")
  private val sleepDay = LocalDate.parse("2026-11-12")
  private lazy val fixtures = Fixtures(dataSource)

  private final class Rig:
    val account: UUID = fixtures.account("Europe/Berlin")
    val clock = MutableClock(bedtime)
    val uow = TransactionalTracking.everyPurposeGranted(PgUnitOfWork(dataSource, clock, DataKeys.development))
    val service = SleepService(clock, IdSource.uuidV7(RandomGenerator.getDefault))

    def input(key: String): SleepInput = SleepInput(account, EntrySource.Bot, key)

    def night(): SleepEpisode =
      uow.transaction(service.goingToSleep(_, input("night")))
      clock.at = morning
      uow.transaction(service.wakeUp(_, input("morning"))) match
        case Right(WokeUp.Closed(episode, _)) => episode
        case other                            => fail(s"expected a closed night, got $other")

    def stored: List[SleepEpisode] =
      uow.transaction(_.tracking.sleep.liveEpisodes(account, DateRange.single(sleepDay)))

  private def rejected(constraint: String)(statement: => Any): Unit =
    val e = intercept[SQLException](statement)
    assert(e.getMessage.contains(constraint), e.getMessage)

  private def episodeRow(account: UUID, onset: Instant, wake: Instant, tier: Int = 2)(using Connection): UUID =
    val id = UUID.randomUUID()
    sql"""INSERT INTO sleep_episodes
            (id, account_id, occurred_at, local_date, tz, source, kind, onset_at, wake_at, tz_onset, tier, origin,
             onset_source, wake_source, confidence, created_at)
          VALUES ($id, $account, $wake, $sleepDay, 'Europe/Berlin', 'bot', 'main', $onset, $wake, 'Europe/Berlin',
                  $tier, 'retrospective', 'retrospective', 'retrospective', ${BigDecimal("0.90")}, $wake)""".execute()
    id

  test("the two taps store one night that reads back as it was recorded (AC-SLP-1)"):
    val rig = Rig()
    val night = rig.night()
    assertEquals(rig.stored, List(night))
    assertEquals(night.duration, Duration.ofMinutes(450))
    assertEquals(night.sleepDay, sleepDay)
    val taps = rig.uow.transaction: tx =>
      CheckinKind.values.toList.flatMap(tx.tracking.sleep.latestLiveCheckin(rig.account, _)).map(_.kind)
    assertEquals(taps, List(CheckinKind.GoingToSleep, CheckinKind.ImUp))

  test("a rating and an adjustment are new rows; the superseded rows stay in the table"):
    val rig = Rig()
    val night = rig.night()
    val rated = rig.uow
      .transaction(rig.service.rate(_, RateEpisode(rig.input("rate"), night.id, Quality.of(5).get)))
      .fold(r => fail(s"refused: $r"), _.episode)
    val adjust = AdjustEpisode(rig.input("adjust"), rated.id, Duration.ofMinutes(-30), Duration.ZERO)
    val adjusted = rig.uow.transaction(rig.service.adjust(_, adjust)).fold(r => fail(s"refused: $r"), _.episode)
    assertEquals(rig.stored.map(e => (e.id, e.quality.map(_.step), e.duration)), List((adjusted.id, Some(5), Duration.ofMinutes(480))))
    withConnection { conn =>
      given Connection = conn
      val voided = sql"""SELECT count(*) FROM sleep_episodes
                         WHERE account_id = ${rig.account} AND void_reason = 'superseded'""".queryOne[Long]()
      assertEquals(voided, Some(2L))
    }

  test("a second night for the sleep day and an overlapping nap are refused on the real tables"):
    val rig = Rig()
    val night = rig.night()
    rig.clock.advance(Duration.ofHours(3))
    val again = EnterSleep(rig.input("typed"), SleepKind.Main, bedtime.minusSeconds(1800), morning.minusSeconds(600))
    assertEquals(rig.uow.transaction(rig.service.enter(_, again)), Left(SleepRefusal.NightExists(night)))
    val nap = EnterSleep(rig.input("nap"), SleepKind.Nap, morning.minusSeconds(1200), morning.plusSeconds(1200))
    assertEquals(rig.uow.transaction(rig.service.enter(_, nap)), Left(SleepRefusal.Overlaps(night)))
    assertEquals(rig.stored, List(night))

  test("the goal history returns the goal in force on a day"):
    val rig = Rig()
    rig.uow.transaction: tx =>
      tx.tracking.sleep.setGoal(rig.account, SleepGoalChange(sleepDay.minusDays(5), Some(480)), bedtime)
      tx.tracking.sleep.setGoal(rig.account, SleepGoalChange(sleepDay, Some(450)), bedtime)
      tx.tracking.sleep.setGoal(rig.account, SleepGoalChange(sleepDay, None), bedtime)
    val goals = rig.uow.transaction(_.tracking.sleep.goals(rig.account, sleepDay))
    assertEquals(goals, List(SleepGoalChange(sleepDay.minusDays(5), Some(480)), SleepGoalChange(sleepDay, None)))
    assertEquals(rig.uow.transaction(_.tracking.sleep.goals(rig.account, sleepDay.minusDays(1))).size, 1)

  test("the schema allows one live night per sleep day"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      episodeRow(account, bedtime, morning)
      rejected("uq_sleep_episodes_main_per_day")(episodeRow(account, morning.plusSeconds(60), morning.plusSeconds(7200)))
    }

  test("the schema refuses a tier 1 episode, a wake before the onset and a length over 18 hours"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      rejected("sleep_episodes_tier_check")(episodeRow(account, bedtime, morning, tier = 1))
      rejected("sleep_episodes_wake_after_onset")(episodeRow(account, morning, bedtime))
      rejected("sleep_episodes_length")(episodeRow(account, morning.minus(Duration.ofHours(19)), morning))
    }

  test("a stored episode cannot be changed, only voided once, and not deleted outside erasure"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      val id = episodeRow(account, bedtime, morning)
      rejected("only a void is allowed")(sql"UPDATE sleep_episodes SET quality = 5 WHERE id = $id".execute())
      rejected("only a void is allowed"):
        sql"UPDATE sleep_episodes SET onset_at = onset_at - interval '1 hour' WHERE id = $id".execute()
      rejected("append-only")(sql"DELETE FROM sleep_episodes WHERE id = $id".execute())
    }

  // ADR-025, ARCHITECTURE SLP-01 and SLP-09: no presence, no score, no stage, and no passive evidence in this slice.
  test("no sleep table holds presence, a score or a stage, and no evidence table exists"):
    withConnection { conn =>
      given Connection = conn
      val columns = sql"""SELECT column_name FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name LIKE 'sleep_%'""".query[String]().toSet
      val banned = List("score", "stage", "last_seen", "online", "presence", "efficiency", "latency")
      assertEquals(columns.filter(column => banned.exists(column.contains)), Set.empty[String])
      val tables = sql"SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public'".query[String]().toSet
      assertEquals(tables.intersect(Set("activity_events", "sleep_activity_profile", "sleep_estimates")), Set.empty)
    }

  test("the application role can run every repository statement, and only erasure can delete"):
    val rig = Rig()
    val night = rig.night()
    withFreshConnection { conn =>
      given Connection = conn
      conn.createStatement().execute("SET ROLE dosegram_app")
      val sleep = PgSleepRepository(conn)
      assertEquals(sleep.episode(rig.account, night.id), Some(night))
      assertEquals(sleep.episodeByClientKey(rig.account, "morning"), Some(night))
      assertEquals(sleep.latestLiveEpisode(rig.account), Some(night))
      assertEquals(sleep.overlapping(rig.account, night.interval), List(night))
      val tap = sleep.checkinByClientKey(rig.account, "night").flatMap(found => sleep.checkin(rig.account, found.id)).get
      assert(sleep.insertCheckin(tap.copy(id = UUID.randomUUID(), clientKey = Some("as-app"))))
      assert(sleep.voidCheckin(rig.account, tap.id, VoidReason.Undone, morning))
      assert(sleep.voidEpisode(rig.account, night.id, VoidReason.Superseded, morning))
      assert(sleep.insertEpisode(night.copy(id = UUID.randomUUID(), clientKey = Some("as-app"))))
      sleep.setGoal(rig.account, SleepGoalChange(sleepDay, Some(450)), morning)
      sleep.setGoal(rig.account, SleepGoalChange(sleepDay, Some(480)), morning)
      rejected("permission denied")(sql"DELETE FROM sleep_checkins WHERE account_id = ${rig.account}".execute())
      conn.createStatement().execute("RESET ROLE")
      conn.createStatement().execute("SET ROLE dosegram_erasure")
      conn.createStatement().execute("SET dosegram.erasure = 'on'")
      assertEquals(sql"DELETE FROM sleep_episodes WHERE account_id = ${rig.account}".execute(), 2)
      assertEquals(sql"DELETE FROM sleep_checkins WHERE account_id = ${rig.account}".execute(), 3)
      assertEquals(sql"DELETE FROM sleep_goals WHERE account_id = ${rig.account}".execute(), 1)
      conn.createStatement().execute("RESET ROLE")
    }
end SleepPgSuite
