package dosecord.adapter.console

import dosecord.contracts.*
import dosecord.core.application.Application
import dosecord.core.application.CommandRegistry
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.ChatMediator
import dosecord.core.domain.DoseSnapshot
import dosecord.core.domain.DstKind
import dosecord.core.domain.Occurrence
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.domain.copy.DoseActionKind
import dosecord.core.ports.Clock
import dosecord.core.ports.DeliveryTarget
import dosecord.core.ports.NewMedication
import dosecord.core.ports.NewOccurrence
import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.NewSchedule
import dosecord.core.ports.NewScheduleRevision
import dosecord.core.ports.OccurrenceOrigin
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.Wake
import dosecord.core.scheduling.CatalogueOutboxRenderer
import dosecord.core.scheduling.Materialiser
import dosecord.core.scheduling.OutboxDispatcher
import dosecord.core.scheduling.ReminderLoop

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import scala.io.Source
import scala.util.Using

/** M1.10 acceptance on the real console module: the one-tap intake transcript — the fired reminder, Taken, the
  * already-recorded no-op, the post-Taken [Undo][Correct], the correction offer past the undo window with
  * [At scheduled time], the missed notice's [Skip] and [Keep missed], Snooze, `/log vitamin d` and `/history` —
  * through the actual `ConsoleAdapter` (its `[#n]` printing and `#<n> <reply>` grammar), the real mediator, the real
  * reminder loop and outbox dispatcher, golden-filed per the M0.12d pattern.
  */
class ConsoleIntakeSuite extends munit.FunSuite:

  test("one-tap intake transcript golden on the real console adapter"):
    ox.supervised:
      val rig = ConsoleIntakeRig()
      val transcript = rig.drive()
      assertEquals(
        transcript,
        ConsoleIntakeRig.expectedGolden,
        "transcript drifted; regenerate with " +
          "./mill adapter-console.test.runMain dosecord.adapter.console.consoleIntakeGoldenGenerate"
      )

      // Row-level effects behind the transcript.
      val dose1Actions = rig.uow.doseActions.listForOccurrence(rig.dose1Id)
      assertEquals(
        dose1Actions.map(_.action),
        List(DoseActionKind.ReminderSent, DoseActionKind.Taken, DoseActionKind.ManuallyCorrected)
      )
      val userActions = dose1Actions.filter(_.actor == dosecord.core.domain.Actor.User)
      assert(userActions.forall(_.idempotencyKey.isDefined), "every user action row carries an idempotency key")
      assert(userActions.forall(_.vendor.contains("console")), "every user action row carries the vendor")
      assertEquals(dose1Actions(1).platformMessageId, Some("1"), "the Taken names the message it came from")
      val manual = rig.uow.occurrences.all.filter(_.origin == OccurrenceOrigin.Manual)
      assertEquals(manual.map(_.status), List(OccurrenceStatus.Taken), "/log created one manual occurrence")
      assertEquals(
        rig.uow.domainEvents.all.count(_.eventType == Event.IntakeTakenType),
        3,
        "taken, correction and manual log each append exactly one intake_taken.v1"
      )

/** Drives the real ConsoleAdapter line by line over the M1.10 [[Application]] composition with the loop and the
  * dispatcher running against the same stores, and interleaves input with the printed output.
  */
