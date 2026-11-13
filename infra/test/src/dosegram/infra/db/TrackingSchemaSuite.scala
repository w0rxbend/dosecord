package dosegram.infra.db

import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.hydration.EnteredAmount
import dosegram.core.domain.hydration.GoalChange
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.hydration.HydrationUnit
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupKey
import dosegram.core.domain.insights.RollupMetrics

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** The schema half of ROADMAP G5.3 and G6.1: the entry tables of ARCHITECTURE sections 10.6 and 10.8 exist in the
  * baseline, their bounds are CHECK constraints, an entry is immutable apart from its first void, and the application
  * role holds every privilege the repositories need.
  */
class TrackingSchemaSuite extends PgSuite:

  private val at = Instant.parse("2026-06-15T09:00:00Z")
  private val day = LocalDate.parse("2026-06-15")
  private lazy val fixtures = Fixtures(dataSource)

  private def hydrationRow(account: UUID, amountMl: Int)(using Connection): UUID =
    val id = UUID.randomUUID()
    sql"""INSERT INTO hydration_entries
            (id, account_id, occurred_at, local_date, tz, source, amount_ml, entered_value, entered_unit, created_at)
          VALUES ($id, $account, $at, $day, 'Europe/Kyiv', 'bot', $amountMl, ${BigDecimal(amountMl)}, 'ml', $at)"""
      .execute()
    id

  private def moodRow(account: UUID, valence: Int, energy: Option[Int], tags: Int)(using Connection): UUID =
    val id = UUID.randomUUID()
    val tagIds = UuidArray(Array.fill(tags)(UUID.randomUUID()))
    sql"""INSERT INTO mood_checkins
            (id, account_id, occurred_at, local_date, tz, source, valence, energy, tag_ids, created_at)
          VALUES ($id, $account, $at, $day, 'Europe/Kyiv', 'bot', $valence, $energy, $tagIds, $at)""".execute()
    id

  private def rejected(constraint: String)(statement: => Any): Unit =
    val e = intercept[SQLException](statement)
    assert(e.getMessage.contains(constraint), e.getMessage)

  test("the tracking and rollup tables exist"):
    val expected = Set(
      "hydration_entries",
      "hydration_goals",
      "hydration_presets",
      "account_tags",
      "mood_checkins",
      "daily_rollups",
      "rollup_dirty"
    )
    withConnection { conn =>
      given Connection = conn
      val tables = sql"SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public'".query[String]().toSet
      assertEquals(expected.diff(tables), Set.empty[String])
    }

  test("an entry amount outside 1 to 5000 ml is rejected"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      rejected("hydration_entries_amount_ml_check")(hydrationRow(account, 0))
      rejected("hydration_entries_amount_ml_check")(hydrationRow(account, 5001))
      hydrationRow(account, 1)
      hydrationRow(account, 5000)
    }

  test("a goal outside 100 to 10000 ml is rejected, and no goal is allowed"):
    val account = fixtures.account()
    withConnection { conn =>
      val hydration = PgHydrationRepository(conn)
      rejected("hydration_goals_target_ml_check")(hydration.setGoal(account, GoalChange(day, Some(99)), at))
      rejected("hydration_goals_target_ml_check")(hydration.setGoal(account, GoalChange(day, Some(10001)), at))
      hydration.setGoal(account, GoalChange(day, None), at)
      assertEquals(hydration.goals(account, day), List(GoalChange(day, None)))
    }

  test("valence and energy outside 1 to 5 and more than five tags are rejected"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      rejected("mood_checkins_valence_check")(moodRow(account, 0, None, 0))
      rejected("mood_checkins_valence_check")(moodRow(account, 6, None, 0))
      rejected("mood_checkins_energy_check")(moodRow(account, 3, Some(6), 0))
      rejected("mood_checkins_tag_ids_check")(moodRow(account, 3, None, 6))
      moodRow(account, 5, Some(1), 5)
    }

  test("mood_checkins has no score or interpretation column"):
    withConnection { conn =>
      given Connection = conn
      val columns = sql"""SELECT column_name FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'mood_checkins'""".query[String]().toSet
      assertEquals(columns.filter(c => c.contains("score") || c.contains("level") || c.contains("interpret")), Set.empty)
    }

  test("a stored entry cannot be changed, only voided once"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      val id = hydrationRow(account, 300)
      rejected("only a void is allowed")(sql"UPDATE hydration_entries SET amount_ml = 900 WHERE id = $id".execute())
      rejected("only a void is allowed")(sql"UPDATE hydration_entries SET local_date = $day + 1 WHERE id = $id".execute())
      assertEquals(PgHydrationRepository(conn).void(account, id, VoidReason.Undone, at), true)
      rejected("already voided"):
        sql"UPDATE hydration_entries SET voided_at = NULL, void_reason = NULL WHERE id = $id".execute()
      val mood = moodRow(account, 2, None, 0)
      rejected("only a void is allowed")(sql"UPDATE mood_checkins SET valence = 5 WHERE id = $mood".execute())
    }

  test("a void needs its reason"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      val id = hydrationRow(account, 300)
      rejected("hydration_entries_void")(sql"UPDATE hydration_entries SET voided_at = $at WHERE id = $id".execute())
    }

  test("an entry cannot be deleted outside erasure"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      val id = hydrationRow(account, 300)
      rejected("append-only")(sql"DELETE FROM hydration_entries WHERE id = $id".execute())
      val mood = moodRow(account, 3, None, 0)
      rejected("append-only")(sql"DELETE FROM mood_checkins WHERE id = $mood".execute())
    }

  test("the erasure role deletes an entry, and the day is queued for its rollup"):
    val account = fixtures.account()
    withFreshConnection { conn =>
      given Connection = conn
      val id = hydrationRow(account, 300)
      sql"DELETE FROM rollup_dirty WHERE account_id = $account".execute()
      conn.createStatement().execute("SET ROLE dosegram_erasure")
      conn.createStatement().execute("SET dosegram.erasure = 'on'")
      assertEquals(sql"DELETE FROM hydration_entries WHERE id = $id".execute(), 1)
      conn.createStatement().execute("RESET ROLE")
      assertEquals(sql"SELECT count(*) FROM rollup_dirty WHERE account_id = $account".queryOne[Long](), Some(1L))
    }

  test("the application role can run every repository statement"):
    val account = fixtures.account()
    withFreshConnection { conn =>
      conn.createStatement().execute("SET ROLE dosegram_app")
      val hydration = PgHydrationRepository(conn)
      val rollups = PgRollupRepository(conn)
      val entry = HydrationEntry(
        id = UUID.randomUUID(),
        accountId = account,
        occurredAt = at,
        localDate = day,
        zone = ZoneId.of("Europe/Kyiv"),
        amount = WaterAmount(300, EnteredAmount(BigDecimal(300), HydrationUnit.Millilitre)),
        source = EntrySource.Bot,
        clientKey = Some("grants"),
        createdAt = at,
        voidedAt = None
      )
      assert(hydration.insert(entry))
      hydration.setGoal(account, GoalChange(day, Some(2000)), at)
      hydration.replacePresets(account, List(250, 500))
      hydration.replacePresets(account, List(200))
      assertEquals(hydration.presets(account), List(200))
      val key = RollupKey(account, day, RollupDomain.Hydration)
      assertEquals(rollups.claimDirtyIn(account, RollupDomain.Hydration, DateRange.single(day)), List(day))
      rollups.put(key, RollupMetrics.Hydration(HydrationDay(300, 1, Some(2000))), at)
      rollups.remove(key)
      assert(hydration.void(account, entry.id, VoidReason.Deleted, at))
      assert(PgMoodRepository(conn).listLive(account, DateRange.single(day)).isEmpty)
    }
end TrackingSchemaSuite
