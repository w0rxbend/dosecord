package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.core.chat.AckPolicy
import dosecord.core.chat.ActionRegistry
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.VendorMarkup
import dosecord.core.domain.copy.AdapterCopy
import ox.channels.Channel
import ox.channels.ChannelClosedException
import ox.fork
import ox.supervised

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import scala.compiletime.uninitialized

/** The Discord `ChatAdapter` (ROADMAP M2.1, DESIGN.md section 5, ADR-013): slash commands only (no prefix commands, no
  * message-content intent), `USER_INSTALL` + bot-DM-only registration, `vendor_event_id = discord:interaction:{id} |
  * discord:message:{id}`, a 1.5 s ack watchdog measured from the interaction snowflake (disabled for `opensForm`),
  * ephemeral refusal of any non-bot-DM interaction as defence in depth, a `dm_channel_id` cache, and `Unreachable` on
  * Discord 50007.
  *
  * The adapter is transport-agnostic: [[DiscordTransport]] is the gateway/REST boundary and `JdaTransport` is the only
  * JDA-backed implementation, so the whole inbound mapping runs against a fake wire in suite A. Every inbound event is
  * handed to an Ox fork — the JDA listener thread never blocks. The ack watchdog is one virtual thread per interaction:
  * it polls the injected clock and applies the declared ack (ADR-013) if neither the mediator nor the handler acked
  * within [[DiscordAdapter.WatchdogTarget]] of the snowflake.
  *
  * @param autocomplete
  *   answers a slash-option autocomplete request (over `medications.name_norm`); injected because adapters never call
  *   the core — the composition root wires the one indexed read (M2.1's deferred-from-M0.8 hard requirement).
  * @param recordAckLatency
  *   records `dosecord_interaction_ack_latency_seconds{vendor="discord"}` samples, measured from the snowflake.
  */
