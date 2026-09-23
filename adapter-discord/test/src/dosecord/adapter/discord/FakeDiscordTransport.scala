package dosecord.adapter.discord

import dosecord.tests.conformance.*

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ListBuffer

/** The fake Discord wire (suite A, ROADMAP M0.13): an in-memory [[DiscordTransport]] standing in for the JDA gateway,
  * so the whole adapter — event mapping, watchdog, registration, error mapping — runs without live Discord. Records
  * sends, acks, interaction replies, modal payloads, autocomplete replies and command registrations; raises
  * [[DiscordApiException]] for the vendor faults; owns no clock (the server does).
  */
final class FakeDiscordTransport(server: DiscordFakeVendorServer) extends DiscordTransport:

  private val connectLatch = new CountDownLatch(1)
  @volatile private var handler: DiscordEvent => Unit = _ => ()

  val sent = ListBuffer.empty[SentObservation]
  val edits = ListBuffer.empty[SentObservation]
  val acks = ListBuffer.empty[AckObservation]
  val modalPayloads = ListBuffer.empty[String]
  val autocompleteReplies = ListBuffer.empty[List[String]]
  val interactionReplies = ListBuffer.empty[(String, DiscordMessage, Boolean)]
  val registered = ListBuffer.empty[List[DiscordCommand]]
  val openChannelCalls = ListBuffer.empty[String]

  private val messageSeq = AtomicLong(0)
  @volatile private var fault = Option.empty[VendorFault]

  override def connect(handler: DiscordEvent => Unit): Unit =
    this.handler = handler

  override def awaitDisconnect(): Unit = connectLatch.await() // mirrors the real gateway's lifecycle

  override def disconnect(): Unit = connectLatch.countDown()

  override def selfUser: DiscordUser = DiscordUser("bot-1", "dosecord")

  /** Delivers one normalized event as the gateway would (the adapter forks it). */
  def deliver(event: DiscordEvent): Unit = handler(event)

  override def sendMessage(channelId: String, message: DiscordMessage, nonce: Option[String]): String =
    maybeFail("send")
    val id = nextMessageId()
    sent += SentObservation(
      Some(id),
      message.text,
      s"channel=$channelId components=${message.components.size} silent=${message.silent} nonce=${nonce.getOrElse("")}"
    )
    id

  override def editMessage(channelId: String, messageId: String, message: DiscordMessage): Unit =
    maybeFail("edit")
    edits += SentObservation(Some(messageId), message.text, s"edit channel=$channelId")

  override def deleteMessage(channelId: String, messageId: String): Unit =
    maybeFail("delete")

  override def addReaction(channelId: String, messageId: String, emoji: String): Unit =
    maybeFail("react")

  override def removeReaction(channelId: String, messageId: String, emoji: String): Unit =
    maybeFail("react")

  override def registerCommands(commands: List[DiscordCommand]): Unit =
    registered += commands

  override def openPrivateChannel(userId: String): String =
    openChannelCalls += userId
    s"dm-$userId"

  def nextMessageId(): String = s"m${messageSeq.incrementAndGet()}"

  def failNext(fault: VendorFault): Unit = this.fault = Some(fault)

  /** Adapter-test escape hatch: raise a raw Discord API code on the next matching op (the shared fault vocabulary has
    * no 5xx case).
    */
  def failNextRaw(code: Int, retryAfterSeconds: Option[Long], meaning: String, op: String): Unit =
    rawFault = Some((code, retryAfterSeconds, meaning, op))

  @volatile private var rawFault = Option.empty[(Int, Option[Long], String, String)]

  private def maybeFail(op: String): Unit =
    rawFault.foreach: (code, retryAfter, meaning, rawOp) =>
      if rawOp == op then
        rawFault = None
        throw DiscordApiException(code, retryAfter, meaning)
    fault.foreach: f =>
      // Discord has no "not modified" or "duplicate reaction" errors: both are vendor-side successes (DESIGN.md
      // section 4.5), so those faults never raise.
      val raises = (f, op) match
        case (VendorFault.RateLimited(_), "send")   => true
        case (VendorFault.Blocked, "send")          => true
        case (VendorFault.EditNotFound, "edit")     => true
        case (VendorFault.DeleteWindow, "delete")   => true
        case _                                      => false
      if raises then
        this.fault = None
        throw f match
          case VendorFault.RateLimited(seconds) => DiscordApiException(429, Some(seconds), "rate limited")
          case VendorFault.Blocked              => DiscordApiException(50007, None, "Cannot send messages to this user")
          case _                                => DiscordApiException(10008, None, "Unknown Message")

  // ---------- Recording (called by the fake interactions) ----------

  def recordAck(kind: String): Unit = acks += AckObservation(kind, server.clock)

  def recordInteractionReply(kind: String, message: DiscordMessage, ephemeral: Boolean): Unit =
    interactionReplies += ((kind, message, ephemeral))

/** A live fake interaction: acks and replies are recorded on the transport; message ids are minted like sends. */
final class FakeDiscordInteraction(
    transport: FakeDiscordTransport,
    override val id: String,
    override val user: DiscordUser,
    override val channelId: String,
    override val context: DiscordContext
) extends DiscordInteraction:
  override def createdAt: Instant = Snowflake.createdAt(id)
  override def deferReply(ephemeral: Boolean): Unit = transport.recordAck("deferReply")
  override def deferUpdate(): Unit = transport.recordAck("deferUpdate")
  override def reply(message: DiscordMessage, ephemeral: Boolean): String =
    transport.recordAck("reply")
    transport.recordInteractionReply("reply", message, ephemeral)
    transport.nextMessageId()
  override def editOriginal(message: DiscordMessage): String = transport.nextMessageId()
  override def editSourceMessage(message: DiscordMessage): String = transport.nextMessageId()
  override def replyModal(modal: DiscordModal): Unit =
    transport.recordAck("replyModal")
    transport.modalPayloads +=
      s"custom_id=${modal.customId};title=${modal.title};fields=${modal.fields.map(_.key).mkString(",")}"
  override def replyChoices(choices: List[String]): Unit =
    transport.recordAck("replyChoices")
    transport.autocompleteReplies += choices
