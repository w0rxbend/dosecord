package dosecord.adapter.console

import dosecord.contracts.*
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.VendorMarkup

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintStream
import java.time.Instant
import java.util.UUID
import scala.util.matching.Regex

/** The console ChatAdapter (ROADMAP M0.12c, DESIGN.md section 5, ADR-005): stdin/stdout on the text-only
  * [[CapabilityProfiles.Console]] profile. Numbered options are already lowered to a numbered legend in the message
  * text by the renderer; the user answers with the number as a plain reply. Every outbound message is printed as
  * `[#<n>] <text>` (`n` is the message id) and the target syntax `#<n> <reply>` produces
  * `MessageReceived(replyTo = handle n)`, so quoted-reply resolution is exercisable from the console.
  *
  * Wire model: every non-empty input line is one wire event with a stable `vendor_event_id`
  * `console:msg:<session>:<seq>` — `<session>` keeps ids unique across process restarts, `<seq>` is the resume cursor
  * (`resumeFrom` continues it). The actor is `CONSOLE_USER_ID` from Settings.
  */
final class ConsoleAdapter(
    userId: String,
    in: BufferedReader,
    out: PrintStream,
    sessionId: String = ConsoleAdapter.defaultSessionId(),
    now: () => Instant = () => Instant.now()
) extends ChatAdapter:

  override val vendor: String = "console"

  override def capabilities: CapabilityProfile = CapabilityProfiles.Console

  private val chat: ChatRef = ChatRef(vendor, s"dm:$userId")
  private val actor: PlatformIdentity = PlatformIdentity(vendor, userId)

  private val lock = new Object
  private var outSeq = 0
  private var inSeq = 0
  @volatile private var running = false

  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit =
    inSeq = resumeFrom.flatMap(_.toIntOption).getOrElse(0)
    running = true
    // The console has no gateway scope of its own: one virtual thread drains stdin (contracts: adapters fork
    // their gateway from their own scope; a blocking readLine cannot be cancelled, and virtual threads are
    // daemons, so the process can still halt).
    Thread.ofVirtual().name("console-reader").start(() => readLoop(sink))
    ()

  override def stop(): Unit =
    running = false

  private def readLoop(sink: InboundSink): Unit =
    try
      while running do
        in.readLine() match
          case null                      => running = false
          case line if line.trim.isEmpty => ()
          case line                      => sink.push(eventFor(line))
    catch case _: java.io.IOException => running = false

  /** Maps one input line into its wire event; the id is stable per `(sessionId, seq)`. */
  private[console] def eventFor(line: String): InboundEvent = lock.synchronized:
    inSeq += 1
    InboundEvent(
      eventId = EventId(UUID.randomUUID()),
      vendor = vendor,
      vendorEventId = s"console:msg:$sessionId:$inSeq",
      receivedAt = now(),
      createdAt = Some(now()),
      actor = actor,
      chat = chat,
      cursor = Some(inSeq.toString),
      body = interpret(line)
    )

  /** The input grammar: `#<n> <reply>` quotes outbound message n; `/name ...` is a command (the console has no native
    * commands; the raw line is carried for the handler); anything else is a plain message.
    */
  private[console] def interpret(line: String): Inbound =
    line.trim match
      case ConsoleAdapter.Target(number, reply) =>
        number.toIntOption match
          case Some(n) => Inbound.MessageReceived(reply.trim, Some(handle(n)), truncated = false)
          case None    => Inbound.MessageReceived(line.trim, None, truncated = false)
      case trimmed if trimmed.length > 1 && trimmed.startsWith("/") =>
        Inbound.CommandInvoked(trimmed.drop(1).takeWhile(c => !c.isWhitespace), Map.empty, trimmed)
      case trimmed =>
        Inbound.MessageReceived(trimmed, None, truncated = false)

  /** Message handles are minted by [[send]] as the printed number, so a `#<n>` target reconstructs them without
    * adapter-side state.
    */
  private def handle(n: Int): MessageHandle = MessageHandle(vendor, chat.chatId, n.toString)

  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    lock.synchronized:
      outSeq += 1
      val handle = MessageHandle(vendor, chat.chatId, outSeq.toString)
      out.println(s"[#$outSeq] ${rendered.chunks.mkString("\n")}")
      out.flush()
      handle

  // Capability honesty: the console profile claims no edit, delete or reactions, so these are Unsupported.
  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle =
    throw ChatError.Unsupported("edit")

  override def delete(handle: MessageHandle): Unit =
    throw ChatError.Unsupported("delete")

  override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit =
    throw ChatError.Unsupported("react")

  override def registerCommands(specs: List[CommandSpec]): Unit =
    () // text-only profile: commands are typed, there is nothing to register

  override def resolveChat(identity: PlatformIdentity): ChatRef =
    ChatRef(vendor, s"dm:${identity.vendorUserId}")

  override def renderText(text: RichText): List[String] =
    VendorMarkup.split(VendorMarkup.render(text, capabilities.markup), capabilities.maxText)

object ConsoleAdapter:
  private val Target: Regex = "^#(\\d+)\\s+(.+)$".r

  private def defaultSessionId(): String = Instant.now().toEpochMilli.toString

  /** The production adapter on the process's standard streams. */
  def stdio(userId: String): ConsoleAdapter =
    ConsoleAdapter(userId, BufferedReader(InputStreamReader(System.in)), System.out)
