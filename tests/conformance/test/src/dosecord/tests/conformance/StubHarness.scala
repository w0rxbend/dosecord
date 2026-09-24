package dosecord.tests.conformance

import dosecord.contracts.*
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.VendorMarkup

import java.time.Duration
import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ListBuffer

/** A generic in-memory fake wire for adapters without a gateway (FakeAdapter and test doubles): the stub server pushes
  * wire events straight to the sink with a stable per-wireId vendor_event_id, and the fault-injecting wrapper plays the
  * honest vendor-fault mappings of DESIGN.md section 4.5 at the adapter boundary (NotModified and DuplicateReaction are
  * success; DeleteWindow and EditNotFound are non-fatal Permanent; Blocked is Unreachable; RateLimited carries the
  * Retry-After).
  */
final class StubVendorServer(val vendor: String, sink: RecordingSink) extends FakeVendorServer:
  private val deliveriesBuf = ListBuffer.empty[(WireEvent, InboundEvent)]
  private val sentBuf = ListBuffer.empty[SentObservation]
  private val acksBuf = ListBuffer.empty[AckObservation]
  private val modalBuf = ListBuffer.empty[String]
  private var armed = Option.empty[VendorFault]
  private var seq = 0
  private var at = Instant.parse("2026-09-21T00:00:00Z")

  override def deliver(event: WireEvent): Unit =
    seq += 1
    val body = event match
      case WireEvent.Message(_, chat, text, replyTo) =>
        Inbound.MessageReceived(text, replyTo.map(id => MessageHandle(vendor, chat, id)), truncated = false)
      case WireEvent.Command(_, _, name, text) => Inbound.CommandInvoked(name, Map.empty, text)
      case WireEvent.Callback(_, chat, callback, source) =>
        Inbound.InteractionSubmitted(
          CallbackRef(20, UUID(0L, 0L), 0, slot = false, callback),
          Nil,
          Some(MessageHandle(vendor, chat, source))
        )
      case WireEvent.Select(_, chat, callback, source) =>
        Inbound.InteractionSubmitted(
          CallbackRef(20, UUID(0L, 0L), 0, slot = false, callback),
          List(callback),
          Some(MessageHandle(vendor, chat, source))
        )
      case WireEvent.ModalSubmit(_, _, callback, fields, _) =>
        val (formId, token) = callback.span(_ != ':') match
          case (id, rest) if rest.nonEmpty => (id, rest.tail)
          case _                           => (callback, callback)
        Inbound.FormSubmitted(
          formId,
          CallbackRef(20, UUID(0L, 0L), 0, slot = false, token),
          fields
        )
    val inbound = InboundEvent(
      eventId = EventId(UUID.randomUUID()),
      vendor = vendor,
      vendorEventId = s"$vendor:${event.wireKey}",
      receivedAt = at,
      createdAt = Some(at),
      actor = PlatformIdentity(vendor, "owner"),
      chat = ChatRef(vendor, event.chatKey),
      cursor = Some(seq.toString),
      body = body
    )
    val delivered = event match
      case WireEvent.Callback(_, _, _, _)       => inbound.copy(interaction = Some(StubInteractionHandle(this)))
      case WireEvent.Select(_, _, _, _)         => inbound.copy(interaction = Some(StubInteractionHandle(this)))
      case WireEvent.ModalSubmit(_, _, _, _, _) => inbound.copy(interaction = Some(StubInteractionHandle(this)))
      case _                                    => inbound
    sink.push(delivered)
    deliveriesBuf += ((event, delivered))

  override def deliveries: List[(WireEvent, InboundEvent)] = deliveriesBuf.toList
  override def sent: List[SentObservation] = sentBuf.toList
  override def acks: List[AckObservation] = acksBuf.toList
  override def modalPayloads: List[String] = modalBuf.toList
  override def failNext(fault: VendorFault): Unit = armed = Some(fault)
  override def clock: Instant = at
  override def advanceClock(by: Duration): Unit = at = at.plus(by)
  override def logs: List[String] = Nil
  override def onStart(resumeFrom: Option[String]): Unit = seq = resumeFrom.flatMap(_.toIntOption).getOrElse(0)

  private[conformance] def recordSent(handle: MessageHandle, chunks: List[String]): Unit =
    sentBuf += SentObservation(Some(handle.messageId), chunks.mkString("\n"), chunks.mkString("\n"))

  private[conformance] def recordAck(kind: String): Unit = acksBuf += AckObservation(kind, at)

  private[conformance] def recordModal(form: RenderedForm): Unit =
    modalBuf += upickle.default.write(form)

  private[conformance] def takeFault(): Option[VendorFault] =
    val fault = armed
    armed = None
    fault

