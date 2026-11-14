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
import dosegram.core.ports.ConsentTaps

import java.time.Duration

/** `GET /insights/overview` (ARCHITECTURE section 9.5, insights-and-correlations): the statistics of the read API of
  * section 11.4 over HTTP, one block per domain that has consent, in the server's words. The figures are the ones the
  * chat shows: both call `StatsQueryService`.
  */
final class InsightsApiSuite extends ApiSuite:

  private def grant(account: TelegramAccount, purposes: ConsentPurpose*): Unit =
    purposes.foreach(tracking.consents.grant(account.accountId, _, ConsentNotice(1, "seeded for the test", "en"), ConsentTaps(clock.now(), clock.now())))

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

  /** Every string a person reads in a block: not the ids, tones, shapes, anchors or dates of the wire. */
  private def wordsOf(value: ujson.Value): List[String] = value match
    case ujson.Str(text) => List(text)
    case ujson.Arr(items) => items.toList.flatMap(wordsOf)
    case ujson.Obj(fields) =>
      fields.toList.collect { case (key, field) if !Set("id", "tone", "shape", "anchor", "markDates")(key) => field }
        .flatMap(wordsOf)
    case _ => Nil

  test("without a consent there is no block, and the notice says where consents are given"):
    val overview = get("/api/v1/insights/overview?range=7", login(accountWithoutConsent()))
    assertEquals(overview.status, 200, overview.body)
    assertEquals(blocks(overview), Nil)
    assertEquals(overview.json("notice").str, "Statistics appear here for the records you turned on in the chat.")
    assertEquals(overview.json("rangeDays").num.toInt, 7)
    assertEquals(overview.json("ranges").arr.toList.map(_.num.toInt), List(7, 30, 90), "the periods the client may offer")
    assertEquals(overview.json("rangeLabel").str, "7 Nov to 13 Nov")
    assertEquals(overview.header("Cache-Control"), None, "the edge, not the module, sets the cache header")

  test("a domain with consent and no record says so, and a domain without consent is absent"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Hydration)
    val overview = get("/api/v1/insights/overview?range=7", login(account))
    assertEquals(blocks(overview).map(_("id").str), List("hydration"))
    assertEquals(blocks(overview).head("noDataText").str, "No data in this period")
    assertEquals(blocks(overview).head("lines").arr.toList, Nil)
    assert(!overview.json.obj.contains("notice"), overview.body)

  test("water, mood and doses are reported in the words of the statistics, with today's vessel against the own goal"):
    val account = accountWithoutConsent()
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

  test("the range is 7, 30 or 90 days (MA-INS-1); anything else is a validation error that names the member"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Hydration)
    val token = login(account)
    assertEquals(get("/api/v1/insights/overview?range=30", token).json("rangeDays").num.toInt, 30)
    assertEquals(get("/api/v1/insights/overview?range=90", token).json("rangeDays").num.toInt, 90)
    val refused = get("/api/v1/insights/overview?range=14", token)
    assertProblem(refused, 422, "validation_failed")
    assertEquals(refused.fieldErrors, List("range" -> "invalid"))
    assertProblem(get("/api/v1/insights/overview", token), 400, "validation_failed")

  test("the overview needs a session, and shows only the session's account"):
    assertProblem(get("/api/v1/insights/overview?range=7"), 401, "unauthenticated")
    val owner = accountWithoutConsent()
    val other = accountWithoutConsent()
    grant(owner, ConsentPurpose.Hydration)
    grant(other, ConsentPurpose.Hydration)
    drink(owner, 900)
    val seenByOther = get("/api/v1/insights/overview?range=7", login(other))
    assertEquals(blocks(seenByOther).head("noDataText").str, "No data in this period")
    assert(!seenByOther.body.contains("900") && !seenByOther.body.contains("0.9"), seenByOther.body)

  test("a withdrawn consent takes the block away at once"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Mood)
    checkIn(account, 2)
    val token = login(account)
    assertEquals(blocks(get("/api/v1/insights/overview?range=7", token)).map(_("id").str), List("mood"))
    clock.advance(Duration.ofSeconds(1))
    assert(tracking.consents.withdraw(account.accountId, ConsentPurpose.Mood, clock.now()))
    assertEquals(blocks(get("/api/v1/insights/overview?range=7", token)), Nil)
  // ---------- GET /insights/month and GET /insights/correlations ----------

  test("the month without a consent has no block, says where consents are given, and names the months around it"):
    val month = get("/api/v1/insights/month", login(accountWithoutConsent()))
    assertEquals(month.status, 200, month.body)
    assertEquals(month.json("month").str, "2026-11")
    assertEquals(month.json("previousMonth").str, "2026-10")
    assert(!month.json.obj.contains("nextMonth"), "the current month has no next month")
    assertEquals(month.json("domains").arr.toList, Nil)
    assertEquals(month.json("medications").arr.toList, Nil)
    assertEquals(month.json("notice").str, "Statistics appear here for the records you turned on in the chat.")

  test("a domain short of 14 days shows the gate with its progress, never a figure (R-INS-11)"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    drink(account, 900)
    checkIn(account, 4)
    val month = get("/api/v1/insights/month?month=2026-11", login(account))
    assertEquals(month.status, 200, month.body)
    assertEquals(month.json("reportable").bool, false)
    val domains = month.json("domains").arr.toList
    assertEquals(domains.map(_("id").str), List("hydration", "mood"))
    assertEquals(domains.map(_("meetsGate").bool), List(false, false))
    assertEquals(
      domains.head("gateText").str,
      "Water: 13 more days with records for the month's figures (1 of 14)"
    )
    assertEquals(domains.head("lines").arr.toList, Nil)
    assertEquals(month.json("summary").str, "1 day with records")
    assertEquals(InsightPhrases.violations(month.body), Nil)

  test("a month after the current one or of another shape is a validation error"):
    val token = login(accountWithoutConsent())
    assertEquals(get("/api/v1/insights/month?month=2026-12", token).fieldErrors, List("month" -> "in_future"))
    assertEquals(get("/api/v1/insights/month?month=november", token).fieldErrors, List("month" -> "invalid"))
    assertEquals(get("/api/v1/insights/month?month=2026-10", token).json("nextMonth").str, "2026-11")

  test("the patterns run no comparison between areas without its consent, and say where it is given"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    drink(account, 900)
    val list = get("/api/v1/insights/correlations", login(account))
    assertEquals(list.status, 200, list.body)
    assertEquals(list.json("crossDomain").bool, false)
    assertEquals(list.json("windowDays").num.toInt, 28)
    assertEquals(list.json("items").arr.toList, Nil)
    assert(list.json("notice").str.startsWith("Comparisons between areas are off."), list.body)

  test("the month and the patterns need a session"):
    assertProblem(get("/api/v1/insights/month"), 401, "unauthenticated")
    assertProblem(get("/api/v1/insights/correlations"), 401, "unauthenticated")

  test("90 days are drawn by week, and a chart of days names the day of every mark so a pick opens it"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    drink(account, 600)
    checkIn(account, 4)
    val token = login(account)
    val week = blocks(get("/api/v1/insights/overview?range=7", token))
    val waterByDay = week.head("charts").arr.head
    assertEquals(waterByDay("id").str, "water-by-day")
    val dates = waterByDay("markDates").arr.toList.map(_.str)
    assertEquals(dates.size, waterByDay("marks").arr.size)
    assertEquals(dates.last, "2026-11-13")
    val quarter = blocks(get("/api/v1/insights/overview?range=90", token))
    assertEquals(quarter.map(_("charts").arr.head("id").str), List("water-by-week", "mood-by-week"))
    val waterByWeek = quarter.head("charts").arr.head
    assertEquals(waterByWeek("marks").arr.size, 13, "12 whole weeks and the 6 days before them")
    assert(!waterByWeek.obj.contains("markDates"), "a week is not one day")
    assert(waterByWeek("marks").arr.last.str.endsWith(": average 0.6 l a day on 1 day"), waterByWeek.toString)

  test("every block names the previous period of the same length in words, with no arrow and no change in percent"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Medication, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.Sleep, ConsentPurpose.Nutrition)
    val overview = get("/api/v1/insights/overview?range=30", login(account))
    assertEquals(overview.status, 200, overview.body)
    assertEquals(blocks(overview).map(_("id").str), List("medication", "hydration", "mood", "sleep", "nutrition"))
    assertEquals(blocks(overview).last("title").str, "Food")
    assertEquals(
      blocks(overview).map(_("comparison").str),
      List.fill(5)("Previous 30 days: No data"),
      "a previous period without a record is No data (MA-INS-4)"
    )
    assertEquals(InsightPhrases.violations(overview.body), Nil)
    assert(!overview.body.contains("↑") && !overview.body.contains("↓"), overview.body)

  test("mood by day of the week stays hidden until a weekday has 3 days with a check-in"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Mood)
    checkIn(account, 3)
    val mood = blocks(get("/api/v1/insights/overview?range=30", login(account))).head
    assertEquals(mood("charts").arr.toList.map(_("id").str), List("mood-by-day"))
    assert(!mood.obj.contains("spark"), "one day has no line")

  test("the dashboard answers in the account's language: titles, marks, tables and the previous period (G2.22)"):
    val account = accountWithoutConsent("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration, ConsentPurpose.Mood, ConsentPurpose.Nutrition)
    drink(account, 600)
    checkIn(account, 4)
    val token = login(account)
    assertEquals(post("/api/v1/meals", ujson.Obj("mealType" -> "lunch"), token, newKey()).status, 201)
    val read = get("/api/v1/settings", token)
    val body = ujson.Obj("language" -> "uk", "timeZone" -> "Europe/Kyiv", "imageTheme" -> "auto")
    val chosen = patch("/api/v1/settings", body, token, ifMatch(read))
    assertEquals(chosen.status, 200, chosen.body)
    val English = List(
      "Previous", "per week", "per day", "by week", "average", "no entry", "logged", "check-in", "Week", "Value",
      "Meals", "Mood", "Water", "Food", "Breakfast", "Pleasant", "pleasant", "Neutral", "of the week", "taken"
    )
    List(7, 30, 90).foreach: range =>
      val overview = get(s"/api/v1/insights/overview?range=$range", token)
      assertEquals(overview.status, 200, overview.body)
      val words = blocks(overview).flatMap(wordsOf).mkString("\n")
      English.foreach(word => assert(!words.contains(word), s"range $range: '$word' in $words"))
      assert(words.contains("Попередні"), words)

  test("the Ukrainian dashboard and month name their counts in the case of the number, with one full stop (G2.22)"):
    val account = accountWithoutConsent("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    assert(tracking.water.setGoal(account.accountId, Some(500)).isRight)
    drink(account, 600)
    checkIn(account, 4)
    val token = login(account)
    val read = get("/api/v1/settings", token)
    val body = ujson.Obj("language" -> "uk", "timeZone" -> "Europe/Kyiv", "imageTheme" -> "auto")
    assertEquals(patch("/api/v1/settings", body, token, ifMatch(read)).status, 200)
    val words = blocks(get("/api/v1/insights/overview?range=30", token)).flatMap(wordsOf).mkString("\n")
    assert(words.contains("Ціль досягнуто в 1 з 1 дня із записами."), words)
    assert(!words.contains(".."), words)
    val month = get("/api/v1/insights/month?month=2026-11", token)
    assertEquals(month.status, 200, month.body)
    val gates = month.json("domains").arr.toList.map(_("gateText").str)
    assert(gates.head.startsWith("Вода: "), gates.toString)
    assert(gates(1).startsWith("Настрій: "), gates.toString)

  // Last in the suite: it moves the shared clock on by a week.
  test("a record of the week before is the previous period of the 7-day view and not part of its own figures"):
    val account = accountWithoutConsent()
    grant(account, ConsentPurpose.Hydration)
    drink(account, 800)
    clock.advance(Duration.ofDays(7))
    val water = blocks(get("/api/v1/insights/overview?range=7", login(account))).head
    assertEquals(water("noDataText").str, "No data in this period")
    assertEquals(water("comparison").str, "Previous 7 days: water logged on 1 day")
end InsightsApiSuite
