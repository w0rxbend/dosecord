package dosegram.adapter.telegram

import dosegram.contracts.*
import dosegram.core.chat.CapabilityProfiles
import dosegram.core.chat.InboxProcessor
import dosegram.core.chat.VendorMarkup
import dosegram.core.domain.copy.AdapterCopy
import dosegram.core.ports.Clock
import dosegram.core.ports.InboundInbox
import dosegram.core.ports.PendingInboxEntry
import ox.discard

import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** How updates reach the adapter. Long polling and a webhook exclude each other (research brief 02, section 4.1). */
enum UpdatesMode:
  /** The adapter runs the `getUpdates` loop itself. */
  case Poll

  /** Updates arrive through [[WebhookHandler]] and [[TelegramAdapter.receive]]; the adapter starts no loop. */
  case Webhook

/** Who may use the bot (ROADMAP G1.6): everyone, or the listed Telegram user ids until invitations replace the list. */
enum AccessPolicy:
  case Open
  case Allowlist(userIds: Set[Long])

  def allows(userId: Long): Boolean = this match
    case Open               => true
    case Allowlist(userIds) => userIds.contains(userId)

/** Counters the adapter reports (ARCHITECTURE sections 7.2.4 and 7.10); `infra` owns the registry. */
trait TelegramMetrics:
  /** Time from the receipt of a callback query to its answer on the wire. */
  def ackLatency(waited: Duration): Unit
  def callbackExpired(): Unit

  /** `dosegram_inbox_quarantined_total` (ARCHITECTURE TG-09). */
  def inboxQuarantined(): Unit = ()

object TelegramMetrics:
  val noop: TelegramMetrics = new TelegramMetrics:
    override def ackLatency(waited: Duration): Unit = ()
    override def callbackExpired(): Unit = ()

final case class TelegramConfig(
    updates: UpdatesMode,
    access: AccessPolicy,
    /** When set, start-up refuses a token whose `getMe` id is another bot's (ARCHITECTURE section 7.9). */
    expectedBotId: Option[Long],
    /** Only a development bot may have its webhook deleted to make room for long polling. */
    developmentBot: Boolean,
    /** The address of the Mini App; an app button is left out while it is unset. */
    miniAppUrl: Option[String],
    ack: AckTiming = AckTiming.Default,
    limits: FloodGate.Limits = FloodGate.Limits.Default,
    /** In webhook mode, what start-up registers with `setWebhook`; without it the webhook is left as it is. */
    webhook: Option[WebhookRegistration] = None
)

/** What `setWebhook` registers (ARCHITECTURE section 7.9, ROADMAP G1.7): the public URL, which carries the path secret,
  * the header secret and the connection count, which the skeleton keeps at one so updates arrive in order. Neither
  * secret shows in a `toString`.
  */
final class WebhookRegistration(val url: String, val secretToken: String, val maxConnections: Int = 1):
  override def toString: String = s"WebhookRegistration(***, max_connections=$maxConnections)"

/** The Telegram `ChatAdapter` (ROADMAP G1.5, ARCHITECTURE section 7): private chats only, one inbound event per update
  * with `vendor_event_id = telegram:update:<update_id>`, a single-shot callback answer with a watchdog, and sends,
  * edits and deletes through the flood gate. It keeps no conversation state: what it needs to edit a message is in the
  * handle.
  *
  * `sendKey` is not sent anywhere: the Bot API has no idempotency key for a send, so the outbox protocol of ADR-009
  * bounds duplicates (ARCHITECTURE TG-19).
  */
