package dosecord.core.chat

import dosecord.contracts.*
import dosecord.core.domain.copy.ReminderCopy
import dosecord.core.ports.*
import dosecord.core.scheduling.OutboxDispatcher
import dosecord.core.telemetry.LogContext
import ox.Ox

import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The core boundary rejects any event that reaches it without a mediator-stamped principal (R8, M0.12a acceptance).
  */
final class MissingPrincipal(event: InboundEvent)
    extends RuntimeException(s"inbound event ${event.eventId.uuid} reached the core without a stamped principal")

/** The minimal handler contract of DESIGN.md section 4.6 steps 6-7; the real flow handlers land in M0.12d
  * (`core/application`). Handlers only ever see stamped events and return one [[Reply]].
  */
trait ChatHandler:
  def handle(event: InboundEvent, principal: Principal): Reply

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

  private final case class DeliveryPlan(accountId: Option[UUID], sends: List[PlannedSend], toast: Option[String])

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
    stripes: Int = 16
)(using Ox)
    extends InboundSink:

  import ChatMediator.*

  private val executor = KeyedExecutor(stripes)

  // Step 1 (verify + ack) runs on the caller's thread, before any database work and outside the executor (ADR-013).
  override def push(event: InboundEvent): Boolean =
    callbackOf(event.body) match
      case Some(ref) if !verifiedCallback(ref) => rejectTampered(event)
      case _                                   =>
        ack(event)
        executor.submit(s"${event.vendor}:${event.chat.chatId}")(process(event))
    true

  private def callbackOf(body: Inbound): Option[CallbackRef] =
    body match
      case Inbound.InteractionSubmitted(callback, _, _) => Some(callback)
      case Inbound.FormSubmitted(_, callback, _)        => Some(callback)
      case _                                            => None

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
      if !interaction.acked
    do
      event.body match
        case Inbound.InteractionSubmitted(ref, _, _) =>
          ActionRegistry
            .byId(ref.actionId)
            .foreach: entry =>
              AckPolicy(AckPolicy.forComponent(entry, profile), interaction)
        case Inbound.FormSubmitted(_, ref, _) =>
          ActionRegistry
            .byId(ref.actionId)
            .foreach: entry =>
              AckPolicy(AckPolicy.forComponent(entry, profile), interaction)
        case Inbound.CommandInvoked(name, _, _) =>
          commands
            .get(name)
            .foreach: spec =>
              AckPolicy(AckPolicy.forCommand(spec, profile), interaction)
        case _ => ()

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

  private[chat] def process(event: InboundEvent): Unit =
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
            deliverFailureToast(event)
            Outcome.Failed
      outcome match
        case Outcome.Duplicate       => replayStoredReply(event)
        case Outcome.Processed(plan) => deliverAfterCommit(event, plan)
        case Outcome.Failed          => ()

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
      val stamped = event.copy(principal = Some(principal))
      val reply =
        resolve(stamped, tx) match
          case Resolution.Ignore         => Reply.empty
          case Resolution.PassThrough    => dispatchCore(stamped)
          case Resolution.Resolved(body) => dispatchCore(stamped.copy(body = body))
      reply.domainEvents.foreach(appendDomainEvent(_, stamped, principal, tx, correlationId, now))
      val sends = (reply.replace.toList ++ reply.followUps).map: message =>
        val outboxId = UUID.randomUUID()
        val enqueued = tx.outbox.enqueue(
          NewOutboxMessage(
            id = outboxId,
            sendKey = message.dedupeKey,
            op = OutboxOp.Send,
            kind = "interaction_reply",
            vendor = event.vendor,
            accountId = principal.accountId.map(_.uuid),
            payload = OutboundMessage.toJson(message),
            importance = "interaction_reply",
            nextAttemptAt = now.plus(SyncSafetyDelay)
          )
        )
        PlannedSend(message, Option.when(enqueued)(outboxId))
      tx.inboundEvents.complete(
        event.vendor,
        event.vendorEventId,
        principal.accountId.map(_.uuid),
        Reply.toJson(reply),
        now
      )
      Outcome.Processed(DeliveryPlan(principal.accountId.map(_.uuid), sends, reply.toast))

  /** The core boundary: only stamped events reach a handler (R8). */
  private[chat] def dispatchCore(event: InboundEvent): Reply =
    val principal = event.principal.getOrElse(throw MissingPrincipal(event))
    handler.handle(event, principal)

  private def appendDomainEvent(
      event: Event,
      inbound: InboundEvent,
      principal: Principal,
      tx: Tx,
      correlationId: String,
      now: Instant
  ): Unit =
    val actor = Actor(inbound.actor.vendor, inbound.actor.vendorUserId, inbound.actor.displayName, principal.accountId)
    val envelope =
      Event.toEnvelopeJson(event, actor, now, Some(correlationId), Some(inbound.eventId.uuid.toString))
    tx.domainEvents.append(
      NewDomainEvent(
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
    )

  // ---------- Resolution rules (DESIGN.md section 4.6 step 5) ----------

  private def resolve(event: InboundEvent, tx: Tx): Resolution =
    event.body match
      case Inbound.ReactionChanged(_, _, added) if !added => Resolution.Ignore
      case Inbound.ReactionChanged(emoji, target, _)      =>
        resolveFrom(tx.renderedMessages.choiceMapFor(target), _.emoji.contains(emoji), Some(target), Resolution.Ignore)
      case Inbound.MessageReceived(text, replyTo, _) =>
        val trimmed = text.trim
        if trimmed.nonEmpty && trimmed.length <= 3 && trimmed.forall(_.isDigit) then
          val index = trimmed.toInt
          replyTo match
            // Quoted-reply numbers resolve by the target's choice_map unconditionally.
            case Some(target) =>
              resolveFrom(
                tx.renderedMessages.choiceMapFor(target),
                _.index == index,
                Some(target),
                Resolution.PassThrough
              )
            // Bare digits resolve against the latest pending prompt only.
            case None =>
              val prompt = tx.renderedMessages.latestPendingPrompt(event.vendor, event.chat.chatId)
              resolveFrom(prompt, _.index == index, prompt.map(_.handle), Resolution.PassThrough)
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
      case Some(ref) => Resolution.Resolved(Inbound.InteractionSubmitted(ref, Nil, source))
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
    * 30-second safety row redelivers.
    */
  private def deliverAfterCommit(event: InboundEvent, plan: DeliveryPlan): Unit =
    val adapter = adapters(event.vendor)
    val liveInteraction = event.interaction.exists(interaction => !interaction.acked)
    plan.sends.foreach: planned =>
      val (ops, _) = Renderer.render(
        planned.message,
        event.chat,
        adapter.capabilities,
        planned.message.dedupeKey,
        RenderContext(liveInteraction = liveInteraction)
      )
      ops.foreach:
        case VendorOp.Send(chat, rendered, sendKey, _) =>
          try
            val handle = adapter.send(chat, rendered, sendKey)
            try
              uow.transaction: tx =>
                tx.renderedMessages.record(
                  handle,
                  plan.accountId,
                  kind = "interaction_reply",
                  subjectType = None,
                  subjectId = None,
                  epoch = None,
                  rendered.choiceMap,
                  clock.now()
                )
                planned.outboxId.foreach: id =>
                  tx.outbox.markSent(id, OutboxDispatcher.encodeHandle(handle), clock.now(), possibleDuplicate = false)
            catch case _: SQLException => () // the safety row redelivers
          catch case _: ChatError => () // the queued safety row redelivers
        case VendorOp.OpenForm(form) =>
          event.interaction.foreach: interaction =>
            if !interaction.acked then interaction.openForm(form)
        case _ => ()
    plan.toast.foreach: toast =>
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
  private def replayStoredReply(event: InboundEvent): Unit =
    val stored =
      try uow.transaction(_.inboundEvents.find(event.vendor, event.vendorEventId))
      catch case _: SQLException => None
    stored.flatMap(row => row.reply.map(reply => (row.accountId, reply))) match
      case Some((accountId, json)) =>
        val reply = Reply.fromJson(json)
        val sends = (reply.replace.toList ++ reply.followUps).map(PlannedSend(_, None))
        deliverAfterCommit(event, DeliveryPlan(accountId, sends, reply.toast))
      case None => () // still in flight elsewhere, or failed before the reply was stored: nothing to replay

  /** The catalogue failure toast when the database is unavailable (R68: visibly fails). */
  private def deliverFailureToast(event: InboundEvent): Unit =
    event.interaction match
      case Some(interaction) =>
        try
          if !interaction.acked then interaction.answer(Some(ReminderCopy.failureToast))
          else interaction.editSource(RenderedMessage(chunks = List(ReminderCopy.failureToast)))
        catch case _: ChatError => ()
      case None =>
        adapters
          .get(event.vendor)
          .foreach: adapter =>
            try
              adapter.send(
                event.chat,
                RenderedMessage(chunks = List(ReminderCopy.failureToast)),
                sendKey = s"failure:${event.vendor}:${event.vendorEventId}"
              )
              ()
            catch case _: ChatError => ()