/** The interaction the stub wire hands back for callback deliveries. */
final class StubInteractionHandle(server: StubVendorServer) extends InteractionHandle:
  override def deferUpdate(): Unit = server.recordAck("deferUpdate")
  override def deferReply(ephemeral: Boolean): Unit = server.recordAck(s"deferReply($ephemeral)")
  override def answer(toast: Option[String]): Unit = server.recordAck(s"answer(${toast.getOrElse("")})")
  override def respond(rendered: RenderedMessage): MessageHandle =
    MessageHandle("stub", "dm:owner", "r1")
  override def editSource(rendered: RenderedMessage): Unit = ()
  override def openForm(form: RenderedForm): Unit = server.recordModal(form)
  override def acked: Boolean = false

/** Records vendor ops and raises armed faults around a delegate adapter. */
final class FaultInjectingAdapter(delegate: ChatAdapter, server: StubVendorServer) extends ChatAdapter:
  override def vendor: String = delegate.vendor
  override def capabilities: CapabilityProfile = delegate.capabilities
  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = delegate.start(sink, resumeFrom)
  override def stop(): Unit = delegate.stop()

  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    server.takeFault() match
      case Some(VendorFault.RateLimited(seconds)) => throw ChatError.RateLimited(Duration.ofSeconds(seconds))
      case Some(VendorFault.Blocked)            => throw ChatError.Unreachable("bot was blocked")
      case Some(other)                          => throw IllegalStateException(s"fault $other on send")
      case None                                 => ()
    val handle = delegate.send(chat, rendered, sendKey)
    server.recordSent(handle, rendered.chunks)
    handle

  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle =
    server.takeFault() match
      // "message is not modified" is success (DESIGN.md section 4.5).
      case Some(VendorFault.NotModified) => handle
      case Some(VendorFault.EditNotFound) =>
        throw ChatError.Permanent("message to edit not found", channelFatal = false)
      case Some(VendorFault.RateLimited(seconds)) => throw ChatError.RateLimited(Duration.ofSeconds(seconds))
      case Some(other)                            => throw IllegalStateException(s"fault $other on edit")
      case None                                   => ()
    val revised = delegate.edit(handle, rendered)
    server.recordSent(revised, rendered.chunks)
    revised

  override def delete(handle: MessageHandle): Unit =
    server.takeFault() match
      case Some(VendorFault.DeleteWindow) =>
        throw ChatError.Permanent("message can't be deleted", channelFatal = false)
      case Some(VendorFault.RateLimited(seconds)) => throw ChatError.RateLimited(Duration.ofSeconds(seconds))
      case Some(other)                            => throw IllegalStateException(s"fault $other on delete")
      case None                                   => ()
    delegate.delete(handle)

  override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit =
    server.takeFault() match
      // REACTION_ALREADY_EXISTS / M_DUPLICATE_ANNOTATION are success (DESIGN.md section 4.5).
      case Some(VendorFault.DuplicateReaction) => ()
      case Some(VendorFault.RateLimited(seconds)) =>
        throw ChatError.RateLimited(Duration.ofSeconds(seconds))
      case Some(other) => throw IllegalStateException(s"fault $other on react")
      case None        => ()
    delegate.react(handle, emoji, on, txnKey)

  override def registerCommands(specs: List[CommandSpec]): Unit = delegate.registerCommands(specs)
  override def resolveChat(identity: PlatformIdentity): ChatRef = delegate.resolveChat(identity)
  override def renderText(text: RichText): List[String] = delegate.renderText(text)

