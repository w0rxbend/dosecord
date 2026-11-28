package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.application.hydration.LogWater
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.CompareCopy
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.CompareSelection
import dosegram.core.domain.insights.MinimumSample
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** `GET /insights/compare` (MINI_APP section 7.11, the Compare view; supervisor decision, 2026-11-28): two periods of
  * `/stats compare` from the same source, its lines word for word, and per area both cells and the difference.
  */
final class InsightCompareApiSuite extends ApiSuite:

  private def grant(account: TelegramAccount, purposes: ConsentPurpose*): Unit =
    val notice = ConsentNotice(1, "seeded for the test", "en")
    purposes.foreach(tracking.consents.grant(account.accountId, _, notice, ConsentTaps(clock.now(), clock.now())))

  /** A record on each of `dates`, made with the clock on that day at 10:00 UTC, then the clock as it was. */
  private def onDays(dates: Seq[LocalDate])(record: => Unit): Unit =
    val now = clock.now()
    try dates.foreach { date => clock.at = date.atTime(10, 0).toInstant(ZoneOffset.UTC); record }
    finally clock.at = now

  private def drink(account: TelegramAccount, millilitres: Int): Unit =
    val amount = WaterAmount.ofMillilitres(millilitres).toOption.get
    val key = s"test:${java.util.UUID.randomUUID()}"
    assert(tracking.water.log(LogWater(account.accountId, amount, EntryDay.Today, EntrySource.MiniApp, key)).isRight)

  private def days(month: Int, from: Int, to: Int): Seq[LocalDate] = (from to to).map(LocalDate.of(2026, month, _))

  /** What `/stats compare` prints for the account now: the chat's own source and copy. */
  private def chatLines(account: TelegramAccount, selection: CompareSelection): List[String] =
    uow.transactionAs(account.accountId): tx =>
      val view = tracking.statsService.compare(tx, account.accountId, selection).toOption.get
      CompareCopy.lines(view.figures, view.zone, keepGates = tx.accounts.gamificationOf(account.accountId))

  private def areas(reply: Reply): List[ujson.Value] = reply.json("areas").arr.toList

  test("last week and this week are the ones /stats compare prints, word for word, with both cells per area"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    // 13 November 2026 is a Friday: last week is 2 to 8 November, this week 9 to 13 November.
    onDays(days(11, 2, 8))(drink(account, 1600))
    onDays(days(11, 9, 12))(drink(account, 1800))
    val compare = get("/api/v1/insights/compare", login(account))
    assertEquals(compare.status, 200, compare.body)
    assertEquals(compare.json("selection").str, "weeks")
    assertEquals(compare.json("lines").arr.map(_.str).toList, chatLines(account, CompareSelection.Weeks))
    assertEquals(compare.json("earlier")("start").str, "2026-11-02")
    assertEquals(compare.json("later")("end").str, "2026-11-13")
    assertEquals(compare.json("later")("daysWithRecords").num.toInt, 4)
    assertEquals(compare.json("gateNote").str, CompareCopy.gateNote(MinimumSample.WeeklyMeanDays))
    assertEquals(areas(compare).map(_("id").str), List("medication", "hydration"))
    val water = areas(compare)(1)
    assertEquals(water("title").str, "Water, a day with records")
    assertEquals(water("earlier")("text").str, "1.6 l (7 days)")
    assertEquals(water("earlier")("value").num, 1600.0)
    assertEquals(water("later")("figure").str, "1.8 l")
    assertEquals(water("difference").str, "0.2 l higher")
    assert(!areas(compare).head.obj.contains("difference"), "no figure on either side of medication")
    assertEquals(compare.json("months").arr.head("month").str, "2026-11")
    assertEquals(compare.json("months").arr.size, CompareSelection.PickerMonths)
    assertEquals(InsightPhrases.violations(compare.body), Nil)

  test("this month against the last, and two months of a pair in either order"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    onDays(days(9, 1, 20))(drink(account, 1500))
    onDays(days(10, 1, 14))(drink(account, 2000))
    val months = get("/api/v1/insights/compare?period=months", login(account))
    assertEquals(months.status, 200, months.body)
    assertEquals(months.json("lines").arr.map(_.str).toList, chatLines(account, CompareSelection.Months))
    assertEquals(areas(months)(1)("later")("text").str, "—")
    val pair = get("/api/v1/insights/compare?first=2026-10&second=2026-09", login(account))
    assertEquals(pair.status, 200, pair.body)
    assertEquals(pair.json("selection").str, "pair")
    val expected = CompareSelection.twoMonths(YearMonth.of(2026, 9), YearMonth.of(2026, 10)).get
    assertEquals(pair.json("lines").arr.map(_.str).toList, chatLines(account, expected))
    assertEquals((pair.json("earlier")("label").str, pair.json("later")("label").str), ("Sep 2026", "Oct 2026"))
    assertEquals(areas(pair)(1)("difference").str, "0.5 l higher")

  test("a month ahead, the same month twice, a missing or malformed month and an unknown period are refused"):
    val token = login(telegramAccount("Europe/Kyiv"))
    assertEquals(get("/api/v1/insights/compare?first=2026-10&second=2026-12", token).fieldErrors, List("second" -> "in_future"))
    assertEquals(get("/api/v1/insights/compare?first=2026-10&second=2026-10", token).fieldErrors, List("second" -> "invalid"))
    assertEquals(get("/api/v1/insights/compare?first=2026-10", token).fieldErrors, List("second" -> "required"))
    assertEquals(get("/api/v1/insights/compare?first=October&second=2026-10", token).fieldErrors, List("first" -> "invalid"))
    assertEquals(get("/api/v1/insights/compare?period=years", token).fieldErrors, List("period" -> "invalid"))

  test("without a consent the comparison has no area and says where consents are given"):
    val compare = get("/api/v1/insights/compare", login(accountWithoutConsent()))
    assertEquals(compare.status, 200, compare.body)
    assertEquals(areas(compare), Nil)
    assertEquals(compare.json("notice").str, "Statistics appear here for the records you turned on in the chat.")

  test("with counts off the gates are left out as the chat does (R-GAM-5)"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    onDays(days(11, 2, 8))(drink(account, 1600))
    uow.transactionAs(account.accountId)(_.accounts.setGamification(account.accountId, false, clock.now()))
    val compare = get("/api/v1/insights/compare", login(account))
    assertEquals(compare.json("lines").arr.map(_.str).toList, chatLines(account, CompareSelection.Weeks))
    assert(!compare.json.obj.contains("gateNote"), compare.body)
    assertEquals(areas(compare)(1)("earlier")("text").str, "1.6 l")

  test("the comparison answers in the account's language (G2.22) and needs a session"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    onDays(days(11, 2, 8))(drink(account, 1600))
    uow.transactionAs(account.accountId)(tx =>
      tx.accounts.saveSettings(
        account.accountId,
        tx.accounts.settingsOf(account.accountId).get.copy(locale = Some("uk")),
        clock.now()
      )
    )
    val compare = get("/api/v1/insights/compare", login(account))
    assertEquals(compare.status, 200, compare.body)
    assertEquals(compare.json("lines").arr.map(_.str).toList, Lang.within(Lang.Uk)(chatLines(account, CompareSelection.Weeks)))
    assertEquals(compare.json("earlier")("label").str, "Минулий тиждень")
    assertProblem(get("/api/v1/insights/compare"), 401, "unauthenticated")
end InsightCompareApiSuite
