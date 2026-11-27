package dosegram.app

import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.ExportCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.ReportCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.ports.ConsentPurpose
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.TelegramSettings
import dosegram.infra.TelegramUpdates
import dosegram.infra.crypto.DataKeys
import dosegram.infra.db.PgInboundInbox
import dosegram.infra.db.PgSuite
import dosegram.render.PngImage
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser
import ox.supervised

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** A new user's first day in the chat, walked through the production assembly on the fake wire, once in Ukrainian and
  * once in English (docs/verification/new-user-review-2026-11-27.md). Nothing is seeded: `/start`, the medications,
  * the reminders and every tracking domain go through the chat, the images through the report worker. The transcript
  * is written to `$WALK_OUT` (or a temporary directory) for the review; the test holds the walk's invariants.
  */
class NewUserWalkSuite extends PgSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  // A Monday.
  private val day = LocalDate.parse("2026-09-21")
  private val ukrainian = NewUserWalk.User(8101L, "CanaryWalkerUk", Some("uk"), 10_000L)
  private val english = NewUserWalk.User(8102L, "CanaryWalkerEn", Some("en"), 20_000L)

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute("SET session_replication_role = replica")
        val rs = st.executeQuery(
          "SELECT string_agg(quote_ident(tablename), ', ') FROM pg_tables " +
            "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"
        )
        rs.next()
        st.execute(s"TRUNCATE ${rs.getString(1)} CASCADE")
        st.execute("SET session_replication_role = DEFAULT")
      finally st.close()
    }

  private val fast = Workers.Cadence(
    reminderPoll = Duration.ofMillis(100),
    materialiserPeriod = Duration.ofMinutes(15),
    outboxIdlePoll = Duration.ofMillis(50),
    sessionSweepPeriod = Duration.ofMillis(100),
    rollupIdlePoll = Duration.ofMillis(100),
    imagePurgePeriod = Duration.ofMillis(100),
    ownerAlertPeriod = Duration.ofMillis(100)
  )

  private val telegram = TelegramSettings(
    "555000111:fake-wire-token",
    FakeTelegramTransport.Bot.id,
    TelegramUpdates.Poll,
    Set(ukrainian.id, english.id)
  )

  private def settings: Settings =
    val separator = if pg.jdbcUrl.contains("?") then "&" else "?"
    Settings(
      enabledAdapters = List(Adapter.Telegram),
      databaseUrl = s"${pg.jdbcUrl}${separator}user=${pg.username}&password=${pg.password}",
      callbackKeys = Settings.parseCallbackKeys("0:MDEyMzQ1Njc4OWFiY2RlZg").fold(error => fail(error), identity),
      role = Role.All,
      instanceId = "new-user-walk",
      logLevel = LogLevel.Info,
      logFormat = LogFormat.Plain,
      consoleUserId = None,
      telegram = Some(telegram),
      publicBaseUrl = None,
      healthPort = freePort(),
      environment = Environment.Development
    )

  /** Draws every card: the bytes do not matter here, the caption (its text equivalent) does. */
  private object AnyBytes extends Rasteriser:
    override def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      Right(PngImage(IArray.unsafeFromArray(s"png:${svg.hashCode}".getBytes(UTF_8))))

  private def strings(query: String): List[String] = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query) // a query of this suite, never input
      Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
    finally st.close()
  }

  /** Whether the bot has work in hand: an update, a message or an image, or a dose due by the clock. */
  private def inFlight(clock: SteppedClock): Boolean =
    strings(
      s"""SELECT (SELECT count(*) FROM inbound_inbox WHERE status = 'pending')
         |  + (SELECT count(*) FROM outbox_messages WHERE status IN ('queued', 'sending', 'failed_retry')
         |       AND next_attempt_at <= '${clock.now().plusSeconds(120)}'::timestamptz)
         |  + (SELECT count(*) FROM report_requests WHERE status IN ('queued', 'rendering'))""".stripMargin
    ).head.toInt > 0 || dosesDue(clock)

  /** Whether the reminder loop has a dose due by the clock that it has not decided yet. */
  private def dosesDue(clock: SteppedClock): Boolean =
    strings(
      s"""SELECT count(*) FROM dose_occurrences WHERE status IN ('pending', 'due', 'snoozed')
         |  AND next_action_at <= '${clock.now()}'::timestamptz
         |  AND error_count < ${dosegram.core.ports.OccurrenceRepository.QuarantineErrorThreshold}""".stripMargin
    ).head.toInt > 0

  /** Whether a dose or a message due by the clock waits for its worker. */
  private def dueNow(clock: SteppedClock): Boolean =
    dosesDue(clock) || strings(
      s"""SELECT count(*) FROM outbox_messages WHERE status IN ('queued', 'sending', 'failed_retry')
         |  AND next_attempt_at <= '${clock.now()}'::timestamptz""".stripMargin
    ).head.toInt > 0

  /** Runs `script` as `user` and returns the transcript and every button label the bot showed. */
  private def walk(user: NewUserWalk.User, zone: ZoneId, startAt: String)(
      script: NewUserWalk => Unit
  ): (List[String], Set[String]) =
    val clock = SteppedClock(NewUserWalk.at(day, startAt, zone))
    val wire = FakeTelegramTransport(clock)
    val images = StatusImages.over(AnyBytes, StatusImages.Stores.inMemory, _ => ())
    supervised:
      val config = settings
      val adapter = TelegramAssembly.adapter(config, telegram, wire, clock)(images, Some(PgInboundInbox(dataSource, DataKeys.development)))
      Launch.start(config, dataSource, Launch.Parts(clock, Map("telegram" -> adapter), fast, images), log = _ => ())
      val chat = NewUserWalk(clock, wire, user, () => zone, () => inFlight(clock), () => dueNow(clock))
      try script(chat)
      catch
        case failure: AssertionError =>
          println("inbox: " + strings("SELECT status || ':' || coalesce(failure_reason, '') FROM inbound_inbox ORDER BY sequence DESC LIMIT 5"))
          println("outbox: " + strings("SELECT kind || ':' || status || ':' || next_attempt_at FROM outbox_messages ORDER BY created_at DESC LIMIT 5"))
          throw failure
      finally write(user, chat.transcript)
      (chat.transcript, chat.labels)

  private def write(user: NewUserWalk.User, lines: List[String]): Unit =
    val dir = sys.env.get("WALK_OUT").map(Path.of(_)).getOrElse(Files.createTempDirectory("new-user-walk"))
    Files.createDirectories(dir)
    val file = dir.resolve(s"walk-${user.languageCode.getOrElse("none")}.txt")
    Files.writeString(file, lines.mkString("\n") + "\n")
    println(s"transcript: $file")

  /** The walk's invariants: nothing failed on the way, and every image carried its text. */
  private def assertClean(lines: List[String]): Unit =
    assertEquals(strings("SELECT status FROM outbox_messages WHERE status IN ('dead', 'failed_permanent')"), Nil)
    assertEquals(strings("SELECT status FROM inbound_inbox WHERE status = 'quarantined'"), Nil)
    assert(!lines.exists(_.contains("(no caption)")), "an image without its text equivalent")

  /** The fixes of the review, as the production path shows them (docs/verification/new-user-review-2026-11-27.md). */
  private def assertReviewed(lang: Lang, lines: List[String], labels: Set[String]): Unit =
    def l(text: => String): String = Lang.within(lang)(text)
    val text = lines.mkString("\n")
    def shows(clue: String, expected: String): Unit = assert(text.contains(expected), s"$clue: '$expected'")
    assert(labels.contains(l(WizardCopy.addMedicationTitle)), "onboarding ends with Add medication")
    shows("the closing line of onboarding", l(IdentityCopy.accountReadyMedication))
    // The snooze lands a minute either side of 13:11 with the stepped clock: the words before the time.
    shows("the snoozed card", l(ReminderCopy.snoozedUntil("§")).takeWhile(_ != '§'))
    shows("a repeat says which reminder it is", l(ReminderCopy.repeatLine("20:00", 2, 3)))
    shows("the final repeat says so", l(ReminderCopy.lastReminder))
    // Findings 5, 9 and 13: one card per dose. A repeat or a card back from a snooze takes the place of the one
    // before (BOT_UX section 9.5 rows 2 and 4), and the missed notice is an edit of the last card (row 8).
    val repeats = messagesWith(lines, " bot #", l(ReminderCopy.repeatLine("20:00", 2, 3)))
    assert(repeats.nonEmpty, "the second reminder of the 20:00 dose")
    repeats.foreach(n => shows(s"the reminder #$n gives way to the next", s"bot deletes #$n"))
    assert(messagesWith(lines, " bot #", l(ReminderCopy.snoozeWakeLine("13:00"))).nonEmpty, "the card after the snooze")
    val snoozed = messagesWith(lines, " bot edits #", l(ReminderCopy.snoozedUntil("§")).takeWhile(_ != '§'))
    snoozed.foreach(n => shows(s"the snoozed card #$n gives way to the one after the snooze", s"bot deletes #$n"))
    val missed = l(ReminderCopy.missedNotice("Magnesium", "20:00"))
    assert(messagesWith(lines, " bot edits #", missed).nonEmpty, "the missed notice edits the dose's card")
    assertEquals(messagesWith(lines, " bot #", missed), Nil, "the missed notice is never a new message")
    shows("the missed notice carries the safety sentence", l(ReminderCopy.unsureAdvice))
    assert(labels.contains(l(MenuCopy.todayTaken("20:00"))), "the /today buttons carry the dose time")
    shows("the goal card names the typed amount", l(WaterCopy.goalPending(250)))
    assert(
      labels.contains(l(MenuCopy.forMedication(MenuCopy.medicationsPause, "Magnesium"))),
      "each /meds row names its medication"
    )
    shows("/privacy starts the export", l(ExportCopy.preparing))
    // Finding 15: the first card of onboarding has nothing to go back to.
    val welcome = lines.dropWhile(!_.contains("you: /start")).drop(1).takeWhile(line => !line.matches("""\S+ \S+ you.*"""))
    assert(welcome.exists(_.contains(s"[${l(Labels.Cancel)}]")), s"the first onboarding card offers Cancel: $welcome")
    assert(!welcome.exists(_.contains(s"[${l(Labels.Back)}]")), s"the first onboarding card offers no Back: $welcome")
    val consentCards = messagesWith(lines, " bot #", l(ConsentCopy.statement(ConsentPurpose.ImagesInChat)))
    assertEquals(consentCards.size, 2, "the images consent card, asked twice")
    shows("the first images consent card gives way to the second", s"bot deletes #${consentCards.head}")
    // Finding 18: a water reminder sent while the one before it was still on its way left both in the chat.
    val reminderWords = l(PromptCopy.waterNudge(HydrationDay(0, 0, None))).takeWhile(_ != '.')
    val waterCards = messagesWith(lines, " bot #", reminderWords)
    waterCards.dropRight(1).foreach(n => shows(s"the water reminder #$n gives way to the next", s"bot deletes #$n"))

  /** The morning summary and `/stats meds` (supervisor decision, 2026-11-27), as the production path shows them. */
  private def assertAnalytics(lang: Lang, lines: List[String]): Unit =
    def l(text: => String): String = Lang.within(lang)(text)
    val text = lines.mkString("\n")
    def shows(clue: String, expected: String): Unit = assert(text.contains(expected), s"$clue: '$expected'")
    shows("the morning summary is on", l(PromptCopy.presetName(PromptPresets.morningSummary(8).id)))
    val summary = l(ReportCopy.dailyTitle(day))
    val sent = messagesWith(lines, " bot #", summary)
    assertEquals(sent.size, 1, "one morning summary of the first day")
    val at = lines.indexWhere(line => line.contains(s" bot #${sent.head}:"))
    assert(lines(at).startsWith("Tue 08:0"), s"the summary comes at the chosen time: ${lines(at)}")
    // The daily card goes first, with its text equivalent as its caption; the summary's text names each domain.
    assert(lines.take(at).reverse.takeWhile(!_.contains(" you")).exists(_.contains("[image")), "the daily card before it")
    val body = lines.drop(at + 1).takeWhile(_.startsWith("      "))
    def t(en: String, uk: String): String = if lang == Lang.Uk then uk else en
    List(t("Doses: ", "Дози: "), t("Water: ", "Вода: "), t("Mood: ", "Настрій: "), t("Sleep: ", "Сон: "))
      .foreach(domain => assert(body.exists(_.contains(domain)), s"the summary's $domain line: $body"))
    assert(body.exists(_.contains(l(ReportCopy.StopThese))), s"Stop these: $body")
    val meds = l(StatsCardCopy.medsTitle(7, day))
    shows("/stats meds", meds)
    shows("/stats meds names the first medication", "1. Vitamin D:")
    shows("/stats meds names the second medication", "2. Magnesium:")
    val strip = lines.indexWhere(_.contains(meds))
    assert(lines.drop(strip).exists(_.contains("[image")), "the strip of /stats meds")

  /** The numbers of the messages that `marker` introduces (" bot #" for a send, " bot edits #" for an edit) and whose
    * text contains `text`.
    */
  private def messagesWith(lines: List[String], marker: String, text: String): List[String] =
    lines.zipWithIndex.collect {
      case (line, i) if line.contains(marker) && lines.drop(i + 1).takeWhile(_.startsWith("      ")).exists(_.contains(text)) =>
        line.split('#')(1).takeWhile(_ != ':')
    }

  /** Labels whose English and Ukrainian forms differ: none of them may show in the Ukrainian walk. */
  private val EnglishOnly: Set[String] =
    def english(label: => String): Option[String] =
      val (en, uk) = (Lang.within(Lang.En)(label), Lang.within(Lang.Uk)(label))
      Option.when(en != uk)(en)
    List(
      english(ConsentCopy.onboardingLabel(ConsentPurpose.Medication)),
      english(ConsentCopy.Agree),
      english(ConsentCopy.Confirm),
      english(Labels.Yes),
      english(Labels.Change),
      english(Labels.Create),
      english(Labels.Taken),
      english(Labels.Skip),
      english(WizardCopy.everyDay),
      english(WizardCopy.weekdays),
      english(WizardCopy.chooseDays),
      english(WizardCopy.noInstructions),
      english(WizardCopy.addMedicationTitle)
    ).flatten.toSet

  /** The walk of the review: one script for both languages, the labels from the catalogue in the user's language. */
  private def firstDay(lang: Lang, zone: ZoneId)(chat: NewUserWalk): Unit =
    def l(label: => String): String = Lang.within(lang)(label)
    def t(en: String, uk: String): String = if lang == Lang.Uk then uk else en
    def at(local: String) = NewUserWalk.at(day, local, zone)
    // A label in the user's language, or the English one where the bot showed that instead (a finding of the review).
    def tap(label: => String): Unit =
      val (own, english) = (l(label), Lang.within(Lang.En)(label))
      chat.tapWhere(own)(shown => shown == own || shown == english)
    chat.note("Onboarding: /start, the medication consent in two taps, the time zone")
    chat.say("/start")
    tap(ConsentCopy.onboardingLabel(ConsentPurpose.Medication))
    tap(ConsentCopy.Agree)
    tap(ConsentCopy.Confirm)
    chat.tapWhere(zone.getId)(_.contains(zone.getId))
    tap(Labels.Yes)
    chat.note("First medication: Vitamin D, every day at 09:00")
    // From the closing message of onboarding where it offers the button, else through /meds.
    if !chat.newestButtons.contains(l(WizardCopy.addMedicationTitle)) then chat.say("/meds")
    tap(WizardCopy.addMedicationTitle)
    chat.say("Vitamin D")
    chat.say("1000 IU")
    chat.say("09:00")
    tap(WizardCopy.noInstructions)
    tap(WizardCopy.everyDay)
    tap(Labels.Create)
    chat.note("Second medication: Magnesium, on weekdays at 13:00 and 20:00, with instructions")
    chat.say("/meds")
    tap(WizardCopy.addMedicationTitle)
    chat.say("Magnesium")
    chat.say(t("1 tablet", "1 таблетка"))
    chat.say("13:00 20:00")
    chat.say(t("with food", "під час їжі"))
    tap(WizardCopy.weekdays)
    tap(Labels.Create)
    chat.note("The medication list with both medications")
    chat.say("/meds")
    chat.note("Water: a typed amount, the water consent, the work-hours plan")
    chat.say(t("250 ml", "250 мл"))
    tap(ConsentCopy.Agree)
    tap(ConsentCopy.Confirm)
    tap(WaterCopy.goalChoice(2000))
    tap(PromptCopy.presetName("water.work_hours_30"))
    tap(PromptCopy.TurnOn)
    chat.note("Mood: /mood, the mood consent, a check-in")
    chat.say("/mood")
    tap(ConsentCopy.Agree)
    tap(ConsentCopy.Confirm)
    chat.tap("4")
    chat.note("Sleep: a typed night before the sleep consent")
    chat.say(t("slept 23:30 7:10", "сон 23:30 7:10"))
    tap(ConsentCopy.Agree)
    tap(ConsentCopy.Confirm)
    chat.tap("4")
    chat.note("A meal typed before the nutrition consent")
    chat.say(t("breakfast: porridge", "сніданок: каша"))
    tap(ConsentCopy.Agree)
    tap(ConsentCopy.Confirm)
    chat.note("/today before the first dose")
    chat.say("/today")
    chat.note("09:00: the first reminder, answered with Taken")
    chat.passUntil(at("09:01"))
    tap(Labels.Taken)
    chat.note("13:00: the Magnesium reminder, snoozed, then skipped when it comes back")
    chat.passUntil(at("13:01"))
    // The dose's own ten-minute snooze: a water reminder due in the same minute also has a snooze button.
    tap(Labels.snooze(10))
    chat.passUntil(at("13:45"))
    tap(Labels.Skip)
    chat.note("/today after the answers")
    chat.say("/today")
    chat.note("/stats with images")
    chat.say("/stats")
    tap(WaterCopy.ShowAsImage)
    // Finding 11: a second image tap before the consent replaces the open consent card.
    tap(WaterCopy.ShowAsImage)
    tap(ConsentCopy.Agree)
    tap(ConsentCopy.Confirm)
    tap(StatsCardCopy.WaterWeek)
    chat.note("/reminders")
    chat.say("/reminders")
    chat.note("The morning summary: on from /reminders, every day at 08:00")
    tap(PromptCopy.kindName(PromptKind.DailyReport))
    tap(PromptCopy.presetName(PromptPresets.morningSummary(8).id))
    tap(PromptCopy.TurnOn)
    chat.note("/stats meds: each medication over 7 days, and its strip")
    chat.say("/stats meds")
    tap(StatsCardCopy.ShowAsImage)
    chat.note("/privacy")
    chat.say("/privacy")
    // Finding 19: /privacy offers the export.
    tap(ExportCopy.ExportMyData)
    chat.note("The rest of the day: what the bot sends on its own until 22:00")
    chat.passUntil(at("22:00"))
    chat.note("The next morning: the summary of the first day at 08:00")
    chat.passUntil(NewUserWalk.at(day.plusDays(1), "08:05", zone))

  test("a new Ukrainian-speaking user's first day in the chat"):
    val zone = ZoneId.of("Europe/Kyiv")
    val (lines, shown) = walk(ukrainian, zone, "08:00")(firstDay(Lang.Uk, zone))
    assertClean(lines)
    assertReviewed(Lang.Uk, lines, shown)
    assertAnalytics(Lang.Uk, lines)
    // Walk-through of 2026-11-27: the onboarding and wizard buttons were English for a Ukrainian reader.
    val english = EnglishOnly.filter(shown)
    assertEquals(english, Set.empty[String], "English buttons in the Ukrainian walk")
    assert(!lines.exists(_.contains("question 1 of")), "an English form heading in the Ukrainian walk")

  test("a new English-speaking user's first day in the chat"):
    val zone = ZoneId.of("Europe/London")
    val (lines, shown) = walk(english, zone, "08:00")(firstDay(Lang.En, zone))
    assertClean(lines)
    assertReviewed(Lang.En, lines, shown)
    assertAnalytics(Lang.En, lines)
end NewUserWalkSuite
