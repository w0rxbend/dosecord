package dosecord.core.application

import dosecord.contracts.*
import dosecord.core.application.MedicationWizardRig.Profile
import dosecord.core.chat.ActionRegistry
import dosecord.core.chat.CallbackMode
import dosecord.core.domain.Actor as DomainActor
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.domain.copy.DoseActionKind
import dosecord.core.ports.DeliveryTarget
import dosecord.core.ports.NewOutboxMessage
import dosecord.core.ports.OccurrenceOrigin
import dosecord.core.ports.OutboxOp

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** M1.10 acceptance on the in-memory ports through the real mediator and the M1.10 [[Application]] composition: the
  * one-tap Taken / Snooze / Skip / Undo / Keep-missed flows, the two-choice correction (from [Correct], from a Taken
  * past the 15-minute undo window, and from a direct Taken beyond the 24 h late-log window reached by a `#<n> 1`
  * quoted reply on a 3-day-old missed notice), manual logging through `/log` and Medications -> Log dose, the
  * universal commands, finalize-on-every-recorded-handle, the repeat's supersede finalize, and the action rows'
  * idempotency keys and vendor attribution. The tap-on-a-row-claimed-by-an-in-flight-tick concurrency half is the
  * Postgres `DoseActionHandlerPgSuite`.
  */
