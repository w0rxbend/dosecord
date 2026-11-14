package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.core.application.insights.RollupService
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.application.nutrition.MealRecorded
import dosegram.core.application.nutrition.NutritionService
import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MealDay
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupMetrics
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.IdSource

import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** ROADMAP G8.1 and G8.3 on Testcontainers Postgres 18: the meal journal on the real tables (V4), the rules the schema
  * keeps by itself, the nutrition rollup, and the privileges of the application and erasure roles.
  */
class MealPgSuite extends PgSuite:

  // 13:05 in Berlin on 12 November 2026.
  private val noon = Instant.parse("2026-11-12T12:05:00Z")
  private val day = LocalDate.parse("2026-11-12")
  private lazy val fixtures = Fixtures(dataSource)

  private final class Rig:
    val account: UUID = fixtures.account("Europe/Berlin")
    val clock = MutableClock(noon)
    val uow = TransactionalTracking.everyPurposeGranted(PgUnitOfWork(dataSource, clock, DataKeys.development))
    val service = NutritionService(clock, IdSource.uuidV7(RandomGenerator.getDefault))

    def log(mealType: MealType, key: String, text: Option[String] = None, owner: UUID = account): MealRecorded =
      val words = text.flatMap(MealText.of(_).toOption.flatten)
      uow
        .transaction(service.log(_, LogMeal(owner, MealInput(mealType, words, None), EntrySource.Bot, key)))
        .fold(refusal => fail(s"refused: $refusal"), identity)

  private def rejected(constraint: String)(statement: => Any): Unit =
    val e = intercept[SQLException](statement)
    assert(e.getMessage.contains(constraint), e.getMessage)

  test("a meal reads back as it was recorded, words included, and one client key records once"):
    val rig = Rig()
    val first = rig.log(MealType.Lunch, "k1", Some("Soup and bread"))
    val again = rig.log(MealType.Lunch, "k1", Some("Soup and bread"))
    assert(again.replayed)
    assertEquals(again.entry, first.entry)
    val stored = rig.uow.transaction(_.tracking.meals.listLive(rig.account, DateRange.single(day)))
    assertEquals(stored, List(first.entry))
    assertEquals(stored.head.text.map(_.value), Some("Soup and bread"))
    assertEquals(stored.head.localDate, day)

  test("a meal of one account is invisible to another (cross-tenant)"):
    val rig = Rig()
    val other = fixtures.account("Europe/Berlin")
    val mine = rig.log(MealType.Dinner, "k1", Some("canary-meal-words"))
    rig.uow.transaction: tx =>
      assertEquals(tx.tracking.meals.get(other, mine.entry.id), None)
      assertEquals(tx.tracking.meals.listLive(other, DateRange.single(day)), Nil)
      assertEquals(tx.tracking.meals.recentTexts(other, MealType.Dinner, 5), Nil)
      assert(!tx.tracking.meals.void(other, mine.entry.id, VoidReason.Deleted, noon))

  test("recent words are distinct per meal type, newest first"):
    val rig = Rig()
    List("soup", "salad", "soup", "pasta").zipWithIndex.foreach: (text, i) =>
      rig.clock.advance(Duration.ofMinutes(1))
      rig.log(MealType.Lunch, s"k$i", Some(text))
    rig.log(MealType.Snack, "s", Some("apple"))
    val recent = rig.uow.transaction(_.tracking.meals.recentTexts(rig.account, MealType.Lunch, 5))
    assertEquals(recent.map(_.value), List("pasta", "soup", "salad"))

  test("a stored meal cannot be changed, only voided once, and not deleted outside erasure"):
    val rig = Rig()
    val meal = rig.log(MealType.Breakfast, "k1")
    withConnection { conn =>
      given Connection = conn
      rejected("only a void is allowed")(sql"UPDATE meals SET meal_type = 'lunch' WHERE id = ${meal.entry.id}".execute())
      rejected("append-only")(sql"DELETE FROM meals WHERE id = ${meal.entry.id}".execute())
    }

  test("the schema refuses words over 300 characters, an unknown meal type and a plan with a snack"):
    val account = fixtures.account()
    withConnection { conn =>
      given Connection = conn
      val long = "a" * 301
      rejected("meals_description_check"):
        sql"""INSERT INTO meals (id, account_id, occurred_at, local_date, tz, source, meal_type, description, created_at)
              VALUES (${UUID.randomUUID()}, $account, $noon, $day, 'UTC', 'bot', 'lunch', $long, $noon)""".execute()
      rejected("meals_meal_type_check"):
        sql"""INSERT INTO meals (id, account_id, occurred_at, local_date, tz, source, meal_type, created_at)
              VALUES (${UUID.randomUUID()}, $account, $noon, $day, 'UTC', 'bot', 'brunch', $noon)""".execute()
      rejected("meal_plans_planned_check"):
        sql"""INSERT INTO meal_plans (account_id, planned, updated_at)
              VALUES ($account, ${Array("breakfast", "snack")}, $noon)""".execute()
    }

  test("no meal table holds energy, a nutrient, an estimate or a photo in this wave"):
    withConnection { conn =>
      given Connection = conn
      val columns = sql"""SELECT column_name FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name LIKE 'meal%'""".query[String]().toSet
      val banned = List("energy", "kcal", "protein", "fat", "carb", "fibre", "estimate", "photo", "score")
      assertEquals(columns.filter(column => banned.exists(column.contains)), Set.empty[String])
    }

  test("the plan reads back, and an empty plan is kept"):
    val rig = Rig()
    rig.uow.transaction: tx =>
      assertEquals(tx.tracking.meals.plan(rig.account), None)
      tx.tracking.meals.savePlan(rig.account, MealPlan(Set(MealType.Lunch, MealType.Dinner)), noon)
      assertEquals(tx.tracking.meals.plan(rig.account), Some(MealPlan(Set(MealType.Lunch, MealType.Dinner))))
      tx.tracking.meals.savePlan(rig.account, MealPlan(Set.empty), noon)
      assertEquals(tx.tracking.meals.plan(rig.account), Some(MealPlan(Set.empty)))

  test("each meal queues its day, and the rollup counts the live meals per type (G8.3)"):
    val rig = Rig()
    rig.log(MealType.Breakfast, "k1")
    rig.log(MealType.Lunch, "k2", Some("soup"))
    rig.log(MealType.Snack, "k3")
    val undone = rig.log(MealType.Snack, "k4")
    rig.uow.transaction(rig.service.undo(_, rig.account, undone.entry.id))
    val rollups = RollupService(rig.uow, rig.clock)
    rig.uow.transaction(rollups.refresh(_, rig.account, RollupDomain.Nutrition, DateRange.single(day)))
    val stored = rig.uow.transaction(_.tracking.rollups.list(rig.account, RollupDomain.Nutrition, DateRange.single(day)))
    assertEquals(stored, Map(day -> RollupMetrics.Nutrition(MealDay(1, 1, 0, 1))))
    withConnection { conn =>
      given Connection = conn
      val metrics = sql"""SELECT metrics::text FROM daily_rollups
                          WHERE account_id = ${rig.account} AND domain = 'nutrition'""".query[String]()
      assertEquals(metrics.size, 1)
      assert(!metrics.head.contains("soup"), "a rollup holds counts only")
    }

  test("the application role can run every repository statement, and only erasure can delete"):
    val rig = Rig()
    val meal = rig.log(MealType.Lunch, "k1", Some("soup"))
    withFreshConnection { conn =>
      given Connection = conn
      conn.createStatement().execute("SET ROLE dosegram_app")
      val meals = PgMealRepository(conn, DataKeys.development.on(conn))
      assertEquals(meals.get(rig.account, meal.entry.id), Some(meal.entry))
      assertEquals(meals.findByClientKey(rig.account, "k1"), Some(meal.entry))
      assertEquals(meals.recentTexts(rig.account, MealType.Lunch, 5).map(_.value), List("soup"))
      assert(meals.insert(meal.entry.copy(id = UUID.randomUUID(), clientKey = Some("as-app"))))
      assert(meals.void(rig.account, meal.entry.id, VoidReason.Deleted, noon))
      meals.savePlan(rig.account, MealPlan.Default, noon)
      meals.savePlan(rig.account, MealPlan(Set(MealType.Lunch)), noon)
      assertEquals(meals.plan(rig.account), Some(MealPlan(Set(MealType.Lunch))))
      rejected("permission denied")(sql"DELETE FROM meals WHERE account_id = ${rig.account}".execute())
      conn.createStatement().execute("RESET ROLE")
      conn.createStatement().execute("SET ROLE dosegram_erasure")
      conn.createStatement().execute("SET dosegram.erasure = 'on'")
      assertEquals(sql"DELETE FROM meals WHERE account_id = ${rig.account}".execute(), 2)
      assertEquals(sql"DELETE FROM meal_plans WHERE account_id = ${rig.account}".execute(), 1)
      conn.createStatement().execute("RESET ROLE")
    }
end MealPgSuite
