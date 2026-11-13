package dosegram.core.chat

import dosegram.contracts.*

import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable.ListBuffer

/** An in-memory ChatAdapter parameterised by a static CapabilityProfile (ADR-005, suite B1/B2 harness). Records every
  * call, mints deterministic handles, supports one-shot failure injection, and refuses ops its profile does not claim —
  * capability honesty is part of the conformance contract.
  *
  * The mediator calls an adapter from its chat stripes in parallel, so every read and write of the state holds one
  * lock. Nothing inside the lock blocks.
  */
final class FakeAdapter(val capabilities: CapabilityProfile, val vendor: String = "fake") extends ChatAdapter:

  private val lock = ReentrantLock()
  private var messageSeq = 0
  private var pendingFailure = Option.empty[ChatError]
  private val recorded = ListBuffer.empty[VendorOp]
  private val messages = scala.collection.mutable.LinkedHashMap.empty[String, (MessageHandle, RenderedMessage)]
  private var running = false
  private var resumeCursor = Option.empty[String]

  private def guarded[A](body: => A): A =
    lock.lock()
    try body
    finally lock.unlock()

  def ops: List[VendorOp] = guarded(recorded.toList)
  def sent: List[(MessageHandle, RenderedMessage)] = guarded(messages.values.toList)
  def isRunning: Boolean = guarded(running)
  def resumeFrom: Option[String] = guarded(resumeCursor)

  /** The next adapter call throws this error (crash/failure injection). */
  def failNext(error: ChatError): Unit = guarded { pendingFailure = Some(error) }

  /** Takes the injected failure, if any, so exactly one call throws it. Call with the lock held. */
  private def maybeFail(): Unit =
    pendingFailure.foreach { e =>
      pendingFailure = None
      throw e
    }

  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = guarded:
    resumeCursor = resumeFrom
    running = true

  override def stop(): Unit = guarded { running = false }

  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle = guarded:
    maybeFail()
    if rendered.attachment.isDefined && !capabilities.media then throw ChatError.Unsupported("media")
    messageSeq += 1
    val handle = MessageHandle(vendor, chat.chatId, s"m$messageSeq")
    recorded += VendorOp.Send(chat, rendered, sendKey, None)
    messages += (s"${handle.chatId}:${handle.messageId}" -> (handle, rendered))
    handle

  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle = guarded:
    maybeFail()
    if capabilities.editOwn == EditCapability.NoEdit then throw ChatError.Unsupported("edit")
    // ADR-015: a message with an attachment is always a new message, so an edit never carries one.
    if rendered.attachment.isDefined then throw ChatError.Unsupported("editMedia")
    val revised = handle.copy(revision = handle.revision + 1)
    recorded += VendorOp.Edit(revised, rendered)
    messages += (s"${handle.chatId}:${handle.messageId}" -> (revised, rendered))
    revised

  override def delete(handle: MessageHandle): Unit = guarded:
    maybeFail()
    if capabilities.deleteOwn == DeleteCapability.NoDelete then throw ChatError.Unsupported("delete")
    recorded += VendorOp.Delete(handle)
    messages -= s"${handle.chatId}:${handle.messageId}"

  override def registerCommands(specs: List[CommandSpec]): Unit = guarded:
    maybeFail()
    recorded += VendorOp.RegisterCommands(specs)

  override def resolveChat(identity: PlatformIdentity): ChatRef =
    ChatRef(vendor, s"dm:${identity.vendorUserId}")

  override def renderText(text: RichText): List[String] =
    VendorMarkup.split(VendorMarkup.render(text, capabilities.markup), capabilities.maxText)
end FakeAdapter
