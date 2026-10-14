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
  val sendNonces = ListBuffer.empty[Option[String]]
  private val nonceIds = scala.collection.mutable.Map.empty[(String, String), String]
  val edits = ListBuffer.empty[SentObservation]
  val editedMessages = ListBuffer.empty[DiscordMessage]
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
  def deliver(event: DiscordEvent): Unit =
    event match
      case DiscordEvent.Component(i: FakeDiscordInteraction, _, _, source) => i.sourceId = Some(source)
      case _                                                               => ()
    handler(event)

  def now: Instant = server.clock
  def interactionOperation(op: String): Unit = maybeFail(op)

  override def sendMessage(channelId: String, message: DiscordMessage, nonce: Option[String]): String =
    validateMessage(message)
    maybeFail("send")
    this.synchronized:
      sendNonces += nonce
      nonce.flatMap(n => nonceIds.get((channelId, n))) match
        case Some(id) => id
        case None     =>
          val id = nextMessageId()
          nonce.foreach(n => nonceIds.update((channelId, n), id))
          sent += SentObservation(
            Some(id),
            message.text,
            s"channel=$channelId components=${message.components.size} silent=${message.silent} nonce=${nonce.getOrElse("")}"
          )
          id

  override def editMessage(channelId: String, messageId: String, message: DiscordMessage): Unit =
    validateMessage(message)
    maybeFail("edit")
    editedMessages += message
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
        case (VendorFault.RateLimited(_), "send") => true
        case (VendorFault.Blocked, "send")        => true
        case (VendorFault.EditNotFound, "edit")   => true
        case (VendorFault.DeleteWindow, "delete") => true
        case _                                    => false
      if raises then
        this.fault = None
        throw f match
          case VendorFault.RateLimited(seconds) => DiscordApiException(429, Some(seconds), "rate limited")
          case VendorFault.Blocked              => DiscordApiException(50007, None, "Cannot send messages to this user")
          case _                                => DiscordApiException(10008, None, "Unknown Message")

  // ---------- Recording (called by the fake interactions) ----------

  def recordAck(kind: String): Unit = acks += AckObservation(kind, server.clock)

  def validateMessage(message: DiscordMessage): Unit =
    val _ = new net.dv8tion.jda.api.utils.messages.MessageCreateBuilder().setContent(message.text).build()

  def recordInteractionReply(kind: String, message: DiscordMessage, ephemeral: Boolean): Unit =
    validateMessage(message)
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
  var sourceId = Option.empty[String]
  private var acknowledged = false
  private var completedOriginal = false
  private var updatingSource = false
  private def initial(kind: String): Unit =
    transport.interactionOperation("ack")
    if acknowledged then throw DiscordApiException(40060, None, "already acknowledged")
    if !transport.now.isBefore(createdAt.plusSeconds(3)) then throw DiscordApiException(10062, None, "expired")
    acknowledged = true
    transport.recordAck(kind)
  private def hook(): Unit =
    transport.interactionOperation("hook")
    if !acknowledged then throw DiscordApiException(40060, None, "not acknowledged")
    if !transport.now.isBefore(createdAt.plusSeconds(900)) then throw DiscordApiException(10015, None, "expired")
  private var originalEphemeral = false
  override def deferReply(ephemeral: Boolean): Unit =
    initial("deferReply")
    originalEphemeral = ephemeral
  override def deferUpdate(): Unit =
    if sourceId.isEmpty then throw DiscordApiException(40060, None, "source required")
    initial("deferUpdate")
    updatingSource = true
  override def reply(message: DiscordMessage, ephemeral: Boolean): String =
    initial("reply")
    originalEphemeral = ephemeral
    completedOriginal = true
    transport.recordInteractionReply("reply", message, ephemeral)
    originalId
  private lazy val originalId = transport.nextMessageId()
  override def editOriginal(message: DiscordMessage): String =
    hook()
    completedOriginal = true
    transport.recordInteractionReply("editOriginal", message, originalEphemeral)
    if updatingSource then sourceId.get else originalId
  override def editSourceMessage(message: DiscordMessage): String =
    if !acknowledged then initial("editSource") else hook()
    val id = sourceId.getOrElse(throw DiscordApiException(40060, None, "source required"))
    transport.recordInteractionReply("editSource", message, originalEphemeral)
    id
  override def followUp(message: DiscordMessage, ephemeral: Boolean): String =
    transport.interactionOperation("followUp")
    hook()
    if !completedOriginal && !updatingSource then editOriginal(message)
    else
      transport.recordInteractionReply("followUp", message, ephemeral)
      transport.nextMessageId()
  override def replyModal(modal: DiscordModal): Unit =
    initial("replyModal")
    completedOriginal = true
    transport.modalPayloads +=
      s"custom_id=${modal.customId};title=${modal.title};fields=${modal.fields.map(_.key).mkString(",")}"
  override def replyChoices(choices: List[String]): Unit =
    initial("replyChoices")
    transport.autocompleteReplies += choices