final class TelegramAdapter(api: BotApi, config: TelegramConfig, runtime: TelegramAdapter.Runtime) extends ChatAdapter:

  import TelegramAdapter.*

  override val vendor: String = Vendor

  override def capabilities: CapabilityProfile = CapabilityProfiles.Telegram

  private val keyboards = Keyboards(config.miniAppUrl)
  private val gate = FloodGate(runtime.clock, config.limits, runtime.sleep)
  private val sink = AtomicReference(Option.empty[InboundSink])
  private val reader = AtomicReference(Option.empty[LongPollReader])
  private val botUsername = AtomicReference(Option.empty[String])

  // ---------- Lifecycle ----------

  /** The processor of the durable inbox, when the adapter has one (ADR-017). The process runs its loop in a fork of its
    * own; [[stop]] ends it.
    */
  val inboxProcessor: Option[InboxProcessor] =
    runtime.inbox.map: inbox =>
      InboxProcessor(
        inbox,
        deliverStored,
        runtime.clock,
        runtime.log,
        quarantined = () => runtime.metrics.inboxQuarantined()
      )

  /** Checks the bot's identity, sets the default menu button and, in polling mode, starts the reader; in webhook mode
    * it registers the webhook when one is configured. A failure here is a failed start: it is thrown, and the process
    * does not come up half-configured.
    */
  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit =
    val identity = verifiedIdentity()
    botUsername.set(identity.username)
    required(api.setChatMenuButton(None, defaultMenuButton), "setChatMenuButton")
    this.sink.set(Some(sink))
    config.updates match
      case UpdatesMode.Webhook =>
        config.webhook.foreach(registration => required(api.setWebhook(registration), "setWebhook"))
      case UpdatesMode.Poll =>
        if config.developmentBot then required(api.deleteWebhook(), "deleteWebhook")
        val polling =
          LongPollReader(api, receive, resumeFrom.flatMap(_.toLongOption), runtime.log, runtime.sleep)
        reader.set(Some(polling))
        runtime.timer.after(Duration.ZERO)(() => polling.run())

  override def stop(): Unit =
    reader.getAndSet(None).foreach(_.stop())
    inboxProcessor.foreach(_.stop())
    sink.set(None)

  /** The Mini App for every private chat when one is configured, the command list otherwise (ADR-016 addendum of
    * 2026-11-13, which takes the default-level variant of BOT_UX BUX-MENU-01 and 02). Commands stay reachable by `/`.
    */
  private def defaultMenuButton: MenuButton =
    config.miniAppUrl.fold(MenuButton.Commands)(url => MenuButton.WebApp(AdapterCopy.menuButton, url))

  private def verifiedIdentity(): BotIdentity =
    val identity = required(api.getMe(), "getMe")
    if config.expectedBotId.exists(_ != identity.id) then
      throw IllegalStateException("telegram: the token belongs to another bot than TELEGRAM_BOT_ID names")
    identity

  private def required[A](result: Either[TelegramError, A], method: String): A =
    result match
      case Right(value) => value
      case Left(error)  => throw IllegalStateException(s"telegram: $method failed at start (${error.productPrefix})")

  // ---------- Inbound ----------

  /** Maps one update and hands it to the mediator. `false` only when the update must come again: the adapter is not
    * started, or the mediator's queue is full.
    */
  def ingest(update: Update): Boolean = handOver(update, runtime.clock.now())(_.push(_))

  /** The receipt of one update from the long poll or the webhook (ADR-017). With an inbox, an update that becomes an
    * event is stored before this returns true, and processed later; a sender outside the allowlist gets the fixed reply
    * and nothing is stored. `false` when the update could not be stored: it must come again. Without an inbox it is
    * [[ingest]].
    */
  def receive(update: Update): Boolean =
    runtime.inbox match
      case None        => ingest(update)
      case Some(inbox) => sink.get().isDefined && screened(update)(_ => stored(inbox, update))

  private def stored(inbox: InboundInbox, update: Update): Boolean =
    try
      inbox.store(InboxCodec.entry(update), runtime.clock.now())
      inboxProcessor.foreach(_.wake())
      true
    catch
      case _: SQLException =>
        runtime.log(s"telegram update not stored: database unavailable update_id=${update.updateId}")
        false

  /** An update from the inbox, processed to its end: true once its effect is durable (TG-08). */
  private def deliverStored(pending: PendingInboxEntry): Boolean =
    InboxCodec.update(pending.entry) match
      case Some(update) => handOver(update, pending.receivedAt)(_.pushAndWait(_))
      case None         =>
        // It will never decode: offering it again cannot help.
        runtime.log(s"telegram inbox entry undecodable: ${pending.entry.vendorEventId}")
        true

  private def handOver(update: Update, receivedAt: Instant)(hand: (InboundSink, InboundEvent) => Boolean): Boolean =
    sink.get() match
      case None         => false
      case Some(target) => screened(update)(draft => hand(target, event(update, draft, receivedAt)))

  /** Maps one update. A draft from an allowed sender goes to `accept`; anything else is settled here: a stranger gets
    * the fixed reply, a callback that cannot become an event is answered, the rest is logged and dropped.
    */
  private def screened(update: Update)(accept: UpdateMapper.Draft => Boolean): Boolean =
    UpdateMapper.map(update, botUsername.get()) match
      case UpdateMapper.Mapped.Deliver(draft) if config.access.allows(draft.userId) => accept(draft)
      case UpdateMapper.Mapped.Deliver(draft)                                       =>
        refuse(draft)
        true
      case UpdateMapper.Mapped.Dismiss(queryId) =>
        answerQuery(queryId, None, Duration.ZERO)
        true
      case UpdateMapper.Mapped.Ignore(reason) =>
        runtime.log(s"telegram update ignored: reason=$reason update_id=${update.updateId}")
        true

  private def event(update: Update, draft: UpdateMapper.Draft, receivedAt: Instant): InboundEvent =
    InboundEvent(
      eventId = EventId(runtime.newEventId()),
      vendor = vendor,
      vendorEventId = InboxCodec.vendorEventId(update.updateId),
      receivedAt = receivedAt,
      createdAt = draft.sentAt,
      // The display name is not passed on: the product never shows it, so it is not collected.
      actor = PlatformIdentity(vendor, draft.userId.toString),
      chat = ChatRef(vendor, draft.chatId.toString),
      cursor = Some(update.updateId.toString),
      body = draft.body,
      interaction =
        draft.callback.map(source => CallbackAnswer(source.queryId, source.source, config.ack, answers, receivedAt))
    )

  /** A sender outside the allowlist gets the one fixed reply; nothing is stored and no event is produced. */
  private def refuse(draft: UpdateMapper.Draft): Unit =
    draft.callback match
      case Some(source) => answerQuery(source.queryId, Some(AdapterCopy.notAvailable), Duration.ZERO)
      case None         =>
        val chat = ChatRef(vendor, draft.chatId.toString)
        try send(chat, RenderedMessage(chunks = renderText(plain(AdapterCopy.notAvailable))), "refusal").discard
        catch case error: ChatError => runtime.log(s"telegram refusal not sent: ${error.getClass.getSimpleName}")

  private val answers: AnswerPort = new AnswerPort:
    override def now(): Instant = runtime.clock.now()
    override def schedule(delay: Duration)(task: () => Unit): Unit = runtime.timer.after(delay)(task)
    override def answerQuery(queryId: String, toast: Option[String], waited: Duration): Unit =
      TelegramAdapter.this.answerQuery(queryId, toast, waited)
    override def editMessage(handle: MessageHandle, rendered: RenderedMessage): Unit = edit(handle, rendered).discard

  /** The one `answerCallbackQuery` of a query. It never throws: an expired query is a success (section 7.10), and any
    * other failure is logged by its kind, because nothing can be shown to the user any more.
    */
  private def answerQuery(queryId: String, toast: Option[String], waited: Duration): Unit =
    gate.beforeAnswer()
    api.answerCallbackQuery(queryId, toast) match
      case Right(())                           => runtime.metrics.ackLatency(waited)
      case Left(TelegramError.CallbackExpired) => runtime.metrics.callbackExpired()
      case Left(error) => runtime.log(s"telegram answerCallbackQuery failed: ${error.productPrefix}")

  // ---------- Outbound ----------

  /** "Sending a photo" in the chat while its image is drawn (VISUALS VA-FB-02). A status, not a message: a refusal is
    * logged by its kind and the drawing goes on.
    */
  def showUploadingPhoto(chat: ChatRef): Unit =
    chat.chatId.toLongOption.foreach: chatId =>
      api.sendChatAction(chatId, ChatAction.UploadPhoto) match
        case Right(())   => ()
        case Left(error) => runtime.log(s"telegram sendChatAction failed: ${error.productPrefix}")

  /** One message per part: the attachment with its caption, then each further text chunk. The keyboard goes on the last
    * part, and the returned handle names that part, because it is the one a tap comes from.
    */
  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    val chatId = chatIdOf(chat.chatId)
    val keyboard = keyboards.inline(rendered.controls)
    val delivery = Delivery(rendered.silent, rendered.suppressPreview)
    val parts = OutgoingPart.of(rendered)
    val sent = parts.zipWithIndex.map: (part, index) =>
      val partKeyboard = if index == parts.size - 1 then keyboard else InlineKeyboard.empty
      sendPart(Envelope(chatId, partKeyboard, delivery), part)
    val handle = MessageIds.handle(chatId, sent.last)
    rendered.deleteAfter.foreach(removeLater(handle, _))
    handle

  private def sendPart(envelope: Envelope, part: OutgoingPart): MessageRef =
    part match
      case OutgoingPart.Text(html)                 => sendText(envelope, html)
      case OutgoingPart.Media(attachment, caption) => sendMedia(envelope, attachment, caption)

  private def sendText(envelope: Envelope, html: String): MessageRef =
    gate.beforeMessageCall(envelope.chatId)
    val sent = api.sendMessage(OutgoingText(envelope.chatId, html, envelope.keyboard, envelope.delivery))
    MessageRef(sentOrThrow(sent, envelope.chatId, "send").messageId, carriesMedia = false)

  /** TG-36: a cached `file_id` first; a rejected one is forgotten and the bytes are uploaded once. Without bytes the
    * text equivalent is sent in place of the image, so the user still gets what the image said.
    */
  private def sendMedia(envelope: Envelope, attachment: AttachmentRef, caption: Option[String]): MessageRef =
    val cached = runtime.fileIds.get(attachment.assetKey, attachment.kind)
    val viaCache = cached.map(fileId => trySend(envelope, attachment, caption, MediaPayload.Cached(fileId)))
    viaCache match
      case Some(Right(sent))                  => mediaRef(sent)
      case Some(Left(TelegramError.BadMedia)) =>
        runtime.fileIds.remove(attachment.assetKey, attachment.kind)
        upload(envelope, attachment, caption)
      case Some(Left(error)) => failed(error, envelope.chatId, "send")
      case None              => upload(envelope, attachment, caption)

  private def upload(envelope: Envelope, attachment: AttachmentRef, caption: Option[String]): MessageRef =
    runtime.media.load(attachment.assetKey) match
      case None =>
        runtime.log(s"telegram asset unavailable: sent the text equivalent (kind=${attachment.kind})")
        sendText(envelope, textEquivalent(attachment, caption))
      case Some(bytes) =>
        val payload = MediaPayload.Upload(fileNameOf(attachment), attachment.mimeType, bytes)
        val sent = sentOrThrow(trySend(envelope, attachment, caption, payload), envelope.chatId, "send")
        sent.fileId.foreach(runtime.fileIds.put(attachment.assetKey, attachment.kind, _))
        mediaRef(sent)

  private def trySend(
      envelope: Envelope,
      attachment: AttachmentRef,
      caption: Option[String],
      payload: MediaPayload
  ): Either[TelegramError, SentMessage] =
    gate.beforeMessageCall(envelope.chatId)
    api.sendMedia(
      OutgoingMedia(
        envelope.chatId,
        attachment.kind,
        payload,
        caption,
        envelope.keyboard,
        envelope.delivery,
        attachment.hasSpoiler
      )
    )

  private def mediaRef(sent: SentMessage): MessageRef = MessageRef(sent.messageId, carriesMedia = true)

  private def textEquivalent(attachment: AttachmentRef, caption: Option[String]): String =
    val label = attachment.kind match
      case MediaKind.Photo    => "image"
      case MediaKind.Document => "document"
    val alternative = VendorMarkup.render(plain(s"[$label: ${attachment.altText}]"), capabilities.markup)
    (alternative :: caption.toList).mkString("\n\n")

  /** Edits the first chunk in place (the caption of a media message, the text otherwise); further chunks are new
    * messages and the last one carries the keyboard, as in the mediator's own split. An edit that changes nothing is a
    * success (TG-22).
    */
  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle =
    // ADR-015: a message with an attachment is always a new message.
    if rendered.attachment.isDefined then throw ChatError.Unsupported("editMedia")
    val chatId = chatIdOf(handle.chatId)
    val ref = messageRefOf(handle)
    val keyboard = keyboards.inline(rendered.controls)
    rendered.chunks match
      case Nil           => throw ChatError.Unsupported("editWithoutText")
      case first :: rest =>
        val firstKeyboard = if rest.isEmpty then keyboard else InlineKeyboard.empty
        editInPlace(MessageTarget(chatId, ref.messageId), ref.carriesMedia, first, firstKeyboard)
        val delivery = Delivery(rendered.silent, rendered.suppressPreview)
        rest.zipWithIndex.foreach: (chunk, index) =>
          val chunkKeyboard = if index == rest.size - 1 then keyboard else InlineKeyboard.empty
          sendText(Envelope(chatId, chunkKeyboard, delivery), chunk)
        handle.copy(revision = handle.revision + 1)

  private def editInPlace(target: MessageTarget, media: Boolean, html: String, keyboard: InlineKeyboard): Unit =
    gate.beforeMessageCall(target.chatId)
    val result =
      if media then api.editMessageCaption(target, html, keyboard) else api.editMessageText(target, html, keyboard)
    result match
      case Right(()) | Left(TelegramError.NotModified) => ()
      case Left(error)                                 => failed(error, target.chatId, "edit")

  /** Deleting a message that is already gone is a success (TG-22). */
  override def delete(handle: MessageHandle): Unit =
    val target = MessageTarget(chatIdOf(handle.chatId), messageRefOf(handle).messageId)
    gate.beforeMessageCall(target.chatId)
    api.deleteMessage(target) match
      case Right(()) | Left(TelegramError.MissingTarget) => ()
      case Left(error)                                   => failed(error, target.chatId, "delete")

  /** A short-lived message (a toast that had to become a message) is removed when its time is up. The timer lives in
    * this process only: after a restart the message stays.
    */
  private def removeLater(handle: MessageHandle, after: Duration): Unit =
    runtime.timer.after(after): () =>
      try delete(handle)
      catch
        case error: ChatError =>
          runtime.log(s"telegram short-lived message not removed: ${error.getClass.getSimpleName}")

  override def registerCommands(specs: List[CommandSpec]): Unit =
    val commands = specs.map(spec => BotCommand(spec.name, spec.description.take(MaxCommandDescriptionChars)))
    api.setMyCommands(commands) match
      case Right(())   => ()
      case Left(error) => failed(error, NoChat, "registerCommands")

  /** That the private chat id equals the user id is an assumption (ARCHITECTURE section 7.3). Nothing in the product
    * path relies on this method: a delivery address is the `chat.id` of an update the user sent.
    */
  override def resolveChat(identity: PlatformIdentity): ChatRef = ChatRef(vendor, identity.vendorUserId)

  override def renderText(text: RichText): List[String] =
    VendorMarkup.split(VendorMarkup.render(text, capabilities.markup), capabilities.maxText)

  // ---------- Errors ----------

  private def sentOrThrow[A](result: Either[TelegramError, A], chatId: Long, op: String): A =
    result match
      case Right(value) => value
      case Left(error)  => failed(error, chatId, op)

  /** Throws the `ChatError` of a failed call; a 429 first pauses the chat for the time the API asked for. */
  private def failed(error: TelegramError, chatId: Long, op: String): Nothing =
    error match
      case TelegramError.RateLimited(retryAfter) if chatId != NoChat => gate.pause(chatId, retryAfter)
      case _                                                         => ()
    throw TelegramErrorMapper
      .toChatError(error, op)
      .getOrElse(ChatError.Retryable(s"telegram $op: unexpected ${error.productPrefix}"))

  private def chatIdOf(chatId: String): Long =
    chatId.toLongOption.getOrElse(throw ChatError.Permanent("telegram: chat id is not a number", channelFatal = false))

  private def messageRefOf(handle: MessageHandle): MessageRef =
    MessageIds
      .decode(handle.messageId)
      .getOrElse(
        throw ChatError.Permanent(
          "telegram: message id is not a number",
          channelFatal = false,
          ChatError.PermanentReason.MissingTarget
        )
      )
