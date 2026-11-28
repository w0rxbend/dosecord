package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.mood.CheckIn
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.YearCopy
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps

import java.time.LocalDate
import java.time.ZoneOffset

/** `GET /insights/year` (MINI_APP section 7.11, the Year tab; supervisor decision, 2026-11-28): the year of `/stats
  * year` from the same source, its lines word for word, a table of months per area and, for water, food, sleep and mood
  * but never medication (R-GAM-2), the days with a record week by week as a chart with its table.
  */
final class InsightYearApiSuite extends ApiSuite:

  private def grant(account: TelegramAccount, purposes: ConsentPurpose*): Unit =
    val notice = ConsentNotice(1, "seeded for the test", "en")
    purposes.foreach(tracking.consents.grant(account.accountId, _, notice, ConsentTaps(clock.now(), clock.now())))

  /** A record on each of `dates`, made with the clock on that day at 10:00 UTC, then the clock as it was. */
  private def onDays(dates: Seq[LocalDate])(record: => Unit): Unit =
    val now = clock.now()
    try dates.foreach { date => clock.at = date.atTime(10, 0).toInstant(ZoneOffset.UTC); record }
    finally clock.at = now

  private def drink(account: TelegramAccount): Unit =
    val amount = WaterAmount.ofMillilitres(1500).toOption.get
    val key = s"test:${java.util.UUID.randomUUID()}"
    assert(tracking.water.log(LogWater(account.accountId, amount, EntryDay.Today, EntrySource.MiniApp, key)).isRight)

  private def checkIn(account: TelegramAccount): Unit =
    val key = s"test:${java.util.UUID.randomUUID()}"
    val command = CheckIn(account.accountId, Valence.of(4).get, None, Nil, EntryDay.Today, EntrySource.MiniApp, key)
    assert(tracking.moods.checkIn(command).isRight)

  private def october(count: Int): Seq[LocalDate] = (1 to count).map(LocalDate.of(2026, 10, _))

  /** What `/stats year` prints for the account now: the chat's own source and copy. */
  private def chatLines(account: TelegramAccount, countsOn: Boolean): List[String] =
    uow.transactionAs(account.accountId): tx =>
      val view = tracking.statsService.year(tx, account.accountId, 2026).toOption.get
      if countsOn then YearCopy.lines(view.figures, view.zone) else YearCopy.linesOmittingGates(view.figures, view.zone)

  private def areas(year: Reply): List[ujson.Value] = year.json("areas").arr.toList

  test("the year is the one /stats year prints, word for word, with a table of months for each area"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration, ConsentPurpose.Mood)
    onDays(october(15))(drink(account))
    onDays(october(3))(checkIn(account))
    val year = get("/api/v1/insights/year", login(account))
    assertEquals(year.status, 200, year.body)
    assertEquals(year.json("year").num.toInt, 2026)
    assertEquals(year.json("lines").arr.map(_.str).toList, chatLines(account, countsOn = true))
    assertEquals(year.json("daysWithRecords").num.toInt, 15)
    assertEquals(year.json("days").num.toInt, LocalDate.parse("2026-11-13").getDayOfYear)
    assertEquals(year.json("summary").str, "Days with records: 15 of 317")
    assertEquals(year.json("gateNote").str, YearCopy.gateNote)
    assert(!year.json.obj.contains("previousYear"), "no record in 2025")
    assert(!year.json.obj.contains("nextYear"), "the current year has no next one")
    assertEquals(areas(year).map(_("id").str), List("medication", "hydration", "mood"))
    val water = areas(year)(1)
    assertEquals(water("title").str, "Water, a day with records")
    val months = water("months").arr.toList
    assertEquals(months.map(_("month").str), (1 to 11).map(m => f"2026-$m%02d").toList)
    val oct = months(9)
    assertEquals((oct("label").str, oct("recordedDays").num.toInt, oct("text").str), ("Oct", 15, "1.5 l"))
    assertEquals(months(8)("text").str, "—", "a month without a record")
    val mood = areas(year)(2)("months").arr(9)
    assertEquals(mood("text").str, "(3)", "below the 14-day gate the recorded days in brackets")
    assertEquals(water("total").str, "2026: 1.5 l (15 days)")
    assertEquals(InsightPhrases.violations(year.body), Nil)

  test("water, food, sleep and mood draw their weeks; medication never does (R-GAM-2)"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    onDays(october(9))(drink(account))
    val year = get("/api/v1/insights/year", login(account))
    val byId = areas(year).map(area => area("id").str -> area).toMap
    assert(!byId("medication").obj.contains("heat"), byId("medication").toString)
    val heat = byId("hydration")("heat")
    val weeks = 46 // 1 January 2026 is a Thursday; 13 November is in the 46th Monday week.
    assertEquals(heat("marks").arr.size, weeks)
    assertEquals(heat("table")("rows").arr.size, weeks)
    assertEquals(heat("table")("columns").arr.map(_.str).toList, List("Week", "Days with a record"))
    // 1 to 4 October is the end of the week of 28 September; 5 to 9 October the start of the next.
    assertEquals(heat("marks").arr(39).str, "28 Sep to 4 Oct: 4 of 7 days with a record")
    assertEquals(heat("marks").arr(40).str, "5 Oct to 11 Oct: 5 of 7 days with a record")
    assertEquals(heat("marks").arr(0).str, "1 Jan to 4 Jan: no day with a record")
    assert(heat("summary").str.contains("9 of 317 days"), heat("summary").str)
    val nodes = heat("scene")("nodes").arr
    assert(nodes.size <= 400, s"${nodes.size} nodes, over the budget of a scene (MA-INS-5)")
    assertEquals(nodes.count(_.obj.get("shape").map(_.str).contains("hit")), weeks, "every week can be picked")
    assert(nodes.forall(node => !node.obj.get("tone").map(_.str).contains("domain-medication")))

  test("with counts off the year drops the minimum-data gates as the chat does (R-GAM-5)"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Mood)
    onDays(october(3))(checkIn(account))
    uow.transactionAs(account.accountId)(_.accounts.setGamification(account.accountId, false, clock.now()))
    val year = get("/api/v1/insights/year", login(account))
    assertEquals(year.status, 200, year.body)
    assertEquals(year.json("lines").arr.map(_.str).toList, chatLines(account, countsOn = false))
    assert(!year.json.obj.contains("gateNote"), year.body)
    assertEquals(areas(year).find(_("id").str == "mood").get("months").arr(9)("text").str, "—")

  test("an earlier year names the next one; a year after the current one or of another shape is refused"):
    val account = telegramAccount("Europe/Kyiv")
    val token = login(account)
    val earlier = get("/api/v1/insights/year?year=2025", token)
    assertEquals(earlier.status, 200, earlier.body)
    assertEquals((earlier.json("year").num.toInt, earlier.json("nextYear").num.toInt), (2025, 2026))
    assertEquals(earlier.json("days").num.toInt, 365)
    assertEquals(earlier.json("notice").str, "Nothing was recorded in 2025.")
    assertEquals(get("/api/v1/insights/year?year=2027", token).fieldErrors, List("year" -> "in_future"))
    assertEquals(get("/api/v1/insights/year?year=1969", token).fieldErrors, List("year" -> "invalid"))
    assertProblem(get("/api/v1/insights/year?year=last", token), 400, "validation_failed")

  test("without a consent the year has no area and says where consents are given"):
    val year = get("/api/v1/insights/year", login(accountWithoutConsent()))
    assertEquals(year.status, 200, year.body)
    assertEquals(areas(year), Nil)
    assertEquals(year.json("notice").str, "Statistics appear here for the records you turned on in the chat.")

  test("the year answers in the account's language (G2.22) and needs a session"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    onDays(october(2))(drink(account))
    uow.transactionAs(account.accountId)(tx =>
      tx.accounts.saveSettings(
        account.accountId,
        tx.accounts.settingsOf(account.accountId).get.copy(locale = Some("uk")),
        clock.now()
      )
    )
    val year = get("/api/v1/insights/year", login(account))
    assertEquals(year.status, 200, year.body)
    val ukrainian = Lang.within(Lang.Uk)(chatLines(account, countsOn = true))
    assertEquals(year.json("lines").arr.map(_.str).toList, ukrainian)
    assertEquals(areas(year).find(_("id").str == "hydration").get("months").arr(9)("label").str, "жовт.")
    assertProblem(get("/api/v1/insights/year"), 401, "unauthenticated")
end InsightYearApiSuite
