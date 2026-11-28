package dosegram.api

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.application.settings.AccountSettingsEdits
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ImageTheme

import java.time.ZoneId

/** The settings and consent routes of ARCHITECTURE section 9.5 (identity-and-consent, schedule-and-reminders) over
  * HTTP. A settings edit is made on the version the client read (API-17); a consent is read from the ledger and is
  * never recorded or withdrawn by a route alone (MINI_APP MA-ROLE-2, MA-STEP-1).
  */
final class SettingsApiSuite extends ApiSuite:

  private val everyDayAtNine = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))

  private def settingsBody(timeZone: String, theme: String = "auto", language: String = "en"): ujson.Obj =
    ujson.Obj("language" -> language, "timeZone" -> timeZone, "imageTheme" -> theme)

  private def animatedBody(animated: ujson.Value): ujson.Obj =
    val body = settingsBody("Europe/Kyiv")
    body("animatedMood") = animated
    body

  test("the settings carry the language, the zone and the theme of the images; an edit needs the version read"):
    val token = login(telegramAccount("Europe/Kyiv"))
    val read = get("/api/v1/settings", token)
    assertEquals(read.status, 200, read.body)
    assertEquals(
      (read.json("language").str, read.json("timeZone").str, read.json("imageTheme").str),
      ("en", "Europe/Kyiv", "auto")
    )
    assertEquals(read.json("languages").arr.map(_("code").str).toList, List("en", "uk"))

    val saved = patch("/api/v1/settings", settingsBody("Europe/Kyiv", "dark"), token, ifMatch(read))
    assertEquals(saved.status, 200, saved.body)
    assertEquals(saved.json("imageTheme").str, "dark")
    assertEquals(get("/api/v1/settings", token).json("imageTheme").str, "dark")
    assertProblem(patch("/api/v1/settings", settingsBody("Europe/Kyiv"), token, ifMatch(read)), 412, "version_conflict")
    assertProblem(patch("/api/v1/settings", settingsBody("Europe/Kyiv"), token), 428, "precondition_required")

    val refused = patch("/api/v1/settings", settingsBody("Mars/Olympus", language = "xx"), token, ifMatch(saved))
    assertProblem(refused, 422, "validation_failed")
    assertEquals(refused.fieldErrors.toSet, Set("language" -> "unsupported", "timeZone" -> "unknown"))
    assertProblem(patch("/api/v1/settings", settingsBody("Europe/Kyiv", "sepia"), token, ifMatch(saved)), 400, "validation_failed")

  test("Animated mood is off for a new account, is switched with the settings, and a body without it keeps it"):
    val token = login(telegramAccount("Europe/Kyiv"))
    val read = get("/api/v1/settings", token)
    assertEquals(read.json("animatedMood").bool, false)
    val on = patch("/api/v1/settings", animatedBody(ujson.True), token, ifMatch(read))
    assertEquals(on.status, 200, on.body)
    assertEquals(on.json("animatedMood").bool, true)
    assertNotEquals(on.json("version").num, read.json("version").num, "the choice is part of the version")
    val kept = patch("/api/v1/settings", settingsBody("Europe/Kyiv", "dark"), token, ifMatch(on))
    assertEquals((kept.json("animatedMood").bool, kept.json("imageTheme").str), (true, "dark"))
    val off = patch("/api/v1/settings", animatedBody(ujson.False), token, ifMatch(kept))
    assertEquals(get("/api/v1/settings", token).json("animatedMood").bool, false, off.body)
    assertProblem(
      patch("/api/v1/settings", animatedBody(ujson.Str("yes")), token, ifMatch(off)),
      400,
      "validation_failed"
    )

  test("Counts in reports and the notes after updates are switched with the settings, as the chat's /settings does"):
    val account = telegramAccount("Europe/Kyiv")
    val token = login(account)
    val read = get("/api/v1/settings", token)
    assertEquals((read.json("countsInReports").bool, read.json("whatsNew").bool), (true, true), "both on by default")
    val body = settingsBody("Europe/Kyiv")
    body("countsInReports") = false
    body("whatsNew") = false
    val off = patch("/api/v1/settings", body, token, ifMatch(read))
    assertEquals(off.status, 200, off.body)
    assertEquals((off.json("countsInReports").bool, off.json("whatsNew").bool), (false, false))
    assertNotEquals(off.json("version").num, read.json("version").num, "both are part of the version")
    val stored = uow.transactionAs(account.accountId)(tx =>
      (tx.accounts.gamificationOf(account.accountId), tx.accounts.whatsNewOf(account.accountId).enabled)
    )
    assertEquals(stored, (false, false), "the columns the chat's switches write")
    val kept = patch("/api/v1/settings", settingsBody("Europe/Kyiv", "dark"), token, ifMatch(off))
    assertEquals((kept.json("countsInReports").bool, kept.json("whatsNew").bool), (false, false), kept.body)
    // The chat's switch moves the value the app reads and its version.
    uow.transactionAs(account.accountId)(tx =>
      AccountSettingsEdits(clock).setCounts(tx, account.accountId, AuditChannel.Bot, on = true)
    )
    val after = get("/api/v1/settings", token)
    assertEquals(after.json("countsInReports").bool, true)
    assertNotEquals(after.header("ETag"), kept.header("ETag"))

  test("every value the chat's /settings sets is a member the app's settings read (BOT_UX 17, supervisor 2026-11-28)"):
    // A new switch of the hub fails here until the app reads its value: an entry either sets a value, read from the
    // document and member named, or only opens a page of the hub.
    val opensAPage = Set(
      "settings.open",
      "settings.day",
      "settings.anchor_pick",
      "settings.work_pick",
      "settings.quiet_pick",
      "settings.privacy"
    )
    val readFrom = Map(
      "settings.language" -> ("/api/v1/settings", "language"),
      "settings.timezone" -> ("/api/v1/settings", "timeZone"),
      "settings.theme" -> ("/api/v1/settings", "imageTheme"),
      "settings.animated" -> ("/api/v1/settings", "animatedMood"),
      "settings.counts" -> ("/api/v1/settings", "countsInReports"),
      "settings.whatsnew" -> ("/api/v1/settings", "whatsNew"),
      "settings.discreet" -> ("/api/v1/notification-settings", "discreet"),
      "settings.quiet_set" -> ("/api/v1/reminders/day", "quietStart"),
      "settings.anchor_set" -> ("/api/v1/reminders/day", "wake"),
      "settings.work_set" -> ("/api/v1/reminders/day", "workDays")
    )
    val hub = dosegram.core.chat.ActionRegistry.entries.map(_.name).filter(_.startsWith("settings.")).toSet
    assertEquals(hub -- opensAPage -- readFrom.keySet, Set.empty[String], "a switch of the hub the app does not read")
    val token = login(telegramAccount("Europe/Kyiv"))
    readFrom.values.toList.distinct.foreach: (path, member) =>
      val read = get(path, token)
      assertEquals(read.status, 200, s"$path: ${read.body}")
      assert(read.json.obj.contains(member), s"$path has no $member: ${read.body}")

  test("the languages offered are the chat's languages, each named as the chat's /settings names it"):
    val read = get("/api/v1/settings", login(accountWithoutConsent()))
    assertEquals(
      read.json("languages").arr.map(choice => choice("code").str -> choice("label").str).toList,
      dosegram.core.domain.copy.Lang.values.toList.map(lang =>
        lang.code -> dosegram.core.domain.copy.LanguageCopy.name(lang)
      )
    )

  test("Ukrainian is offered in its own name; once chosen, the bootstrap and the server's sentences follow (G2.22)"):
    val account = telegramAccount("Europe/Kyiv")
    val token = login(account)
    val read = get("/api/v1/settings", token)
    assertEquals(read.json("languages").arr.map(_("label").str).toList, List("English", "Українська"))
    assertEquals(exchange(initDataFor(account.telegramUserId)).json("bootstrap")("language").str, "en")
    val chosen = patch("/api/v1/settings", settingsBody("Europe/Kyiv", language = "uk"), token, ifMatch(read))
    assertEquals(chosen.status, 200, chosen.body)
    assertEquals(chosen.json("language").str, "uk")
    val again = exchange(initDataFor(account.telegramUserId))
    assertEquals(again.json("bootstrap")("language").str, "uk")
    val consents = get("/api/v1/consents", again.json("token").str)
    assertEquals(consents.status, 200, consents.body)
    assert(consents.body.contains("Щоб відкликати згоду, надішліть /privacy у чаті."), consents.body)

  test("an account still on the default UTC zone changes its language and theme without choosing a region zone"):
    val token = login(telegramAccount("UTC"))
    val read = get("/api/v1/settings", token)
    assertEquals(read.json("timeZone").str, "UTC")
    val chosen = patch("/api/v1/settings", settingsBody("UTC", "dark", language = "uk"), token, ifMatch(read))
    assertEquals(chosen.status, 200, chosen.body)
    assertEquals((chosen.json("language").str, chosen.json("imageTheme").str), ("uk", "dark"))
    // A zone that is not a region zone is still refused when it is a change.
    val moved = patch("/api/v1/settings", settingsBody("Etc/GMT+5"), token, ifMatch(chosen))
    assertProblem(moved, 422, "validation_failed")
    assertEquals(moved.fieldErrors.toSet, Set("timeZone" -> "unknown"))

  test("a new time zone is previewed first, then moves the schedules that follow the account"):
    val account = telegramAccount("Europe/Kyiv")
    fixtures.schedule(clock, account.accountId, "Vitamin D", everyDayAtNine, ZoneId.of("Europe/Kyiv"))
    val token = login(account)
    val preview = post("/api/v1/time-zone-previews", ujson.Obj("timeZone" -> "Europe/Lisbon"), token)
    assertEquals(preview.status, 200, preview.body)
    assertEquals(preview.json("localTime").str, "It is 08:00 there now.")
    assertEquals(
      preview.json("lines").arr.map(_.str).toList,
      List("1 schedule follows your time zone and keeps its clock times.")
    )
    assertEquals(get("/api/v1/settings", token).json("timeZone").str, "Europe/Kyiv", "a preview writes nothing")
    assertProblem(post("/api/v1/time-zone-previews", ujson.Obj("timeZone" -> "Nowhere"), token), 422, "validation_failed")

    val read = get("/api/v1/settings", token)
    val moved = patch("/api/v1/settings", settingsBody("Europe/Lisbon"), token, ifMatch(read))
    assertEquals(moved.json("timeZone").str, "Europe/Lisbon")
    val schedules = uow.transaction(_.schedules.listFollowingForTzChange(account.accountId))
    assert(schedules.nonEmpty && schedules.forall(_.tz == ZoneId.of("Europe/Lisbon")), schedules.toString)

  test("without the medication consent a preview names no schedule"):
    val account = accountWithoutConsent()
    val preview = post("/api/v1/time-zone-previews", ujson.Obj("timeZone" -> "Asia/Tokyo"), login(account))
    assertEquals(preview.json("lines").arr.map(_.str).toList, List("No schedule is changed by this."))

  test("the zone list holds region zones with their offsets now"):
    val zones = get("/api/v1/time-zones", login(accountWithoutConsent())).json("items").arr.toList
    val kyiv = zones.find(_("id").str == "Europe/Kyiv").get
    assertEquals(kyiv("offset").str, "+02:00")
    assert(!zones.exists(_("id").str.startsWith("Etc/")))
    assert(zones.size > 300, s"${zones.size} zones")

  test("quiet hours may cross midnight, equal ends are refused, and discreet reminders are a switch"):
    val token = login(accountWithoutConsent())
    val read = get("/api/v1/notification-settings", token)
    assertEquals(read.status, 200, read.body)
    assert(!read.json.obj.contains("quietHours"), "off until set")
    assert(!read.json("discreet").bool)
    val body = ujson.Obj("discreet" -> true, "quietHours" -> ujson.Obj("start" -> "22:30", "end" -> "07:00"))
    val saved = patch("/api/v1/notification-settings", body, token, ifMatch(read))
    assertEquals(saved.status, 200, saved.body)
    assertEquals(saved.json("quietHours")("start").str, "22:30")
    assert(saved.json("discreet").bool)
    assertProblem(patch("/api/v1/notification-settings", body, token, ifMatch(read)), 412, "version_conflict")

    val empty = ujson.Obj("discreet" -> true, "quietHours" -> ujson.Obj("start" -> "08:00", "end" -> "08:00"))
    val refused = patch("/api/v1/notification-settings", empty, token, ifMatch(saved))
    assertProblem(refused, 422, "validation_failed")
    assertEquals(refused.fieldErrors, List("quietHours" -> "quiet_hours_empty"))
    val malformed = ujson.Obj("discreet" -> true, "quietHours" -> ujson.Obj("start" -> "25:00", "end" -> "7"))
    assertEquals(
      patch("/api/v1/notification-settings", malformed, token, ifMatch(saved)).fieldErrors,
      List("quietHours.start" -> "invalid", "quietHours.end" -> "invalid")
    )
    val off = patch("/api/v1/notification-settings", ujson.Obj("discreet" -> false), token, ifMatch(saved))
    assert(!off.json.obj.contains("quietHours"), off.body)

  test("the consents are read from the ledger, each with its state; the app neither grants nor withdraws one"):
    val account = accountWithoutConsent()
    fixtures.grant(account.accountId, ConsentPurpose.Hydration)
    val token = login(account)
    val consents = get("/api/v1/consents", token)
    assertEquals(consents.status, 200, consents.body)
    val items = consents.json("items").arr.toList
    assertEquals(items.map(_("purpose").str), ConsentPurpose.values.toList.map(_.id))
    assertEquals(items.filter(_("granted").bool).map(_("purpose").str), List("hydration"))
    assertEquals(items.find(_("purpose").str == "mood").get("text").str, "Off. Send /mood in the chat to turn it on.")
    assert(items.find(_("purpose").str == "hydration").get("text").str.startsWith("On since "))

    assertProblem(post("/api/v1/consents/hydration/withdrawals", ujson.Obj(), token), 403, "stepup_required")
    assertProblem(post("/api/v1/consents/unknown/withdrawals", ujson.Obj(), token), 404, "not_found")
    assert(uow.transaction(_.consents.isGranted(account.accountId, ConsentPurpose.Hydration)), "nothing was withdrawn")

  test("a Ukrainian account reads each consent's name and its date line in Ukrainian (G2.22)"):
    val account = accountWithoutConsent("Europe/Kyiv")
    fixtures.grant(account.accountId, ConsentPurpose.Hydration)
    val token = login(account)
    val read = get("/api/v1/settings", token)
    assertEquals(patch("/api/v1/settings", settingsBody("Europe/Kyiv", language = "uk"), token, ifMatch(read)).status, 200)
    val items = get("/api/v1/consents", token).json("items").arr.toList
    val byPurpose = items.map(item => item("purpose").str -> item).toMap
    assertEquals(
      items.map(_("title").str),
      List(
        "Ліки",
        "Вода",
        "Настрій",
        "Сон",
        "Порівняння між розділами",
        "Зображення звітів у чаті",
        "Їжа",
        "Імпорт із файлів"
      )
    )
    assert(byPurpose("hydration")("text").str.startsWith("Увімкнено з "), byPurpose("hydration").toString)
    assert(!items.exists(_("text").str.contains("On since")), items.toString)

  test("the session bootstrap names the purposes granted now"):
    val account = accountWithoutConsent()
    fixtures.grant(account.accountId, ConsentPurpose.Mood)
    val session = exchange(initDataFor(account.telegramUserId))
    assertEquals(session.json("bootstrap")("consents").arr.map(_.str).toList, List("mood"))

  test("every settings route needs a session"):
    List("/api/v1/settings", "/api/v1/notification-settings", "/api/v1/time-zones", "/api/v1/consents").foreach: path =>
      assertProblem(get(path), 401, "unauthenticated")

  test("a setting changed in the chat's /settings is the value the app reads, and the app's edit the chat's (BOT_UX 17)"):
    val account = accountWithoutConsent("Europe/Kyiv")
    val token = login(account)
    val read = get("/api/v1/settings", token)
    val chat = AccountSettingsEdits(clock)
    uow.transactionAs(account.accountId)(tx =>
      chat.save(tx, account.accountId, AuditChannel.Bot)(_.copy(imageTheme = ImageTheme.Dark, isDiscreet = true))
    )
    val after = get("/api/v1/settings", token)
    assertEquals(after.json("imageTheme").str, "dark")
    assertNotEquals(after.header("ETag"), read.header("ETag"), "an edit from the chat moves the version")
    assert(get("/api/v1/notification-settings", token).json("discreet").bool)
    patch("/api/v1/settings", settingsBody("Europe/Kyiv", "light"), token, ifMatch(after))
    val stored = uow.transactionAs(account.accountId)(_.accounts.settingsOf(account.accountId))
    assertEquals(stored.map(_.imageTheme), Some(ImageTheme.Light))
    val audited = withConnection: conn =>
      val st = conn.prepareStatement(
        """SELECT channel || ':' || array_to_string(changed_fields, ',') FROM audit_log
          |WHERE subject_ref = ? AND object_type = 'settings' ORDER BY occurred_at, id""".stripMargin
      )
      try
        st.setObject(1, account.accountId)
        val rows = st.executeQuery()
        Iterator.continually(rows).takeWhile(_.next()).map(_.getString(1)).toList
      finally st.close()
    // Both rows carry the same instant of the test clock: their order is not the point. The app's first save also
    // stores the language it sent, which the account had not chosen before.
    assertEquals(audited.sorted, List("bot:image_theme,discreet", "miniapp:locale,image_theme"))
end SettingsApiSuite
