package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.mood.CheckIn
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose

import java.time.Duration

/** `GET /insights/overview` (ARCHITECTURE section 9.5, insights-and-correlations): the statistics of the read API of
  * section 11.4 over HTTP, one block per domain that has consent, in the server's words. The figures are the ones the
  * chat shows: both call `StatsQueryService`.
  */
final class InsightsApiSuite extends ApiSuite:

  private def grant(account: TelegramAccount, purposes: ConsentPurpose*): Unit =
    purposes.foreach(tracking.consents.grant(account.accountId, _, ConsentNotice(1, "seeded for the test"), clock.now()))

  private def drink(account: TelegramAccount, millilitres: Int): Unit =
    val amount = WaterAmount.ofMillilitres(millilitres).toOption.get
    val key = s"test:${java.util.UUID.randomUUID()}"
    assert(tracking.water.log(LogWater(account.accountId, amount, EntryDay.Today, EntrySource.MiniApp, key)).isRight)

  private def checkIn(account: TelegramAccount, step: Int): Unit =
    val command = CheckIn(
      account.accountId,
      Valence.of(step).get,
      None,
      Nil,
      EntryDay.Today,
      EntrySource.MiniApp,
      s"test:${java.util.UUID.randomUUID()}"
    )
    assert(tracking.moods.checkIn(command).isRight)

  private def blocks(reply: Reply): List[ujson.Value] = reply.json("blocks").arr.toList

  test("without a consent there is no block, and the notice says where consents are given"):
    val overview = get("/api/v1/insights/overview?range=7", login(telegramAccount()))
    assertEquals(overview.status, 200, overview.body)
    assertEquals(blocks(overview), Nil)
    assertEquals(overview.json("notice").str, "Statistics appear here for the records you turned on in the chat.")
    assertEquals(overview.json("rangeDays").num.toInt, 7)
    assertEquals(overview.json("ranges").arr.toList.map(_.num.toInt), List(7, 30), "the periods the client may offer")
    assertEquals(overview.json("rangeLabel").str, "7 Nov to 13 Nov")
    assertEquals(overview.header("Cache-Control"), None, "the edge, not the module, sets the cache header")

  test("a domain with consent and no record says so, and a domain without consent is absent"):
    val account = telegramAccount()
    grant(account, ConsentPurpose.Hydration)
    val overview = get("/api/v1/insights/overview?range=7", login(account))
    assertEquals(blocks(overview).map(_("id").str), List("hydration"))
    assertEquals(blocks(overview).head("noDataText").str, "No data in this period")
    assertEquals(blocks(overview).head("lines").arr.toList, Nil)
    assert(!overview.json.obj.contains("notice"), overview.body)

  test("water, mood and doses are reported in the words of the statistics, with today's vessel against the own goal"):
    val account = telegramAccount()
    grant(account, ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    assert(tracking.water.setGoal(account.accountId, Some(2000)).isRight)
    drink(account, 500)
    drink(account, 750)
    checkIn(account, 4)
    val overview = get("/api/v1/insights/overview?range=7", login(account))
    assertEquals(overview.status, 200, overview.body)
    assertEquals(blocks(overview).map(_("id").str), List("medication", "hydration", "mood"))
    assertEquals(blocks(overview).map(_("title").str), List("Medication", "Water", "Mood"))

    val medication = blocks(overview).head
    assertEquals(medication("noDataText").str, "No data in this period", "no dose is scheduled for this account")

    val water = blocks(overview)(1)
    val waterLines = water("lines").arr.toList.map(_.str)
    assertEquals(waterLines.last, "1 of 7 days have data")
    assert(waterLines.exists(_.contains("more recorded days")), s"an average needs more days: $waterLines")
    assertEquals(water("vessel")("valueMl").num.toInt, 1250)
    assertEquals(water("vessel")("goalMl").num.toInt, 2000)
    assertEquals(water("vessel")("label").str, "Water today: 1.25 of 2.0 l (62%)")

    val moodLines = blocks(overview)(2)("lines").arr.toList.map(_.str)
    assertEquals(moodLines.head, "1 check-in")
    assertEquals(moodLines.last, "1 of 7 days have data")

    val everyLine = blocks(overview).flatMap(_("lines").arr.toList.map(_.str))
    assertEquals(everyLine.flatMap(InsightPhrases.violations), Nil, "no line states a cause or judges a value")

  test("the range is 7 or 30 days; anything else is a validation error that names the member"):
    val account = telegramAccount()
    grant(account, ConsentPurpose.Hydration)
    val token = login(account)
    assertEquals(get("/api/v1/insights/overview?range=30", token).json("rangeDays").num.toInt, 30)
    val refused = get("/api/v1/insights/overview?range=90", token)
    assertProblem(refused, 422, "validation_failed")
    assertEquals(refused.fieldErrors, List("range" -> "invalid"))
    assertProblem(get("/api/v1/insights/overview", token), 400, "validation_failed")

  test("the overview needs a session, and shows only the session's account"):
    assertProblem(get("/api/v1/insights/overview?range=7"), 401, "unauthenticated")
    val owner = telegramAccount()
    val other = telegramAccount()
    grant(owner, ConsentPurpose.Hydration)
    grant(other, ConsentPurpose.Hydration)
    drink(owner, 900)
    val seenByOther = get("/api/v1/insights/overview?range=7", login(other))
    assertEquals(blocks(seenByOther).head("noDataText").str, "No data in this period")
    assert(!seenByOther.body.contains("900") && !seenByOther.body.contains("0.9"), seenByOther.body)

  test("a withdrawn consent takes the block away at once"):
    val account = telegramAccount()
    grant(account, ConsentPurpose.Mood)
    checkIn(account, 2)
    val token = login(account)
    assertEquals(blocks(get("/api/v1/insights/overview?range=7", token)).map(_("id").str), List("mood"))
    clock.advance(Duration.ofSeconds(1))
    assert(tracking.consents.withdraw(account.accountId, ConsentPurpose.Mood, clock.now()))
    assertEquals(blocks(get("/api/v1/insights/overview?range=7", token)), Nil)
end InsightsApiSuite
