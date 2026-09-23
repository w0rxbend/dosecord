package dosecord.core.scheduling

import dosecord.contracts.ChatRef
import dosecord.contracts.LoopDispatch
import dosecord.contracts.Node
import dosecord.contracts.Inline
import dosecord.contracts.OutboundMessage
import dosecord.contracts.RenderedControls
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.MediatorFakes
import dosecord.core.domain.DoseSnapshot
import dosecord.core.domain.DstKind
import dosecord.core.domain.Occurrence
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.ports.DeliveryTarget
import dosecord.core.ports.NewOccurrence
import dosecord.core.ports.NewSchedule
import dosecord.core.ports.NewScheduleRevision
import dosecord.core.ports.OccurrenceOrigin
import dosecord.core.ports.OutboxMessage
import dosecord.core.ports.OutboxOp
import dosecord.core.ports.OutboxStatus

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** ROADMAP M1.7: the dispatcher's renderer resolves the payload at send time through the M1.4a catalogue — reminder
  * body and two control rows with catalogued labels, snooze options filtered by `Decide.availableSnoozeOptions`,
  * callback tokens that decode back to the dose actions, the missed notice, the finalize summary, and the mediator's
  * 30 s safety rows (`interaction_reply` payloads).
  */
class CatalogueOutboxRendererSuite extends munit.FunSuite:

  private val utc = ZoneId.of("UTC")
  private val t0 = Instant.parse("2026-09-21T09:00:00Z")
  private val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.fill[Byte](32)(7)), None))
  private val snapshot = DoseSnapshot("Vitamin D", Some(BigDecimal(1000)), Some("IU"), Some("with breakfast"))

  private final case class Seed(
      uow: MediatorFakes.InMemoryUnitOfWork,
      accountId: UUID,
      occurrenceId: UUID,
      channelId: UUID
  )

  private def seed(state: Occurrence): Seed =
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val accountId = UUID.randomUUID()
    val medicationId = UUID.randomUUID()
    val scheduleId = UUID.randomUUID()
    val occurrenceId = UUID.randomUUID()
    val channelId = UUID.randomUUID()
    uow.transaction { tx =>
      tx.schedules.insert(
        NewSchedule(scheduleId, medicationId, accountId, "fixed_times", utc, true, LocalDate.of(2026, 9, 21)),
        t0
      )
      tx.revisions.append(
        NewScheduleRevision(
          UUID.randomUUID(),
          scheduleId,
          revision = 1,
          effectiveFrom = t0,
          tz = utc,
          rule = Rule.AsNeeded(1, 0),
          policy = ReminderPolicy.Default,
          doseSnapshot = snapshot,
          createdBy = "user",
          reason = None
        ),
        t0
      )
      tx.occurrences.insertAll(
        List(
          NewOccurrence(
            id = occurrenceId,
            accountId = accountId,
            medicationId = medicationId,
            scheduleId = Some(scheduleId),
            revision = Some(1),
            origin = OccurrenceOrigin.Scheduled,
            localDate = LocalDate.of(2026, 9, 21),
            localTime = None,
            slotKey = "t0900",
            tz = utc,
            dstKind = DstKind.None,
            doseSnapshot = snapshot,
            state = state
          )
        )
      )
      ()
    }
    uow.channels.register(accountId, DeliveryTarget(channelId, "fake", Some("dm:owner")))
    Seed(uow, accountId, occurrenceId, channelId)

  private def dueState: Occurrence =
    Occurrence.scheduled(t0, ReminderPolicy.Default).copy(status = OccurrenceStatus.Due, epoch = 1)

  private def renderer(uow: MediatorFakes.InMemoryUnitOfWork): CatalogueOutboxRenderer =
    CatalogueOutboxRenderer(uow, codec, MediatorFakes.FixedClock(t0))

  private def row(seed: Seed, kind: String, payload: String, op: OutboxOp = OutboxOp.Send): OutboxMessage =
    OutboxMessage(
      id = UUID.randomUUID(),
      sendKey = s"occ:${seed.occurrenceId}:e1:s1:kinitial:c${seed.channelId}",
      op = op,
      kind = kind,
      vendor = "fake",
      accountId = Some(seed.accountId),
      occurrenceId = Some(seed.occurrenceId),
      channelId = Some(seed.channelId),
      epoch = Some(1),
      payload = payload,
      target = None,
      importance = "reminder",
      status = OutboxStatus.Queued,
      attempts = 1,
      nextAttemptAt = t0,
      leaseUntil = None,
      attemptedAt = None,
      previousAttemptedAt = None,
      opsDone = 0,
      possibleDuplicate = false,
      platformMessageId = None,
      lastError = None,
      createdAt = t0,
      sentAt = None
    )

  test("a reminder renders the catalogue body and the two control rows with decodable tokens"):
    val s = seed(dueState)
    val dispatch = LoopDispatch.Reminder(s.occurrenceId, Some("dm:owner"), "initial", 1, silent = false)
    val (chat, rendered) = renderer(s.uow).render(row(s, "reminder", LoopDispatch.toJson(dispatch)), CapabilityProfiles.Discord)

    assertEquals(chat, ChatRef("fake", "dm:owner"))
    val text = rendered.chunks.mkString("\n")
    assert(text.contains("Time for Vitamin D, 1000 IU."), s"body was: $text")
    assert(text.contains("with breakfast"), s"instructions line missing: $text")
    rendered.controls match
      case List(RenderedControls.Buttons(row1), RenderedControls.Buttons(row2)) =>
        assertEquals(row1.flatten.map(_.label), List("Taken", "Snooze 10m", "Skip"))
        assertEquals(row2.flatten.map(_.label), List("Snooze 30m", "Snooze 1h"))
      case other => fail(s"unexpected controls: $other")

    val decoded = rendered.choiceMap.map(entry => codec.decode(entry.callback).toOption.get)
    assertEquals(
      decoded.map(_.action.name),
      List("dose.taken", "dose.snooze", "dose.skip", "dose.snooze", "dose.snooze")
    )
    assertEquals(decoded.map(_.value), List(0L, 10L, 0L, 30L, 60L))
    assert(decoded.forall(_.subject == s.occurrenceId), "every token is bound to the occurrence")

  test("a spent snooze budget renders [Taken][Skip] only"):
    val spent = dueState.copy(snoozeCount = ReminderPolicy.Default.maxSnoozes)
    val s = seed(spent)
    val dispatch = LoopDispatch.Reminder(s.occurrenceId, Some("dm:owner"), "initial", 1, silent = false)
    val (_, rendered) = renderer(s.uow).render(row(s, "reminder", LoopDispatch.toJson(dispatch)), CapabilityProfiles.Discord)

    rendered.controls match
      case List(RenderedControls.Buttons(rows)) =>
        assertEquals(rows.flatten.map(_.label), List("Taken", "Skip"))
      case other => fail(s"unexpected controls: $other")

  test("the missed notice renders its catalogue sentence and the three controls"):
    val missed = dueState.copy(status = OccurrenceStatus.Missed, missedAt = Some(t0.plusSeconds(600)))
    val s = seed(missed)
    val dispatch = LoopDispatch.MissedNotice(s.occurrenceId, Some("dm:owner"), silent = false)
    val (_, rendered) = renderer(s.uow).render(row(s, "missed_notice", LoopDispatch.toJson(dispatch)), CapabilityProfiles.Discord)

    assert(rendered.chunks.mkString("\n").contains("I did not get a response for Vitamin D at 09:00"))
    val labels = rendered.controls.collect { case RenderedControls.Buttons(rows) => rows.flatten.map(_.label) }
    assertEquals(labels, List(List("I took it", "Skip", "Keep missed")))
    val actions = rendered.choiceMap.map(entry => codec.decode(entry.callback).toOption.get.action.name)
    assertEquals(actions, List("dose.taken", "dose.skip", "dose.keep_missed"))

  test("finalize renders the outcome copy of the resolved occurrence and keeps [Undo][Correct] on a taken one"):
    val taken = dueState.copy(
      status = OccurrenceStatus.Taken,
      takenAt = Some(t0.plusSeconds(180)),
      effectiveAt = Some(t0.plusSeconds(180))
    )
    val s = seed(taken)
    val dispatch = LoopDispatch.FinalizeControls(s.occurrenceId, "resolved")
    val finalizeRow = row(s, "reminder_finalize", LoopDispatch.toJson(dispatch), op = OutboxOp.Finalize)
      .copy(target = Some("dm:owner:m1"))
    val (chat, rendered) = renderer(s.uow).render(finalizeRow, CapabilityProfiles.Discord)

    assertEquals(chat, ChatRef("fake", "dm:owner"))
    assertEquals(rendered.chunks, List("Recorded at 09:03."))
    rendered.controls match
      case List(RenderedControls.Buttons(rows)) =>
        assertEquals(rows.flatten.map(_.label), List("Undo", "Correct"), "the post-Taken follow-up stays (M1.10)")
      case other => fail(s"unexpected controls: $other")
    val actions = rendered.choiceMap.map(entry => codec.decode(entry.callback).toOption.get.action.name)
    assertEquals(actions, List("dose.undo", "dose.correct"))

  test("a superseded finalize drops the controls and keeps the reminder text"):
    val s = seed(dueState)
    val dispatch = LoopDispatch.FinalizeControls(s.occurrenceId, "superseded")
    val finalizeRow = row(s, "reminder_finalize", LoopDispatch.toJson(dispatch), op = OutboxOp.Finalize)
      .copy(target = Some("dm:owner:m1"))
    val (_, rendered) = renderer(s.uow).render(finalizeRow, CapabilityProfiles.Discord)

    assertEquals(rendered.controls, Nil)
    assert(rendered.chunks.exists(_.contains("Time for Vitamin D")), s"the reminder text stays: ${rendered.chunks}")

  test("an interaction_reply safety row renders through the pure renderer and resolves the account chat"):
    val s = seed(dueState)
    val message = OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("Something went wrong on my side — please try again in a moment.")))),
      dedupeKey = "dedupe-1",
      correlationId = "corr-1"
    )
    val safetyRow = row(s, "interaction_reply", OutboundMessage.toJson(message)).copy(occurrenceId = None, epoch = None)
    val (chat, rendered) = renderer(s.uow).render(safetyRow, CapabilityProfiles.Discord)

    assertEquals(chat, ChatRef("fake", "dm:owner"))
    assertEquals(rendered.chunks, List("Something went wrong on my side — please try again in a moment."))

  test("the row's channel chat wins over the payload chat id (fallback rows are vendor-scoped)"):
    val s = seed(dueState)
    val fallbackChannelId = UUID.randomUUID()
    s.uow.channels.register(s.accountId, DeliveryTarget(fallbackChannelId, "fake", Some("dm:other")))
    val dispatch = LoopDispatch.Reminder(s.occurrenceId, Some("dm:owner"), "initial", 1, silent = false)
    val fallbackRow = row(s, "reminder", LoopDispatch.toJson(dispatch)).copy(channelId = Some(fallbackChannelId))

    val (chat, _) = renderer(s.uow).render(fallbackRow, CapabilityProfiles.Discord)
    assertEquals(chat, ChatRef("fake", "dm:other"))

  // ---------- catch-up digest (M1.8) ----------

  private final case class DigestSeed(
      uow: MediatorFakes.InMemoryUnitOfWork,
      accountId: UUID,
      channelId: UUID,
      occurrenceIds: List[UUID]
  )

  /** One account, one channel, one occurrence per given state (distinct slot keys). */
  private def digestSeed(states: List[Occurrence]): DigestSeed =
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val accountId = UUID.randomUUID()
    val medicationId = UUID.randomUUID()
    val scheduleId = UUID.randomUUID()
    val channelId = UUID.randomUUID()
    val occurrenceIds = states.map(_ => UUID.randomUUID())
    uow.transaction { tx =>
      tx.schedules.insert(
        NewSchedule(scheduleId, medicationId, accountId, "fixed_times", utc, true, LocalDate.of(2026, 9, 21)),
        t0
      )
      tx.revisions.append(
        NewScheduleRevision(
          UUID.randomUUID(),
          scheduleId,
          revision = 1,
          effectiveFrom = t0,
          tz = utc,
          rule = Rule.AsNeeded(1, 0),
          policy = ReminderPolicy.Default,
          doseSnapshot = snapshot,
          createdBy = "user",
          reason = None
        ),
        t0
      )
      tx.occurrences.insertAll(
        states.zip(occurrenceIds).map { (state, id) =>
          NewOccurrence(
            id = id,
            accountId = accountId,
            medicationId = medicationId,
            scheduleId = Some(scheduleId),
            revision = Some(1),
            origin = OccurrenceOrigin.Scheduled,
            localDate = LocalDate.of(2026, 9, 21),
            localTime = None,
            slotKey = s"t${id.toString.take(8)}",
            tz = utc,
            dstKind = DstKind.None,
            doseSnapshot = snapshot,
            state = state
          )
        }
      )
      ()
    }
    uow.channels.register(accountId, DeliveryTarget(channelId, "fake", Some("dm:owner")))
    DigestSeed(uow, accountId, channelId, occurrenceIds)

  private def unknownState(epoch: Int): Occurrence =
    Occurrence
      .scheduled(t0, ReminderPolicy.Default)
      .copy(status = OccurrenceStatus.Unknown, unknownReason = Some(dosecord.core.domain.UnknownReason.Outage),
        nextActionAt = None, epoch = epoch)

  private def missedState(epoch: Int): Occurrence =
    Occurrence
      .scheduled(t0, ReminderPolicy.Default)
      .copy(status = OccurrenceStatus.Missed, missedAt = Some(t0), nextActionAt = None, epoch = epoch)

  private def takenState(epoch: Int): Occurrence =
    Occurrence
      .scheduled(t0, ReminderPolicy.Default)
      .copy(status = OccurrenceStatus.Taken, takenAt = Some(t0), effectiveAt = Some(t0), nextActionAt = None,
        epoch = epoch)

  private def digestRow(s: DigestSeed, items: List[dosecord.contracts.DigestItem]): OutboxMessage =
    OutboxMessage(
      id = UUID.randomUUID(),
      sendKey = s"digest:${s.accountId}:32400",
      op = OutboxOp.Send,
      kind = "digest",
      vendor = "fake",
      accountId = Some(s.accountId),
      occurrenceId = None,
      channelId = Some(s.channelId),
      epoch = None,
      payload = LoopDispatch.toJson(LoopDispatch.Digest(s.accountId, 32400L, items)),
      target = None,
      importance = "reminder",
      status = OutboxStatus.Queued,
      attempts = 1,
      nextAttemptAt = t0,
      leaseUntil = None,
      attemptedAt = None,
      previousAttemptedAt = None,
      opsDone = 0,
      possibleDuplicate = false,
      platformMessageId = None,
      lastError = None,
      createdAt = t0,
      sentAt = None
    )

  test("a digest renders the catalogue header, one line per live item, and resolution controls"):
    val s = digestSeed(List(unknownState(1), missedState(2), dueState))
    val items = List(
      dosecord.contracts.DigestItem(s.occurrenceIds(0), 1),
      dosecord.contracts.DigestItem(s.occurrenceIds(1), 2),
      dosecord.contracts.DigestItem(s.occurrenceIds(2), 1)
    )
    val (chat, rendered) = renderer(s.uow).render(digestRow(s, items), CapabilityProfiles.Discord)

    assertEquals(chat, ChatRef("fake", "dm:owner"))
    val text = rendered.chunks.mkString("\n")
    assert(text.contains(dosecord.core.domain.copy.DigestCopy.header(3)), s"header missing: $text")
    assertEquals(text.linesIterator.count(_.startsWith("• ")), 3, "one line per live item")
    val labels = rendered.controls.collect { case RenderedControls.Buttons(rows) => rows.flatten.map(_.label) }
    assertEquals(
      labels,
      List(
        List("I took it", "Skip"),
        List("I took it", "Skip", "Keep missed"),
        List("Taken", "Skip")
      )
    )
    val decoded = rendered.choiceMap.map(entry => codec.decode(entry.callback).toOption.get)
    assertEquals(
      decoded.map(_.subject),
      List(s.occurrenceIds(0), s.occurrenceIds(0), s.occurrenceIds(1), s.occurrenceIds(1), s.occurrenceIds(1),
        s.occurrenceIds(2), s.occurrenceIds(2)),
      "every token is bound to its occurrence"
    )
    assertEquals(decoded.map(_.action.name),
      List("dose.taken", "dose.skip", "dose.taken", "dose.skip", "dose.keep_missed", "dose.taken", "dose.skip"))

  test("stale-epoch and resolved items are dropped at render time; the header counts what remains"):
    val s = digestSeed(List(takenState(2), unknownState(1)))
    val items = List(
      dosecord.contracts.DigestItem(s.occurrenceIds(0), 1), // stale epoch: the occurrence moved to epoch 2
      dosecord.contracts.DigestItem(s.occurrenceIds(1), 1)
    )
    val (_, rendered) = renderer(s.uow).render(digestRow(s, items), CapabilityProfiles.Discord)

    val text = rendered.chunks.mkString("\n")
    assert(text.contains(dosecord.core.domain.copy.DigestCopy.header(1)), s"header was: $text")
    assertEquals(text.linesIterator.count(_.startsWith("• ")), 1)

  test("a digest whose items all resolved is skipped with DigestEmpty"):
    val s = digestSeed(List(takenState(1)))
    val items = List(dosecord.contracts.DigestItem(s.occurrenceIds(0), 1))
    intercept[DigestEmpty](renderer(s.uow).render(digestRow(s, items), CapabilityProfiles.Discord))

  test("a merged bucket repeating an occurrence renders it once"):
    val s = digestSeed(List(unknownState(1)))
    val items = List(
      dosecord.contracts.DigestItem(s.occurrenceIds(0), 1),
      dosecord.contracts.DigestItem(s.occurrenceIds(0), 1)
    )
    val (_, rendered) = renderer(s.uow).render(digestRow(s, items), CapabilityProfiles.Discord)
    assertEquals(rendered.chunks.mkString("\n").linesIterator.count(_.startsWith("• ")), 1)

  test("more items than the 8-dose cap: eight control rows and a closing count line"):
    val s = digestSeed(List.fill(9)(unknownState(1)))
    val items = s.occurrenceIds.map(id => dosecord.contracts.DigestItem(id, 1))
    val (_, rendered) = renderer(s.uow).render(digestRow(s, items), CapabilityProfiles.Discord)

    val text = rendered.chunks.mkString("\n")
    assert(text.contains(dosecord.core.domain.copy.DigestCopy.header(9)), s"header was: $text")
    assert(text.contains(dosecord.core.domain.copy.DigestCopy.more(1)), s"closing line missing: $text")
    assertEquals(text.linesIterator.count(_.startsWith("• ")), 8, "only the capped items get lines")
    assertEquals(rendered.controls.size, 8)