class DoseIntakeSuite extends munit.FunSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val due = Instant.parse("2026-09-21T06:05:00Z") // 09:05 in Kyiv

  private def withRig(test: MedicationWizardRig => Unit): Unit =
    ox.supervised:
      test(MedicationWizardRig(Profile.Console))

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private def channel(rig: MedicationWizardRig, account: UUID): Unit =
    rig.uow.channels.register(account, DeliveryTarget(UUID.randomUUID(), "fake", Some("dm:user-1")))

  private def token(action: String, subject: UUID, value: Long = 0): String =
    MedicationWizardRig.codec
      .encode(CallbackMode.Direct, ActionRegistry.byName(action).get, subject, value)
      .wire

  /** The missed notice the loop's miss transition would enqueue (R23), delivered by the dispatcher. */
  private def enqueueMissedNotice(rig: MedicationWizardRig, account: UUID, occurrenceId: UUID): Unit =
    val occ = rig.uow.occurrences.get(occurrenceId).get
    rig.uow.outbox.enqueue(
      NewOutboxMessage(
        id = UUID.randomUUID(),
        sendKey = s"test:missed:$occurrenceId",
        op = OutboxOp.Send,
        kind = "missed_notice",
        vendor = "fake",
        accountId = Some(account),
        occurrenceId = Some(occurrenceId),
        epoch = Some(occ.state.epoch),
        payload = LoopDispatch.toJson(LoopDispatch.MissedNotice(occurrenceId, Some("dm:user-1"), silent = false)),
        importance = "reminder",
        nextAttemptAt = rig.clock.now()
      )
    )

  private def seedDueDose(rig: MedicationWizardRig, account: UUID): UUID =
    val outcome = rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv,
      dose = (Some(BigDecimal(1000)), Some("IU")))
    rig.clock.at = due
    outcome.scheduleId

  test("Taken records, finalizes every recorded handle, appends exactly one intake_taken.v1"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      val scheduleId = seedDueDose(rig, account)
      val occurrenceId = rig.uow.occurrences.listBySchedule(scheduleId).head.id
      // A second recorded handle for the same occurrence (a fallback channel's message): both must be finalized.
      rig.uow.renderedMessages.record(
        MessageHandle("fake", "dm:user-1", "m9"),
        Some(account),
        "reminder",
        subjectType = Some("occurrence"),
        subjectId = Some(occurrenceId),
        epoch = Some(1),
        Nil,
        rig.clock.now()
      )

      rig.tickLoop() // pending -> due, the initial reminder enqueued
      rig.dispatchOnce() // the reminder is message m1 with its choice_map recorded
      val takenWire = rig.wireOf("Time for Vitamin D", "Taken")

      // The console's `#m1 1` resolves by target to dose.taken and records it.
      val ops = rig.quoted("m1", "1")
      val chunks = ops.collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten
      assert(chunks.contains("Recorded at 09:05."), s"the recorded toast: $chunks")

      val occ = rig.uow.occurrences.get(occurrenceId).get
      assertEquals(occ.status, OccurrenceStatus.Taken)
      assertEquals(occ.state.takenAt, Some(due))
      assertEquals(occ.state.effectiveAt, Some(due))

      val actions = rig.uow.doseActions.listForOccurrence(occurrenceId)
      assertEquals(actions.map(_.action), List(DoseActionKind.ReminderSent, DoseActionKind.Taken))
      val takenRow = actions.last
      assertEquals(takenRow.actor, DomainActor.User)
      assert(takenRow.idempotencyKey.isDefined, "every action row carries an idempotency key")
      assertEquals(takenRow.vendor, Some("fake"), "every action row carries the vendor")
      assertEquals(takenRow.platformMessageId, Some("m1"), "and the message id it came from")
      assertEquals(rig.uow.domainEvents.all.count(_.eventType == Event.IntakeTakenType), 1)

      // finalize on every recorded handle: one finalize op per handle, both targeting this occurrence's messages.
      val finalizeTargets = rig.uow.outbox.allEnqueued.filter(_.op == OutboxOp.Finalize).flatMap(_.target)
      assert(finalizeTargets.contains("dm:user-1:m1"), s"the reminder handle is finalized: $finalizeTargets")
      assert(finalizeTargets.contains("dm:user-1:m9"), s"the fallback handle is finalized: $finalizeTargets")

      // Dispatching the finalize replaces the outcome onto the chat (the console profile cannot edit, DESIGN.md
      // section 4.3's last rung) with the kept [Undo][Correct] follow-up resolving on the new message.
      rig.dispatchOnce()
      val outcome = rig.ops.collect {
        case VendorOp.Send(_, m, _, _) if m.choiceMap.nonEmpty => m
      }.lastOption.get
      assert(outcome.chunks.exists(_.contains("Recorded at 09:05.")), s"the finalize outcome: ${outcome.chunks}")
      val kept = outcome.choiceMap.map(e => MedicationWizardRig.codec.decode(e.callback).toOption.get.action.name)
      assertEquals(kept, List("dose.undo", "dose.correct"), "the post-Taken follow-up stays one tap away")

      // A second Taken on the same control is the semantic no-op: "Already recorded", no new action row.
      val (handle, _) = rig.tapWithHandle(takenWire)
      assert(handle.calls.asScala.exists(_.contains("Already recorded at 09:05.")), s"${handle.calls}")
      assertEquals(rig.uow.doseActions.listForOccurrence(occurrenceId).size, 2, "no new action row")

  test("Skip records skipped with the neutral confirmation"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      val scheduleId = seedDueDose(rig, account)
      val occurrenceId = rig.uow.occurrences.listBySchedule(scheduleId).head.id
      rig.tickLoop()
      rig.dispatchOnce()
      val skipWire = rig.wireOf("Time for Vitamin D", "Skip")
      val (handle, _) = rig.tapWithHandle(skipWire)
      assert(handle.calls.asScala.exists(_.contains("Marked skipped.")), s"${handle.calls}")
      assertEquals(rig.uow.occurrences.get(occurrenceId).get.status, OccurrenceStatus.Skipped)
      assertEquals(
        rig.uow.doseActions.listForOccurrence(occurrenceId).map(_.action),
        List(DoseActionKind.ReminderSent, DoseActionKind.Skipped)
      )

  test("Snooze snoozes to the chosen time; options past the next occurrence are filtered out"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      val outcome = rig.seedSchedule(account, "Vitamin D", dailyAt("09:00", "09:30"), kyiv,
        dose = (Some(BigDecimal(1000)), Some("IU")))
      rig.clock.at = due
      rig.tickLoop()
      rig.dispatchOnce()
      // The 09:30 dose bounds the snooze at 09:29: only the 10-minute option survives (ADR-012 filtering).
      assertEquals(
        rig.lastChoiceMap("Time for Vitamin D").map(_.label),
        List("Taken", "Snooze 10m", "Skip")
      )
      val snoozeWire = rig.wireOf("Time for Vitamin D", "Snooze 10m")
      val (handle, _) = rig.tapWithHandle(snoozeWire)
      assert(
        handle.calls.asScala.exists(_.contains("Okay, I'll remind you again at 09:15.")),
        s"${handle.calls}"
      )
      val occ = rig.uow.occurrences.listBySchedule(outcome.scheduleId).head
      assertEquals(occ.status, OccurrenceStatus.Snoozed)
      assertEquals(occ.state.snoozedUntil, Some(due.plusSeconds(600)))

  test("Undo within the 15-minute window returns the dose to due"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      val scheduleId = seedDueDose(rig, account)
      val occurrenceId = rig.uow.occurrences.listBySchedule(scheduleId).head.id
      rig.tickLoop()
      rig.dispatchOnce()
      rig.quoted("m1", "1")
      rig.clock.at = due.plusSeconds(300) // 09:10 Kyiv, inside the window
      val undoWire = token("dose.undo", occurrenceId)
      val (handle, _) = rig.tapWithHandle(undoWire)
      assert(
        handle.calls.asScala.exists(_.contains("Undone. I'll check again at 09:20.")),
        s"${handle.calls}"
      )
      val occ = rig.uow.occurrences.get(occurrenceId).get
      assertEquals(occ.status, OccurrenceStatus.Due)
      assertEquals(occ.state.takenAt, None)
      val actions = rig.uow.doseActions.listForOccurrence(occurrenceId)
      assertEquals(actions.map(_.action), List(DoseActionKind.ReminderSent, DoseActionKind.Taken, DoseActionKind.Undone))
      assertEquals(actions.last.undoesSeq, Some(2))

  test("Taken 20 minutes past the undo window offers Correct and records manually_corrected with an explicit effective_at"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      val scheduleId = seedDueDose(rig, account)
      val occurrenceId = rig.uow.occurrences.listBySchedule(scheduleId).head.id
      rig.tickLoop()
      rig.dispatchOnce()
      rig.quoted("m1", "1")
      rig.clock.at = due.plusSeconds(20 * 60) // 09:25 Kyiv, past the 15-minute window
      val undoWire = token("dose.undo", occurrenceId)
      val ops = rig.tap(undoWire)
      assert(
        ops.exists(_.contains("The undo window has passed — correct it instead?")),
        s"the correction is offered instead: $ops"
      )
      val correctWire = rig.wireOf("The undo window has passed", "At scheduled time")
      val (handle, _) = rig.tapWithHandle(correctWire)
      assert(
        handle.calls.asScala.exists(_.contains("Corrected — logged as taken at 09:00.")),
        s"${handle.calls}"
      )
      val actions = rig.uow.doseActions.listForOccurrence(occurrenceId)
      assertEquals(actions.map(_.action), List(DoseActionKind.ReminderSent, DoseActionKind.Taken, DoseActionKind.ManuallyCorrected))
      assertEquals(
        actions.last.effectiveAt,
        Some(Instant.parse("2026-09-21T06:00:00Z")),
        "the correction records an explicit effective_at"
      )

  test("a `#<n> 1` reply on a 3-day-old missed notice resolves by target and opens the correction"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      val outcome = rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv,
        dose = (Some(BigDecimal(1000)), Some("IU")))
      val occurrenceId = rig.seedOccurrence(outcome.scheduleId, daysBack = 3, LocalTime.of(9, 0), "old0900",
        _.copy(status = OccurrenceStatus.Missed, nextActionAt = None,
          missedAt = Some(Instant.parse("2026-09-18T08:00:00Z"))))
      rig.clock.at = due
      enqueueMissedNotice(rig, account, occurrenceId)
      rig.dispatchOnce() // the missed notice is message m1 with [I took it][Skip][Keep missed]
      val ops = rig.quoted("m1", "1") // [I took it], three days late: beyond the 24 h late-log window
      val chunks = ops.collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten
      assert(chunks.exists(_.contains("When did you take it?")), s"the correction opens: $chunks")
      // The correction prompt is message m2; `2` answers [At scheduled time] by target.
      val ops2 = rig.quoted("m2", "2")
      val chunks2 = ops2.collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten
      assert(chunks2.exists(_.contains("Corrected — logged as taken at 09:00.")), s"$chunks2")
      val actions = rig.uow.doseActions.listForOccurrence(occurrenceId)
      assertEquals(actions.map(_.action), List(DoseActionKind.ManuallyCorrected))
      assertEquals(actions.head.effectiveAt, Some(Instant.parse("2026-09-18T06:00:00Z")))
      assertEquals(actions.head.platformMessageId, Some("m2"))
      assertEquals(rig.uow.domainEvents.all.count(_.eventType == Event.IntakeTakenType), 1)

  test("the missed notice's [I took it] records taken within the window; [Keep missed] finalizes and changes nothing"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      val outcome = rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv,
        dose = (Some(BigDecimal(1000)), Some("IU")))
      val missedId = rig.seedOccurrence(outcome.scheduleId, daysBack = 0, LocalTime.of(9, 0), "missed0900",
        _.copy(status = OccurrenceStatus.Missed, nextActionAt = None, missedAt = Some(due)))
      val keptId = rig.seedOccurrence(outcome.scheduleId, daysBack = 0, LocalTime.of(9, 0), "missed0900b",
        _.copy(status = OccurrenceStatus.Missed, nextActionAt = None, missedAt = Some(due)))
      rig.clock.at = due
      enqueueMissedNotice(rig, account, missedId)
      enqueueMissedNotice(rig, account, keptId)
      rig.dispatchOnce() // m1 and m2: the two missed notices

      val ops = rig.quoted("m1", "1") // [I took it]
      val chunks = ops.collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten
      assert(chunks.contains("Recorded at 09:05."), s"the taken toast: $chunks")
      assertEquals(rig.uow.occurrences.get(missedId).get.status, OccurrenceStatus.Taken)

      val ops2 = rig.quoted("m2", "3") // [Keep missed]
      val chunks2 = ops2.collect { case VendorOp.Send(_, m, _, _) => m.chunks }.flatten
      assert(chunks2.contains("Okay — left marked missed."), s"the keep-missed answer: $chunks2")
      assertEquals(rig.uow.occurrences.get(keptId).get.status, OccurrenceStatus.Missed)
      assertEquals(rig.uow.doseActions.listForOccurrence(keptId), Nil, "an acknowledgement, not a transition")
      assert(
        rig.uow.outbox.allEnqueued.exists(m => m.op == OutboxOp.Finalize && m.target.contains("dm:user-1:m2")),
        "the notice's recorded handle is finalized"
      )

  test("/log vitamin d creates one manual occurrence with taken"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      rig.seedSchedule(account, "Vitamin D", Rule.AsNeeded(10, 0), kyiv)
      rig.clock.at = due
      val out = rig.line("/log vitamin d")
      assert(out.contains("Recorded at 09:05."), s"$out")
      val manual = rig.uow.occurrences.all.filter(_.origin == OccurrenceOrigin.Manual)
      assertEquals(manual.size, 1, "exactly one manual occurrence")
      assertEquals(manual.head.status, OccurrenceStatus.Taken)
      assert(manual.head.slotKey.startsWith("manual:"), s"the manual slot key: ${manual.head.slotKey}")
      assertEquals(manual.head.medicationId, rig.uow.medications.all.head.id)
      assertEquals(manual.head.state.effectiveAt, Some(due))
      val actions = rig.uow.doseActions.listForOccurrence(manual.head.id)
      assertEquals(actions.map(_.action), List(DoseActionKind.Taken))
      assert(actions.head.idempotencyKey.isDefined)
      assertEquals(rig.uow.domainEvents.all.count(_.eventType == Event.IntakeTakenType), 1)

  test("the universal commands resolve the named medication or the latest open dose"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      rig.clock.at = due

      val nothingOut = rig.line("/taken")
      assert(nothingOut.contains("Nothing is due right now."), s"$nothingOut")

      rig.clock.at = MedicationWizardRig.t0 // materialise the 48 h horizon from midnight
      rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv)
      rig.seedSchedule(account, "Vitamin K", dailyAt("08:00"), kyiv)
      rig.seedSchedule(account, "Vitamin B12", dailyAt("07:00"), kyiv)
      rig.clock.at = due

      val takenOut = rig.line("/taken") // the latest open dose is Vitamin D (09:00 over 08:00 and 07:00)
      assert(takenOut.contains("Recorded at 09:05."), s"$takenOut")
      assertEquals(rig.uow.occurrences.all.count(_.status == OccurrenceStatus.Taken), 1)

      val skipOut = rig.line("/skip vitamin k")
      assert(skipOut.contains("Marked skipped."), s"$skipOut")
      assertEquals(rig.uow.occurrences.all.count(_.status == OccurrenceStatus.Skipped), 1)

      val unknownOut = rig.line("/taken aspirin")
      assert(unknownOut.contains("I could not find aspirin."), s"$unknownOut")

      val noOpenOut = rig.line("/skip vitamin d")
      assert(noOpenOut.contains("No open dose for Vitamin D."), s"$noOpenOut")

      val usageOut = rig.line("/snooze abc")
      assert(usageOut.contains("/snooze <minutes>"), s"$usageOut")

      val snoozeOut = rig.line("/snooze 10") // the only open dose left is Vitamin B12
      assert(snoozeOut.contains("Okay, I'll remind you again at 09:15."), s"$snoozeOut")
      assertEquals(rig.uow.occurrences.all.count(_.status == OccurrenceStatus.Snoozed), 1)

  test("Medications -> Log dose opens the picker and a tap logs a manual dose"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      rig.seedSchedule(account, "Vitamin C", Rule.AsNeeded(10, 0), kyiv)
      rig.clock.at = due
      rig.line("/menu")
      rig.tapLabel("What would you like to do?", "Medications")
      val picker = rig.tapLabel("Medications", "Log dose")
      assert(picker.exists(_.contains("Log a dose of which medication?")), s"$picker")
      val (handle, _) =
        rig.tapWithHandle(rig.wireOf("Log a dose of which medication?", "Log Vitamin C"))
      assert(handle.calls.asScala.exists(_.contains("Recorded at 09:05.")), s"${handle.calls}")
      val manual = rig.uow.occurrences.all.filter(_.origin == OccurrenceOrigin.Manual)
      assertEquals(manual.map(_.status), List(OccurrenceStatus.Taken))

  test("a repeat reminder finalizes the previous message"):
    withRig: rig =>
      val account = rig.seedAccount()
      channel(rig, account)
      val scheduleId = seedDueDose(rig, account)
      val occurrenceId = rig.uow.occurrences.listBySchedule(scheduleId).head.id
      rig.tickLoop()
      rig.dispatchOnce() // m1: the initial reminder
      rig.clock.at = due.plusSeconds(11 * 60) // 09:16 Kyiv, past the 10-minute cadence
      rig.tickLoop() // the repeat
      val finalizes = rig.uow.outbox.allEnqueued.filter(m => m.op == OutboxOp.Finalize)
      assert(finalizes.exists(_.target.contains("dm:user-1:m1")), s"the previous message is finalized: $finalizes")
      assert(finalizes.exists(_.payload.contains("superseded")), s"as superseded: $finalizes")
      assertEquals(rig.uow.occurrences.get(occurrenceId).get.state.reminderSeq, 2)
      rig.dispatchOnce()
      // The superseded finalize goes out as a replacement (the console cannot edit) carrying the reminder text with
      // the controls dropped; the repeat itself is the new reminder with a fresh choice_map.
      val replacement = rig.ops.collect {
        case VendorOp.Send(_, m, _, _) if m.chunks.exists(_.contains("Time for Vitamin D")) => m
      }.filter(_.choiceMap.isEmpty).lastOption
      assert(replacement.isDefined, s"the superseded replacement drops its controls: ${rig.ops}")
      val repeat = rig.ops.collect {
        case VendorOp.Send(_, m, _, _) if m.chunks.exists(_.contains("Time for Vitamin D")) => m
      }.lastOption.get
      assert(repeat.choiceMap.nonEmpty, "the repeat carries fresh controls")
end DoseIntakeSuite