final class DiscordAdapter(
    transport: DiscordTransport,
    autocomplete: (PlatformIdentity, String) => List[String],
    recordAckLatency: Duration => Unit,
    now: () => Instant = () => Instant.now(),
    log: String => Unit = _ => ()
) extends ChatAdapter:

  override val vendor: String = "discord"

  override def capabilities: CapabilityProfile = CapabilityProfiles.Discord

  @volatile private var sink: InboundSink = uninitialized
  @volatile private var running = false
  @volatile private var commandSpecs: Map[String, CommandSpec] = Map.empty
  private val events = Channel.unlimited[DiscordEvent]
  private val dmChannels = new ConcurrentHashMap[String, String]()
  private val lastCursor = new AtomicReference[String]()
  private val lifecycleSeq = new AtomicLong(0)

  @volatile private var connectFailure: Throwable = null

  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit =
    this.sink = sink
    resumeFrom.foreach(lastCursor.set)
    running = true
    val ready = new java.util.concurrent.CountDownLatch(1)
    // The adapter owns its gateway scope (contracts: adapters fork their gateway from their own scope; the mediator
    // supervises start/stop). `start` returns only once the gateway is ready, so `registerCommands` right after is safe.
    Thread
      .ofVirtual()
      .name("discord-gateway")
      .start: () =>
        try
          supervised:
            // The JDA listener thread may not fork into the scope (Ox allows forks only from the scope's own threads),
            // so events land in an unlimited channel and one dispatcher fork forks each event (ROADMAP M2.1: every JDA
            // event is handed to an Ox fork; the gateway thread never blocks).
            fork:
              while true do
                val event = events.receive()
                fork(handleEvent(event))
            transport.connect(event => onGatewayEvent(event))
            ready.countDown()
            transport.awaitDisconnect()
        catch
          case e: Throwable =>
            connectFailure = e
            throw e
        finally ready.countDown()
    ready.await()
    if connectFailure != null then throw IllegalStateException("discord gateway failed to connect", connectFailure)

  override def stop(): Unit =
    running = false
    transport.disconnect()

  /** Called on the gateway thread: enqueue the event and return immediately. */
  private def onGatewayEvent(event: DiscordEvent): Unit =
    try events.send(event)
    catch case _: ChannelClosedException => () // the scope is shutting down

  private def handleEvent(event: DiscordEvent): Unit =
    try
      event match
        case DiscordEvent.SlashCommand(interaction, name, options)         => onSlashCommand(interaction, name, options)
        case DiscordEvent.Component(interaction, customId, values, source) =>
          onComponent(interaction, customId, values, source)
        case DiscordEvent.ModalSubmit(interaction, customId, fields) => onModalSubmit(interaction, customId, fields)
        case DiscordEvent.Autocomplete(interaction, _, _, query)     => onAutocomplete(interaction, query)
        case DiscordEvent.Message(id, createdAt, author, channelId, text, replyTo) =>
          onMessage(id, createdAt, author, channelId, text, replyTo)
        case DiscordEvent.Lifecycle(state, fromCursor) => onLifecycle(state, fromCursor)
    catch
      case e: ChatError => log(s"discord: chat error handling ${event.productPrefix}: ${e.getClass.getSimpleName}")
      case e: Exception => log(s"discord: event handling failed: ${e.getClass.getSimpleName}")

  /** Defence in depth (ROADMAP M2.1, owner directive): registration makes guild and group-DM contexts unreachable, so
    * this only fires for a stale registration or a vendor surprise. The reply is ephemeral — visible only to the sender
    * — and nothing reaches the core.
    */
  private def refuseNonDm(interaction: DiscordInteraction): Boolean =
    if interaction.context == DiscordContext.BotDm then false
    else
      val _ = DiscordErrorMapper.guard(
        interaction.reply(DiscordMessage(AdapterCopy.dmOnlyRedirect), ephemeral = true)
      )
      log(s"discord: refused an interaction from context ${interaction.context} with the ephemeral DM-only redirect")
      true

  private def onSlashCommand(interaction: DiscordInteraction, name: String, options: List[DiscordOption]): Unit =
    if !refuseNonDm(interaction) then
      cacheDm(interaction)
      val handle = newHandle(interaction)
      val args = options.map(o => o.name -> o.value).toMap
      val raw = (s"/$name" +: options.map(_.value)).mkString(" ")
      startWatchdog(handle, declaredCommandAck(name))
      push(interactionEvent(interaction, Inbound.CommandInvoked(name, args, raw), handle))

  private def onComponent(
      interaction: DiscordInteraction,
      customId: String,
      values: List[String],
      sourceMessageId: String
  ): Unit =
    if !refuseNonDm(interaction) then
      cacheDm(interaction)
      val handle = newHandle(interaction)
      // Select menus carry the chosen option's token in `values`; buttons carry it in the custom id.
      val token = values.headOption.filter(_.startsWith("dc:")).getOrElse(customId)
      val callback = peekedRef(token)
      val source = Some(MessageHandle(vendor, interaction.channelId, sourceMessageId))
      startWatchdog(handle, declaredComponentAck(callback))
      push(interactionEvent(interaction, Inbound.InteractionSubmitted(callback, values, source), handle))

  private def onModalSubmit(interaction: DiscordInteraction, customId: String, fields: Map[String, String]): Unit =
    if !refuseNonDm(interaction) then
      cacheDm(interaction)
      val handle = newHandle(interaction)
      // The modal's custom id is `<form id>:<submit token>` (DiscordTranslation.toModal); a bare `dc:` id is accepted
      // for robustness. A modal submit is never form-opening, so the watchdog always applies (ADR-013's opensForm
      // exemption covers the interaction that opened the form, not its submission).
      val (formId, token) = splitModalId(customId)
      startWatchdog(handle, AckPolicy.Ack.DeferUpdate)
      push(interactionEvent(interaction, Inbound.FormSubmitted(formId, peekedRef(token), fields), handle))

  /** `<form id>:<submit token>` -> both halves; a bare token yields it twice (the form id is informational). */
  private def splitModalId(customId: String): (String, String) =
    if customId.startsWith("dc:") then (customId, customId)
    else
      customId.span(_ != ':') match
        case (id, rest) if rest.nonEmpty => (id, rest.tail)
        case _                           => (customId, customId)

  private def onAutocomplete(interaction: DiscordInteraction, query: String): Unit =
    if !refuseNonDm(interaction) then
      cacheDm(interaction)
      val identity = PlatformIdentity(vendor, interaction.user.id, Some(interaction.user.name))
      val choices = autocomplete(identity, query).take(DiscordAdapter.MaxAutocompleteChoices)
      val _ = DiscordErrorMapper.guard(interaction.replyChoices(choices))
      ()

  private def onMessage(
      id: String,
      createdAt: Instant,
      author: DiscordUser,
      channelId: String,
      text: String,
      replyTo: Option[String]
  ): Unit =
    if author.id != transport.selfUser.id then
      dmChannels.put(author.id, channelId)
      push(
        InboundEvent(
          eventId = EventId(UUID.randomUUID()),
          vendor = vendor,
          vendorEventId = s"discord:message:$id",
          receivedAt = now(),
          createdAt = Some(createdAt),
          actor = PlatformIdentity(vendor, author.id, Some(author.name)),
          chat = ChatRef(vendor, channelId),
          cursor = Some(id),
          body =
            Inbound.MessageReceived(text, replyTo.map(mid => MessageHandle(vendor, channelId, mid)), truncated = false)
        )
      )

  private def onLifecycle(state: LifecycleState, fromCursor: Option[String]): Unit =
    push(
      InboundEvent(
        eventId = EventId(UUID.randomUUID()),
        vendor = vendor,
        vendorEventId = s"discord:lifecycle:${lifecycleSeq.incrementAndGet()}",
        receivedAt = now(),
        actor = PlatformIdentity(vendor, transport.selfUser.id, Some(transport.selfUser.name)),
        chat = ChatRef(vendor, "system"),
        cursor = Option(lastCursor.get()),
        body = Inbound.AdapterLifecycle(state, fromCursor.orElse(Option(lastCursor.get())))
      )
    )

  private def push(event: InboundEvent): Unit =
    event.cursor.foreach(lastCursor.set)
    sink.push(event)
    ()

  private def cacheDm(interaction: DiscordInteraction): Unit =
    if interaction.context == DiscordContext.BotDm then dmChannels.put(interaction.user.id, interaction.channelId)

  private def newHandle(interaction: DiscordInteraction): DiscordInteractionHandle =
    DiscordInteractionHandle(interaction, now, recordAckLatency)

  // ---------- The ack watchdog (ADR-013: 1.5 s from the snowflake, the declared type, disabled for opensForm) ----------

  private def declaredCommandAck(name: String): AckPolicy.Ack =
    commandSpecs.get(name) match
      case Some(spec) if spec.opensForm => AckPolicy.Ack.NoAck
      case Some(spec)                   => AckPolicy.forCommand(spec, capabilities)
      case None                         => AckPolicy.Ack.DeferReply(ephemeral = false)

  private def declaredComponentAck(callback: CallbackRef): AckPolicy.Ack =
    ActionRegistry.byId(callback.actionId) match
      case Some(entry) if entry.opensForm => AckPolicy.Ack.NoAck
      case Some(entry)                    => AckPolicy.forComponent(entry, capabilities)
      case None                           => AckPolicy.Ack.DeferUpdate

  private def startWatchdog(handle: DiscordInteractionHandle, declared: AckPolicy.Ack): Unit =
    if declared != AckPolicy.Ack.NoAck then
      val deadline = handle.createdAt.plus(DiscordAdapter.WatchdogTarget)
      Thread
        .ofVirtual()
        .name(s"discord-ack-watchdog-${handle.interactionId}")
        .start: () =>
          var done = false
          while !done && running do
            if handle.acked then done = true
            else
              val remaining = Duration.between(now(), deadline).toMillis
              if remaining <= 0 then
                try AckPolicy(declared, handle)
                catch case e: Exception => log(s"discord: watchdog ack failed: ${e.getClass.getSimpleName}")
                done = true
              else Thread.sleep(math.min(remaining, 100L))
          ()
      ()

  // ---------- Inbound plumbing ----------

  private def interactionEvent(
      interaction: DiscordInteraction,
      body: Inbound,
      handle: InteractionHandle
  ): InboundEvent =
    InboundEvent(
      eventId = EventId(UUID.randomUUID()),
      vendor = vendor,
      vendorEventId = s"discord:interaction:${interaction.id}",
      receivedAt = now(),
      createdAt = Some(interaction.createdAt),
      actor = PlatformIdentity(vendor, interaction.user.id, Some(interaction.user.name)),
      chat = ChatRef(vendor, interaction.channelId),
      cursor = Some(interaction.id),
      body = body,
      interaction = Some(handle)
    )

  /** The wire form of a component/modal callback. A token that does not parse still reaches the mediator, which
    * re-verifies the MAC and rejects it with the tamper toast (DESIGN.md section 4.4) — the adapter never drops an
    * interaction silently.
    */
  private def peekedRef(token: String): CallbackRef =
    CallbackPeek.decode(token) match
      case Some(p) => CallbackRef(p.actionId, p.subject, p.value, p.slot, token)
      case None    => CallbackRef(0, new UUID(0L, 0L), 0L, slot = false, token)

  // ---------- Outbound ----------

  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    val nonce = DiscordAdapter.nonceOf(sendKey)
    rendered.chunks.init.foreach: chunk =>
      val _ = DiscordErrorMapper.guard(
        transport.sendMessage(
          chat.chatId,
          DiscordMessage(chunk, silent = rendered.silent, suppressPreview = rendered.suppressPreview),
          Some(nonce)
        )
      )
    val last = rendered.chunks.last
    val messageId = DiscordErrorMapper.guard(
      transport.sendMessage(
        chat.chatId,
        DiscordTranslation.toMessage(rendered.copy(chunks = List(last))),
        Some(nonce)
      )
    )
    MessageHandle(vendor, chat.chatId, messageId)

  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle =
    DiscordErrorMapper.guard(
      transport.editMessage(handle.chatId, handle.messageId, DiscordTranslation.toMessage(rendered))
    )
    handle.copy(revision = handle.revision + 1)

  override def delete(handle: MessageHandle): Unit =
    DiscordErrorMapper.guard(transport.deleteMessage(handle.chatId, handle.messageId))

  override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit =
    if on then DiscordErrorMapper.guard(transport.addReaction(handle.chatId, handle.messageId, emoji))
    else DiscordErrorMapper.guard(transport.removeReaction(handle.chatId, handle.messageId, emoji))

  override def registerCommands(specs: List[CommandSpec]): Unit =
    commandSpecs = specs.map(spec => spec.name -> spec).toMap
    DiscordErrorMapper.guard(transport.registerCommands(DiscordRegistration.plan(specs)))

  override def resolveChat(identity: PlatformIdentity): ChatRef =
    val cached = dmChannels.get(identity.vendorUserId)
    val channelId =
      if cached != null then cached
      else
        val opened = DiscordErrorMapper.guard(transport.openPrivateChannel(identity.vendorUserId))
        dmChannels.put(identity.vendorUserId, opened)
        opened
    ChatRef(vendor, channelId)

  override def renderText(text: RichText): List[String] =
    VendorMarkup.split(VendorMarkup.render(text, capabilities.markup), capabilities.maxText)

  /** The `InteractionHandle` over `deferReply` / `deferEdit` / `reply` / `editOriginal` / `replyModal` (ROADMAP M2.1).
    * The first ack wins (mediator, watchdog or handler race on one lock) and records the ack latency from the
    * snowflake; later `respond`/`editSource` calls edit through the deferred hook.
    */
  private final class DiscordInteractionHandle(
      interaction: DiscordInteraction,
      now: () => Instant,
      recordLatency: Duration => Unit
  ) extends InteractionHandle:
    val createdAt: Instant = interaction.createdAt
    val interactionId: String = interaction.id

    @volatile private var ackedFlag = false
    private val ackLock = new Object

    override def acked: Boolean = ackedFlag

    private def ackOnce[A](op: => A): Option[A] = ackLock.synchronized:
      if ackedFlag then None
      else
        val result = DiscordErrorMapper.guard(op)
        ackedFlag = true
        recordLatency(Duration.between(createdAt, now()))
        Some(result)

    override def deferUpdate(): Unit = ackOnce(interaction.deferUpdate())

    override def deferReply(ephemeral: Boolean): Unit = ackOnce(interaction.deferReply(ephemeral))

    /** Discord has no single-shot answer: a toast is an ephemeral interaction reply, a bare answer a defer. */
    override def answer(toast: Option[String]): Unit = toast match
      case Some(text) => ackOnce(interaction.reply(DiscordMessage(text), ephemeral = true))
      case None       => ackOnce(interaction.deferUpdate())

    override def respond(rendered: RenderedMessage): MessageHandle =
      ackLock.synchronized:
        val messageId =
          if ackedFlag then DiscordErrorMapper.guard(interaction.editOriginal(DiscordTranslation.toMessage(rendered)))
          else ackOnce(interaction.reply(DiscordTranslation.toMessage(rendered), rendered.ephemeral)).get
        MessageHandle(vendor, interaction.channelId, messageId)

    override def editSource(rendered: RenderedMessage): Unit = ackLock.synchronized:
      if ackedFlag then
        val _ = DiscordErrorMapper.guard(interaction.editOriginal(DiscordTranslation.toMessage(rendered)))
      else ackOnce(interaction.editSourceMessage(DiscordTranslation.toMessage(rendered)))
      ()

    /** The modal IS the ack (M0.8: `replyModal` as the first and only ack on an un-acked interaction). */
    override def openForm(form: RenderedForm): Unit = ackOnce(interaction.replyModal(DiscordTranslation.toModal(form)))
  end DiscordInteractionHandle
end DiscordAdapter

object DiscordAdapter:

  /** The watchdog target measured from the interaction snowflake (ADR-013; the hard limit is the 3 s ack deadline in
    * the capability profile).
    */
  val WatchdogTarget: Duration = Duration.ofMillis(1500)

  private val MaxAutocompleteChoices = 25 // Discord's autocomplete choice cap.

  /** `base64url(sha256(sendKey)).take(22)` (DESIGN.md section 5): JDA's `setNonce` sends `enforce_nonce: true`. */
  private[discord] def nonceOf(sendKey: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(sendKey.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding().encodeToString(digest).take(22)

  /** The production adapter on a real JDA gateway (the only live-credential path; everything else runs on fakes). */
  def jda(
      token: String,
      autocomplete: (PlatformIdentity, String) => List[String],
      recordAckLatency: Duration => Unit,
      now: () => Instant = () => Instant.now(),
      log: String => Unit = _ => ()
  ): DiscordAdapter =
    DiscordAdapter(JdaTransport(token), autocomplete, recordAckLatency, now, log)
