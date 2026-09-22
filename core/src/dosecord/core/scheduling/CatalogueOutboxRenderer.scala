package dosecord.core.scheduling

import dosecord.contracts.Block
import dosecord.contracts.CapabilityProfile
import dosecord.contracts.ChatRef
import dosecord.contracts.Choice
import dosecord.contracts.ChoiceSet
import dosecord.contracts.ChoiceStyle
import dosecord.contracts.Importance
import dosecord.contracts.Inline
import dosecord.contracts.LoopDispatch
import dosecord.contracts.Node
import dosecord.contracts.OutboundMessage
import dosecord.contracts.RenderedMessage
import dosecord.contracts.VendorOp
import dosecord.core.chat.ActionRegistry
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackMode
import dosecord.core.chat.Controls
import dosecord.core.chat.Renderer
import dosecord.core.domain.Decide
import dosecord.core.domain.DoseSnapshot
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.ReminderCopy
import dosecord.core.ports.Clock
import dosecord.core.ports.OutboxMessage
import dosecord.core.ports.StoredOccurrence
import dosecord.core.ports.Tx
import dosecord.core.ports.UnitOfWork

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The production [[OutboxRenderer]] (ROADMAP M1.7): the payload is resolved at send time — the loop's `LoopDispatch`
  * rows against the current occurrence row and the M1.4a copy catalogue (reminder text, snooze options filtered by
  * `Decide.availableSnoozeOptions`), the mediator's 30 s safety rows (`OutboundMessage` payloads, DESIGN.md section 4.6
  * step 8) through the pure renderer. Every database read is a short transaction completed before the vendor call
  * (ADR-009); the chat of a reminder row comes from the payload or the account's delivery channel.
  */
