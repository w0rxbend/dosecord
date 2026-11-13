package dosegram.infra.db

import dosegram.contracts.*
import dosegram.core.application.capture.CaptureHandler
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.reminders.PromptMessages
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.application.reminders.RemindersHandler
import dosegram.core.chat.*
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.DaySet
import dosegram.core.domain.reminders.NudgePlan
import dosegram.core.domain.reminders.PromptPreset
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.reminders.TimeRef
import dosegram.core.domain.reminders.PromptStatus
import dosegram.core.domain.reminders.SuppressReason
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.PromptOccurrence
import dosegram.core.ports.StatusCards
import dosegram.core.scheduling.CatalogueOutboxRenderer
import dosegram.core.scheduling.OutboxDispatcher
import dosegram.core.scheduling.PromptLoop
import dosegram.core.scheduling.PromptOutboxRenderer

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.random.RandomGenerator
import scala.language.implicitConversions

/** ADR-023 on Testcontainers Postgres 18: water prompts from the plan through the outbox to the fake wire. The owner's
  * preset on a Monday in Kyiv (UTC+2 in November): slots at 09:30 to 17:00 local.
  */
class PromptLoopPgSuite extends PgSuite:

  private val monday = "2026-11-16"
  private lazy val fixtures = Fixtures(dataSource)
  private val ids = IdSource.uuidV7(RandomGenerator.getDefault)
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  /** A local time of the Monday in Kyiv. */
  private def kyiv(hhmm: String): Instant = Instant.parse(s"${monday}T$hhmm:00+02:00")

  private final class Rig(
      start: Instant = kyiv("09:00"),
      preset: PromptPreset = PromptPresets.WaterWorkHours,
      consent: ConsentPurpose = ConsentPurpose.Hydration
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
    val uow = PgUnitOfWork(dataSource, clock)
    val hydration = HydrationService(clock, ids)
    val settings = ReminderSettings(clock, ids)
    val adapter = FakeAdapter(CapabilityProfiles.Telegram)
    val errors = scala.collection.mutable.ListBuffer.empty[Throwable]

    def loop(): PromptLoop =
      PromptLoop(uow, clock, ids, PromptMessages(codec), hydration, settings, (_, e) => errors += e)

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
      rig.clock.at = local.atZone(zone).toInstant
      val before = rig.allPrompts.map(_.id).toSet
      rig.tickAndSend()
      val sent = rig.allPrompts.find(p => !before(p.id) && p.status == PromptStatus.Sent)
      persona(rig, sent, rig.clock.now())
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
