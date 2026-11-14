package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput as MealCommand
import dosegram.core.domain.EntrySource
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.ConsentPurpose

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** ROADMAP G8.2 over HTTP: the meal routes of ARCHITECTURE section 9.5 and MINI_APP section 7.8. A meal logged in the
  * app is the same record as one logged in chat; a description is returned only to its owner; no answer carries
  * energy, a nutrient or a target.
  */
final class NutritionApiSuite extends ApiSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")

  /** A fresh account at 13:00 in Kyiv of a new day: the suite shares one clock. */
  private def eater(purposes: ConsentPurpose*): (TelegramAccount, String) =
    startNewDay()
    clock.at = clock.now().plus(Duration.ofHours(6))
    val account = accountWithoutConsent("Europe/Kyiv")
    fixtures.grant(account.accountId, (ConsentPurpose.Nutrition +: purposes)*)
    (account, login(account))

  private def meal(token: String, fields: (String, ujson.Value)*): Reply =
    post("/api/v1/meals", ujson.Obj.from(fields), token, newKey())

  private def today: LocalDate = clock.now().atZone(kyiv).toLocalDate

  /** One case per meal route, with the request an account without the consent sends. */
  private val mealRoutes: List[(String, String => Reply)] = List(
    "listMeals" -> (token => get("/api/v1/meals", token)),
    "createMeal" -> (token => meal(token, "mealType" -> "lunch")),
    "listRecentMeals" -> (token => get("/api/v1/meals/recent?mealType=lunch", token)),
    "getMeal" -> (token => get(s"/api/v1/meals/${java.util.UUID.randomUUID()}", token)),
    "deleteMeal" -> (token => delete(s"/api/v1/meals/${java.util.UUID.randomUUID()}", token)),
    "getNutritionSummary" -> (token => get("/api/v1/nutrition/summary", token)),
    "getNutritionSettings" -> (token => get("/api/v1/nutrition/settings", token)),
    "updateNutritionSettings" -> (token =>
      patch("/api/v1/nutrition/settings", ujson.Obj("planned" -> ujson.Arr()), token, Map("If-Match" -> "\"v1\""))
    )
  )

  test("without the nutrition consent every meal route answers consent_required and stores nothing"):
    startNewDay()
    val account = accountWithoutConsent("Europe/Kyiv")
    fixtures.grant(account.accountId, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.Medication)
    val token = login(account)
    mealRoutes.foreach: (route, call) =>
      val reply = call(token)
      assertEquals((route, reply.status, reply.code), (route, 403, "consent_required"), reply.body)
      assertEquals(reply.json("purpose").str, "nutrition", route)
    val stored = tracking.uow.transaction(_.tracking.meals.listLive(account.accountId, DateRange.single(today)))
    assertEquals(stored, Nil)

  test("every documented meal route has a consent case"):
    val documented =
      Endpoints.documented.flatMap(_.info.name).filter(name => name.contains("Meal") || name.contains("Nutrition"))
    assertEquals(mealRoutes.map(_._1).sorted, documented.sorted)

  test("a meal needs only its type; the same key records once, and the day says which planned meals are logged"):
    val (_, token) = eater()
    val key = newKey()
    val first = post("/api/v1/meals", ujson.Obj("mealType" -> "lunch"), token, key)
    assertEquals(first.status, 201, first.body)
    assertEquals(first.json("meal")("mealType").str, "lunch")
    assertEquals(first.json("meal")("text").str, s"Lunch at ${first.json("meal")("time").str}")
    assertEquals(first.json("day")("plannedLogged").num.toInt, 1)
    assertEquals(first.json("day")("notLogged").arr.map(_.str).toList, List("breakfast", "dinner"))
    assert(first.json("day")("text").str.endsWith("1 of 3 planned meals logged"), first.body)
    val again = post("/api/v1/meals", ujson.Obj("mealType" -> "lunch"), token, key)
    assertEquals(again.json("meal")("id").str, first.json("meal")("id").str)
    assertEquals(get("/api/v1/meals", token).json("items").arr.size, 1)

  test("a meal logged in the app equals one logged in chat in the database (G8.2)"):
    val (account, token) = eater()
    val words = MealText.of("Soup and bread").toOption.flatten
    val chat = tracking.uow
      .transaction(
        tracking.nutritionService
          .log(_, LogMeal(account.accountId, MealCommand(MealType.Lunch, words, None), EntrySource.Bot, "chat:1"))
      )
      .toOption
      .get
      .entry
    val app = meal(token, "mealType" -> "lunch", "description" -> "Soup and bread")
    assertEquals(app.status, 201, app.body)
    val stored = tracking.uow.transaction(_.tracking.meals.listLive(account.accountId, DateRange.single(today)))
    val fromApp = stored.find(_.source == EntrySource.MiniApp).get
    def record(entry: dosegram.core.domain.nutrition.MealEntry) =
      (entry.accountId, entry.localDate, entry.zone, entry.mealType, entry.text, entry.occurredAt)
    assertEquals(record(fromApp), record(chat))

  test("a change supersedes the meal and keeps its time; a delete voids it; a past day can be filled"):
    val (_, token) = eater()
    val first = meal(token, "mealType" -> "lunch", "description" -> "soup")
    val id = first.json("meal")("id").str
    val changed = meal(token, "mealType" -> "dinner", "description" -> "pasta", "supersedes" -> id)
    assertEquals(changed.status, 201, changed.body)
    assertEquals(changed.json("meal")("occurredAt").str, first.json("meal")("occurredAt").str)
    assertEquals(get("/api/v1/meals", token).json("items").arr.map(_("description").str).toList, List("pasta"))
    assertEquals(get(s"/api/v1/meals/$id", token).status, 404)
    assertEquals(meal(token, "mealType" -> "snack", "supersedes" -> id).status, 404)
    val newId = changed.json("meal")("id").str
    assertEquals(delete(s"/api/v1/meals/$newId", token).json("meals").num.toInt, 0)
    assertEquals(delete(s"/api/v1/meals/$newId", token).status, 200, "a second delete answers the same")
    val yesterday = clock.now().minus(Duration.ofDays(1))
    val past = meal(token, "mealType" -> "breakfast", "occurredAt" -> yesterday.toString)
    assertEquals(past.json("meal")("date").str, today.minusDays(1).toString)
    val future = meal(token, "mealType" -> "breakfast", "occurredAt" -> clock.now().plus(Duration.ofHours(2)).toString)
    assertEquals(future.fieldErrors, List("occurredAt" -> "in_future"))
    assertEquals(meal(token, "mealType" -> "lunch", "description" -> "a" * 301).fieldErrors, List("description" -> "text_too_long"))
    assertEquals(meal(token, "mealType" -> "brunch").status, 400)

  test("recent descriptions are per meal type, distinct and newest first"):
    val (_, token) = eater()
    List("soup", "salad", "soup").foreach: text =>
      clock.at = clock.now().plus(Duration.ofMinutes(1))
      meal(token, "mealType" -> "lunch", "description" -> text)
    meal(token, "mealType" -> "dinner", "description" -> "steak")
    val recent = get("/api/v1/meals/recent?mealType=lunch", token)
    assertEquals(recent.status, 200, recent.body)
    assertEquals(recent.json("descriptions").arr.map(_.str).toList, List("soup", "salad"))
    assertEquals(get("/api/v1/meals/recent?mealType=brunch", token).status, 400)

  test("the summary counts days per main meal without percentages, and an empty plan gives a count, not a rate"):
    val (_, token) = eater()
    meal(token, "mealType" -> "breakfast")
    meal(token, "mealType" -> "snack")
    val summary = get("/api/v1/nutrition/summary", token)
    assertEquals(summary.status, 200, summary.body)
    assertEquals(summary.json("days").arr.size, 7)
    assertEquals(summary.json("perMeal").arr.map(m => m("mealType").str -> m("days").num.toInt).toList,
      List("breakfast" -> 1, "lunch" -> 0, "dinner" -> 0))
    assertEquals(summary.json("text").str, "Breakfast 1, Lunch 0, Dinner 0 of 7 days")
    assert(!summary.body.contains("%"), summary.body)
    assert(summary.json("today")("text").str.endsWith("1 of 3 planned meals logged, 1 snack"), summary.body)
    val settings = get("/api/v1/nutrition/settings", token)
    assertEquals(settings.json("planned").arr.map(_.str).toList, List("breakfast", "lunch", "dinner"))
    val none = patch("/api/v1/nutrition/settings", ujson.Obj("planned" -> ujson.Arr()), token, ifMatch(settings))
    assertEquals(none.status, 200, none.body)
    assertProblem(patch("/api/v1/nutrition/settings", ujson.Obj("planned" -> ujson.Arr()), token, ifMatch(settings)), 412, "version_conflict")
    val snack = patch("/api/v1/nutrition/settings", ujson.Obj("planned" -> ujson.Arr("snack")), token, ifMatch(none))
    assertEquals(snack.fieldErrors, List("planned" -> "snack_not_planned"))
    val today = get("/api/v1/nutrition/summary", token).json("today")
    assertEquals((today("planned").num.toInt, today("notLogged").arr.size), (0, 0))
    assert(today("text").str.endsWith("1 meal logged, 1 snack"), today.toString)

  test("no meal answer carries energy, a nutrient, a target or a photo (MA-FOOD-1)"):
    val (_, token) = eater()
    val recorded = meal(token, "mealType" -> "lunch", "description" -> "soup")
    val bodies = List(recorded.body, get("/api/v1/meals", token).body, get("/api/v1/nutrition/summary", token).body)
    val banned = List("kcal", "energy", "calorie", "protein", "target", "budget", "photo", "remaining")
    bodies.foreach(body => banned.foreach(word => assert(!body.toLowerCase.contains(word), s"'$word' in $body")))

  test("meal words are returned only to their owner (cross-tenant)"):
    val (ownerAccount, owner) = eater()
    val id = meal(owner, "mealType" -> "dinner", "description" -> "canary-meal-words").json("meal")("id").str
    val (_, stranger) = eater()
    assertEquals(get(s"/api/v1/meals/$id", stranger).status, 404)
    assertEquals(delete(s"/api/v1/meals/$id", stranger).status, 404)
    assertEquals(meal(stranger, "mealType" -> "lunch", "supersedes" -> id).status, 404)
    List("/api/v1/meals", "/api/v1/meals/recent?mealType=dinner", "/api/v1/nutrition/summary").foreach: path =>
      val reply = get(path, stranger)
      assertEquals(reply.status, 200, reply.body)
      assert(!reply.body.contains("canary-meal-words"), s"$path leaked: ${reply.body}")
    assert(get(s"/api/v1/meals/$id", login(ownerAccount)).body.contains("canary-meal-words"))