final class CatalogueOutboxRenderer(uow: UnitOfWork, codec: CallbackCodec, clock: Clock) extends OutboxRenderer:

  override def render(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
    row.kind match
      case "reminder"          => renderReminder(row, capabilities)
      case "missed_notice"     => renderMissedNotice(row, capabilities)
      case "reminder_finalize" => renderFinalize(row, capabilities)
      case "interaction_reply" => renderInteractionReply(row, capabilities)
      case other               => throw new IllegalArgumentException(s"outbox row ${row.id}: unknown kind '$other'")

  // ---------- interaction replies (the 30 s safety row of synchronously delivered replies) ----------

  private def renderInteractionReply(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
    val message = OutboundMessage.fromJson(row.payload)
    val chat = chatForAccount(row)
    lower(row, chat, capabilities, message)

  /** The vendor's DM chat of the account's primary channel; a safety row whose account has no such chat cannot be
    * redelivered.
    */
  private def chatForAccount(row: OutboxMessage): ChatRef =
    val chatId = row.accountId.flatMap: accountId =>
      uow.transaction: tx =>
        tx.channels.activePrimaryChannels(accountId).find(_.vendor == row.vendor).flatMap(_.chatId)
    ChatRef(
      row.vendor,
      chatId.getOrElse(
        throw new IllegalStateException(s"outbox row ${row.id}: no delivery channel chat for vendor ${row.vendor}")
      )
    )

  // ---------- reminders ----------

  private def renderReminder(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
    val dispatch = row.payloadAs[LoopDispatch.Reminder]("reminder")
    val facts = reminderFacts(row, dispatch.occurrenceId, dispatch.chatId)
    val body = ReminderCopy
      .reminderBody(facts.snapshot.medicationName, doseText(facts.snapshot), facts.snapshot.instructions)
      .map(line => Node.Paragraph(List(Inline.Text(line))))
    val taken = token("dose.taken", facts.occ.id, 0)
    val skip = token("dose.skip", facts.occ.id, 0)
    val snoozes =
      Decide
        .availableSnoozeOptions(facts.occ.state, facts.policy, clock.now(), facts.nextOccurrence)
        .map(minutes => (minutes, token("dose.snooze", facts.occ.id, minutes.toLong)))
    val blocks =
      if snoozes.isEmpty then
        // The snooze budget is spent (ADR-012): the row still resolves with [Taken][Skip].
        List(
          Block.Choices(
            ChoiceSet(
              id = "reminder.main",
              choices = List(
                Choice(Labels.Taken, taken, ChoiceStyle.Success),
                Choice(Labels.Skip, skip)
              )
            )
          )
        )
      else Controls.reminder(taken, skip, snoozes)
    val message = OutboundMessage(
      body = body,
      blocks = blocks,
      importance = Importance.Reminder,
      silent = dispatch.silent,
      dedupeKey = row.sendKey,
      correlationId = row.sendKey
    )
    lower(row, facts.chat, capabilities, message)

  private def renderMissedNotice(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
    val dispatch = row.payloadAs[LoopDispatch.MissedNotice]("missed_notice")
    val facts = reminderFacts(row, dispatch.occurrenceId, dispatch.chatId)
    val time = formatTime(facts.occ, facts.occ.state.scheduledFor)
    val message = OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(ReminderCopy.missedNotice(facts.snapshot.medicationName, time))))),
      blocks = List(
        Block.Choices(
          ChoiceSet(
            id = "missed_notice",
            choices = List(
              Choice(Labels.ITookIt, token("dose.taken", facts.occ.id, 0), ChoiceStyle.Success),
              Choice(Labels.Skip, token("dose.skip", facts.occ.id, 0)),
              Choice(Labels.KeepMissed, token("dose.keep_missed", facts.occ.id, 0))
            )
          )
        )
      ),
      importance = Importance.Reminder,
      silent = dispatch.silent,
      dedupeKey = row.sendKey,
      correlationId = row.sendKey
    )
    lower(row, facts.chat, capabilities, message)

  // ---------- finalize ----------

  /** The finalize edit carries the outcome copy of the occurrence's current status (DESIGN.md section 7.3): a resolved
    * row shows its confirmation, a superseded one keeps the reminder text with the controls dropped.
    */
  private def renderFinalize(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
    val dispatch = row.payloadAs[LoopDispatch.FinalizeControls]("reminder_finalize")
    val target = OutboxDispatcher.decodeHandle(
      row.vendor,
      row.target.getOrElse(throw new IllegalStateException(s"outbox row ${row.id}: finalize op without a target"))
    )
    val occ = uow.transaction(
      _.occurrences
        .get(dispatch.occurrenceId)
        .getOrElse(
          throw new IllegalStateException(s"outbox row ${row.id}: unknown occurrence ${dispatch.occurrenceId}")
        )
    )
    val snapshot = occ.doseSnapshot.getOrElse(
      throw new IllegalStateException(s"occurrence ${occ.id} carries no dose snapshot")
    )
    val summary = occ.state.status match
      case OccurrenceStatus.Taken =>
        List(ReminderCopy.takenConfirmation(formatTime(occ, occ.state.takenAt.getOrElse(occ.state.scheduledFor))))
      case OccurrenceStatus.Skipped => List(ReminderCopy.skippedConfirmation)
      case OccurrenceStatus.Missed  =>
        List(ReminderCopy.missedNotice(snapshot.medicationName, formatTime(occ, occ.state.scheduledFor)))
      case _ =>
        ReminderCopy.reminderBody(snapshot.medicationName, doseText(snapshot), snapshot.instructions)
    val message = OutboundMessage(
      body = summary.map(line => Node.Paragraph(List(Inline.Text(line)))),
      importance = Importance.Reminder,
      dedupeKey = row.sendKey,
      correlationId = row.sendKey
    )
    lower(row, ChatRef(row.vendor, target.chatId), capabilities, message)

  // ---------- helpers ----------

  private final case class ReminderFacts(
      chat: ChatRef,
      occ: StoredOccurrence,
      snapshot: DoseSnapshot,
      policy: ReminderPolicy,
      nextOccurrence: Option[Instant]
  )

  private def reminderFacts(
      row: OutboxMessage,
      occurrenceId: UUID,
      payloadChatId: Option[String]
  ): ReminderFacts =
    uow.transaction: tx =>
      val occ = tx.occurrences
        .get(occurrenceId)
        .getOrElse(throw new IllegalStateException(s"outbox row ${row.id}: unknown occurrence $occurrenceId"))
      val snapshot = occ.doseSnapshot.getOrElse(
        throw new IllegalStateException(s"occurrence ${occ.id} carries no dose snapshot")
      )
      val (policy, _) = tx.policies.forOccurrence(occ)
      val nextOccurrence = occ.scheduleId.flatMap(tx.occurrences.nextScheduledAfter(_, occ.state.scheduledFor))
      val chatId = payloadChatId.orElse(channelChatId(tx, row, occ))
      ReminderFacts(
        ChatRef(
          row.vendor,
          chatId.getOrElse(
            throw new IllegalStateException(s"outbox row ${row.id}: no chat for occurrence $occurrenceId")
          )
        ),
        occ,
        snapshot,
        policy,
        nextOccurrence
      )

  private def channelChatId(tx: Tx, row: OutboxMessage, occ: StoredOccurrence): Option[String] =
    row.channelId.flatMap: channelId =>
      tx.channels.activePrimaryChannels(occ.accountId).find(_.channelId == channelId).flatMap(_.chatId)

  /** One message through the pure renderer's ladder (DESIGN.md section 4.3); the first send op is the deliverable — the
    * outbox protocol is one row, one message.
    */
  private def lower(
      row: OutboxMessage,
      chat: ChatRef,
      capabilities: CapabilityProfile,
      message: OutboundMessage
  ): (ChatRef, RenderedMessage) =
    val (ops, _) = Renderer.render(message, chat, capabilities, row.sendKey)
    val rendered = ops
      .collectFirst { case VendorOp.Send(_, sent, _, _) => sent }
      .getOrElse(throw new IllegalStateException(s"outbox row ${row.id}: renderer produced no send op"))
    (chat, rendered)

  private def token(action: String, subject: UUID, value: Long): String =
    val entry = ActionRegistry
      .byName(action)
      .getOrElse(throw new IllegalArgumentException(s"unknown action '$action'"))
    codec.encode(CallbackMode.Direct, entry, subject, value).wire

  private def doseText(snapshot: DoseSnapshot): Option[String] =
    snapshot.doseAmount.map: amount =>
      val number = amount.bigDecimal.stripTrailingZeros.toPlainString
      snapshot.doseUnit.map(unit => s"$number $unit").getOrElse(number)

  private def formatTime(occ: StoredOccurrence, at: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(occ.tz).format(at)

  extension (row: OutboxMessage)
    private def payloadAs[A <: LoopDispatch](kind: String)(using scala.reflect.ClassTag[A]): A =
      LoopDispatch.fromJson(row.payload) match
        case expected: A => expected
        case other       =>
          throw new IllegalArgumentException(
            s"outbox row ${row.id}: $kind kind with ${other.getClass.getSimpleName} payload"
          )
