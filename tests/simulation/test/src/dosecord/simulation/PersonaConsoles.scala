package dosecord.simulation

import dosecord.adapter.console.ConsoleAdapter
import dosecord.contracts.CapabilityProfile
import dosecord.contracts.ChatAdapter
import dosecord.contracts.ChatRef
import dosecord.contracts.CommandSpec
import dosecord.contracts.InboundSink
import dosecord.contracts.MessageHandle
import dosecord.contracts.PlatformIdentity
import dosecord.contracts.RenderedMessage
import dosecord.contracts.RichText
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.ports.Clock

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader

/** One persona's real console adapter (M0.12c): it mints the `[#n]` message handles and prints what the persona would
  * see; its stdout is discarded because the transcript is rendered from the recorded structured ops.
  */
final class PersonaConsole(val personaId: String, userId: String, clock: Clock):
  val chatId: String = s"dm:$userId"
  private val out = ByteArrayOutputStream()
  private val inner = ConsoleAdapter(userId, BufferedReader(StringReader("")), PrintStream(out), "sim", () => clock.now())

  def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    inner.send(chat, rendered, sendKey)

  def capabilities: CapabilityProfile = inner.capabilities

  def resolveChat(identity: PlatformIdentity): ChatRef = inner.resolveChat(identity)

  def renderText(text: RichText): List[String] = inner.renderText(text)

/** The vendor-facing adapter the mediator and the dispatcher talk to: one `console` vendor multiplexing the four
  * personas by chat id. Every call is recorded into the transcript before being delegated to the persona's real
  * ConsoleAdapter.
  */
final class ConsoleMux(personas: List[PersonaConsole], transcript: SimTranscript) extends ChatAdapter:

  override val vendor: String = "console"

  override val capabilities: CapabilityProfile = CapabilityProfiles.Console

  private val byChatId: Map[String, PersonaConsole] = personas.map(p => p.chatId -> p).toMap

  override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = ()

  override def stop(): Unit = ()

  override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle =
    val persona = route(chat.chatId)
    val handle = persona.send(chat, rendered, sendKey)
    transcript.record(persona.personaId, chat.chatId, sendKey, rendered, handle)
    handle

  // Capability honesty, same as the real console adapter: the profile claims no edit/delete/reactions.
  override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle =
    throw dosecord.contracts.ChatError.Unsupported("edit")

  override def delete(handle: MessageHandle): Unit =
    throw dosecord.contracts.ChatError.Unsupported("delete")

  override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit =
    throw dosecord.contracts.ChatError.Unsupported("react")

  override def registerCommands(specs: List[CommandSpec]): Unit = ()

  override def resolveChat(identity: PlatformIdentity): ChatRef =
    ChatRef(vendor, s"dm:${identity.vendorUserId}")

  override def renderText(text: RichText): List[String] =
    personas.head.renderText(text)

  private def route(chatId: String): PersonaConsole =
    byChatId.getOrElse(chatId, throw new IllegalArgumentException(s"unknown console chat '$chatId'"))
