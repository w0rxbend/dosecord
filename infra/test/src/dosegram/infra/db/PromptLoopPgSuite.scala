package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.contracts.*
import dosegram.core.application.capture.CaptureHandler
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.insights.ReportComposer
import dosegram.core.application.insights.RollupService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealInput
import dosegram.core.application.reminders.PromptMessages
import dosegram.core.application.sleep.SleepInput
import dosegram.core.application.sleep.SleepService
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.application.reminders.RemindersHandler
import dosegram.core.chat.*
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DayHighlight
import dosegram.core.domain.insights.HighlightDomain
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.QuietHours
import dosegram.core.domain.reminders.Anchor
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.DaySet
import dosegram.core.domain.reminders.NudgePlan
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPreset
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.reminders.TimeRef
import dosegram.core.domain.reminders.PromptStatus
import dosegram.core.domain.reminders.SuppressReason
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DayCardView
import dosegram.core.ports.IdSource
import dosegram.core.ports.MonthCardView
import dosegram.core.ports.PromptOccurrence
import dosegram.core.ports.StatusCards
import dosegram.core.ports.WeekCardView
import dosegram.core.ports.WeekHighlightsView
import dosegram.core.scheduling.CatalogueOutboxRenderer
import dosegram.core.scheduling.OutboxDispatcher
import dosegram.core.scheduling.PromptLoop
import dosegram.core.scheduling.PromptOutboxRenderer

import java.security.SecureRandom
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import scala.language.implicitConversions

/** ADR-023 on Testcontainers Postgres 18: water prompts from the plan through the outbox to the fake wire. The owner's
  * preset on a Monday in Kyiv (UTC+2 in November): slots at 09:30 to 17:00 local.
  */
