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
import dosecord.core.domain.copy.DigestCopy
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
  * (ADR-009); the chat of a reminder row resolves through the row's delivery channel first (chat ids are vendor-scoped,
  * so a dispatcher-enqueued fallback row renders against its own channel), with the payload's chat id as the fallback
  * for rows without a channel.
  */
final class CatalogueOutboxRenderer(uow: UnitOfWork, codec: CallbackCodec, clock: Clock) extends OutboxRenderer:

  override def render(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
    row.kind match
      case "reminder"          => renderReminder(row, capabilities)
      case "missed_notice"     => renderMissedNotice(row, capabilities)
      case "reminder_finalize" => renderFinalize(row, capabilities)
      case "digest"            => renderDigest(row, capabilities)
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

  // ---------- catch-up digest (M1.8) ----------

  /** The catch-up digest (DESIGN.md section 7.5): the payload's items are re-read immediately before rendering — an
    * item whose occurrence resolved or whose epoch moved past the item's is dropped, and when nothing remains the send
    * is skipped ([[DigestEmpty]]). Live items render one catalogue line each with resolution controls; at most
    * [[DigestCopy.DigestMaxItems]] items carry controls (the renderer's native-component budget, "8 doses x 3
    * buttons"), the rest fold into one closing line.
    */
  private def renderDigest(row: OutboxMessage, capabilities: CapabilityProfile): (ChatRef, RenderedMessage) =
    val dispatch = row.payloadAs[LoopDispatch.Digest]("digest")
    val facts = digestFacts(row, dispatch)
    val shown = facts.items.take(DigestCopy.DigestMaxItems)
    val header = Node.Paragraph(List(Inline.Text(DigestCopy.header(facts.items.size))))
    val itemLines = shown.map(item => Node.Paragraph(List(Inline.Text(item.line))))
    val overflow =
      if facts.items.size > shown.size then
        List(Node.Paragraph(List(Inline.Text(DigestCopy.more(facts.items.size - shown.size)))))
      else Nil
    val blocks = shown.map: item =>
      Block.Choices(ChoiceSet(id = s"digest.item.${item.occurrenceId}", choices = item.choices))
    val message = OutboundMessage(
      body = header :: itemLines ++ overflow,
      blocks = blocks,
      importance = Importance.Reminder,
      dedupeKey = row.sendKey,
      correlationId = row.sendKey
    )
    lower(row, facts.chat, capabilities, message)

  private final case class DigestItemFacts(occurrenceId: UUID, line: String, choices: List[Choice])

  private final case class DigestFacts(chat: ChatRef, items: List[DigestItemFacts])

  /** The re-read of DESIGN.md section 7.5, in one short transaction completed before any vendor call: keep only items
    * whose occurrence still exists, still carries the item's epoch, and is not resolved (`taken`/`skipped`/`cancelled`
    * needs no digest line).
    */
  private def digestFacts(row: OutboxMessage, dispatch: LoopDispatch.Digest): DigestFacts =
    uow.transaction: tx =>
      val chatId = channelChatId(tx, row).getOrElse(
        throw new IllegalStateException(s"outbox row ${row.id}: no channel chat for digest ${row.sendKey}")
      )
      val seen = scala.collection.mutable.HashSet[UUID]()
      val items = dispatch.items.flatMap: item =>
        if !seen.add(item.occurrenceId) then None // a merged bucket can carry an occurrence twice; render it once
        else
          tx.occurrences.get(item.occurrenceId) match
            case None      => None
            case Some(occ) =>
              val live = occ.state.epoch == item.epoch &&
                (occ.state.status.isOpen || occ.state.status == OccurrenceStatus.Unknown ||
                  occ.state.status == OccurrenceStatus.Missed)
              if !live then None else Some(digestItem(occ))
      if items.isEmpty then throw DigestEmpty(row.sendKey)
      DigestFacts(ChatRef(row.vendor, chatId), items)

  /** One digest line and its resolution controls (M1.8): an open dose offers [Taken][Skip], an `unknown` one [I took
    * it][Skip] (the user's word resolves it as real evidence, ADR-012), a `missed` one the missed-notice row [I took
    * it][Skip][Keep missed].
    */
  private def digestItem(occ: StoredOccurrence): DigestItemFacts =
    val snapshot = occ.doseSnapshot.getOrElse(
      throw new IllegalStateException(s"occurrence ${occ.id} carries no dose snapshot")
    )
    val line = DigestCopy.item(snapshot.medicationName, doseText(snapshot), formatTime(occ, occ.state.scheduledFor))
    val choices = occ.state.status match
      case OccurrenceStatus.Unknown =>
        List(
          Choice(Labels.ITookIt, token("dose.taken", occ.id, 0), ChoiceStyle.Success),
          Choice(Labels.Skip, token("dose.skip", occ.id, 0))
        )
      case OccurrenceStatus.Missed =>
        List(
          Choice(Labels.ITookIt, token("dose.taken", occ.id, 0), ChoiceStyle.Success),
          Choice(Labels.Skip, token("dose.skip", occ.id, 0)),
          Choice(Labels.KeepMissed, token("dose.keep_missed", occ.id, 0))
        )
      case _ =>
        List(
          Choice(Labels.Taken, token("dose.taken", occ.id, 0), ChoiceStyle.Success),
          Choice(Labels.Skip, token("dose.skip", occ.id, 0))
        )
    DigestItemFacts(occ.id, line, choices)

  // ---------- finalize ----------

  /** The finalize edit carries the outcome copy of the occurrence's current status (DESIGN.md section 7.3): a resolved
    * row shows its confirmation, a superseded one keeps the reminder text with the controls dropped. A resolved Taken
    * keeps the post-Taken follow-up [Undo][Correct] (M1.4a recorded decision, M1.10): the correction and the undo
    * within the 15-minute window stay one tap away on the recorded handle.
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
        // The effective time is what the user recorded (for a correction it differs from taken_at).
        List(
          ReminderCopy.takenConfirmation(
            formatTime(occ, occ.state.effectiveAt.orElse(occ.state.takenAt).getOrElse(occ.state.scheduledFor))
          )
        )
      case OccurrenceStatus.Skipped => List(ReminderCopy.skippedConfirmation)
      case OccurrenceStatus.Missed  =>
        List(ReminderCopy.missedNotice(snapshot.medicationName, formatTime(occ, occ.state.scheduledFor)))
      case _ =>
        ReminderCopy.reminderBody(snapshot.medicationName, doseText(snapshot), snapshot.instructions)
    val keep =
      if occ.state.status == OccurrenceStatus.Taken && dispatch.reason == "resolved" then
        List(
          Block.Choices(
            ChoiceSet(
              id = "post_taken.followup",
              choices = List(
                Choice(Labels.Undo, token("dose.undo", occ.id, 0)),
                Choice(Labels.Correct, token("dose.correct", occ.id, 0))
              )
            )
          )
        )
      else Nil
    val message = OutboundMessage(
      body = summary.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks = keep,
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
      // The row's channel is authoritative: chat ids are vendor-scoped, so a fallback row (enqueued by the
      // dispatcher with the original payload) must resolve through its own channel, not the payload's chat id.
      val chatId = channelChatId(tx, row).orElse(payloadChatId)
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

  private def channelChatId(tx: Tx, row: OutboxMessage): Option[String] =
    row.channelId.flatMap(channelId => tx.channels.byId(channelId).flatMap(_.chatId))

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