private final class ConsoleIntakeRig(using ox.Ox):
  import ConsoleIntakeRig.*

  val uow = ConsoleFlowFakes.InMemoryUnitOfWork()
  @volatile private var nowAt: Instant = t0
  val clock: Clock = new Clock:
    override def now(): Instant = nowAt
  private val out = ByteArrayOutputStream()
  private val adapter =
    ConsoleAdapter("owner", BufferedReader(StringReader("")), PrintStream(out), "s1", () => nowAt)
  private val adapters: Map[String, ChatAdapter] = Map("console" -> adapter)
  private val mediator =
    ChatMediator(uow, adapters, codec, Application.handler(uow, adapters, codec, clock), clock, CommandRegistry.byName)

  // The seeded regimen: Vitamin D 1000 IU daily 09:00 Europe/Kyiv, one pending dose today.
  private val account = seedAccount()
  private val (scheduleId, medicationId) = seedSchedule(account, "Vitamin D", List("09:00"))
  val dose1Id: UUID = seedOccurrence(scheduleId, medicationId, Instant.parse("2026-09-21T06:00:00Z"), "t0900")
  uow.channels.register(account, DeliveryTarget(UUID.randomUUID(), "console", Some("dm:owner")))
  advanceTo(Instant.parse("2026-09-21T06:05:00Z")) // 09:05 in Kyiv

  private val transcript = new StringBuilder

  private def printed: String = out.toString("UTF-8")

  private def capture(effect: => Unit): Unit =
    val before = printed.length
    effect
    await(printed.length > before, "the step produced output")
    transcript ++= printed.substring(before)

  def line(input: String): Unit =
    val before = printed.length
    mediator.push(adapter.eventFor(input))
    await(printed.length > before, s"'$input' produced output")
    transcript ++= s"> $input\n" + printed.substring(before)

  def tickLoop(): Unit = ReminderLoop(uow, Materialiser(uow, clock), Wake.polling, clock, "rig").tick(nowAt)
  def dispatchOnce(): Unit =
    capture(OutboxDispatcher(uow, adapters, CatalogueOutboxRenderer(uow, codec, clock), clock).dispatchOnce())

  def advanceTo(instant: Instant): Unit = nowAt = instant

  def drive(): String =
    tickLoop()
    dispatchOnce() // #1: the reminder
    line("#1 1") // #2: Recorded at 09:05.
    dispatchOnce() // #3: the finalize replacement with [Undo][Correct]
    line("#3 2") // #4: When did you take it? [Now][At scheduled time][Cancel]
    line("#4 3") // #5: No change.
    line("#1 1") // #6: Already recorded at 09:05. (the stale choice_map resolves; the FSM no-ops)
    advanceTo(Instant.parse("2026-09-21T06:25:00Z")) // 09:25 in Kyiv, past the 15-minute undo window
    line("#3 1") // #7: The undo window has passed — correct it instead?
    line("#7 2") // #8: Corrected — logged as taken at 09:00.
    dispatchOnce() // #9, #10: the finalize replacements
    val dose2 = seedMissed("t0830", Instant.parse("2026-09-21T05:30:00Z"))
    missedNotice(dose2)
    dispatchOnce() // #11: the missed notice
    line("#11 2") // #12: Marked skipped.
    dispatchOnce() // #13: the skipped replacement
    val dose3 = seedMissed("t0845", Instant.parse("2026-09-21T05:45:00Z"))
    missedNotice(dose3)
    dispatchOnce() // #14: the missed notice
    line("#14 3") // #15: Okay — left marked missed.
    dispatchOnce() // #16: the keep-missed replacement
    val dose4 = seedDue("t0850", Instant.parse("2026-09-21T05:50:00Z"))
    reminder(dose4)
    dispatchOnce() // #17: the reminder
    line("#17 2") // #18: Okay, I'll remind you again at 09:35.
    dispatchOnce() // #19: the snoozed replacement
    line("/log vitamin d") // #20: Recorded at 09:25. (the manual occurrence)
    line("/history") // #21: the 7-day listing
    transcript.toString

  // ---------- Seeding ----------

  private def seedAccount(): UUID =
    val principal = uow.identities.resolve(PlatformIdentity("console", "owner"), t0)
    uow.accounts.createAccount(principal.identityId, "Europe/Kyiv", t0).uuid

  private def seedSchedule(account: UUID, name: String, times: List[String]): (UUID, UUID) =
    uow.transaction: tx =>
      val medicationId = UUID.randomUUID()
      val scheduleId = UUID.randomUUID()
      tx.medications.insert(
        NewMedication(medicationId, account, name, Some(BigDecimal(1000)), Some("IU"), Some("with breakfast")),
        t0
      )
      tx.schedules.insert(
        NewSchedule(scheduleId, medicationId, account, "fixed_times", kyiv, true, LocalDate.of(2026, 9, 21)),
        t0
      )
      tx.revisions.append(
        NewScheduleRevision(
          UUID.randomUUID(),
          scheduleId,
          revision = 1,
          effectiveFrom = t0,
          tz = kyiv,
          rule = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.map(HhMm.unsafe)))),
          policy = ReminderPolicy.Default,
          doseSnapshot = DoseSnapshot(name, Some(BigDecimal(1000)), Some("IU"), Some("with breakfast")),
          createdBy = "user",
          reason = Some("create")
        ),
        t0
      )
      (scheduleId, medicationId)

  private def seedOccurrence(scheduleId: UUID, medicationId: UUID, scheduledFor: Instant, slotKey: String): UUID =
    uow.transaction: tx =>
      val revision = tx.revisions.latest(scheduleId).get
      val local = scheduledFor.atZone(kyiv)
      val row = NewOccurrence(
        id = UUID.randomUUID(),
        accountId = account,
        medicationId = medicationId,
        scheduleId = Some(scheduleId),
        revision = Some(1),
        origin = OccurrenceOrigin.Scheduled,
        localDate = local.toLocalDate,
        localTime = Some(HhMm.unsafe(f"${local.getHour}%02d:${local.getMinute}%02d")),
        slotKey = slotKey,
        tz = kyiv,
        dstKind = DstKind.None,
        doseSnapshot = revision.doseSnapshot,
        state = Occurrence.scheduled(scheduledFor, revision.policy)
      )
      require(tx.occurrences.insertAll(List(row)) == 1, s"fixture occurrence at $scheduledFor conflicted")
      row.id

  private def seedMissed(slotKey: String, scheduledFor: Instant): UUID =
    val id = seedOccurrence(scheduleId, medicationId, scheduledFor, slotKey)
    uow.transaction: tx =>
      val occ = tx.occurrences.get(id).get
      require(
        tx.occurrences.applyTransition(id, occ.version, occ.state.epoch,
          occ.state.copy(status = OccurrenceStatus.Missed, missedAt = Some(nowAt), nextActionAt = None,
            epoch = occ.state.epoch + 1),
          nowAt)
      )
    id

  private def seedDue(slotKey: String, scheduledFor: Instant): UUID =
    val id = seedOccurrence(scheduleId, medicationId, scheduledFor, slotKey)
    uow.transaction: tx =>
      val occ = tx.occurrences.get(id).get
      require(
        tx.occurrences.applyTransition(id, occ.version, occ.state.epoch,
          occ.state.copy(status = OccurrenceStatus.Due, epoch = occ.state.epoch + 1),
          nowAt)
      )
    id

  private def missedNotice(occurrenceId: UUID): Unit =
    uow.outbox.enqueue(
      NewOutboxMessage(
        id = UUID.randomUUID(),
        sendKey = s"test:missed:$occurrenceId",
        op = OutboxOp.Send,
        kind = "missed_notice",
        vendor = "console",
        accountId = Some(account),
        occurrenceId = Some(occurrenceId),
        epoch = Some(uow.occurrences.get(occurrenceId).get.state.epoch),
        payload = LoopDispatch.toJson(LoopDispatch.MissedNotice(occurrenceId, Some("dm:owner"), silent = false)),
        importance = "reminder",
        nextAttemptAt = nowAt
      )
    )

  private def reminder(occurrenceId: UUID): Unit =
    uow.outbox.enqueue(
      NewOutboxMessage(
        id = UUID.randomUUID(),
        sendKey = s"test:reminder:$occurrenceId",
        op = OutboxOp.Send,
        kind = "reminder",
        vendor = "console",
        accountId = Some(account),
        occurrenceId = Some(occurrenceId),
        epoch = Some(uow.occurrences.get(occurrenceId).get.state.epoch),
        payload =
          LoopDispatch.toJson(LoopDispatch.Reminder(occurrenceId, Some("dm:owner"), "initial", 1, silent = false)),
        importance = "reminder",
        nextAttemptAt = nowAt
      )
    )

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    assert(ok, clue)

private object ConsoleIntakeRig:
  val t0: Instant = Instant.parse("2026-09-21T00:00:00Z")
  val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
  val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  val ResourcePath = "/goldens/intake-flows.console.golden"
  val FilePath = "adapter-console/test/resources/goldens/intake-flows.console.golden"

  def expectedGolden: String =
    val stream = Option(getClass.getResourceAsStream(ResourcePath))
      .getOrElse(throw IllegalStateException(s"$ResourcePath is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

/** Regenerates the one-tap intake console transcript golden:
  * `./mill adapter-console.test.runMain dosecord.adapter.console.consoleIntakeGoldenGenerate`.
  */
@main def consoleIntakeGoldenGenerate(): Unit =
  ox.supervised:
    val transcript = ConsoleIntakeRig().drive()
    val target = Path.of(ConsoleIntakeRig.FilePath)
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.writeString(target, transcript)
    println(s"wrote $target")