class PromptLoopPgSuite extends PgSuite:

  private val monday = "2026-11-16"
  private lazy val fixtures = Fixtures(dataSource)
  // Four prompt loops tick from parallel threads: the generator must be thread-safe, as in production, or two of them
  // draw the same bits and one row fails on a duplicate key.
  private val ids = IdSource.uuidV7(SecureRandom())
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  /** A local time of the Monday in Kyiv. */
  private def kyiv(hhmm: String): Instant = Instant.parse(s"${monday}T$hhmm:00+02:00")

  private final class Rig(
      start: Instant = kyiv("09:00"),
      preset: PromptPreset = PromptPresets.WaterWorkHours,
      consent: ConsentPurpose = ConsentPurpose.Hydration,
      cards: StatusCards = StatusCards.textOnly,
      drawing: CardDrawing = CardDrawing.unavailable
  ):
    val clock = MutableClock(start)
    // One rig at a time: the plans and queued rows of the earlier tests stay out of this one's loop and wire.
    withConnection: conn =>
      given Connection = conn
      sql"UPDATE prompt_schedules SET enabled = false".execute()
      sql"UPDATE outbox_messages SET status = 'cancelled' WHERE status = 'queued'".execute()
    val account: UUID = fixtures.account("Europe/Kyiv")
    fixtures.grant(account, consent)
    fixtures.deliveryChannel(account, "fake", s"chat-$account", start)
    val uow = PgUnitOfWork(dataSource, clock, DataKeys.development)
    val hydration = HydrationService(clock, ids)
    val settings = ReminderSettings(clock, ids)
    val adapter = FakeAdapter(CapabilityProfiles.Telegram)
    val errors = scala.collection.mutable.ListBuffer.empty[Throwable]

    def loop(): PromptLoop =
      val reports = ReportComposer(StatsQueryService(RollupService(uow, clock), clock), cards, drawing, codec)
      PromptLoop(uow, clock, ids, PromptMessages(codec), hydration, settings, reports, (_, e) => errors += e)

    val dispatcher = OutboxDispatcher(
      uow,
      Map("fake" -> adapter),
      PromptOutboxRenderer(uow, CatalogueOutboxRenderer(uow, codec, clock)),
      clock
    )

    uow.transaction(tx => assert(settings.apply(tx, account, preset).isRight))

    def at(hhmm: String): Unit = clock.at = kyiv(hhmm).plusSeconds(5)

    def tickAndSend(): Unit =
      loop().tick()
      while dispatcher.dispatchOnce() > 0 do ()
      assertEquals(errors.toList, Nil)

    def prompts: List[PromptOccurrence] =
      uow.transaction(_.reminders.prompts.decidedOn(account, LocalDate.parse(monday)))

    def allPrompts: List[PromptOccurrence] =
      val dates = withConnection: conn =>
        given Connection = conn
        sql"SELECT DISTINCT local_date FROM prompt_occurrences WHERE account_id = $account ORDER BY local_date"
          .query[LocalDate]()
      uow.transaction(tx => dates.flatMap(tx.reminders.prompts.decidedOn(account, _)))

    /** The prompt that the tick at `tick` decided and sent, if any. A prompt's `decided_at` is the clock of the tick
      * that inserted it and never changes, so one statement finds it; reading every decided prompt before and after
      * each tick grew with the days simulated.
      */
    def sentBy(tick: Instant): Option[PromptOccurrence] =
      val dates = withConnection: conn =>
        given Connection = conn
        sql"""SELECT DISTINCT local_date FROM prompt_occurrences
              WHERE account_id = $account AND decided_at >= $tick AND status = 'sent' ORDER BY local_date"""
          .query[LocalDate]()
      if dates.isEmpty then None
      else
        uow.transaction(tx => dates.flatMap(tx.reminders.prompts.decidedOn(account, _)))
          .find(p => !p.decidedAt.isBefore(tick) && p.status == PromptStatus.Sent)

    def drink(ml: Int, key: String): Unit =
      val amount = WaterAmount.ofMillilitres(ml).toOption.get
      uow.transaction(tx => assert(hydration.log(tx, LogWater(account, amount, EntryDay.Today, EntrySource.Bot, key)).isRight))

    def open: List[(MessageHandle, RenderedMessage)] = adapter.sent

    /** A tap on the prompt `handle` of the button that carries `wire`, handled on its own transaction. */
    def tap(handler: ChatHandler, handle: MessageHandle, wire: String, eventId: String): Reply =
      val payload = codec.decode(wire).toOption.get
      val ref = CallbackRef(payload.action.id, payload.subject, payload.value, slot = false, raw = wire)
      val event = InboundEvent(
        EventId(UUID.randomUUID()),
        "fake",
        eventId,
        clock.now(),
        actor = PlatformIdentity("fake", "owner", None),
        chat = ChatRef("fake", handle.chatId),
        principal = None,
        body = Inbound.InteractionSubmitted(ref, Some(handle))
      )
      val principal = Principal(IdentityId(UUID.randomUUID()), Some(AccountId(account)), linked = true)
      uow.transaction(handler.handle(event, principal, _))

    def labels(message: RenderedMessage): List[String] = message.controls.flatMap:
      case RenderedControls.Buttons(rows)       => rows.flatten.map(_.label)
      case RenderedControls.Numbered(_, choices) => choices.map(_.label)
      case RenderedControls.NoControls          => Nil

  test("a due water prompt goes out through the outbox, silent, with the amounts and the two close buttons"):
    val rig = Rig()
    rig.at("09:30")
    rig.tickAndSend()
    assertEquals(rig.prompts.map(p => (p.slotKey, p.status)), List("0930" -> PromptStatus.Sent))
    val List((_, message)) = rig.open: @unchecked
    assert(message.silent, "a prompt is silent unless the user turned sound on")
    assert(message.chunks.mkString.contains("Water reminder."), message.chunks)
    assertEquals(rig.labels(message), List("+ 200 ml", "+ 300 ml", "+ 500 ml", "Snooze 1 h", "Pause for today"))

  test("the next prompt replaces the unanswered one: the chat holds one open water prompt"):
    val rig = Rig()
    rig.at("09:30")
    rig.tickAndSend()
    val first = rig.open.head._1
    rig.at("10:00")
    rig.tickAndSend()
    assertEquals(rig.open.size, 1, "the earlier prompt was deleted")
    assert(rig.adapter.ops.contains(VendorOp.Delete(first)), rig.adapter.ops)
    assertEquals(rig.prompts.map(p => (p.slotKey, p.status)), List("0930" -> PromptStatus.Expired, "1000" -> PromptStatus.Sent))

  test("a prompt replaced before it left the queue is cancelled and never sent"):
    val rig = Rig()
    rig.at("09:30")
    rig.loop().tick() // decided and queued, not dispatched
    rig.at("10:00")
    rig.tickAndSend()
    assertEquals(rig.adapter.ops.count(_.isInstanceOf[VendorOp.Send]), 1, rig.adapter.ops)
    assertEquals(rig.open.size, 1)
    withConnection: conn =>
      given Connection = conn
      val first = rig.prompts.head.sendKey.get
      assertEquals(
        sql"SELECT status FROM outbox_messages WHERE send_key = $first".queryOne[String](),
        Some("cancelled")
      )

  test("a slot that falls due while the previous prompt is being sent waits for it, then replaces it (review #18)"):
    val rig = Rig()
    rig.at("09:30")
    rig.loop().tick()
    val first = rig.prompts.head.sendKey.get
    // The dispatcher claimed the send and waits on the chat's pace: no handle yet, too late to cancel.
    withConnection: conn =>
      given Connection = conn
      sql"UPDATE outbox_messages SET status = 'sending' WHERE send_key = $first".execute()
    rig.at("10:00")
    rig.loop().tick()
    assertEquals(rig.prompts.map(p => (p.slotKey, p.status)), List("0930" -> PromptStatus.Sent), "decided later")
    withConnection: conn =>
      given Connection = conn
      sql"UPDATE outbox_messages SET status = 'queued' WHERE send_key = $first".execute()
    while rig.dispatcher.dispatchOnce() > 0 do ()
    val firstHandle = rig.open.head._1
    rig.clock.at = rig.clock.at.plusSeconds(30)
    rig.tickAndSend()
    assert(rig.adapter.ops.contains(VendorOp.Delete(firstHandle)), rig.adapter.ops)
    assertEquals(rig.open.size, 1, "the chat holds one open water prompt")
    assertEquals(rig.prompts.map(p => (p.slotKey, p.status)), List("0930" -> PromptStatus.Expired, "1000" -> PromptStatus.Sent))

  test("water logged within the interval skips the slot, and the goal reached stops the day"):
    val rig = Rig()
    rig.uow.transaction(tx => rig.hydration.setGoal(tx, rig.account, Some(1000)))
    rig.at("09:30")
    rig.tickAndSend()
    rig.at("09:40")
    rig.drink(300, "typed-1")
    rig.at("10:00")
    rig.tickAndSend()
    assertEquals(rig.prompts.last.reason, Some(SuppressReason.RecentEntry))
    rig.at("10:15")
    rig.drink(700, "typed-2")
    rig.at("11:30")
    rig.tickAndSend()
    assertEquals(rig.prompts.last.reason, Some(SuppressReason.GoalReached))
    assertEquals(rig.adapter.ops.count(_.isInstanceOf[VendorOp.Send]), 1)

  test("bedtime mode (G7.2): after Going to sleep the water prompt is held and recorded as bedtime; I'm up ends it"):
    val rig = Rig()
    fixtures.grant(rig.account, ConsentPurpose.Sleep)
    val sleep = SleepService(rig.clock, ids)
    rig.at("09:10")
    rig.uow.transaction(tx => assert(sleep.goingToSleep(tx, SleepInput(rig.account, EntrySource.Bot, "night")).isRight))
    rig.at("09:30")
    rig.tickAndSend()
    assertEquals(rig.prompts.map(p => (p.slotKey, p.reason)), List("0930" -> Some(SuppressReason.Bedtime)))
    rig.at("10:45")
    rig.uow.transaction(tx => assert(sleep.wakeUp(tx, SleepInput(rig.account, EntrySource.Bot, "up")).isRight))
    rig.at("11:00")
    rig.tickAndSend()
    assertEquals(rig.prompts.last.status, PromptStatus.Sent)
    assertEquals(rig.adapter.ops.count(_.isInstanceOf[VendorOp.Send]), 1)

  test("the morning touchpoint (G7.2) is sent in bedtime mode, and an I'm up before it answers it"):
    val rig = Rig(start = kyiv("06:00"), preset = PromptPresets.SleepMorning, consent = ConsentPurpose.Sleep)
    val sleep = SleepService(rig.clock, ids)
    rig.clock.at = kyiv("06:00").minus(Duration.ofHours(7))
    rig.uow.transaction(tx => assert(sleep.goingToSleep(tx, SleepInput(rig.account, EntrySource.Bot, "night")).isRight))
    rig.at("07:00")
    rig.tickAndSend()
    assertEquals(rig.prompts.map(p => (p.slotKey, p.status)), List("0700" -> PromptStatus.Sent))
    val (handle, _) = rig.open.head
    val parts = CaptureParts.over(rig.uow, rig.clock, ids, StatusCards.textOnly)
    val handler = RemindersHandler(codec, rig.clock, rig.settings, parts, inner = (_, _, _) => Reply.empty)
    val up = codec.encode(CallbackMode.Direct, ActionRegistry.byName("prompt.sleep").get, rig.prompts.head.id, 1)
    rig.at("07:05")
    val card = rig.tap(handler, handle, up.wire, "up-tap").replace.getOrElse(fail("expected the night card"))
    assert(card.body.toString.contains("Slept 23:00 to 07:05, 8 h 05 min (your check-ins)."), card.body)
    val labels = card.blocks.collect { case Block.Choices(set) => set.choices.map(_.label) }.flatten
    assertEquals(labels, List("1", "2", "3", "4", "5", "Adjust times", "Undo"))
    assertEquals(rig.prompts.head.status, PromptStatus.Answered)
    rig.clock.at = Instant.parse("2026-11-16T23:00:00+02:00")
    rig.uow.transaction(tx => assert(sleep.goingToSleep(tx, SleepInput(rig.account, EntrySource.Bot, "night-2")).isRight))
    rig.clock.at = Instant.parse("2026-11-17T06:30:00+02:00")
    rig.uow.transaction(tx => assert(sleep.wakeUp(tx, SleepInput(rig.account, EntrySource.Bot, "up")).isRight))
    rig.clock.at = Instant.parse("2026-11-17T07:00:05+02:00")
    rig.tickAndSend()
    val tuesdayPrompts = rig.uow.transaction(_.reminders.prompts.decidedOn(rig.account, LocalDate.parse("2026-11-17")))
    assertEquals(tuesdayPrompts.map(_.reason), List(Some(SuppressReason.RecentEntry)))

  test("a tap logs the amount, closes the prompt and turns it into its outcome without buttons; a second tap logs nothing"):
    val rig = Rig()
    rig.at("09:30")
    rig.tickAndSend()
    val (handle, _) = rig.open.head
    val prompt = rig.prompts.head
    val parts = CaptureParts.over(rig.uow, rig.clock, ids, StatusCards.textOnly)
    val handler = RemindersHandler(codec, rig.clock, rig.settings, parts, inner = (_, _, _) => Reply.empty)
    val token = codec.encode(CallbackMode.Direct, ActionRegistry.byName("prompt.water_add").get, prompt.id, 300)
    def tap(eventId: String): Reply = rig.tap(handler, handle, token.wire, eventId)
    rig.at("09:33")
    val reply = tap("tap-1")
    val outcome = reply.replace.getOrElse(fail(s"expected an edit of the prompt, got $reply"))
    assertEquals(outcome.replaces, Some(handle))
    assertEquals(outcome.blocks, Nil, "the buttons go")
    assert(outcome.body.toString.contains("Logged 300 ml"), outcome.body)
    assertEquals(rig.prompts.head.status, PromptStatus.Answered)
    assertEquals(tap("tap-2").toast, Some("This reminder is already closed."))
    val total = rig.uow.transaction(tx => rig.hydration.day(tx, rig.account, EntryDay.Today)).toOption.get
    assertEquals(total.recorded.map(_.totalMl), Some(300))

  test("a mood prompt answered by a tap is closed, so the next prompt leaves its recorded check-in in the chat"):
    val rig = Rig(preset = PromptPresets.MoodMiddayEvening, consent = ConsentPurpose.Mood)
    rig.at("13:30")
    rig.tickAndSend()
    val List((handle, _)) = rig.open: @unchecked
    val parts = CaptureParts.over(rig.uow, rig.clock, ids, StatusCards.textOnly)
    val handler = new CaptureHandler(codec, rig.clock, parts, inner = (_, _, _) => Reply.empty)
    val step = codec.encode(CallbackMode.Direct, ActionRegistry.byName("mood.checkin").get, rig.account, 4)
    rig.at("13:33")
    val reply = rig.tap(handler, handle, step.wire, "mood-tap-1")
    val recorded = reply.replace.getOrElse(fail(s"expected an edit of the prompt, got $reply"))
    assertEquals(recorded.replaces, Some(handle))
    assertEquals(rig.prompts.map(_.status), List(PromptStatus.Answered))
    rig.at("20:00")
    rig.tickAndSend()
    assert(!rig.adapter.ops.contains(VendorOp.Delete(handle)), rig.adapter.ops)
    assertEquals(rig.prompts.map(_.status), List(PromptStatus.Answered, PromptStatus.Sent))
    assertEquals(rig.open.size, 2, "the recorded check-in and the new prompt")

  // ---------- scheduled reports (VISUALS_AND_ANALYTICS section 10) ----------

  private val sunday = "2026-11-22"
  private def sundayAt(hhmm: String): Instant = Instant.parse(s"${sunday}T$hhmm:00+02:00")
  // The weekly report is due on the Monday after the week, at 09:00 (PRODUCT_SPEC R-INS-4).
  private def mondayAt(hhmm: String, date: String = "2026-11-23"): Instant = Instant.parse(s"${date}T$hhmm:00+02:00")

  /** The messages the fake wire holds in the chat of the rig's own account. */
  private def chatOf(rig: Rig): List[(MessageHandle, RenderedMessage)] =
    rig.open.filter(_._1.chatId == s"chat-${rig.account}")

  test("a weekly report over a week without records sends nothing and is recorded as no records (VA-PS-05)"):
    val rig = Rig(start = sundayAt("18:00"), preset = PromptPresets.WeeklyReport)
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.tickAndSend()
    assertEquals(rig.allPrompts.map(p => (p.status, p.reason)), List(PromptStatus.Suppressed -> Some(SuppressReason.NoRecords)))
    assertEquals(rig.open, Nil)

  test("a weekly report sends the week's text with Stop these; Stop these turns the plan off in the same message"):
    val rig = Rig(start = sundayAt("09:00"), preset = PromptPresets.WeeklyReport)
    rig.clock.at = sundayAt("09:10")
    rig.drink(700, "w-sunday")
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.tickAndSend()
    assertEquals(rig.allPrompts.map(_.status), List(PromptStatus.Sent))
    val (handle, message) = rig.open.lastOption.getOrElse(fail("no report was sent"))
    assert(message.toString.contains("Your week: the last 7 days"), message)
    assert(message.toString.contains("Water: "), message)
    assertEquals(rig.labels(message), List("Stop these"))
    assert(message.toString.contains("Days with records: water 1 of 7."), message)
    // A second pass on the same Monday sends nothing more: one report per period (VA-PS-09).
    rig.clock.at = mondayAt("09:20")
    rig.tickAndSend()
    assertEquals(rig.open.size, 1)
    val parts = CaptureParts.over(rig.uow, rig.clock, ids, StatusCards.textOnly)
    val handler = RemindersHandler(codec, rig.clock, rig.settings, parts, inner = (_, _, _) => Reply.empty)
    val token =
      codec.encode(CallbackMode.Direct, ActionRegistry.byName("reminders.report_stop").get, rig.account, 5L)
    val reply = rig.tap(handler, handle, token.wire, "stop-1")
    val edited = reply.replace.getOrElse(fail(s"expected an edit of the report, got $reply"))
    assertEquals(edited.replaces, Some(handle))
    assertEquals(edited.blocks, Nil)
    assert(edited.body.toString.contains("Weekly summaries are off."), edited.body)
    assert(rig.uow.transaction(_.reminders.prompts.schedule(rig.account, PromptPresets.WeeklyReport.kind)).forall(!_.enabled))

  test("the weekly report covers the seven days before its Monday; today's records wait for the next one (R-INS-4)"):
    val rig = Rig(start = sundayAt("09:00"), preset = PromptPresets.WeeklyReport)
    rig.clock.at = sundayAt("10:00")
    rig.drink(300, "w-sunday")
    rig.clock.at = mondayAt("08:30")
    rig.drink(900, "w-monday")
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.tickAndSend()
    val (_, message) = chatOf(rig).lastOption.getOrElse(fail("no report was sent"))
    // Sunday's entry is in the week and Monday's is not: one day of seven has water.
    assert(message.toString.contains("1 of 7 days have data"), message)
    assert(message.toString.contains("Days with records: water 1 of 7."), message)

  test("counts (G9.4): ten water entries in the week pass a milestone, one line each; with the switch off none shows"):
    val rig = Rig(start = sundayAt("08:00"), preset = PromptPresets.WeeklyReport)
    (1 to 10).foreach: i =>
      rig.clock.at = sundayAt("08:00").plusSeconds(60L * i)
      rig.drink(100, s"ten-$i")
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.tickAndSend()
    val (_, message) = chatOf(rig).lastOption.getOrElse(fail("no report was sent"))
    assert(message.toString.contains("Days with records: water 1 of 7."), message)
    assert(message.toString.contains("10 water entries recorded so far."), message)
    val off = Rig(start = sundayAt("08:00"), preset = PromptPresets.WeeklyReport)
    off.uow.transaction(_.accounts.setGamification(off.account, false, off.clock.now()))
    off.clock.at = sundayAt("09:00")
    off.drink(100, "off-1")
    off.clock.at = mondayAt("09:00").plusSeconds(5)
    off.tickAndSend()
    val (_, quiet) = chatOf(off).lastOption.getOrElse(fail("no report was sent"))
    assert(!quiet.toString.contains("Days with records"), quiet)
    assert(!quiet.toString.contains("so far"), quiet)

  test("the weekly report is offered once, after the first week with records on three days, and Yes turns it on (X-19)"):
    val rig = Rig(start = sundayAt("08:00").minus(Duration.ofDays(6)))
    List("2026-11-17", "2026-11-19", "2026-11-22").foreach: date =>
      rig.clock.at = Instant.parse(s"${date}T12:00:00+02:00")
      rig.drink(250, s"offer-$date")
    def offers(): List[(MessageHandle, RenderedMessage)] =
      rig.loop().offerReports()
      while rig.dispatcher.dispatchOnce() > 0 do ()
      chatOf(rig).filter(_._2.toString.contains("weekly summary"))
    rig.clock.at = mondayAt("08:55")
    assertEquals(offers(), Nil, "not before the report's own time")
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    val List((handle, offer)) = offers(): @unchecked
    assert(offer.toString.contains("Last week has records on 3 days."), offer)
    assertEquals(rig.labels(offer), List("Yes, send it", "No, thanks"))
    val plan = rig.uow.transaction(_.reminders.prompts.schedule(rig.account, PromptKind.WeeklyReport))
    assertEquals(plan.map(_.enabled), Some(false), "the offer leaves the plan off until the answer")
    rig.clock.at = mondayAt("09:30")
    assertEquals(offers().size, 1, "asked once")
    val parts = CaptureParts.over(rig.uow, rig.clock, ids, StatusCards.textOnly)
    val handler = RemindersHandler(codec, rig.clock, rig.settings, parts, inner = (_, _, _) => Reply.empty)
    val yes = offer.choiceMap.find(_.label == "Yes, send it").get.callback
    val answer = rig.tap(handler, handle, yes, "offer-yes").replace.getOrElse(fail("expected the offer's answer"))
    assert(answer.body.toString.contains("Weekly summaries are on: Mondays at 09:00."), answer.body)
    val on = rig.uow.transaction(_.reminders.prompts.schedule(rig.account, PromptKind.WeeklyReport))
    assertEquals(on.map(p => (p.enabled, p.presetId)), Some((true, "report.weekly")))

  test("a week with records on two days is not offered a report"):
    val rig = Rig(start = sundayAt("08:00").minus(Duration.ofDays(6)))
    List("2026-11-17", "2026-11-19").foreach: date =>
      rig.clock.at = Instant.parse(s"${date}T12:00:00+02:00")
      rig.drink(250, s"two-$date")
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.loop().offerReports()
    while rig.dispatcher.dispatchOnce() > 0 do ()
    assertEquals(chatOf(rig).filter(_._2.toString.contains("weekly summary")), Nil)
    assertEquals(rig.uow.transaction(_.reminders.prompts.schedule(rig.account, PromptKind.WeeklyReport)), None)

  test("three weekly reports in a row not followed by any use pause the report with one line and Resume (VA-PS-06)"):
    val rig = Rig(start = sundayAt("08:00"), preset = PromptPresets.WeeklyReport)
    val mondays = List("2026-11-23", "2026-11-30", "2026-12-07")
    mondays.foreach: date =>
      rig.clock.at = Instant.parse(s"${date}T08:00:00+02:00").minus(Duration.ofDays(1))
      rig.drink(400, s"weekly-$date")
      rig.clock.at = mondayAt("09:00", date).plusSeconds(5)
      rig.tickAndSend()
      rig.clock.at = mondayAt("09:10", date).plus(Duration.ofDays(1))
      rig.tickAndSend()
    val sent = rig.allPrompts.filter(_.kind == PromptKind.WeeklyReport).filter(_.status.wasSent)
    assertEquals(sent.map(_.status), List.fill(3)(PromptStatus.Expired))
    val plan = rig.uow.transaction(_.reminders.prompts.schedule(rig.account, PromptKind.WeeklyReport)).get
    assertEquals(plan.enabled, false)
    val (handle, notice) = chatOf(rig).lastOption.getOrElse(fail("no pause notice"))
    assert(notice.toString.contains("Weekly summaries are paused for now."), notice)
    assertEquals(rig.labels(notice), List("Resume"))
    val parts = CaptureParts.over(rig.uow, rig.clock, ids, StatusCards.textOnly)
    val handler = RemindersHandler(codec, rig.clock, rig.settings, parts, inner = (_, _, _) => Reply.empty)
    val resumed = rig.tap(handler, handle, notice.choiceMap.head.callback, "resume").replace.getOrElse(fail("no answer"))
    assert(resumed.body.toString.contains("Weekly summaries are on again."), resumed.body)
    val again = rig.uow.transaction(_.reminders.prompts.schedule(rig.account, PromptKind.WeeklyReport)).get
    assertEquals(again.enabled, true)
    rig.clock.at = mondayAt("09:10", "2026-12-08").plus(Duration.ofHours(1))
    rig.tickAndSend()
    assert(rig.uow.transaction(_.reminders.prompts.schedule(rig.account, PromptKind.WeeklyReport)).get.enabled, "Resume starts afresh")

  test("a weekly report followed by a use of the bot within a day counts as seen"):
    val rig = Rig(start = sundayAt("08:00"), preset = PromptPresets.WeeklyReport)
    rig.clock.at = sundayAt("09:00")
    rig.drink(400, "seen-1")
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.tickAndSend()
    withConnection: conn =>
      given Connection = conn
      val at = mondayAt("12:00")
      sql"UPDATE platform_identities SET last_seen_at = $at WHERE user_id = ${rig.account}".execute()
    rig.clock.at = mondayAt("09:10").plus(Duration.ofDays(1))
    rig.tickAndSend()
    assertEquals(rig.allPrompts.filter(_.kind == PromptKind.WeeklyReport).map(_.status), List(PromptStatus.Answered))

  test("a monthly report goes out on the 1st only when a domain has records on 14 days of the month (R-INS-11)"):
    val first = Instant.parse("2026-12-01T09:00:00+02:00")
    val rig = Rig(start = first, preset = PromptPresets.MonthlyReport)
    (1 to 14).foreach { day =>
      rig.clock.at = Instant.parse(f"2026-11-$day%02dT12:00:00+02:00")
      rig.drink(500, s"m-$day")
    }
    rig.clock.at = Instant.parse("2026-12-01T10:00:05+02:00")
    rig.tickAndSend()
    assertEquals(rig.allPrompts.filter(_.planDate == LocalDate.parse("2026-12-01")).map(_.status), List(PromptStatus.Sent))
    val (_, message) = rig.open.lastOption.getOrElse(fail("no report was sent"))
    assert(message.toString.contains("Your month"), message)
    assert(message.toString.contains("November 2026"), message)
    assert(message.toString.contains("14 days with records"), message)

  test("a monthly report of three pages goes as one album, each page captioned, then its text with Stop these (6.6)"):
    val pages = StatusCards.textOnly
    val threePages = new StatusCards:
      export pages.{mood, water, day, sleep, food}
      override def month(view: MonthCardView, look: CardLook): List[CardPage] =
        (1 to 3).toList.map(page => CardPage(s"card.month.p$page", "<svg/>", s"Page $page of the month.", "month.png", false))
    val drawing: CardDrawing = (page, _) =>
      Right(AttachmentRef(MediaKind.Photo, s"asset-${page.templateId}", "image/png", page.altText))
    val first = Instant.parse("2026-12-01T09:00:00+02:00")
    val rig = Rig(start = first, preset = PromptPresets.MonthlyReport, cards = threePages, drawing = drawing)
    fixtures.grant(rig.account, ConsentPurpose.ImagesInChat)
    (1 to 14).foreach { day =>
      rig.clock.at = Instant.parse(f"2026-11-$day%02dT12:00:00+02:00")
      rig.drink(500, s"album-$day")
    }
    rig.clock.at = Instant.parse("2026-12-01T10:00:05+02:00")
    rig.tickAndSend()
    val List((_, album), (_, text)) = chatOf(rig): @unchecked
    assertEquals(album.album.map(_.altText), List("Page 1 of the month.", "Page 2 of the month.", "Page 3 of the month."))
    assertEquals((album.attachment, album.controls, album.chunks), (None, Nil, Nil))
    assert(text.toString.contains("Your month"), text)
    assertEquals(rig.labels(text), List("Stop these"))

  test("the month in one image is the last photo of the monthly album (IMG-24)"):
    val pages = StatusCards.textOnly
    val withPoster = new StatusCards:
      export pages.{mood, water, day, sleep, food}
      override def month(view: MonthCardView, look: CardLook): List[CardPage] =
        (1 to 2).toList.map(page => CardPage(s"card.month.p$page", "<svg/>", s"Page $page of the month.", "month.png", false))
      override def monthPoster(view: MonthCardView, look: CardLook): Option[CardPage] =
        Some(CardPage("card.month.poster.v1", "<svg/>", "November 2026, your month in one image.", "poster.png", false))
    val drawing: CardDrawing = (page, _) =>
      Right(AttachmentRef(MediaKind.Photo, s"asset-${page.templateId}", "image/png", page.altText))
    val rig = Rig(start = Instant.parse("2026-12-01T09:00:00+02:00"), preset = PromptPresets.MonthlyReport, cards = withPoster, drawing = drawing)
    fixtures.grant(rig.account, ConsentPurpose.ImagesInChat)
    (1 to 14).foreach { day =>
      rig.clock.at = Instant.parse(f"2026-11-$day%02dT12:00:00+02:00")
      rig.drink(500, s"poster-$day")
    }
    rig.clock.at = Instant.parse("2026-12-01T10:00:05+02:00")
    rig.tickAndSend()
    val List((_, album), (_, text)) = chatOf(rig): @unchecked
    assertEquals(
      album.album.map(_.assetKey),
      List("asset-card.month.p1", "asset-card.month.p2", "asset-card.month.poster.v1")
    )
    assert(text.toString.contains("Your month"), text)

  test("a monthly report of a month short of the gate sends nothing"):
    val first = Instant.parse("2027-01-01T09:00:00+02:00")
    val rig = Rig(start = first, preset = PromptPresets.MonthlyReport)
    (1 to 13).foreach { day =>
      rig.clock.at = Instant.parse(f"2026-12-$day%02dT12:00:00+02:00")
      rig.drink(500, s"d-$day")
    }
    rig.clock.at = Instant.parse("2027-01-01T10:00:05+02:00")
    rig.tickAndSend()
    assertEquals(
      rig.allPrompts.filter(_.planDate == LocalDate.parse("2027-01-01")).map(p => (p.status, p.reason)),
      List(PromptStatus.Suppressed -> Some(SuppressReason.NoRecords))
    )
    assertEquals(rig.open, Nil)
  // The morning summary (supervisor decision, 2026-11-27): yesterday in one message, at the time the user picked.

  test("a report's text falls due after its images, so a batch never sends the text before the card"):
    // The verification of 2026-11-28 saw the morning summary's text before its daily card: both rows fell due at the
    // same instant, and a claimed batch has no order among equals.
    val pages = StatusCards.textOnly
    val dayCard = new StatusCards:
      export pages.{mood, water, sleep, food}
      override def day(view: DayCardView, look: CardLook): Option[CardPage] =
        Some(CardPage("card.day.v1", "<svg/>", s"Day card of ${view.date}.", "day.png", false))
    val drawing: CardDrawing = (page, _) =>
      Right(AttachmentRef(MediaKind.Photo, s"asset-${page.templateId}", "image/png", page.altText))
    val rig = Rig(start = sundayAt("09:00"), preset = PromptPresets.morningSummary(8), cards = dayCard, drawing = drawing)
    fixtures.grant(rig.account, ConsentPurpose.ImagesInChat)
    rig.clock.at = sundayAt("10:00")
    rig.drink(700, "order-sunday")
    rig.clock.at = mondayAt("08:00").plusSeconds(5)
    rig.loop().tick()
    val due = withConnection: conn =>
      given Connection = conn
      given RowMapper[(String, Instant)] = rs => (rs.getString(1), rs.getTimestamp(2).toInstant)
      sql"""SELECT send_key, next_attempt_at FROM outbox_messages
            WHERE account_id = ${rig.account} AND status = 'queued' AND kind = ${PromptLoop.OutboxKind}"""
        .query[(String, Instant)]()
    val (images, text) = due.partition(_._1.contains(":image"))
    assertEquals((images.size, text.size), (1, 1), due)
    assert(images.head._2.isBefore(text.head._2), due)

  test("the morning summary sends yesterday in one message after the daily card, with Stop these"):
    val pages = StatusCards.textOnly
    val dayCard = new StatusCards:
      export pages.{mood, water, sleep, food}
      override def day(view: DayCardView, look: CardLook): Option[CardPage] =
        Some(CardPage("card.day.v1", "<svg/>", s"Day card of ${view.date}.", "day.png", false))
    val drawing: CardDrawing = (page, _) =>
      Right(AttachmentRef(MediaKind.Photo, s"asset-${page.templateId}", "image/png", page.altText))
    val rig = Rig(start = sundayAt("09:00"), preset = PromptPresets.morningSummary(8), cards = dayCard, drawing = drawing)
    fixtures.grant(rig.account, ConsentPurpose.ImagesInChat)
    rig.clock.at = sundayAt("10:00")
    rig.drink(700, "d-sunday")
    rig.clock.at = mondayAt("07:30")
    rig.drink(300, "d-monday")
    rig.clock.at = mondayAt("08:00").plusSeconds(5)
    rig.tickAndSend()
    val List((_, photo), (_, text)) = chatOf(rig): @unchecked
    assertEquals(photo.attachment.map(_.altText), Some("Day card of 2026-11-22."))
    assert(text.toString.contains("Yesterday, Sun 22 Nov"), text)
    // Yesterday's 700 ml, not this morning's 300 ml.
    assert(text.toString.contains("Water: 0.7 l"), text)
    assertEquals(rig.labels(text), List("Stop these"))
    val sent = rig.allPrompts.filter(_.kind == PromptKind.DailyReport)
    assertEquals(sent.map(p => (p.status, p.budgeted)), List(PromptStatus.Sent -> true), "it counts toward the budget")
    // One summary a day: a later pass sends nothing more.
    rig.clock.at = mondayAt("08:40")
    rig.tickAndSend()
    assertEquals(chatOf(rig).size, 2)

  test("on the day of the weekly report the morning summary goes as text: one unprompted image a day"):
    val pages = StatusCards.textOnly
    val cards = new StatusCards:
      export pages.{mood, water, sleep, food}
      override def day(view: DayCardView, look: CardLook): Option[CardPage] =
        Some(CardPage("card.day.v1", "<svg/>", "Day card.", "day.png", false))
      override def week(view: WeekCardView, look: CardLook): Option[CardPage] =
        Some(CardPage("card.week.v1", "<svg/>", "Week card.", "week.png", false))
    val drawing: CardDrawing = (page, _) =>
      Right(AttachmentRef(MediaKind.Photo, s"asset-${page.templateId}", "image/png", page.altText))
    val rig = Rig(start = sundayAt("09:00"), preset = PromptPresets.morningSummary(8), cards = cards, drawing = drawing)
    rig.uow.transaction(tx => assert(rig.settings.apply(tx, rig.account, PromptPresets.WeeklyReport).isRight))
    fixtures.grant(rig.account, ConsentPurpose.ImagesInChat)
    rig.clock.at = sundayAt("10:00")
    rig.drink(700, "i-sunday")
    rig.clock.at = mondayAt("08:00").plusSeconds(5)
    rig.tickAndSend()
    val List((_, summary)) = chatOf(rig): @unchecked
    assertEquals(summary.attachment, None)
    assert(summary.toString.contains("Yesterday, Sun 22 Nov"), summary)
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.tickAndSend()
    assertEquals(chatOf(rig).flatMap(_._2.attachment).map(_.altText), List("Week card."))

  // Image round 5 (supervisor decision, 2026-11-28): the week highlights (VISUALS IMG-25) after the weekly card.
  test("the weekly report sends its card and the week highlights as one album, the highest day of each domain"):
    val pages = StatusCards.textOnly
    val views = scala.collection.mutable.ListBuffer.empty[WeekHighlightsView]
    val cards = new StatusCards:
      export pages.{mood, water, day, sleep, food}
      override def week(view: WeekCardView, look: CardLook): Option[CardPage] =
        Some(CardPage("card.week.v1", "<svg/>", "Week card.", "week.png", false))
      override def weekHighlights(view: WeekHighlightsView, look: CardLook): Option[CardPage] =
        views += view
        Some(CardPage("card.week.highlights.v1", "<svg/>", "Highlights card.", "highlights.png", false))
    val drawing: CardDrawing = (page, _) =>
      Right(AttachmentRef(MediaKind.Photo, s"asset-${page.templateId}", "image/png", page.altText))
    val rig = Rig(start = Instant.parse("2026-11-16T08:00:00+02:00"), preset = PromptPresets.WeeklyReport, cards = cards, drawing = drawing)
    fixtures.grant(rig.account, ConsentPurpose.ImagesInChat)
    List("2026-11-16" -> 500, "2026-11-18" -> 900, "2026-11-18" -> 800, "2026-11-20" -> 1200).zipWithIndex.foreach:
      case ((date, ml), index) =>
        rig.clock.at = Instant.parse(s"${date}T12:0${index}:00+02:00")
        rig.drink(ml, s"highlight-$index")
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.tickAndSend()
    val List((_, album), (_, text)) = chatOf(rig): @unchecked
    assertEquals(album.album.map(_.altText), List("Week card.", "Highlights card."))
    assert(text.toString.contains("Your week: the last 7 days"), text)
    val List(view) = views.toList: @unchecked
    assertEquals(view.from, LocalDate.parse("2026-11-16"))
    // Wednesday's two glasses make the highest day; medication is never a row.
    assertEquals(view.rows, List(DayHighlight(HighlightDomain.Water, List(0, 2, 4), Some(List(2) -> 1700.0))))

  // Analytics round 6 (supervisor decision, 2026-11-28): IR-05 reaches the weekly report as its one pattern.
  test("the weekly report carries IR-05: mood on days with every planned meal logged, both counts, not a cause"):
    val rig = Rig(start = Instant.parse("2026-11-02T08:00:00+02:00"), preset = PromptPresets.WeeklyReport)
    fixtures.grant(rig.account, ConsentPurpose.Nutrition, ConsentPurpose.Mood, ConsentPurpose.CrossDomainPatterns)
    val parts = CaptureParts.over(rig.uow, rig.clock, ids, StatusCards.textOnly)
    (3 to 22).foreach: day =>
      val all = day % 2 == 0
      rig.clock.at = Instant.parse(f"2026-11-$day%02dT12:00:00+02:00")
      val meals = if all then List(MealType.Breakfast, MealType.Lunch, MealType.Dinner) else List(MealType.Lunch)
      rig.uow.transaction: tx =>
        meals.foreach: meal =>
          assert(parts.nutrition.log(tx, LogMeal(rig.account, MealInput(meal, None, None), EntrySource.Bot, s"m$day$meal")).isRight)
        val valence = dosegram.core.domain.mood.Valence.of(if all then 4 else 3).get
        val checkIn = dosegram.core.application.mood.CheckIn(
          rig.account, valence, None, Nil, EntryDay.Today, EntrySource.Bot, s"c$day"
        )
        assert(parts.mood.checkIn(tx, checkIn).isRight)
    rig.clock.at = mondayAt("09:00").plusSeconds(5)
    rig.tickAndSend()
    val (_, message) = chatOf(rig).lastOption.getOrElse(fail("no report was sent"))
    assert(message.toString.contains("On the 10 days you logged every meal you planned"), message)
    assert(message.toString.contains("On the 10 other days with a meal logged"), message)
    assert(message.toString.contains("It does not show a cause."), message)

  test("the morning summary of a day without records sends nothing (VA-PS-05)"):
    val rig = Rig(start = sundayAt("09:00"), preset = PromptPresets.morningSummary(8))
    rig.clock.at = mondayAt("08:00").plusSeconds(5)
    rig.tickAndSend()
    assertEquals(rig.allPrompts.map(p => (p.status, p.reason)), List(PromptStatus.Suppressed -> Some(SuppressReason.NoRecords)))
    assertEquals(chatOf(rig), Nil)

  test("the morning summary is held in quiet hours and by the shared daily budget"):
    val quiet = Rig(start = sundayAt("09:00"), preset = PromptPresets.morningSummary(7))
    quiet.uow.transaction(tx =>
      assert(quiet.settings.setQuiet(tx, quiet.account, QuietHours(HhMm.unsafe("22:00"), HhMm.unsafe("07:30"))).isRight)
    )
    quiet.clock.at = sundayAt("10:00")
    quiet.drink(700, "q-sunday")
    quiet.clock.at = mondayAt("07:00").plusSeconds(5)
    quiet.tickAndSend()
    assertEquals(
      quiet.allPrompts.filter(_.kind == PromptKind.DailyReport).map(_.reason),
      List(Some(SuppressReason.QuietHours))
    )
    assertEquals(chatOf(quiet), Nil)
    // Three meal nudges and an evening mood prompt spend the default budget of four before the summary at 10:00.
    val busy = Rig(start = sundayAt("09:00"), preset = PromptPresets.MealAnchors, consent = ConsentPurpose.Nutrition)
    List(ConsentPurpose.Mood, ConsentPurpose.Hydration).foreach(fixtures.grant(busy.account, _))
    busy.uow.transaction: tx =>
      List(PromptPresets.MoodEvening, PromptPresets.morningSummary(10))
        .foreach(preset => assert(busy.settings.apply(tx, busy.account, preset).isRight))
      List(Anchor.Breakfast -> "07:10", Anchor.Lunch -> "07:40", Anchor.Dinner -> "08:10").foreach: (anchor, at) =>
        assert(busy.settings.setAnchor(tx, busy.account, anchor, LocalTime.parse(at)).isRight)
    busy.clock.at = sundayAt("10:00")
    busy.drink(700, "b-sunday")
    List("07:10", "07:40", "08:10", "09:10", "10:00").foreach: hhmm =>
      busy.clock.at = mondayAt(hhmm).plusSeconds(5)
      busy.tickAndSend()
    val monday = busy.allPrompts.filter(_.planDate == LocalDate.parse("2026-11-23"))
    assertEquals(monday.count(p => p.budgeted && p.status.wasSent), 4)
    assertEquals(monday.filter(_.kind == PromptKind.DailyReport).map(_.reason), List(Some(SuppressReason.Budget)))

  test("meal nudges (G8.4): each meal anchor names its meal, a meal logged that day suppresses it, a tap logs it"):
    val rig = Rig(start = kyiv("07:00"), preset = PromptPresets.MealAnchors, consent = ConsentPurpose.Nutrition)
    val parts = CaptureParts.over(rig.uow, rig.clock, ids, StatusCards.textOnly)
    rig.at("07:40")
    // Breakfast is logged before its anchor, at 07:40: the 08:00 nudge is not sent.
    rig.uow.transaction: tx =>
      val meal = MealInput(MealType.Breakfast, None, None)
      assert(parts.nutrition.log(tx, LogMeal(rig.account, meal, EntrySource.Bot, "breakfast")).isRight)
    rig.at("08:00")
    rig.tickAndSend()
    assertEquals(rig.prompts.map(p => (p.slotKey, p.status, p.reason)), List(("0800", PromptStatus.Suppressed, Some(SuppressReason.RecentEntry))))
    assertEquals(rig.open, Nil)
    rig.at("13:00")
    rig.tickAndSend()
    val List((handle, message)) = rig.open: @unchecked
    assert(message.chunks.mkString.contains("Meal reminder: lunch."), message.chunks)
    assertEquals(rig.labels(message), List("Log lunch", "Snooze 1 h", "Pause for today"))
    assertEquals(rig.prompts.last.budgeted, true, "a meal nudge counts against the shared budget")
    val handler = RemindersHandler(codec, rig.clock, rig.settings, parts, inner = (_, _, _) => Reply.empty)
    val wire = message.choiceMap.find(_.label == "Log lunch").get.callback
    rig.at("13:04")
    val outcome = rig.tap(handler, handle, wire, "meal-tap-1").replace.getOrElse(fail("expected the prompt's outcome"))
    assert(outcome.body.toString.contains("Lunch saved at 13:04."), outcome.body)
    assertEquals(rig.prompts.last.status, PromptStatus.Answered)
    val meals = rig.uow.transaction(_.tracking.meals.listLive(rig.account, DateRange.single(LocalDate.parse(monday))))
    assertEquals(meals.map(_.mealType), List(MealType.Breakfast, MealType.Lunch))

  test("meal nudges are silent and stop without the nutrition consent"):
    val rig = Rig(start = kyiv("07:00"), preset = PromptPresets.MealAnchors, consent = ConsentPurpose.Nutrition)
    rig.at("08:00")
    rig.tickAndSend()
    val List((_, sent)) = rig.open: @unchecked
    assert(sent.silent, "a meal nudge is silent")
    rig.uow.transaction(_.consents.withdraw(rig.account, ConsentPurpose.Nutrition, rig.clock.now()))
    rig.at("13:00")
    rig.tickAndSend()
    assertEquals(rig.prompts.last.reason, Some(SuppressReason.NoConsent))

  test("catch-up after downtime sends at most the newest prompt, and nothing once it is stale"):
    val rig = Rig()
    rig.at("12:05")
    rig.tickAndSend()
    assertEquals(rig.adapter.ops.count(_.isInstanceOf[VendorOp.Send]), 1)
    assertEquals(
      rig.prompts.map(p => (p.slotKey, p.reason)),
      List("0930", "1000", "1030", "1100", "1130").map(_ -> Some(SuppressReason.CatchUp)) :+ ("1200" -> None)
    )
    rig.at("17:45")
    rig.tickAndSend()
    assertEquals(rig.adapter.ops.count(_.isInstanceOf[VendorOp.Send]), 1, "the 17:00 slot is stale at 17:45")
    assertEquals(rig.prompts.last.reason, Some(SuppressReason.Stale))
    assert(rig.prompts.forall(_.dueAt.isBefore(rig.clock.now())))

  test("two loops and a restart decide each slot once: one outbox row per send key"):
    val rig = Rig()
    rig.at("09:30")
    val start = CountDownLatch(1)
    val threads = (1 to 4).map: _ =>
      val loop = rig.loop()
      Thread.ofVirtual().start(() => { start.await(); loop.tick(); () })
    start.countDown()
    threads.foreach(_.join())
    rig.loop().tick()
    assertEquals(rig.errors.toList, Nil)
    assertEquals(rig.prompts.size, 1)
    withConnection: conn =>
      given Connection = conn
      val rows = sql"SELECT count(*) FROM outbox_messages WHERE account_id = ${rig.account} AND kind = 'prompt'"
        .queryOne[Int]()
      assertEquals(rows, Some(1))

  test("a plan turned on mid-morning decides nothing before it was chosen"):
    val rig = Rig(start = kyiv("11:10"))
    rig.at("11:30")
    rig.tickAndSend()
    assertEquals(rig.prompts.map(_.slotKey), List("1130"))
    assertEquals(Duration.between(rig.prompts.head.dueAt, kyiv("11:30")), Duration.ZERO)

  // ---------- 30 days with the owner's preset (ADR-023) ----------

  private val zone = ZoneId.of("Europe/Kyiv")

  /** A Monday; the 30 days include the end of summer time on Sunday 25 October. */
  private val simulationStart = LocalDate.of(2026, 10, 12)

  /** What the simulated user does after each pass of the loop, given the prompt just sent, if any. */
  private type Persona = (Rig, Option[PromptOccurrence], Instant) => Unit

  /** Ticks every 30 minutes, 5 seconds past the slot, for 30 days, and returns every decided prompt by plan date. */
  private def simulate(rig: Rig, persona: Persona): Map[LocalDate, List[PromptOccurrence]] =
    val days = (0 until 30).map(i => simulationStart.plusDays(i.toLong))
    for
      date <- days
      half <- 0 until 48
    do
      val local = date.atStartOfDay.plusMinutes(30L * half).plusSeconds(5)
      val tick = local.atZone(zone).toInstant
      rig.clock.at = tick
      rig.tickAndSend()
      persona(rig, rig.sentBy(tick), rig.clock.now())
    rig.allPrompts.groupBy(_.planDate)

  private def isWorkDay(date: LocalDate): Boolean = date.getDayOfWeek.getValue <= 5

  private def sentOn(day: List[PromptOccurrence]): Int = day.count(_.status.wasSent)

  private def assertQuiet(all: Map[LocalDate, List[PromptOccurrence]]): Unit =
    val quiet = DayProfile.DefaultQuiet
    all.values.flatten
      .filter(_.status.wasSent)
      .foreach(p => assert(!quiet.contains(p.dueAt.atZone(zone).toLocalTime), s"sent inside quiet hours: $p"))

  private def at(date: LocalDate, hhmm: String): Instant = date.atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant

  private def simulationDays: List[LocalDate] = (0 until 30).toList.map(i => simulationStart.plusDays(i.toLong))

  test("30 days, a user who answers every prompt: 16 slots per work day, every second one sent, none at weekends"):
    val rig = Rig(start = at(simulationStart, "00:00"))
    val all = simulate(
      rig,
      (r, sent, now) =>
        sent.foreach: p =>
          r.clock.at = now.plus(Duration.ofMinutes(5))
          r.drink(250, s"answer-${p.id}")
    )
    simulationDays.foreach: date =>
      val day = all.getOrElse(date, Nil)
      if isWorkDay(date) then
        assertEquals(day.size, 16, s"$date")
        assertEquals(sentOn(day), 8, s"$date: the answer to a prompt skips the next slot")
        assertEquals(day.filterNot(_.status.wasSent).flatMap(_.reason).distinct, List(SuppressReason.RecentEntry))
      else assertEquals(day, Nil, s"$date is a weekend")
    assertQuiet(all)
    assertEquals(rig.open.size, 1, "the chat holds one open prompt at the end")

  test("30 days, a user who never answers: the interval widens after three, the day pauses after six"):
    val rig = Rig(start = at(simulationStart, "00:00"))
    val all = simulate(rig, (_, _, _) => ())
    simulationDays.filter(isWorkDay).foreach: date =>
      val day = all(date)
      assertEquals(day.size, 16, s"$date")
      assertEquals(
        day.filter(_.status.wasSent).map(_.slotKey),
        List("0930", "1000", "1030", "1130", "1230", "1330"),
        s"$date"
      )
      assertEquals(day.count(_.reason.contains(SuppressReason.Ignored)), 7, s"$date")
    assertEquals(all.keySet.filterNot(isWorkDay), Set.empty[LocalDate])
    assertQuiet(all)
    assertEquals(rig.open.size, 1, "the paused day leaves one message: the pause notice")

  test("30 days, a user who drinks at a quarter to every hour toward a 2 l goal: the goal ends the day"):
    val rig = Rig(start = at(simulationStart, "00:00"))
    rig.uow.transaction(tx => rig.hydration.setGoal(tx, rig.account, Some(2000)))
    val all = simulate(
      rig,
      (r, _, now) =>
        val local = now.atZone(zone).toLocalTime
        // The tick of half past each hour from 09:30 to 16:30: the user drinks 15 minutes later.
        if local.getMinute == 30 && local.getHour >= 9 && local.getHour <= 16 then
          r.clock.at = now.plus(Duration.ofMinutes(15))
          r.drink(250, s"own-${r.clock.at}")
    )
    simulationDays.filter(isWorkDay).foreach: date =>
      val day = all(date)
      assertEquals(day.size, 16, s"$date")
      assertEquals(sentOn(day), 8, s"$date")
      assertEquals(day.last.reason, Some(SuppressReason.GoalReached), s"$date: 2 l reached at 16:45")
    assertQuiet(all)

  test("30 days of a window across midnight: nothing is sent inside quiet hours"):
    val evening = PromptPresets.waterCustom(
      NudgePlan.Interval(TimeRef.At(LocalTime.of(20, 0)), TimeRef.At(LocalTime.of(6, 0)), 30, DaySet.Every, false)
    )
    val rig = Rig(start = at(simulationStart, "00:00"), preset = evening)
    val all = simulate(
      rig,
      (r, sent, now) =>
        sent.foreach: p =>
          r.clock.at = now.plusSeconds(60)
          r.drink(100, s"e-${p.id}")
    )
    // The first plan date is the evening before the start, the last one runs past the end: both are partial.
    val whole = (date: LocalDate) => !date.isBefore(simulationStart) && date.isBefore(simulationStart.plusDays(29))
    assertEquals(all.keySet.count(whole), 29)
    all.filter((date, _) => whole(date)).foreach: (date, day) =>
      assertEquals(day.size, 19, s"$date: 20:30 to 05:30")
      assertEquals(day.filter(_.status.wasSent).map(_.slotKey), List("2030", "2130"), s"$date")
      assertEquals(day.count(_.reason.contains(SuppressReason.QuietHours)), 16, s"$date")
    assertQuiet(all)