/** Suite A wiring over any gateway-less adapter. */
final class StubWiring(
    val vendor: String,
    val profile: CapabilityProfile,
    val transportTraits: Set[String],
    newAdapter: () => ChatAdapter
) extends AdapterWiring:
  override val defaultChat: ChatRef = ChatRef(vendor, "dm:owner")
  override def start(scenario: String): AdapterUnderTest =
    val sink = RecordingSink()
    val server = StubVendorServer(vendor, sink)
    AdapterUnderTest(FaultInjectingAdapter(newAdapter(), server), server, sink)

/** A deliberately dishonest adapter: it claims `buttons` and `select` but rejects sends carrying them — capability
  * honesty must fail it (ROADMAP M0.13 acceptance).
  */
class LyingButtonsAdapter extends ChatAdapter:
  override val vendor: String = "liar"
  override val capabilities: CapabilityProfile = CapabilityProfiles.Console.copy(buttons = true, select = true)
  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = ()
  override def stop(): Unit = ()
  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    rendered.controls.foreach {
      case RenderedControls.Buttons(_)   => throw ChatError.Unsupported("buttons")
      case RenderedControls.SelectMenu(_, _, _, _) => throw ChatError.Unsupported("select")
      case _ => ()
    }
    MessageHandle(vendor, chat.chatId, "m1")
  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle =
    throw ChatError.Unsupported("edit")
  override def delete(handle: MessageHandle): Unit = throw ChatError.Unsupported("delete")
  override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit =
    throw ChatError.Unsupported("react")
  override def registerCommands(specs: List[CommandSpec]): Unit = ()
  override def resolveChat(identity: PlatformIdentity): ChatRef = ChatRef(vendor, s"dm:${identity.vendorUserId}")
  override def renderText(text: RichText): List[String] =
    VendorMarkup.split(VendorMarkup.render(text, capabilities.markup), capabilities.maxText)

/** The honest twin of [[LyingButtonsAdapter]]: same claims, delivers everything. */
final class HonestButtonsAdapter extends LyingButtonsAdapter:
  override val vendor: String = "honest"
  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    MessageHandle(vendor, chat.chatId, "m1")

/** Serialises every adapter call on one monitor and records delivered texts, so a polling await on another thread has a
  * happens-before edge with the mediator's delivery threads (a bare LinkedHashMap read can spin on stale data).
  */
final class SynchronizedRecording(delegate: ChatAdapter) extends ChatAdapter:
  private val delivered = ListBuffer.empty[String]
  override def vendor: String = delegate.vendor
  override def capabilities: CapabilityProfile = delegate.capabilities
  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit =
    this.synchronized(delegate.start(sink, resumeFrom))
  override def stop(): Unit = this.synchronized(delegate.stop())
  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    this.synchronized:
      val handle = delegate.send(chat, rendered, sendKey)
      delivered += rendered.chunks.mkString("\n")
      handle
  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle =
    this.synchronized(delegate.edit(handle, rendered))
  override def delete(handle: MessageHandle): Unit = this.synchronized(delegate.delete(handle))
  override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit =
    this.synchronized(delegate.react(handle, emoji, on, txnKey))
  override def registerCommands(specs: List[CommandSpec]): Unit = this.synchronized(delegate.registerCommands(specs))
  override def resolveChat(identity: PlatformIdentity): ChatRef = delegate.resolveChat(identity)
  override def renderText(text: RichText): List[String] = delegate.renderText(text)
  def deliveredTexts: List[String] = this.synchronized(delivered.toList)