end TelegramAdapter

object TelegramAdapter:

  val Vendor = "telegram"

  /** `BotCommand.description` is 1 to 256 characters (research brief 02, section 8). */
  val MaxCommandDescriptionChars = 256

  /** A caption has at most 1024 characters (ARCHITECTURE section 7.8). */
  val MaxCaptionChars = 1024

  private val NoChat = 0L

  /** What the adapter is run with besides its configuration: time, the timer, the asset bytes, the `file_id` cache, a
    * log sink that gets fixed labels and identifiers only, the metrics, the two sources of non-determinism, and the
    * inbox.
    */
  final case class Runtime(
      clock: Clock,
      timer: Timer,
      media: MediaStore,
      fileIds: FileIdCache,
      log: String => Unit,
      metrics: TelegramMetrics = TelegramMetrics.noop,
      sleep: Duration => Unit = wait => Thread.sleep(wait.toMillis),
      newEventId: () => UUID = () => UUID.randomUUID(),
      /** The durable inbox (ADR-017); without it an update is processed as it is received. */
      inbox: Option[InboundInbox] = None
  )

  /** Where one part goes and what it carries besides its content. */
  private final case class Envelope(chatId: Long, keyboard: InlineKeyboard, delivery: Delivery)

  private enum OutgoingPart:
    case Text(html: String)
    case Media(attachment: AttachmentRef, caption: Option[String])

  private object OutgoingPart:
    /** TG-39: a first chunk that fits a caption is the caption; a longer one follows the image as text. */
    def of(rendered: RenderedMessage): List[OutgoingPart] =
      (rendered.attachment, rendered.chunks) match
        case (None, chunks)                                                       => chunks.map(Text(_))
        case (Some(attachment), first :: rest) if first.length <= MaxCaptionChars =>
          Media(attachment, Some(first)) :: rest.map(Text(_))
        case (Some(attachment), chunks) => Media(attachment, None) :: chunks.map(Text(_))

  private def plain(text: String): RichText = List(Node.Paragraph(List(Inline.Text(text))))

  private def fileNameOf(attachment: AttachmentRef): String =
    attachment.fileName.getOrElse:
      attachment.kind match
        case MediaKind.Photo    => "image.png"
        case MediaKind.Document => "document"
end TelegramAdapter
