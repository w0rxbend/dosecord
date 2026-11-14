package dosegram.core.chat

import dosegram.contracts.*
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.*
import dosegram.core.scheduling.OutboxDispatcher
import dosegram.core.telemetry.LogContext
import ox.Ox
import ox.discard

import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import scala.util.control.NonFatal

/** The core boundary rejects any event that reaches it without a mediator-stamped principal (R8, M0.12a acceptance).
  */
final class MissingPrincipal(event: InboundEvent)
    extends RuntimeException(s"inbound event ${event.eventId.uuid} reached the core without a stamped principal")

/** The minimal handler contract of DESIGN.md section 4.6 steps 6-7; the real flow handlers land in M0.12d
  * (`core/application`). Handlers only ever see stamped events, run inside the per-event transaction and return one
  * [[Reply]].
  */
trait ChatHandler:
  def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply

object ChatMediator:
  /** The outbox safety-row delay for synchronously delivered replies (DESIGN.md section 4.6 step 8). */
  val SyncSafetyDelay: Duration = Duration.ofSeconds(30)

  /** Toast follow-ups degrade to a short auto-deleting message once the single-shot ack is spent (DESIGN.md section 4.6
    * step 8).
    */
  val ToastDeleteAfter: Duration = Duration.ofSeconds(30)

  private[chat] enum Resolution:
    case PassThrough
    case Ignore
    case Resolved(body: Inbound)

  private enum Outcome:
    case Duplicate, Failed
    case Processed(plan: DeliveryPlan)

  private final case class PlannedSend(message: OutboundMessage, outboxId: Option[UUID])

  private final case class DeliveryPlan(
      accountId: Option[UUID],
      sends: List[PlannedSend],
      toast: Option[String],
      domainEvents: List[NewDomainEvent]
  )

/** The ChatMediator (DESIGN.md section 4, ADR-005): adapters push intents through [[InboundSink]]; the mediator owns
  * MAC verification, the ack policy, identity stamping, per-chat ordering, the resolution rules, the per-event
  * transaction and the replay of stored replies. The core never sees a vendor except as an opaque string.
  */
