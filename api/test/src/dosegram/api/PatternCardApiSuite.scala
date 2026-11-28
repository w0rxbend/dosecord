package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps

import java.time.LocalDate
import java.time.ZoneOffset

/** The pattern card of IR-05 in the Mini App's patterns (supervisor decision, 2026-11-28; VISUALS IR-05): the days with
  * every planned meal logged against the other days with a meal, each group's mood mean with its days, beside the
  * sentence of the same comparison, only with the cross-domain consent.
  */
final class PatternCardApiSuite extends ApiSuite:

  private def grant(account: TelegramAccount, purposes: ConsentPurpose*): Unit =
    val notice = ConsentNotice(1, "seeded for the test", "en")
    purposes.foreach(tracking.consents.grant(account.accountId, _, notice, ConsentTaps(clock.now(), clock.now())))

  /** On 20 days before today: every planned meal and a pleasant check-in on even days, lunch and a neutral one on odd. */
  private def seed(account: TelegramAccount): Unit =
    val now = clock.now()
    try
      (1 to 20).foreach: back =>
        val day = LocalDate.of(2026, 11, 13).minusDays(back.toLong)
        clock.at = day.atTime(12, 0).toInstant(ZoneOffset.UTC)
        val everything = back % 2 == 0
        val meals = if everything then List(MealType.Breakfast, MealType.Lunch, MealType.Dinner) else List(MealType.Lunch)
        meals.foreach: meal =>
          val command = LogMeal(account.accountId, MealInput(meal, None, None), EntrySource.MiniApp, s"t:${java.util.UUID.randomUUID()}")
          assert(uow.transactionAs(account.accountId)(tracking.nutritionService.log(_, command)).isRight)
        val valence = Valence.of(if everything then 4 else 3).get
        val checkIn = CheckIn(account.accountId, valence, None, Nil, EntryDay.Today, EntrySource.MiniApp, s"t:${day}")
        assert(tracking.moods.checkIn(checkIn).isRight)
    finally clock.at = now

  private def items(account: TelegramAccount): List[ujson.Value] =
    val list = get("/api/v1/insights/correlations", login(account))
    assertEquals(list.status, 200, list.body)
    list.json("items").arr.toList

  test("IR-05 has a pattern card: each group's mood mean with its days, from the groups of its sentence"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Nutrition, ConsentPurpose.Mood, ConsentPurpose.CrossDomainPatterns)
    seed(account)
    val pattern = items(account).find(_("id").str.startsWith("IR-05")).getOrElse(fail("IR-05 is not shown"))
    val card = pattern("chart")
    assertEquals(card("summary").str, pattern("text").str, "the card's accessible name is the pattern's sentence")
    assertEquals(
      card("table")("rows").arr.map(_.arr.map(_.str).toList).toList.map(_.take(2)),
      List(List("Every planned meal logged", "10"), List("Other days with a meal logged", "10"))
    )
    assertEquals(card("marks").arr.size, 2)
    assert(card("marks").arr(0).str.startsWith("Every planned meal logged: "), card("marks").toString)
    val dots = card("scene")("nodes").arr.filter(_("shape").str == "circle")
    assertEquals(dots.size, 2)
    assert(dots(0)("x").num > dots(1)("x").num, "the group with the higher mean sits further along the scale")
    assertEquals(InsightPhrases.violations(pattern.render()), Nil)

  test("without the cross-domain consent there is no IR-05 and no card"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Nutrition, ConsentPurpose.Mood)
    seed(account)
    assert(!items(account).exists(_("id").str.startsWith("IR-05")))
end PatternCardApiSuite
