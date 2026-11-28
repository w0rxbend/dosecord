package dosegram.api

import dosegram.api.ApiSuite.TelegramAccount
import dosegram.core.application.hydration.LogWater
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.InsightPhrases
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.RhythmCopy
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps

import java.time.LocalDate
import java.time.ZoneOffset

/** `GET /insights/rhythm` (MINI_APP section 7.11, the Rhythm view; supervisor decision, 2026-11-28): the rhythm of the
  * day of `/stats rhythm` from the same source, its lines word for word, and per area its 24 hours.
  */
final class InsightRhythmApiSuite extends ApiSuite:

  private def grant(account: TelegramAccount, purposes: ConsentPurpose*): Unit =
    val notice = ConsentNotice(1, "seeded for the test", "en")
    purposes.foreach(tracking.consents.grant(account.accountId, _, notice, ConsentTaps(clock.now(), clock.now())))

  /** A glass on each of the `days` days before the clock's day, at 07:00 UTC (09:00 in Kyiv), then the clock as it was.
    */
  private def drinkOn(account: TelegramAccount, days: Int): Unit =
    val now = clock.now()
    val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
    try
      (1 to days).foreach: back =>
        clock.at = today.minusDays(back.toLong).atTime(7, 0).toInstant(ZoneOffset.UTC)
        val amount = WaterAmount.ofMillilitres(250).toOption.get
        val key = s"test:${java.util.UUID.randomUUID()}"
        assert(tracking.water.log(LogWater(account.accountId, amount, EntryDay.Today, EntrySource.MiniApp, key)).isRight)
    finally clock.at = now

  /** What `/stats rhythm` prints for the account now: the chat's own source and copy. */
  private def chatLines(account: TelegramAccount): List[String] =
    uow.transactionAs(account.accountId): tx =>
      val view = tracking.statsService.rhythm(tx, account.accountId).toOption.get
      RhythmCopy.lines(view.figures, view.zone, keepGates = tx.accounts.gamificationOf(account.accountId))

  private def areas(reply: Reply): List[ujson.Value] = reply.json("areas").arr.toList

  test("the rhythm is the one /stats rhythm prints, word for word, with each area's 24 hours"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    drinkOn(account, 14)
    val rhythm = get("/api/v1/insights/rhythm", login(account))
    assertEquals(rhythm.status, 200, rhythm.body)
    assertEquals(rhythm.json("lines").arr.map(_.str).toList, chatLines(account))
    assertEquals(rhythm.json("days").num.toInt, 30)
    assertEquals(rhythm.json("daysWithRecords").num.toInt, 14)
    assertEquals(rhythm.json("gateNote").str, RhythmCopy.gateNote)
    assertEquals(areas(rhythm).map(_("id").str), List("medication", "hydration"))
    val water = areas(rhythm)(1)
    assertEquals(water("title").str, "Water")
    assertEquals(water("counts").str, "Water entries")
    assertEquals(water("days").str, "14 days")
    assertEquals(water("hours").arr.map(_.num.toInt).toList, List.tabulate(24)(hour => if hour == 9 then 14 else 0))
    assertEquals(water("summary").str, "14 entries, most often 09:00 to 10:00 · morning 14, afternoon 0, evening 0, night 0")
    val medication = areas(rhythm).head
    assert(!medication.obj.contains("hours"), "no dose: no pattern")
    assertEquals(medication("text").str, "Doses: —")
    assertEquals(InsightPhrases.violations(rhythm.body), Nil)

  test("below the gate an area has its recorded days and no hours"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    drinkOn(account, 6)
    val water = areas(get("/api/v1/insights/rhythm", login(account)))(1)
    assertEquals(water("recordedDays").num.toInt, 6)
    assertEquals(water("text").str, "Water: records on 6 days")
    assert(!water.obj.contains("hours"), water.toString)

  test("without a consent the rhythm has no area and says where consents are given"):
    val rhythm = get("/api/v1/insights/rhythm", login(accountWithoutConsent()))
    assertEquals(rhythm.status, 200, rhythm.body)
    assertEquals(areas(rhythm), Nil)
    assertEquals(rhythm.json("notice").str, "Statistics appear here for the records you turned on in the chat.")

  test("with counts off the gate and the days are left out as the chat does (R-GAM-5)"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    drinkOn(account, 14)
    uow.transactionAs(account.accountId)(_.accounts.setGamification(account.accountId, false, clock.now()))
    val rhythm = get("/api/v1/insights/rhythm", login(account))
    assertEquals(rhythm.json("lines").arr.map(_.str).toList, chatLines(account))
    assert(!rhythm.json.obj.contains("gateNote"), rhythm.body)
    assert(areas(rhythm)(1)("text").str.startsWith("Water: 14 entries"), rhythm.body)

  test("the rhythm answers in the account's language (G2.22) and needs a session"):
    val account = telegramAccount("Europe/Kyiv")
    grant(account, ConsentPurpose.Hydration)
    drinkOn(account, 14)
    uow.transactionAs(account.accountId)(tx =>
      tx.accounts.saveSettings(
        account.accountId,
        tx.accounts.settingsOf(account.accountId).get.copy(locale = Some("uk")),
        clock.now()
      )
    )
    val rhythm = get("/api/v1/insights/rhythm", login(account))
    assertEquals(rhythm.status, 200, rhythm.body)
    assertEquals(rhythm.json("lines").arr.map(_.str).toList, Lang.within(Lang.Uk)(chatLines(account)))
    assertEquals(areas(rhythm)(1)("title").str, "Вода")
    assertProblem(get("/api/v1/insights/rhythm"), 401, "unauthenticated")
end InsightRhythmApiSuite
