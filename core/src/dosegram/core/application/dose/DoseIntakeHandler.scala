package dosegram.core.application.dose

import dosegram.contracts.*
import dosegram.core.application.CommandRegistry
import dosegram.core.application.capture.MedicationConsentGate
import dosegram.core.application.capture.MedicationScreen
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.Decide
import dosegram.core.domain.DecideContext
import dosegram.core.domain.DispatchIntent
import dosegram.core.domain.DoseSnapshot
import dosegram.core.domain.DstKind
import dosegram.core.domain.Feedback
import dosegram.core.domain.FinalizeReason
import dosegram.core.domain.LastUserAction
import dosegram.core.domain.Occurrence
import dosegram.core.domain.OccurrenceEvent
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Refusal
import dosegram.core.domain.copy.DoseActionKind
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.InputCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.input.SnoozeDuration
import dosegram.core.ports.Clock
import dosegram.core.ports.NewDoseAction
import dosegram.core.ports.NewOccurrence
import dosegram.core.ports.OccurrenceOrigin
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.Tx
import dosegram.core.scheduling.FinalizeDispatch

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** The M1.10 one-tap intake flows (ROADMAP M1.10, DESIGN.md section 7.3 user rows, ADR-012): the direct-mode handlers
  * Taken / Snooze / Skip / Undo / Correct / Keep missed / Log, and the universal commands `/taken [medication]`,
  * `/snooze <min>`, `/skip [medication]`, `/log <medication>`. Every handler loads the occurrence
  * `SELECT ... FOR UPDATE` inside the mediator's per-event transaction (a tap on a row claimed by an in-flight tick
  * waits and applies on the fresh row, DESIGN.md section 7.4) and delegates the transition to the M1.3
  * [[Decide.decide]] FSM — no transition logic lives here.
  *
  * The recorded effects of one user event, in the same transaction: the fenced occurrence write, the `dose_actions`
  * row(s) carrying the inbound event's idempotency key plus vendor, platform identity and message id (R27), the
  * finalize ops for every recorded handle of the occurrence (DESIGN.md section 7.6), and — on a transition to Taken —
  * exactly one `dosegram.medication.intake_taken.v1` domain event (R30), returned through [[Reply.domainEvents]] so the
  * mediator appends and publishes it (ADR-003).
  *
  * The two-choice correction (`dose.correct` value 1 = Now, 2 = At scheduled time, 3 = Cancel) is reached from
  * [Correct] on the post-Taken follow-up, from a Taken after the 15-minute undo window ("The undo window has passed —
  * correct it instead?") and from a direct Taken beyond the 24 h late-log window; custom times, notes and reason chips
  * are M3.1. Manual logging (`dose.log`, `/log`) creates one `origin = manual` occurrence born `taken` (as-needed and
  * scheduled medications alike).
  */
final class DoseIntakeHandler(codec: CallbackCodec, clock: Clock, inner: ChatHandler) extends ChatHandler:

  import DoseIntakeHandler.*

  private val gate = MedicationConsentGate(codec, clock)

  /** Every dose button and dose command is behind the `medication` consent (PRODUCT_SPEC R-IDC-9): without it nothing
    * is recorded, and the answer is the consent prompt, which leads to Today.
    */
  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    event.body match
      case Inbound.InteractionSubmitted(ref, source) if isDoseAction(ref) =>
        gate.guarded(event, principal, tx)(MedicationScreen.Today)(onTap(event, principal, ref, source, tx))
      case Inbound.CommandInvoked(name, _, _) if commandNames.contains(name) =>
        guardLinked(event, principal):
          gate.guarded(event, principal, tx)(MedicationScreen.Today)(onCommand(event, principal, name, tx))
      case _ => inner.handle(event, principal, tx)

  private def isDoseAction(ref: CallbackRef): Boolean =
    ActionRegistry.byId(ref.actionId).exists(_.name.startsWith("dose."))

  private def guardLinked(event: InboundEvent, principal: Principal)(reply: => Reply): Reply =
    if principal.accountId.isEmpty then
      Reply(followUps =
        List(
          OutboundMessage(
            body = List(Node.Paragraph(List(Inline.Text(IdentityCopy.createFirst)))),
            dedupeKey = s"dose:unlinked:${event.vendor}:${event.vendorEventId}",
            correlationId = s"${event.vendor}:${event.vendorEventId}"
          )
        )
      )
    else reply

  // ---------- Direct-mode taps ----------

  private def onTap(
      event: InboundEvent,
      principal: Principal,
      ref: CallbackRef,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    ActionRegistry.byId(ref.actionId).map(_.name) match
      case Some("dose.taken") =>
        withOccurrence(event, principal, ref.subject, tx): occ =>
          applyUserEvent(event, principal, occ, OccurrenceEvent.Taken, source, tx)
      case Some("dose.skip") =>
        withOccurrence(event, principal, ref.subject, tx): occ =>
          applyUserEvent(event, principal, occ, OccurrenceEvent.Skipped(reason = None), source, tx)
      case Some("dose.snooze") =>
        withOccurrence(event, principal, ref.subject, tx): occ =>
          applyUserEvent(event, principal, occ, OccurrenceEvent.Snoozed(ref.value.toInt), source, tx)
      case Some("dose.undo") =>
        withOccurrence(event, principal, ref.subject, tx): occ =>
          applyUserEvent(event, principal, occ, OccurrenceEvent.Undo, source, tx)
      case Some("dose.correct")     => onCorrectTap(event, principal, ref, source, tx)
      case Some("dose.keep_missed") =>
        withOccurrence(event, principal, ref.subject, tx): occ =>
          keepMissed(occ, tx)
      case Some("dose.log") =>
        withMedication(event, principal, ref.subject, tx): medication =>
          logDose(event, principal, medication, source, tx)
      case _ => inner.handle(event, principal, tx) // dose.note: M3.1

  /** The two-choice correction (M1.10): value 0 opens the prompt, 1 records Now, 2 records At scheduled time, 3 is
    * Cancel. A correction only applies to a resolved row; an open row gets the refusal toast.
    */
  private def onCorrectTap(
      event: InboundEvent,
      principal: Principal,
      ref: CallbackRef,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    ref.value match
      case 0 =>
        withOccurrence(event, principal, ref.subject, tx): occ =>
          if occ.status.isOpen then Reply(toast = Some(ReminderCopy.refusal(Refusal.CorrectOnOpenRow)))
          else correctionPromptReply(event, occ, ReminderCopy.correctionPrompt)
      case 1 =>
        withOccurrence(event, principal, ref.subject, tx): occ =>
          applyUserEvent(event, principal, occ, OccurrenceEvent.Corrected(clock.now()), source, tx)
      case 2 =>
        withOccurrence(event, principal, ref.subject, tx): occ =>
          applyUserEvent(event, principal, occ, OccurrenceEvent.Corrected(occ.state.scheduledFor), source, tx)
      case _ => Reply(toast = Some(ReminderCopy.correctionCancelled))

  /** [Keep missed] on the missed notice (R23): the row stays missed; the notice's recorded handles are finalized so a
    * late tap on them stops resolving. No state change, no action row — an acknowledgement, not a transition.
    */
  private def keepMissed(occ: StoredOccurrence, tx: Tx): Reply =
    if occ.status != OccurrenceStatus.Missed then Reply(toast = Some(ReminderCopy.staleControlToast))
    else
      FinalizeDispatch.enqueue(tx, occ.id, occ.accountId, occ.state.epoch, FinalizeReason.Resolved, clock.now())
      Reply(toast = Some(ReminderCopy.keepMissedConfirmation))

  // ---------- The decide pipeline ----------

  /** One user event against a locked occurrence: decide, the fenced write, the action row(s) with the inbound event's
    * idempotency key and vendor attribution, the finalize ops for every recorded handle, and the intake event on a
    * transition to Taken.
    */
  private def applyUserEvent(
      event: InboundEvent,
      principal: Principal,
      occ: StoredOccurrence,
      userEvent: OccurrenceEvent,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    val now = clock.now()
    val (policy, quiet) = tx.policies.forOccurrence(occ)
    val ctx = DecideContext(
      event = userEvent,
      delivered = tx.outbox.deliveredFor(occ.id),
      nextOccurrenceScheduledFor = occ.scheduleId.flatMap(tx.occurrences.nextScheduledAfter(_, occ.state.scheduledFor)),
      lastUndoableAction = lastUndoableAction(tx, occ.id)
    )
    val transition = Decide.decide(occ.state, policy, quiet, now, ctx)
    if transition.row == occ.state then noTransitionReply(event, occ, transition.feedback)
    else
      val applied =
        tx.occurrences.applyTransition(occ.id, occ.version, occ.state.epoch, transition.row, now)
      if !applied then
        // The row was locked FOR UPDATE above, so the guarded write cannot lose a race; failing the transaction is the
        // honest failure and the mediator's failure toast answers (R68).
        throw new IllegalStateException(s"occurrence ${occ.id} changed while locked for a user action")
      val correlationId = s"${event.vendor}:${event.vendorEventId}"
      (transition.action.toList ++ transition.additionalActions).foreach: intent =>
        tx.doseActions.append(
          NewDoseAction
            .from(intent, occ.id, occ.accountId, correlationId)
            .copy(
              idempotencyKey = Some(correlationId),
              vendor = Some(event.vendor),
              platformIdentityId = Some(principal.identityId.uuid),
              platformMessageId = source.map(_.messageId)
            )
        )
      transition.dispatches.foreach:
        case DispatchIntent.FinalizeControls(reason) =>
          FinalizeDispatch.enqueue(tx, occ.id, occ.accountId, transition.row.epoch, reason, now)
        case _ => () // a user event never sends a reminder of its own
      feedbackReply(occ, transition, takenEvent(occ, userEvent, transition, now))
    end if
  end applyUserEvent

  /** Exactly one `intake_taken.v1` per transition to Taken recorded by a user event (R30); corrections carry the
    * explicit `effective_at` they recorded.
    */
  private def takenEvent(
      occ: StoredOccurrence,
      userEvent: OccurrenceEvent,
      transition: dosegram.core.domain.Transition,
      now: Instant
  ): List[Event] =
    val recordsTaken = userEvent match
      case OccurrenceEvent.Taken        => true
      case OccurrenceEvent.Corrected(_) => true
      case _                            => false
    if recordsTaken && transition.row.status == OccurrenceStatus.Taken then
      val effectiveAt = transition.row.effectiveAt.getOrElse(now)
      List(
        Event.IntakeTaken(
          AccountId(occ.accountId),
          occ.id,
          effectiveAt,
          takenLate = effectiveAt.isAfter(occ.state.dueWindowEnd)
        )
      )
    else Nil

  /** The reply of a transition: the catalogue confirmation as the toast; the finalize ops carry the recorded handles.
    */
  private def feedbackReply(
      occ: StoredOccurrence,
      transition: dosegram.core.domain.Transition,
      events: List[Event]
  ): Reply =
    val toast = transition.feedback match
      case Feedback.Recorded(_) =>
        Some(ReminderCopy.takenConfirmation(formatTime(transition.row.takenAt.getOrElse(clock.now()), occ.tz)))
      case Feedback.SkippedRecorded     => Some(ReminderCopy.skippedConfirmation)
      case Feedback.SnoozedUntil(until) => Some(ReminderCopy.snoozeConfirmation(formatTime(until, occ.tz)))
      case Feedback.Undone(next)        => Some(ReminderCopy.undoConfirmation(formatTime(next, occ.tz)))
      case Feedback.Corrected(at)       => Some(ReminderCopy.correctedConfirmation(formatTime(at, occ.tz)))
      case _                            => None
    Reply(toast = toast, domainEvents = events)

  /** The reply of a no-op or refusal: a second Taken answers "Already recorded at 09:03" with no new action row
    * (DESIGN.md section 7.3); the two correction refusals open the correction flow instead of a toast.
    */
  private def noTransitionReply(
      event: InboundEvent,
      occ: StoredOccurrence,
      feedback: Feedback
  ): Reply =
    feedback match
      case Feedback.AlreadyRecorded(at) =>
        Reply(toast = Some(ReminderCopy.alreadyRecorded(formatTime(at, occ.tz))))
      case Feedback.Refused(Refusal.CorrectionPrompt) =>
        correctionPromptReply(event, occ, ReminderCopy.correctionPrompt)
      case Feedback.Refused(Refusal.UndoWindowPassedOfferCorrect) =>
        correctionPromptReply(event, occ, ReminderCopy.undoWindowPassed)
      case Feedback.Refused(reason) => Reply(toast = Some(ReminderCopy.refusal(reason)))
      case _                        => Reply.empty

  /** "When did you take it?" / "The undo window has passed — correct it instead?" with [Now][At scheduled time]
    * [Cancel] (M1.10; custom time is M3.1).
    */
  private def correctionPromptReply(
      event: InboundEvent,
      occ: StoredOccurrence,
      bodyText: String
  ): Reply =
    Reply(followUps =
      List(
        OutboundMessage(
          body = List(Node.Paragraph(List(Inline.Text(bodyText)))),
          blocks = List(
            Block.Choices(
              ChoiceSet(
                id = "dose.correct",
                choices = List(
                  Choice.callback(Labels.Now, directToken("dose.correct", occ.id, 1), ChoiceStyle.Success),
                  Choice.callback(Labels.AtScheduledTime, directToken("dose.correct", occ.id, 2)),
                  Choice.callback(Labels.Cancel, directToken("dose.correct", occ.id, 3))
                )
              )
            )
          ),
          dedupeKey = s"dose:correct:${occ.id}:${event.vendor}:${event.vendorEventId}",
          correlationId = s"${event.vendor}:${event.vendorEventId}"
        )
      )
    )

  // ---------- Universal commands ----------

  private def onCommand(event: InboundEvent, principal: Principal, name: String, tx: Tx): Reply =
    name match
      case CommandRegistry.Taken.name =>
        resolveOpen(principal, tx, medicationArg(event))
          .fold(identity, occ => applyUserEvent(event, principal, occ, OccurrenceEvent.Taken, None, tx))
      case CommandRegistry.Skip.name =>
        resolveOpen(principal, tx, medicationArg(event))
          .fold(
            identity,
            occ => applyUserEvent(event, principal, occ, OccurrenceEvent.Skipped(reason = None), None, tx)
          )
      case CommandRegistry.Snooze.name =>
        SnoozeDuration.parse(snoozeArg(event)) match
          case Left(SnoozeDuration.Rejection.Empty) => Reply(followUps = List(usage(event, CommandRegistry.Snooze)))
          case Left(rejection)                      =>
            Reply(followUps = List(notUnderstood(event, CommandRegistry.Snooze, InputCopy.duration(rejection))))
          case Right(minutes) =>
            // `/snooze <min>` carries no medication: it always targets the latest open dose (M1.10).
            resolveOpen(principal, tx, None)
              .fold(identity, occ => applyUserEvent(event, principal, occ, OccurrenceEvent.Snoozed(minutes), None, tx))
      case CommandRegistry.Log.name =>
        medicationArg(event) match
          case Some(name) =>
            withMedicationByName(event, principal, name, tx): medication =>
              logDose(event, principal, medication, None, tx)
          case None => LogDosePicker.reply(codec, event, principal, tx)
      case _ => Reply.empty

  /** The optional medication argument: structured args where the vendor supplies them, else the raw line after the
    * command name (the console grammar).
    */
  private def medicationArg(event: InboundEvent): Option[String] =
    event.body match
      case Inbound.CommandInvoked(_, args, raw) =>
        args
          .get("medication")
          .map(_.trim)
          .filter(_.nonEmpty)
          .orElse:
            val rest = raw.trim.drop(1).dropWhile(c => !c.isWhitespace).trim
            Option.when(rest.nonEmpty)(rest)
      case _ => None

  /** The typed `/snooze` length, read by the shared duration grammar ("10", "10m", "1h", "1.5h"). */
  private def snoozeArg(event: InboundEvent): String =
    event.body match
      case Inbound.CommandInvoked(_, args, raw) =>
        args
          .get("minutes")
          .map(_.trim)
          .filter(_.nonEmpty)
          .getOrElse:
            raw.trim.drop(1).dropWhile(c => !c.isWhitespace).trim
      case _ => ""

  private def usage(event: InboundEvent, spec: CommandSpec): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(CommandRegistry.usage(spec))))),
      dedupeKey = s"usage:${spec.name}:${event.vendor}:${event.vendorEventId}",
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )

  /** An argument that was typed but not understood: what was not understood, the examples and the command's grammar
    * (BUX-CMD-04); nothing is recorded.
    */
  private def notUnderstood(event: InboundEvent, spec: CommandSpec, reason: String): OutboundMessage =
    OutboundMessage(
      body = List(reason, CommandRegistry.usage(spec)).map(text => Node.Paragraph(List(Inline.Text(text)))),
      dedupeKey = s"usage:${spec.name}:${event.vendor}:${event.vendorEventId}",
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )

  /** The command target: the named medication's latest open occurrence, or the account's latest open one when the
    * medication is omitted (M1.10).
    */
  private def resolveOpen(
      principal: Principal,
      tx: Tx,
      medicationName: Option[String]
  ): Either[Reply, StoredOccurrence] =
    val account = principal.accountId.get.uuid
    medicationName match
      case Some(name) =>
        tx.medications.findByNameNorm(account, normalizeName(name)) match
          case None             => Left(Reply(toast = Some(ReminderCopy.unknownMedication(name))))
          case Some(medication) =>
            tx.occurrences.latestOpenForAccount(account, Some(medication.id), clock.now()) match
              case None      => Left(Reply(toast = Some(ReminderCopy.noOpenDose(medication.name))))
              case Some(occ) => Right(occ)
      case None =>
        tx.occurrences.latestOpenForAccount(account, None, clock.now()) match
          case None      => Left(Reply(toast = Some(ReminderCopy.nothingDue)))
          case Some(occ) => Right(occ)

  // ---------- Manual logging ----------

  /** One `origin = manual` occurrence born `taken` (as-needed and scheduled medications alike), its `taken` action row
    * and exactly one `intake_taken.v1` (R30). Excluded from the adherence denominator by M1.4b.
    */
  private def logDose(
      event: InboundEvent,
      principal: Principal,
      medication: StoredMedication,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    val account = principal.accountId.get.uuid
    val occurrenceId = source.fold(UUID.randomUUID())(pickerIntent(account, _))
    tx.occurrences.lockById(occurrenceId) match
      case Some(logged) => alreadyLogged(logged)
      case None         => recordLog(event, principal, medication, source, occurrenceId, tx)

  /** One displayed picker is one intent (BUX-TAKE-02, C20): the occurrence id is derived from the picker message, so
    * every tap on it, however fast and after any restart, names the same row and the primary key admits one.
    */
  private def pickerIntent(account: UUID, picker: MessageHandle): UUID =
    UUID.nameUUIDFromBytes(
      s"dose.log:$account:${picker.vendor}:${picker.chatId}:${picker.messageId}".getBytes(StandardCharsets.UTF_8)
    )

  /** A later tap on a picker that already logged: nothing changes. The first tap's edit, or its outbox retry, shows the
    * confirmation (BUX-STALE-01).
    */
  private def alreadyLogged(logged: StoredOccurrence): Reply =
    Reply(toast = Some(ReminderCopy.alreadyLogged(formatTime(logged.state.scheduledFor, logged.tz))))

  private def recordLog(
      event: InboundEvent,
      principal: Principal,
      medication: StoredMedication,
      source: Option[MessageHandle],
      occurrenceId: UUID,
      tx: Tx
  ): Reply =
    val now = clock.now()
    val account = principal.accountId.get.uuid
    val zone = ZoneId.of(tx.accounts.timezoneOf(account).getOrElse("UTC"))
    val local = now.atZone(zone)
    val state = Occurrence(
      status = OccurrenceStatus.Taken,
      scheduledFor = now,
      dueWindowStart = now,
      dueWindowEnd = now,
      missDeadline = now,
      nextActionAt = None,
      takenAt = Some(now),
      effectiveAt = Some(now),
      epoch = 1
    )
    val row = NewOccurrence(
      id = occurrenceId,
      accountId = account,
      medicationId = medication.id,
      scheduleId = None,
      revision = None,
      origin = OccurrenceOrigin.Manual,
      localDate = local.toLocalDate,
      localTime = Some(HhMm.unsafe(f"${local.getHour}%02d:${local.getMinute}%02d")),
      slotKey = s"manual:$occurrenceId",
      tz = zone,
      dstKind = DstKind.None,
      doseSnapshot = DoseSnapshot(medication.name, medication.doseAmount, medication.doseUnit, medication.instructions),
      state = state
    )
    // A concurrent tap on the same picker waits on the key and inserts nothing: it is the same intent, already logged.
    if tx.occurrences.insertAll(List(row)) == 1 then recorded(event, principal, source, row, now, tx)
    else tx.occurrences.lockById(occurrenceId).fold(Reply(toast = Some(ReminderCopy.staleControlToast)))(alreadyLogged)
  end recordLog

  /** The `taken` action row of a new manual occurrence, the picker's confirmation and the intake event. */
  private def recorded(
      event: InboundEvent,
      principal: Principal,
      source: Option[MessageHandle],
      row: NewOccurrence,
      now: Instant,
      tx: Tx
  ): Reply =
    tx.doseActions.append(
      NewDoseAction(
        id = UUID.randomUUID(),
        occurrenceId = row.id,
        accountId = row.accountId,
        action = DoseActionKind.Taken,
        actor = dosegram.core.domain.Actor.User,
        occurredAt = now,
        priorStatus = OccurrenceStatus.Taken,
        newStatus = OccurrenceStatus.Taken,
        correlationId = s"${event.vendor}:${event.vendorEventId}",
        effectiveAt = Some(now),
        idempotencyKey = Some(s"${event.vendor}:${event.vendorEventId}"),
        vendor = Some(event.vendor),
        platformIdentityId = Some(principal.identityId.uuid),
        platformMessageId = source.map(_.messageId)
      )
    )
    val time = formatTime(now, row.tz)
    Reply(
      replace = source.map(confirmation(event, row.doseSnapshot.medicationName, time, row.id, _)),
      toast = Some(ReminderCopy.takenConfirmation(time)),
      domainEvents = List(Event.IntakeTaken(AccountId(row.accountId), row.id, now, takenLate = false))
    )

  /** The picker edited in place into the confirmation, its keyboard removed so it cannot be tapped again. */
  private def confirmation(
      event: InboundEvent,
      name: String,
      time: String,
      occurrenceId: UUID,
      picker: MessageHandle
  ): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(ReminderCopy.logged(name, time))))),
      replaces = Some(picker),
      dedupeKey = s"dose:logged:$occurrenceId",
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )

  // ---------- Loaders ----------

  private def withOccurrence(
      event: InboundEvent,
      principal: Principal,
      occurrenceId: UUID,
      tx: Tx
  )(f: StoredOccurrence => Reply): Reply =
    principal.accountId match
      case None          => guardLinked(event, principal)(Reply.empty)
      case Some(account) =>
        tx.occurrences.lockById(occurrenceId) match
          case Some(occ) if occ.accountId == account.uuid => f(occ)
          case _                                          => Reply(toast = Some(ReminderCopy.staleControlToast))

  private def withMedication(
      event: InboundEvent,
      principal: Principal,
      medicationId: UUID,
      tx: Tx
  )(f: StoredMedication => Reply): Reply =
    principal.accountId match
      case None          => guardLinked(event, principal)(Reply.empty)
      case Some(account) =>
        tx.medications.get(medicationId) match
          case Some(medication) if medication.accountId == account.uuid => f(medication)
          case _ => Reply(toast = Some(ReminderCopy.staleControlToast))

  private def withMedicationByName(
      event: InboundEvent,
      principal: Principal,
      name: String,
      tx: Tx
  )(f: StoredMedication => Reply): Reply =
    principal.accountId match
      case None          => guardLinked(event, principal)(Reply.empty)
      case Some(account) =>
        tx.medications.findByNameNorm(account.uuid, normalizeName(name)) match
          case Some(medication) => f(medication)
          case None             => Reply(toast = Some(ReminderCopy.unknownMedication(name)))

  // ---------- Helpers ----------

  /** The latest user action row still eligible for undo (taken/skipped/snoozed, not already undone), read from
    * `dose_actions` for the FSM's undo arm (ADR-012).
    */
  private def lastUndoableAction(tx: Tx, occurrenceId: UUID): Option[LastUserAction] =
    val actions = tx.doseActions.listForOccurrence(occurrenceId)
    val undoneSeqs =
      actions.flatMap(action => if action.action == DoseActionKind.Undone then action.undoesSeq.toList else Nil).toSet
    actions.reverse
      .find(action =>
        action.actor == dosegram.core.domain.Actor.User &&
          undoableKinds.contains(action.action) && !undoneSeqs.contains(action.seq)
      )
      .map(action => LastUserAction(action.action, action.seq, action.occurredAt))

  private def directToken(actionName: String, subject: UUID, value: Long): String =
    codec.encode(CallbackMode.Direct, ActionRegistry.byName(actionName).get, subject, value).wire

  private def normalizeName(name: String): String = name.trim.toLowerCase(Locale.ROOT)

  private def formatTime(at: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(at)

private object DoseIntakeHandler:
  val commandNames: Set[String] =
    Set(CommandRegistry.Taken.name, CommandRegistry.Snooze.name, CommandRegistry.Skip.name, CommandRegistry.Log.name)

  val undoableKinds: Set[DoseActionKind] =
    Set(DoseActionKind.Taken, DoseActionKind.Skipped, DoseActionKind.Snoozed)
end DoseIntakeHandler
