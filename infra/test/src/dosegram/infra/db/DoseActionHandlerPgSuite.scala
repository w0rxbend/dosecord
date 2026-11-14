package dosegram.infra.db

import dosegram.infra.crypto.DataKeys
import dosegram.contracts.*
import dosegram.core.application.Application
import dosegram.core.application.CommandRegistry
import dosegram.core.application.capture.CaptureParts
import dosegram.core.ports.IdSource
import dosegram.core.ports.StatusCards
import dosegram.core.chat.*
import dosegram.core.domain.ActionRowIntent
import dosegram.core.domain.Actor as DomainActor
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.DoseActionKind
import dosegram.core.ports.NewDoseAction

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import scala.language.implicitConversions

/** M1.10 acceptance on Testcontainers Postgres 18: the one-tap handler's `SELECT ... FOR UPDATE` against an in-flight
  * tick (the tap waits and applies on the fresh row, DESIGN.md section 7.4), the `dose_actions` idempotency key and
  * vendor attribution, exactly one `intake_taken.v1` per taken, the second-Taken no-op, and `/log`'s manual
  * occurrence — all through the real mediator and the M1.10 [[Application]] composition.
  */
class DoseActionHandlerPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  private val actor = PlatformIdentity("fake", "user-1", None)
  private lazy val fixtures = Fixtures(dataSource)

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE dose_actions, dose_occurrences, schedule_revisions, medication_schedules, medications, " +
            "inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "delivery_channels, platform_identities, users CASCADE"
        )
      finally st.close()
    }

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  /** A linked identity with a known vendor user id plus its healthy primary channel (Fixtures.deliveryChannel mints
    * its own vendor user id; the mediator's actor must match it).
    */
  private def seedLinkedAccount(chatId: String): UUID =
    val accountId = UUID.randomUUID()
    val identityId = UUID.randomUUID()
    val channelId = UUID.randomUUID()
    withConnection { conn =>
      given Connection = conn
      sql"INSERT INTO users (id, timezone, status) VALUES ($accountId, 'Europe/Kyiv', 'active')".execute()
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, dm_channel_id, linked_at)
            VALUES ($identityId, $accountId, 'fake', 'user-1', $chatId, $t0)""".execute()
      sql"""INSERT INTO delivery_channels (id, account_id, platform_identity_id, role, priority, state, updated_at)
            VALUES ($channelId, $accountId, $identityId, 'primary', 0, 'healthy', $t0)""".execute()
    }
    fixtures.grant(accountId, dosegram.core.ports.ConsentPurpose.Medication)
    accountId

  private final class SyncAdapter(val inner: FakeAdapter) extends ChatAdapter:
    private def around[A](f: => A): A = inner.synchronized(f)
    override def vendor: String = inner.vendor
    override def capabilities: CapabilityProfile = inner.capabilities
    override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = around(inner.start(sink, resumeFrom))
    override def stop(): Unit = around(inner.stop())
    override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle = around(
      inner.send(chat, rendered, sendKey)
    )
    override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle = around(
      inner.edit(handle, rendered)
    )
    override def delete(handle: MessageHandle): Unit = around(inner.delete(handle))
    override def registerCommands(specs: List[CommandSpec]): Unit = around(inner.registerCommands(specs))
    override def resolveChat(identity: PlatformIdentity): ChatRef = around(inner.resolveChat(identity))
    override def renderText(text: RichText): List[String] = around(inner.renderText(text))

  private def sendsOf(adapter: SyncAdapter): List[VendorOp.Send] =
    adapter.inner.synchronized(adapter.inner.ops.collect { case s: VendorOp.Send => s })

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(20)
      ok = cond
    assert(ok, clue)

  private def token(action: String, subject: UUID, value: Long = 0): String =
    codec.encode(CallbackMode.Direct, ActionRegistry.byName(action).get, subject, value).wire

  private def callbackRef(raw: String): CallbackRef =
    val payload = codec.decode(raw).toOption.get
    CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, raw)

  private def event(body: Inbound, vendorEventId: String): InboundEvent =
    InboundEvent(
      EventId(UUID.randomUUID()),
      "fake",
      vendorEventId,
      t0,
      actor = actor,
      chat = ChatRef("fake", "chat-1"),
      principal = None,
      body = body
    )

  private def mediator(clock: MutableClock, adapter: SyncAdapter)(using ox.Ox): ChatMediator =
    val uow = PgUnitOfWork(dataSource, clock, DataKeys.development)
    val adapters: Map[String, ChatAdapter] = Map("fake" -> adapter)
    ChatMediator(uow, adapters, codec, Application.handler(uow, adapters, codec, clock)(textOnlyParts(uow, clock)), clock, CommandRegistry.byName)

  private def textOnlyParts(uow: dosegram.core.ports.UnitOfWork, clock: dosegram.core.ports.Clock): CaptureParts =
    CaptureParts.over(uow, clock, IdSource.uuidV7(java.util.random.RandomGenerator.getDefault), StatusCards.textOnly)

  test("a tap on a row claimed by an in-flight tick waits and applies on the fresh row"):
    ox.supervised:
      val account = seedLinkedAccount("chat-1")
      val clock = MutableClock(t0)
      val created = fixtures.schedule(clock, account, "Vitamin D", dailyAt("09:00"), kyiv,
        doseAmount = Some(BigDecimal(1000)), doseUnit = Some("IU"))
      clock.advance(Duration.ofMinutes(365)) // 06:05Z: the 09:00 Kyiv dose is due
      val occurrenceId = fixtures.uow
        .transaction(_.occurrences.listBySchedule(created.scheduleId))
        .head
        .id
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val m = mediator(clock, adapter)

      // The in-flight tick: claims the row FOR UPDATE and holds the lock across the latch.
      val locked = CountDownLatch(1)
      val release = CountDownLatch(1)
      val tick = Thread.ofVirtual().start(() =>
        fixtures.uow.transaction: tx =>
          val rows = tx.occurrences.claimDue(clock.now(), 50)
          assert(rows.size == 1, s"one claimed row, got ${rows.size}")
          locked.countDown()
          release.await()
          val occ = rows.head
          val dueState = occ.state.copy(
            status = OccurrenceStatus.Due,
            reminderSeq = 1,
            lastRemindedAt = Some(clock.now()),
            nextActionAt = Some(clock.now().plusSeconds(600)),
            epoch = occ.state.epoch + 1
          )
          assert(tx.occurrences.applyTransition(occ.id, occ.version, occ.state.epoch, dueState, clock.now()))
          tx.doseActions.append(
            NewDoseAction.from(
              ActionRowIntent(DoseActionKind.ReminderSent, DomainActor.System, clock.now(),
                OccurrenceStatus.Pending, OccurrenceStatus.Due),
              occ.id,
              occ.accountId,
              "tick"
            )
          )
          ()
      )
      locked.await()

      // The tap: its transaction blocks on the row lock until the tick commits, then decides on the fresh row.
      Thread.ofVirtual().start(() => m.push(event(
        Inbound.InteractionSubmitted(callbackRef(token("dose.taken", occurrenceId)), None),
        "evt-tap-1"
      )))
      Thread.sleep(300)
      assert(sendsOf(adapter).isEmpty, "the tap is still waiting on the row the tick holds")
      release.countDown()
      tick.join(15_000)
      await(sendsOf(adapter).nonEmpty, "the tap completed after the tick committed")

      val finalOcc = fixtures.uow.transaction(_.occurrences.get(occurrenceId)).get
      assertEquals(finalOcc.status, OccurrenceStatus.Taken)
      val actions = fixtures.uow.transaction(_.doseActions.listForOccurrence(occurrenceId))
      assertEquals(actions.map(_.action), List(DoseActionKind.ReminderSent, DoseActionKind.Taken))
      assertEquals(
        actions(1).priorStatus,
        OccurrenceStatus.Due,
        "the tap decided on the fresh row the tick wrote, not the stale pending one"
      )
      assertEquals(actions(1).effectiveAt, Some(clock.now()))

  test("a taken carries idempotency key, vendor and message id; a second Taken writes nothing"):
    ox.supervised:
      val account = seedLinkedAccount("chat-1")
      val clock = MutableClock(t0)
      val created = fixtures.schedule(clock, account, "Vitamin D", dailyAt("09:00"), kyiv)
      clock.advance(Duration.ofMinutes(365))
      val occurrenceId = fixtures.occurrenceAt(created.scheduleId, Instant.parse("2026-09-21T06:00:00Z"), "tx900",
        status = OccurrenceStatus.Due)
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val m = mediator(clock, adapter)
      val source = MessageHandle("fake", "chat-1", "m1")

      m.push(event(
        Inbound.InteractionSubmitted(callbackRef(token("dose.taken", occurrenceId)), Some(source)),
        "evt-taken-1"
      ))
      await(sendsOf(adapter).size == 1, "the recorded toast")

      val actions = fixtures.uow.transaction(_.doseActions.listForOccurrence(occurrenceId))
      assertEquals(actions.map(_.action), List(DoseActionKind.Taken))
      assertEquals(actions.head.idempotencyKey, Some("fake:evt-taken-1"))
      assertEquals(actions.head.vendor, Some("fake"))
      assertEquals(actions.head.platformMessageId, Some("m1"))
      assertEquals(
        countSql("SELECT count(*) FROM domain_events WHERE type = 'dosegram.medication.intake_taken.v1'"),
        1L,
        "exactly one intake_taken.v1"
      )

      // A second Taken on the same control: "Already recorded", no new action row, no new domain event.
      m.push(event(
        Inbound.InteractionSubmitted(callbackRef(token("dose.taken", occurrenceId)), Some(source)),
        "evt-taken-2"
      ))
      await(sendsOf(adapter).size == 2, "the already-recorded answer")
      assert(sendsOf(adapter)(1).message.chunks.exists(_.contains("Already recorded at")), s"$sendsOf(adapter)")
      assertEquals(fixtures.uow.transaction(_.doseActions.listForOccurrence(occurrenceId)).size, 1)
      assertEquals(
        countSql("SELECT count(*) FROM domain_events WHERE type = 'dosegram.medication.intake_taken.v1'"),
        1L
      )

  test("/log vitamin d creates one manual occurrence with taken"):
    ox.supervised:
      val account = seedLinkedAccount("chat-1")
      val clock = MutableClock(t0)
      fixtures.medication(account, "Vitamin D", t0, doseAmount = Some(BigDecimal(1000)), doseUnit = Some("IU"))
      clock.advance(Duration.ofMinutes(365))
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val m = mediator(clock, adapter)

      m.push(event(Inbound.CommandInvoked("log", Map.empty, "/log vitamin d"), "evt-log-1"))
      await(sendsOf(adapter).size == 1, "the recorded toast")

      val rows = withConnection { conn =>
        val st = conn.createStatement()
        try
          val rs = st.executeQuery(
            s"SELECT id, origin, status, slot_key FROM dose_occurrences WHERE account_id = '$account'"
          )
          val out = List.newBuilder[(UUID, String, String, String)]
          while rs.next() do out += ((UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4)))
          out.result()
        finally st.close()
      }
      assertEquals(rows.size, 1, "exactly one manual occurrence")
      assertEquals(rows.head._2, "manual")
      assertEquals(rows.head._3, "taken")
      assert(rows.head._4.startsWith("manual:"), s"the manual slot key: ${rows.head._4}")
      val actions = fixtures.uow.transaction(_.doseActions.listForOccurrence(rows.head._1))
      assertEquals(actions.map(_.action), List(DoseActionKind.Taken))
      assertEquals(actions.head.idempotencyKey, Some("fake:evt-log-1"))
      assertEquals(
        countSql("SELECT count(*) FROM domain_events WHERE type = 'dosegram.medication.intake_taken.v1'"),
        1L
      )

  test("two racing taps on one log picker, from two processes, and a tap after a restart record one dose"):
    ox.supervised:
      val account = seedLinkedAccount("chat-1")
      val clock = MutableClock(t0)
      val medication = fixtures.medication(account, "Vitamin D", t0)
      clock.advance(Duration.ofMinutes(365))
      val picker = MessageHandle("fake", "chat-1", "m7")
      val tap = Inbound.InteractionSubmitted(callbackRef(token("dose.log", medication)), Some(picker))
      val adapters = List.fill(3)(SyncAdapter(FakeAdapter(CapabilityProfiles.Telegram)))
      val mediators = adapters.map(mediator(clock, _))
      def toasts = adapters.flatMap(sendsOf).filter(_.message.deleteAfter.isDefined).flatMap(_.message.chunks)

      // Two processes take two updates of the same picker at once; the picker's primary key admits one occurrence.
      val start = CountDownLatch(1)
      val racers = List("evt-log-a", "evt-log-b").zip(mediators).map: (id, m) =>
        Thread.ofVirtual().start(() =>
          start.await()
          m.push(event(tap, id))
        )
      start.countDown()
      racers.foreach(_.join(15_000))
      await(toasts.size == 2, s"both taps answered: $toasts")
      assertEquals(toasts.sorted, List("Already logged at 09:05.", "Recorded at 09:05."))

      // A third process, after a restart, takes a late tap on the same stale keyboard.
      mediators(2).push(event(tap, "evt-log-c"))
      await(toasts.size == 3, s"the late tap answered: $toasts")
      assertEquals(toasts.last, "Already logged at 09:05.")

      assertEquals(countSql(s"SELECT count(*) FROM dose_occurrences WHERE account_id = '$account'"), 1L)
      assertEquals(countSql("SELECT count(*) FROM dose_actions WHERE action = 'taken'"), 1L)
      assertEquals(
        countSql("SELECT count(*) FROM domain_events WHERE type = 'dosegram.medication.intake_taken.v1'"),
        1L
      )

  test("Undo of a manual log cancels the row in Postgres and leaves the loop nothing to claim"):
    ox.supervised:
      val account = seedLinkedAccount("chat-1")
      val clock = MutableClock(t0)
      val medication = fixtures.medication(account, "Vitamin D", t0)
      clock.advance(Duration.ofMinutes(365))
      val picker = MessageHandle("fake", "chat-1", "m7")
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Telegram))
      val m = mediator(clock, adapter)
      def toasts = sendsOf(adapter).filter(_.message.deleteAfter.isDefined).flatMap(_.message.chunks)

      m.push(event(Inbound.InteractionSubmitted(callbackRef(token("dose.log", medication)), Some(picker)), "evt-log"))
      await(toasts.size == 1, s"the log answered: $toasts")
      val occurrenceId = fixtures.uow.transaction(_.occurrences.listForAccountBetween(account, java.time.LocalDate.MIN,
        java.time.LocalDate.MAX)).head.id
      clock.advance(Duration.ofMinutes(5))
      m.push(event(Inbound.InteractionSubmitted(callbackRef(token("dose.undo", occurrenceId)), Some(picker)), "evt-undo"))
      await(
        fixtures.uow.transaction(_.occurrences.get(occurrenceId)).exists(_.status == OccurrenceStatus.Cancelled),
        "the undo cancelled the manual row"
      )

      val actions = fixtures.uow.transaction(_.doseActions.listForOccurrence(occurrenceId))
      assertEquals(actions.map(_.action), List(DoseActionKind.Taken, DoseActionKind.Undone))
      assertEquals(actions.last.newStatus, OccurrenceStatus.Cancelled)
      clock.advance(Duration.ofHours(6))
      assertEquals(fixtures.uow.transaction(_.occurrences.claimDue(clock.now(), 50)), Nil, "nothing to remind")

  private def countSql(query: String): Long = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query)
      rs.next()
      rs.getLong(1)
    finally st.close()
  }
end DoseActionHandlerPgSuite
