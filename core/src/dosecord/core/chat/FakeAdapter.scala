package dosecord.core.chat

import dosecord.contracts.*

import scala.collection.mutable.ListBuffer

/** An in-memory ChatAdapter parameterised by a static CapabilityProfile (ADR-005, suite B1/B2 harness). Records every
  * call, mints deterministic handles, supports one-shot failure injection, and refuses ops its profile does not claim —
  * capability honesty is part of the conformance contract.
  */
final class FakeAdapter(val capabilities: CapabilityProfile, val vendor: String = "fake") extends ChatAdapter:

  private var messageSeq = 0
  private var pendingFailure = Option.empty[ChatError]
  private val recorded = ListBuffer.empty[VendorOp]
  private val messages = scala.collection.mutable.LinkedHashMap.empty[String, (MessageHandle, RenderedMessage)]
  private var running = false
  private var resumeCursor = Option.empty[String]

  def ops: List[VendorOp] = recorded.toList
  def sent: List[(MessageHandle, RenderedMessage)] = messages.values.toList
  def isRunning: Boolean = running
  def resumeFrom: Option[String] = resumeCursor

  /** The next adapter call throws this error (crash/failure injection). */
  def failNext(error: ChatError): Unit = pendingFailure = Some(error)

  private def maybeFail(): Unit =
    pendingFailure.foreach { e =>
      pendingFailure = None
      throw e
    }

  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit =
    resumeCursor = resumeFrom
    running = true

  override def stop(): Unit =
    running = false

  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    maybeFail()
    messageSeq += 1
    val handle = MessageHandle(vendor, chat.chatId, s"m$messageSeq")
    recorded += VendorOp.Send(chat, rendered, sendKey, None)
    messages += (s"${handle.chatId}:${handle.messageId}" -> (handle, rendered))
    handle

  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle =
    maybeFail()
    if capabilities.editOwn == EditCapability.NoEdit then throw ChatError.Unsupported("edit")
    val revised = handle.copy(revision = handle.revision + 1)
    recorded += VendorOp.Edit(revised, rendered)
    messages += (s"${handle.chatId}:${handle.messageId}" -> (revised, rendered))
    revised

  override def delete(handle: MessageHandle): Unit =
    maybeFail()
    if capabilities.deleteOwn == DeleteCapability.NoDelete then throw ChatError.Unsupported("delete")
    recorded += VendorOp.Delete(handle)
    messages -= s"${handle.chatId}:${handle.messageId}"

  override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit =
    maybeFail()
    if !capabilities.botReactions then throw ChatError.Unsupported("react")
    recorded += VendorOp.React(handle, emoji, on, txnKey)

  override def registerCommands(specs: List[CommandSpec]): Unit =
    maybeFail()
    recorded += VendorOp.RegisterCommands(specs)

  override def resolveChat(identity: PlatformIdentity): ChatRef =
    ChatRef(vendor, s"dm:${identity.vendorUserId}")

  override def renderText(text: RichText): List[String] =
    VendorMarkup.split(VendorMarkup.render(text, capabilities.markup), capabilities.maxText)
end FakeAdapter