final class ChatMediator(
    uow: UnitOfWork,
    adapters: Map[String, ChatAdapter],
    codec: CallbackCodec,
    handler: ChatHandler,
    clock: Clock,
    commands: Map[String, CommandSpec] = Map.empty,
    stripes: Int = 16,
    events: DomainEventBus = DomainEventBus.noop
)(using Ox)
    extends InboundSink:

  import ChatMediator.*

  private val executor = KeyedExecutor(stripes)

  // Step 1 (verify + ack) runs on the caller's thread, before any database work and outside the executor (ADR-013).
  override def push(event: InboundEvent): Boolean =
    if admitted(event) then executor.submit(chatKey(event))(process(event))
    true

  /** [[push]] for a durable inbox (ADR-017): the event takes its place in its chat's queue like any other, and the call
    * returns when it has been processed. False when its transaction failed or the handler threw.
    */
  override def pushAndWait(event: InboundEvent): Boolean =
    if !admitted(event) then true
    else
      val outcome = CompletableFuture[Boolean]()
      executor.submit(chatKey(event)):
        val committed =
          try processed(event)
          catch case NonFatal(_) => false
        outcome.complete(committed).discard
      // Interruptible, unlike join: a scope that ends while the event waits must be able to cancel the caller.
      outcome.get()

  /** Step 1: a tampered token is rejected here and goes no further; anything else is acknowledged and goes on. */
  private def admitted(event: InboundEvent): Boolean =
    callbackOf(event.body) match
      case Some(ref) if !verifiedCallback(ref) =>
        rejectTampered(event)
        false
      case _ =>
        ack(event)
        true

  private def chatKey(event: InboundEvent): String = s"${event.vendor}:${event.chat.chatId}"

  private def callbackOf(body: Inbound): Option[CallbackRef] =
    body match
      case Inbound.InteractionSubmitted(callback, _) => Some(callback)
      case Inbound.FormSubmitted(_, callback, _)     => Some(callback)
      case _                                         => None

  /** The mediator re-verifies the raw token against the claimed fields (ADR-006); the decoded form is never trusted.
    */
  private def verifiedCallback(ref: CallbackRef): Boolean =
    codec.decode(ref.raw) match
      case Right(payload) =>
        payload.action.id == ref.actionId && payload.subject == ref.subject &&
        payload.value == ref.value && (payload.mode == CallbackMode.Slot) == ref.slot
      case Left(_) => false

  private def ack(event: InboundEvent): Unit =
    for
      interaction <- event.interaction
      profile <- adapters.get(event.vendor).map(_.capabilities)
      if declared(event.body)
    do AckPolicy.ackBeforeWork(profile, interaction)

  /** Only a registered action or command is acknowledged before database work (ADR-013). */
  private def declared(body: Inbound): Boolean =
    body match
      case Inbound.CommandInvoked(name, _, _) => commands.contains(name)
      case other => callbackOf(other).exists(ref => ActionRegistry.byId(ref.actionId).isDefined)

  /** A tampered token: the catalogue toast plus an audit row, and nothing reaches the core (DESIGN.md section 4.4).
    */
  private def rejectTampered(event: InboundEvent): Unit =
    event.interaction.foreach: interaction =>
      try
        if !interaction.acked then interaction.answer(Some(ReminderCopy.staleControlToast))
        else interaction.editSource(RenderedMessage(chunks = List(ReminderCopy.staleControlToast)))
      catch case _: ChatError => ()
    try
      uow.transaction: tx =>
        tx.audit.append(
          AuditEntry(
            kind = "callback_tampered",
            outcome = "rejected",
            vendor = event.vendor,
            vendorEventId = Some(event.vendorEventId),
            chatId = Some(event.chat.chatId)
          )
        )
    catch case _: SQLException => () // the toast already fired; nothing more can be recorded

  // ---------- Steps 3-8, inside the keyed executor ----------

  private[chat] def process(event: InboundEvent): Unit = processed(event).discard

  /** Steps 3 to 8; false when the transaction failed. */
  private def processed(event: InboundEvent): Boolean =
    val correlationId = s"${event.vendor}:${event.vendorEventId}"
    LogContext.scoped(
      LogContext(
        correlationId = Some(correlationId),
        vendorEventId = Some(event.vendorEventId),
        vendor = Some(event.vendor),
        handler = Some(event.body.productPrefix)
      )
    ):
      val outcome =
        try uow.transaction(tx => processInTx(event, tx, correlationId))
        catch
          case _: SQLException =>
            // A failed commit acknowledgement is not proof of rollback. Read the durable
            // idempotency record before replay; never re-run the handler here.
            deliverFailureToast(event, ReminderCopy.checkingOutcome)
            replayStoredReply(event, reportUnknown = false)
            Outcome.Failed
      outcome match
        case Outcome.Duplicate       => replayStoredReply(event)
        case Outcome.Processed(plan) =>
          // ADR-003: domain events are consumed in-process, after the appending transaction commits.
          plan.domainEvents.foreach(events.publish)
          deliverAfterCommit(event, plan)
        case Outcome.Failed => ()
      outcome != Outcome.Failed

  /** The per-event transaction (ADR-003): inbound insert-or-replay, identity stamping, the handler, `domain_events`,
    * outbox rows and the stored reply commit together or not at all.
    */
  private def processInTx(event: InboundEvent, tx: Tx, correlationId: String): Outcome =
    val now = clock.now()
    val inserted = tx.inboundEvents.insert(
      NewInboundEvent(event.vendor, event.vendorEventId, event.eventId.uuid, event.receivedAt)
    )
    if !inserted then Outcome.Duplicate
    else
      val principal = tx.identities.resolve(event.actor, now)
      tx.identities.recordDirectChat(principal.identityId, event.chat.chatId)
      val stamped = event.copy(principal = Some(principal))
      val reply =
        resolve(stamped, tx) match
          case Resolution.Ignore         => Reply.empty
          case Resolution.PassThrough    => dispatchCore(stamped, tx)
          case Resolution.Resolved(body) => dispatchCore(stamped.copy(body = body), tx)
      // The handler may have linked the account inside this transaction (M0.12d account create): re-resolve so the
      // stored reply, the outbox rows and the domain events all carry the effective account id (C2).
      val effectivePrincipal = tx.identities.resolve(event.actor, now)
      val appendedEvents =
        reply.domainEvents.map(appendDomainEvent(_, stamped, effectivePrincipal, tx, correlationId, now))
      val sends = (reply.replace.toList ++ reply.followUps)
        .map(asDelivered)
        .map: message =>
          val outboxId = UUID.randomUUID()
          val enqueued = tx.outbox.enqueue(
            NewOutboxMessage(
              id = outboxId,
              sendKey = message.dedupeKey,
              op = if message.replaces.isDefined then OutboxOp.Edit else OutboxOp.Send,
              kind = "interaction_reply",
              vendor = event.vendor,
              accountId = effectivePrincipal.accountId.map(_.uuid),
              payload = InteractionReplyPayload.encode(message, event.chat),
              target = message.replaces.map(OutboxDispatcher.encodeHandle),
              importance = "interaction_reply",
              nextAttemptAt = now.plus(SyncSafetyDelay)
            )
          )
          PlannedSend(message, Option.when(enqueued)(outboxId))
      tx.inboundEvents.complete(
        event.vendor,
        event.vendorEventId,
        effectivePrincipal.accountId.map(_.uuid),
        Reply.toJson(reply),
        now
      )
      Outcome.Processed(
        DeliveryPlan(effectivePrincipal.accountId.map(_.uuid), sends, reply.toast, appendedEvents)
      )

  /** ADR-015: a message with an attachment is always a new message, so it never edits the message it replaces. */
  private def asDelivered(message: OutboundMessage): OutboundMessage =
    if message.attachment.isDefined then message.copy(replaces = None) else message

  /** The core boundary: only stamped events reach a handler (R8). */
  private[chat] def dispatchCore(event: InboundEvent, tx: Tx): Reply =
    val principal = event.principal.getOrElse(throw MissingPrincipal(event))
    handler.handle(event, principal, tx)

  private def appendDomainEvent(
      event: Event,
      inbound: InboundEvent,
      principal: Principal,
      tx: Tx,
      correlationId: String,
      now: Instant
  ): NewDomainEvent =
    val actor = Actor(inbound.actor.vendor, inbound.actor.vendorUserId, inbound.actor.displayName, principal.accountId)
    val envelope =
      Event.toEnvelopeJson(event, actor, now, Some(correlationId), Some(inbound.eventId.uuid.toString))
    val row = NewDomainEvent(
      id = envelope.id.uuid,
      eventType = Event.messageType(event),
      source = Event.sourceOf(event).value,
      subject = Some(actor.subject),
      accountId = principal.accountId.map(_.uuid),
      correlationId = Some(correlationId),
      causationId = Some(inbound.eventId.uuid.toString),
      actorJson = envelope.actorJson,
      occurredAt = now,
      dataJson = envelope.envelopeJson
    )
    tx.domainEvents.append(row)
    row

  // ---------- Resolution rules (DESIGN.md section 4.6 step 5) ----------

  private def resolve(event: InboundEvent, tx: Tx): Resolution =
    event.body match
      case Inbound.MessageReceived(text, replyTo, _) =>
        val trimmed = text.trim
        if trimmed.nonEmpty && trimmed.length <= 3 && trimmed.forall(_.isDigit) then
          val index = trimmed.toInt
          replyTo match
            // Quoted-reply numbers resolve by the target's choice_map unconditionally.
            case Some(target) =>
              resolveFrom(
                tx.renderedMessages.choiceMapFor(target),
                entry => entry.byNumber && entry.index == index,
                Some(target),
                Resolution.PassThrough
              )
            // Bare digits resolve against the latest pending prompt only.
            case None =>
              val prompt = tx.renderedMessages.latestPendingPrompt(event.vendor, event.chat.chatId)
              resolveFrom(
                prompt,
                entry => entry.byNumber && entry.index == index,
                prompt.map(_.handle),
                Resolution.PassThrough
              )
        else if replyTo.isEmpty then
          // Bare hotkeys (the choice label) resolve against the latest pending prompt only.
          val prompt = tx.renderedMessages.latestPendingPrompt(event.vendor, event.chat.chatId)
          resolveFrom(prompt, _.label.equalsIgnoreCase(trimmed), prompt.map(_.handle), Resolution.PassThrough)
        else Resolution.PassThrough
      case _ => Resolution.PassThrough

  private def resolveFrom(
      row: Option[RenderedChoiceMap],
      matches: ChoiceMapEntry => Boolean,
      source: Option[MessageHandle],
      fallback: Resolution
  ): Resolution =
    row.flatMap(_.choiceMap.find(matches)).flatMap(decodeEntry) match
      case Some(ref) => Resolution.Resolved(Inbound.InteractionSubmitted(ref, source))
      case None      => fallback

  private def decodeEntry(entry: ChoiceMapEntry): Option[CallbackRef] =
    codec
      .decode(entry.callback)
      .toOption
      .map(payload =>
        CallbackRef(
          payload.action.id,
          payload.subject,
          payload.value,
          payload.mode == CallbackMode.Slot,
          entry.callback
        )
      )

  // ---------- Delivery after commit ----------

  /** Synchronous delivery of the reply after commit (DESIGN.md section 4.6 step 8): every send records
    * `rendered_messages.choice_map` and marks its outbox row sent in a follow-up write; if that write is lost the
    * 30-second safety row retries the persisted op and destination (source edits remain edits).
    */
  private def deliverAfterCommit(event: InboundEvent, plan: DeliveryPlan): Unit =
    val adapter = adapters(event.vendor)
    plan.sends.foreach: planned =>
      val (ops, _) =
        Renderer.render(planned.message, event.chat, adapter.capabilities, planned.message.dedupeKey)
      ops.foreach:
        case VendorOp.Send(chat, rendered, sendKey, _) =>
          try
            val handle = deliverSend(adapter, planned.message.replaces, chat, rendered, sendKey)
            recordDelivery(plan.accountId, planned, handle, rendered)
          catch case _: ChatError => () // the queued safety row redelivers
        case _ => ()
    plan.toast.foreach(deliverToast(event, adapter, _))

  /** An edit of the source where the vendor edits at any age (a missing source is replaced by a new message), a plain
    * send otherwise. Only the first chunk fits the edit; later chunks are new messages and the last carries the
    * controls.
    */
  private def deliverSend(
      adapter: ChatAdapter,
      replaces: Option[MessageHandle],
      chat: ChatRef,
      rendered: RenderedMessage,
      sendKey: String
  ): MessageHandle =
    replaces.filter(_ => adapter.capabilities.editOwn == EditCapability.AnyAge) match
      case None         => adapter.send(chat, rendered, sendKey)
      case Some(source) =>
        val single = rendered.chunks.size == 1
        val first = rendered.copy(
          chunks = List(rendered.chunks.head),
          controls = if single then rendered.controls else Nil,
          choiceMap = if single then rendered.choiceMap else Nil
        )
        val edited =
          try adapter.edit(source, first)
          catch
            case e: ChatError.Permanent if !e.channelFatal && e.reason == ChatError.PermanentReason.MissingTarget =>
              adapter.send(ChatRef(source.vendor, source.chatId), first, s"$sendKey:replacement")
        rendered.chunks.tail.zipWithIndex.foldLeft(edited): (_, part) =>
          val (chunk, index) = part
          val last = index == rendered.chunks.size - 2
          val page = rendered.copy(
            chunks = List(chunk),
            controls = if last then rendered.controls else Nil,
            choiceMap = if last then rendered.choiceMap else Nil
          )
          adapter.send(chat, page, s"$sendKey:chunk:${index + 1}")

  private def recordDelivery(
      accountId: Option[UUID],
      planned: PlannedSend,
      handle: MessageHandle,
      rendered: RenderedMessage
  ): Unit =
    try
      uow.transaction: tx =>
        tx.renderedMessages.record(
          handle,
          accountId,
          kind = "interaction_reply",
          subjectType = None,
          subjectId = None,
          epoch = None,
          rendered.choiceMap,
          clock.now()
        )
        if planned.message.replaces.isDefined then
          tx.renderedMessages.recordEdit(handle, rendered.choiceMap, clock.now())
        planned.outboxId.foreach: id =>
          tx.outbox.markSent(id, OutboxDispatcher.encodeHandle(handle), clock.now(), possibleDuplicate = false)
    catch case _: SQLException => () // the safety row redelivers

  /** The toast rides the single-shot answer while it is unspent, and degrades to a short auto-deleting message once the
    * ack before database work has spent it (DESIGN.md section 4.6 step 8).
    */
  private def deliverToast(event: InboundEvent, adapter: ChatAdapter, toast: String): Unit =
    event.interaction match
      case Some(interaction) if !interaction.acked => interaction.answer(Some(toast))
      case _                                       =>
        try
          adapter.send(
            event.chat,
            RenderedMessage(chunks = List(toast), deleteAfter = Some(ToastDeleteAfter)),
            sendKey = s"toast:${event.vendor}:${event.vendorEventId}"
          )
          ()
        catch case _: ChatError => ()

  /** A duplicate delivery replays the stored reply (C3); the handler is not re-run. */
  private def replayStoredReply(event: InboundEvent, reportUnknown: Boolean = true): Unit =
    val plan =
      try storedDeliveryPlan(event)
      catch
        case _: SQLException =>
          if reportUnknown then deliverFailureToast(event, ReminderCopy.checkingOutcome)
          None
    // Replay never re-publishes domain events or re-runs a handler. A failed read is not
    // an absent handle: fail closed rather than recreating a missing-source replacement.
    plan.foreach(deliverAfterCommit(event, _))

  private def storedDeliveryPlan(event: InboundEvent): Option[DeliveryPlan] =
    uow.transaction: tx =>
      tx.inboundEvents
        .find(event.vendor, event.vendorEventId)
        .flatMap: row =>
          row.reply.map: json =>
            val reply = Reply.fromJson(json)
            val sends = (reply.replace.toList ++ reply.followUps)
              .map(asDelivered)
              .map: message =>
                val delivered = tx.outbox.recordedHandle(event.vendor, message.dedupeKey)
                val replay = if message.replaces.isDefined then
                  message.copy(replaces =
                    delivered.map(OutboxDispatcher.decodeHandle(event.vendor, _)).orElse(message.replaces)
                  )
                else message
                PlannedSend(replay, tx.outbox.replyId(event.vendor, message.dedupeKey))
            DeliveryPlan(row.accountId, sends, reply.toast, Nil)

  /** The catalogue failure notice when the database is unavailable (R68: visibly fails): the single-shot answer while
    * it is unspent, a silent message otherwise.
    */
  private def deliverFailureToast(event: InboundEvent, text: String): Unit =
    event.interaction.filterNot(_.acked) match
      case Some(interaction) =>
        try interaction.answer(Some(text))
        catch case _: ChatError => ()
      case None =>
        adapters
          .get(event.vendor)
          .foreach: adapter =>
            try
              adapter.send(
                event.chat,
                RenderedMessage(chunks = List(text), silent = true),
                sendKey = s"failure:${event.vendor}:${event.vendorEventId}"
              )
              ()
            catch case _: ChatError => ()
