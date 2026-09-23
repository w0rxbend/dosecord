package dosecord.infra.db

import dosecord.contracts.*
import dosecord.core.application.Application
import dosecord.core.application.CommandRegistry
import dosecord.core.chat.*
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.Clock
import dosecord.core.scheduling.ScheduleLifecycle

import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import scala.language.implicitConversions

/** M1.9 acceptance on Testcontainers Postgres 18 (the in-memory half is `MedicationWizardSuite` and `MenuSuite`): the
  * add-medication wizard's rows (medication, schedule, revision, the 48 h occurrences, `schedule_created.v1`, NULL
  * instructions), restart survival at every step through a fresh engine over the same database, `/cancel` leaving no
  * row, pause -> resume from the menu firing the next slot, Account -> Timezone moving the following schedule only,
  * and `/history` matching `dose_occurrences` for the seeded 7 days.
  */
class MedicationWizardPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  private val actor = PlatformIdentity("fake", "user-1")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val utc = ZoneId.of("UTC")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "conversation_sessions, form_runs, callback_slots, dose_actions, dose_occurrences, schedule_revisions, " +
            "medication_schedules, medications, mood_checkins, delivery_channels, platform_identities, users CASCADE"
        )
      finally st.close()
    }

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private lazy val fixtures = Fixtures(dataSource)

  private final class FixedClock(at: Instant) extends Clock:
    override def now(): Instant = at

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
    override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit = around(
      inner.react(handle, emoji, on, txnKey)
    )
    override def registerCommands(specs: List[CommandSpec]): Unit = around(inner.registerCommands(specs))
    override def resolveChat(identity: PlatformIdentity): ChatRef = around(inner.resolveChat(identity))
    override def renderText(text: RichText): List[String] = around(inner.renderText(text))

  private final class Rig(using ox.Ox):
    val clock = FixedClock(t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
    val adapters: Map[String, ChatAdapter] = Map("fake" -> adapter)
    val mediator = ChatMediator(
      uow,
      adapters,
      codec,
      Application.handler(uow, adapters, codec, clock),
      clock,
      commands = CommandRegistry.byName
    )
    private var seq = 0

    def line(input: String): List[String] =
      seq += 1
      val body =
        if input.length > 1 && input.startsWith("/") then
          Inbound.CommandInvoked(input.drop(1).takeWhile(c => !c.isWhitespace), Map.empty, input)
        else Inbound.MessageReceived(input, None, truncated = false)
      val event = InboundEvent(
        EventId(UUID.randomUUID()),
        "fake",
        s"fake:msg:pgwiz:$seq",
        t0,
        actor = actor,
        chat = ChatRef("fake", "dm:user-1"),
        body = body
      )
      val before = sends.size
      mediator.push(event)
      await(sends.size > before, s"'$input' produced a reply")
      sends.drop(before).flatMap(_.message.chunks)

    def sends: List[VendorOp.Send] =
      adapter.inner.synchronized(adapter.inner.ops.collect { case s: VendorOp.Send => s })

    def choiceWire(containing: String, label: String): String =
      adapter.inner.synchronized:
        adapter.inner.sent
          .reverse
          .collectFirst:
            case (_, rendered) if rendered.chunks.exists(_.contains(containing)) =>
              rendered.choiceMap.collectFirst { case e if e.label == label => e.callback }
          .flatten
          .getOrElse(throw new NoSuchElementException(s"no message containing '$containing' with choice '$label'"))

    def tap(wire: String): List[String] =
      seq += 1
      val payload = codec.decode(wire).toOption.get
      val event = InboundEvent(
        EventId(UUID.randomUUID()),
        "fake",
        s"fake:msg:pgwiz:$seq",
        t0,
        actor = actor,
        chat = ChatRef("fake", "dm:user-1"),
        body = Inbound.InteractionSubmitted(
          CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, wire),
          Nil,
          None
        )
      )
      val before = sends.size
      mediator.push(event)
      await(sends.size > before, s"the tap on '$wire' produced a reply")
      sends.drop(before).flatMap(_.message.chunks)

    def tapLabel(containing: String, label: String): List[String] = tap(choiceWire(containing, label))

    def restartEngine(): WizardEngine =
      WizardEngine(
        flows = Application.flows(clock, ScheduleLifecycle(uow, clock)),
        flowCommands = Map.empty,
        codec = codec,
        clock = clock,
        inner = (_: InboundEvent, _: Principal, _: dosecord.core.ports.Tx) => Reply.empty,
        uow = uow,
        adapters = adapters
      )

    /** Seeds the account and links the rig's actor to it (as the create flow would). */
    def seedAccount(timezone: String = "Europe/Kyiv"): UUID =
      val accountId = fixtures.account(timezone)
      withConnection { conn =>
        given Connection = conn
        sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, linked_at)
              VALUES (${UUID.randomUUID()}, $accountId, 'fake', 'user-1', $t0)""".execute()
      }
      accountId

    def seedSchedule(
        accountId: UUID,
        name: String,
        rule: Rule,
        zone: ZoneId,
        doseAmount: Option[BigDecimal] = None,
        doseUnit: Option[String] = None,
        instructions: Option[String] = None,
        tzFollowsUser: Boolean = true
    ) =
      fixtures.schedule(clock, accountId, name, rule, zone, doseAmount = doseAmount, doseUnit = doseUnit,
        instructions = instructions, tzFollowsUser = tzFollowsUser)

    /** The console-grammar wizard drive: /menu -> Medications -> Add medication -> the four answers. */
    def driveWizardToForm(instructions: String): Unit =
      line("/menu")
      line("2") // Medications
      line("2") // Add medication
      line("Vitamin D")
      line("1000 IU")
      line("09:00")
      line(instructions)

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(20)
      ok = cond
    assert(ok, clue)

  private def countSql(query: String): Long = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query)
      rs.next()
      rs.getLong(1)
    finally st.close()
  }

  private final case class MedicationRow(
      name: String,
      doseAmount: Option[BigDecimal],
      doseUnit: Option[String],
      instructions: Option[String],
      status: String
  )
  private given RowMapper[MedicationRow] = rs =>
    MedicationRow(
      rs.getString("name"),
      Option(rs.getBigDecimal("dose_amount")).map(BigDecimal(_)),
      rs.optString("dose_unit"),
      rs.optString("instructions"),
      rs.getString("status")
    )

  private def medicationRows(): List[MedicationRow] = withConnection { conn =>
    given Connection = conn
    sql"SELECT name, dose_amount, dose_unit, instructions, status FROM medications".query[MedicationRow]()
  }

  private final case class OccurrenceRow(scheduledFor: Instant, status: String, revision: Int)
  private given RowMapper[OccurrenceRow] = rs =>
    OccurrenceRow(rs.instant("scheduled_for"), rs.getString("status"), rs.getInt("revision"))

  private def occurrenceRows(scheduleId: UUID): List[OccurrenceRow] = withConnection { conn =>
    given Connection = conn
    sql"""SELECT scheduled_for, status, revision FROM dose_occurrences
          WHERE schedule_id = $scheduleId ORDER BY scheduled_for""".query[OccurrenceRow]()
  }

  // ---------- Wizard create ----------

  test("the wizard writes the medication, the schedule, the 48 h occurrences and the event"):
    ox.supervised:
      val rig = Rig()
      rig.seedAccount()
      rig.driveWizardToForm("with breakfast")
      rig.line("1") // Every day
      val confirm = rig.line("1") // Create
      assert(confirm.exists(_.contains("Vitamin D is set.")), s"created: $confirm")

      val medications = medicationRows()
      assertEquals(medications.size, 1)
      assertEquals(medications.head.name, "Vitamin D")
      assertEquals(medications.head.doseAmount, Some(BigDecimal(1000)))
      assertEquals(medications.head.doseUnit, Some("IU"))
      assertEquals(medications.head.instructions, Some("with breakfast"))

      val schedule = fixtures.uow.transaction(_.schedules.listForMedication(
        medicationIdOf("Vitamin D")).head)
      assertEquals(schedule.kind, "fixed_times")
      assertEquals(schedule.tz.getId, "Europe/Kyiv")
      assert(schedule.tzFollowsUser)
      assertEquals(schedule.currentRevision, 1)

      assertEquals(
        occurrenceRows(schedule.id).map(r => (r.scheduledFor, r.status, r.revision)),
        List(
          (Instant.parse("2026-09-21T06:00:00Z"), "pending", 1),
          (Instant.parse("2026-09-22T06:00:00Z"), "pending", 1)
        )
      )

      val events = withConnection { conn =>
        given Connection = conn
        sql"SELECT type FROM domain_events".query[String]()
      }
      assertEquals(events, List("dosecord.medication.schedule_created.v1"))
      assertEquals(countSql("SELECT count(*) FROM conversation_sessions"), 0L)

  test("a skipped instructions field stores NULL"):
    ox.supervised:
      val rig = Rig()
      rig.seedAccount()
      rig.driveWizardToForm("")
      rig.line("1")
      rig.line("1")
      assertEquals(medicationRows().head.instructions, None, "instructions stay NULL")

  test("/cancel mid-wizard leaves no medication row"):
    ox.supervised:
      val rig = Rig()
      rig.seedAccount()
      rig.line("/menu")
      rig.line("2")
      rig.line("2")
      rig.line("Vitamin D")
      val out = rig.line("/cancel")
      assert(out.exists(_.contains(WizardCopy.setupCancelled)), s"cancelled: $out")
      assertEquals(countSql("SELECT count(*) FROM medications"), 0L)
      assertEquals(countSql("SELECT count(*) FROM medication_schedules"), 0L)
      assertEquals(countSql("SELECT count(*) FROM dose_occurrences"), 0L)

  // ---------- Restart survival (one test per step) ----------

  test("restart at the form step: the stored prompt is re-sent and the wizard completes"):
    ox.supervised:
      val rig = Rig()
      rig.seedAccount()
      rig.line("/menu")
      rig.line("2")
      rig.line("2")
      assert(rig.sends.last.message.chunks.exists(_.contains("question 1 of 4")), "the form question is out")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1, "the stored form prompt is re-sent")
      assert(rig.sends.last.message.chunks.exists(_.contains("question 1 of 4")), "the re-sent prompt is the form's")

      rig.line("Vitamin D")
      rig.line("1000 IU")
      rig.line("09:00")
      rig.line("")
      // Post-restart choices are tapped by wire: the re-sent prompt and the new steps share the fixed clock's
      // `sent_at`, so the numbered-reply resolution is ambiguous here (the console restart quirk).
      rig.tapLabel(WizardCopy.daysPrompt, WizardCopy.everyDay)
      rig.tapLabel("every day 09:00", Labels.Create)
      assertEquals(medicationRows().size, 1, "the wizard completed after the restart")
      assertEquals(countSql("SELECT count(*) FROM conversation_sessions"), 0L)

  test("restart at the days step: the flow continues to completion"):
    ox.supervised:
      val rig = Rig()
      rig.seedAccount()
      rig.driveWizardToForm("")
      assert(rig.sends.last.message.chunks.exists(_.contains(WizardCopy.daysPrompt)), "the days prompt is out")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1)
      assert(rig.sends.last.message.chunks.exists(_.contains(WizardCopy.daysPrompt)), "the re-sent prompt is the days step's")

      rig.tapLabel(WizardCopy.daysPrompt, WizardCopy.everyDay)
      rig.tapLabel("every day 09:00", Labels.Create)
      assertEquals(medicationRows().size, 1)
      assertEquals(countSql("SELECT count(*) FROM dose_occurrences"), 2L)

  test("restart at the days picker step: the multi-select picker survives"):
    ox.supervised:
      val rig = Rig()
      rig.seedAccount()
      rig.driveWizardToForm("")
      rig.line("3") // Choose days...
      assert(rig.sends.last.message.chunks.exists(_.contains("1) Mon")), "the day picker is out")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1)
      assert(rig.sends.last.message.chunks.exists(_.contains("1) Mon")), "the re-sent prompt is the picker's")

      rig.line("1 3 5")
      assert(rig.sends.last.message.chunks.exists(_.contains("on Mon, Wed, Fri")), s"the card: ${rig.sends.last.message.chunks}")
      rig.tapLabel("on Mon, Wed, Fri", Labels.Create)
      assertEquals(medicationRows().size, 1)
      val revision = withConnection { conn =>
        given Connection = conn
        sql"SELECT rule FROM schedule_revisions WHERE revision = 1".queryOne[String]()
      }
      assert(revision.get.contains("\"Mon\"") && revision.get.contains("\"Wed\"") && revision.get.contains("\"Fri\""),
        s"the custom days: $revision")

  test("restart at the confirm step: the card is re-sent and Create completes"):
    ox.supervised:
      val rig = Rig()
      rig.seedAccount()
      rig.driveWizardToForm("with breakfast")
      rig.line("1") // Every day
      assert(rig.sends.last.message.chunks.exists(_.contains("every day 09:00 Europe/Kyiv")), "the card is out")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1)
      assert(rig.sends.last.message.chunks.exists(_.contains("every day 09:00 Europe/Kyiv")), "the re-sent prompt is the card's")

      rig.tapLabel("every day 09:00 Europe/Kyiv", Labels.Create)
      assertEquals(medicationRows().size, 1)
      assertEquals(countSql("SELECT count(*) FROM dose_occurrences"), 2L)

  // ---------- Menu lifecycle ----------

  test("pause then resume from the menu fires the next slot"):
    ox.supervised:
      val rig = Rig()
      val accountId = rig.seedAccount()
      val created = rig.seedSchedule(accountId, "Vitamin D", dailyAt("09:00"), kyiv)

      rig.line("/menu")
      rig.tapLabel("What would you like to do?", "Medications")
      rig.tapLabel("Medications", "Pause")
      assert(rig.sends.last.message.chunks.exists(_.contains("Paused since Mon")), s"paused: ${rig.sends.last.message.chunks}")
      assertEquals(
        occurrenceRows(created.scheduleId).map(_.status).distinct,
        List("cancelled"),
        "all open rows cancelled paused"
      )
      assertEquals(
        withConnection { conn =>
          given Connection = conn
          sql"SELECT status FROM medication_schedules".queryOne[String]()
        },
        Some("paused")
      )

      rig.tapLabel("Paused since Mon", "Resume")
      assert(
        rig.sends.last.message.chunks.exists(_.contains("Resumed Vitamin D — next dose Mon 09:00")),
        s"resumed: ${rig.sends.last.message.chunks}"
      )
      // The next slot fired again: Mon 09:00 Kyiv (06:00Z) pending under the resume revision.
      assertEquals(
        occurrenceRows(created.scheduleId).filter(_.status == "pending").map(r => (r.scheduledFor, r.revision)),
        List((Instant.parse("2026-09-21T06:00:00Z"), 3), (Instant.parse("2026-09-22T06:00:00Z"), 3))
      )

  test("Account -> Timezone moves the following schedule and leaves the non-following one"):
    ox.supervised:
      val rig = Rig()
      val accountId = rig.seedAccount()
      val following = rig.seedSchedule(accountId, "Vitamin D", dailyAt("09:00"), kyiv, tzFollowsUser = true)
      val other = rig.seedSchedule(accountId, "Vitamin K", dailyAt("09:00"), utc, tzFollowsUser = false)

      rig.line("/menu")
      rig.tapLabel("What would you like to do?", "Account")
      rig.tapLabel("Account", "Timezone")
      rig.tapLabel("Pick your timezone", "UTC-04:00 — America/New_York")
      val out = rig.tapLabel("It is", Labels.Yes)
      assert(out.exists(_.contains("It is 20:00 for you now.")), s"the echo: $out")

      assertEquals(
        withConnection { conn =>
          given Connection = conn
          sql"SELECT timezone FROM users WHERE id = $accountId".queryOne[String]()
        },
        Some("America/New_York")
      )
      // The following schedule moved to 09:00 New York (13:00Z).
      assertEquals(
        occurrenceRows(following.scheduleId).filter(_.status == "pending").map(_.scheduledFor),
        List(Instant.parse("2026-09-21T13:00:00Z"), Instant.parse("2026-09-22T13:00:00Z"))
      )
      // The non-following schedule is untouched.
      assertEquals(
        occurrenceRows(other.scheduleId).map(_.scheduledFor),
        List(Instant.parse("2026-09-21T09:00:00Z"), Instant.parse("2026-09-22T09:00:00Z"))
      )

  test("/history matches dose_occurrences for the seeded 7 days"):
    ox.supervised:
      val rig = Rig()
      val accountId = rig.seedAccount()
      val created = rig.seedSchedule(accountId, "Vitamin D", dailyAt("09:00"), kyiv,
        doseAmount = Some(BigDecimal(1000)), doseUnit = Some("IU"))
      // Six seeded days with resolved statuses (Tue 15 .. Sun 20).
      val days = List(
        ("2026-09-15T06:00:00Z", "taken_on_time"),
        ("2026-09-16T06:00:00Z", "taken_late"),
        ("2026-09-17T06:00:00Z", "skipped"),
        ("2026-09-18T06:00:00Z", "missed"),
        ("2026-09-19T06:00:00Z", "unknown"),
        ("2026-09-20T06:00:00Z", "taken_on_time2")
      )
      days.foreach { (instant, slotKey) =>
        fixtures.occurrenceAt(created.scheduleId, Instant.parse(instant), slotKey, status = OccurrenceStatus.Taken)
      }
      // Push one taken row past the due window so it renders `taken_late`; one skipped; one missed; one unknown.
      withConnection { conn =>
        given Connection = conn
        sql"""UPDATE dose_occurrences
              SET effective_at = ${Instant.parse("2026-09-15T06:10:00Z")}, taken_at = ${Instant.parse("2026-09-15T06:10:00Z")}
              WHERE slot_key = 'taken_on_time'""".execute()
        sql"""UPDATE dose_occurrences
              SET effective_at = ${Instant.parse("2026-09-20T06:10:00Z")}, taken_at = ${Instant.parse("2026-09-20T06:10:00Z")}
              WHERE slot_key = 'taken_on_time2'""".execute()
        sql"""UPDATE dose_occurrences
              SET effective_at = ${Instant.parse("2026-09-16T08:30:00Z")}, taken_at = ${Instant.parse("2026-09-16T08:30:00Z")}
              WHERE slot_key = 'taken_late'""".execute()
        sql"""UPDATE dose_occurrences
              SET status = 'skipped', skipped_at = ${Instant.parse("2026-09-17T07:00:00Z")},
                  taken_at = NULL, effective_at = NULL, next_action_at = NULL
              WHERE slot_key = 'skipped'""".execute()
        sql"""UPDATE dose_occurrences
              SET status = 'missed', missed_at = ${Instant.parse("2026-09-18T08:00:00Z")},
                  taken_at = NULL, effective_at = NULL, next_action_at = NULL
              WHERE slot_key = 'missed'""".execute()
        sql"""UPDATE dose_occurrences
              SET status = 'unknown', unknown_reason = 'outage',
                  taken_at = NULL, effective_at = NULL, next_action_at = NULL
              WHERE slot_key = 'unknown'""".execute()
      }

      val history = rig.line("/history")
      // The rendered lines match the repository rows for the seeded window.
      val expected = withConnection { conn =>
        given Connection = conn
        sql"""SELECT count(*) FROM dose_occurrences
              WHERE account_id = $accountId AND local_date >= '2026-09-15' AND local_date <= '2026-09-21'
                AND status <> 'cancelled'""".queryOne[Long]()
      }
      assertEquals(expected, Some(7L), "6 seeded rows + today's materialised dose")
      assert(history.exists(_.contains("Last 7 days")), s"the title: $history")
      assertEquals(history.flatMap(_.split("\n")).count(_.contains("09:00 Vitamin D 1000 IU —")), 7,
        s"one line per row: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — taken at 09:10")), s"on-time: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — taken at 11:30 (late)")), s"late: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — skipped at 10:00")), s"skipped: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — missed")), s"missed: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — unknown")), s"unknown: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — scheduled")), s"today: $history")

  private def medicationIdOf(name: String): UUID = withConnection { conn =>
    given Connection = conn
    sql"SELECT id FROM medications WHERE name = $name".queryOne[UUID]()
  }.get
