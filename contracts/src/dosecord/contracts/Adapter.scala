package dosecord.contracts

import upickle.default.ReadWriter

import java.time.Duration

import Json.given

// ---------- Closed error hierarchy (DESIGN.md section 4.5) ----------

sealed abstract class ChatError(msg: String, val channelFatal: Boolean = false) extends Exception(msg)

object ChatError:
  final case class Retryable(msg: String) extends ChatError(msg) derives ReadWriter
  final case class RateLimited(retryAfter: Duration) extends ChatError("rate limited") derives ReadWriter
  // DM closed, blocked, never /start-ed, no shared guild, M_FORBIDDEN on the DM room, Discord 50007.
  final case class Unreachable(msg: String) extends ChatError(msg, channelFatal = true) derives ReadWriter
  final case class TooOld(msg: String) extends ChatError(msg) derives ReadWriter
  // A conformance failure if the profile claimed support for the op.
  final case class Unsupported(op: String) extends ChatError(op) derives ReadWriter
  final case class Permanent(msg: String, override val channelFatal: Boolean) extends ChatError(msg, channelFatal)
      derives ReadWriter

  given ReadWriter[ChatError] = upickle.default.ReadWriter.merge(
    summon[ReadWriter[Retryable]],
    summon[ReadWriter[RateLimited]],
    summon[ReadWriter[Unreachable]],
    summon[ReadWriter[TooOld]],
    summon[ReadWriter[Unsupported]],
    summon[ReadWriter[Permanent]]
  )

// ---------- Renderer output (adapter-facing, all plain data) ----------

final case class RenderedChoice(
    index: Int,
    label: String,
    callback: String,
    style: ChoiceStyle = ChoiceStyle.Secondary,
    emoji: Option[String] = None
) derives ReadWriter:
  require(index >= 1, "choice index is 1-based")

enum RenderedControls derives ReadWriter:
  case NoControls
  case Buttons(rows: List[List[RenderedChoice]])
  case SelectMenu(id: String, choices: List[RenderedChoice], minSelect: Int, maxSelect: Int)
  // Numbered legend in the text plus the digit reactions the bot must add to its own message
  // (empty when the profile cannot react: the text-only rung).
  case Numbered(id: String, choices: List[RenderedChoice], reactions: List[String])

/** One row of `rendered_messages.choice_map` (DESIGN.md section 4.3): index, label and emoji all resolve to the
  * callback.
  */
final case class ChoiceMapEntry(setId: String, index: Int, label: String, emoji: Option[String], callback: String)
    derives ReadWriter

final case class RenderedField(
    key: String,
    label: String,
    tpe: FieldType,
    required: Boolean,
    placeholder: Option[String],
    options: List[String],
    /** Pre-filled value (M1.9: modal inputs and FormRunner questions show the previous answers). */
    value: Option[String] = None
) derives ReadWriter

final case class RenderedForm(id: String, title: String, fields: List[RenderedField], submit: String) derives ReadWriter

/** The lowered message an adapter executes: vendor-markup text chunks (split at `maxText`, blocks attach to the last
  * chunk) plus controls. `choiceMap` is recorded by the mediator before any reaction sub-op so controls stay resolvable
  * after a crash (DESIGN.md section 9).
  */
final case class RenderedMessage(
    chunks: List[String],
    controls: List[RenderedControls] = Nil,
    form: Option[RenderedForm] = None,
    ephemeral: Boolean = false,
    deleteAfter: Option[Duration] = None,
    silent: Boolean = false,
    suppressPreview: Boolean = false,
    choiceMap: List[ChoiceMapEntry] = Nil
) derives ReadWriter:
  require(chunks.nonEmpty && chunks.forall(_.nonEmpty), "at least one non-empty text chunk")

/** Which ladder rung each block resolved to (DESIGN.md section 4.3: the report names the rung). Keys: choice set / form
  * ids, `visibility`, `finalize`.
  */
final case class RenderReport(
    rungs: Map[String, String],
    splitInto: Int = 1,
    paged: Boolean = false,
    warnings: List[String] = Nil
) derives ReadWriter

// ---------- Vendor ops (serialisable: the M6.3-alt out-of-process escape hatch) ----------

enum VendorOp derives ReadWriter:
  case Send(chat: ChatRef, message: RenderedMessage, sendKey: String, replyTo: Option[MessageHandle])
  case Edit(handle: MessageHandle, message: RenderedMessage)
  case Delete(handle: MessageHandle)
  case React(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String)
  // Remove the bot's own reactions (reaction-tier finalize; always permitted on Zulip/Matrix).
  case UnreactAll(handle: MessageHandle)
  // Open a native modal through a live interaction (rung 1 of the form ladder).
  case OpenForm(form: RenderedForm)
  case Ack(toast: Option[String])
  case Typing(chat: ChatRef)
  case RegisterCommands(specs: List[CommandSpec])

// ---------- Commands the core registers ----------

final case class CommandArg(
    name: String,
    description: String,
    required: Boolean = true,
    choices: List[String] = Nil,
    autocomplete: Boolean = false
) derives ReadWriter:
  require(name.nonEmpty, "arg name must not be empty")

/** Static per command (DESIGN.md section 4.2): Discord fixes the ephemeral flag at defer time, so visibility and
  * opensForm are declared here, not chosen per reply (ADR-013).
  */
final case class CommandSpec(
    name: String,
    description: String,
    args: List[CommandArg] = Nil,
    visibility: Visibility = Visibility.Persistent,
    opensForm: Boolean = false
) derives ReadWriter:
  require(name.nonEmpty, "command name must not be empty")

// ---------- Adapter contract (DESIGN.md section 4.5) ----------

/** Mediator-side event sink handed to the adapter at start. A callback object, not data; the M6.3-alt host stubs it
  * over the socket with InboundEvent as the wire payload.
  */
trait InboundSink:
  /** Returns false when the per-adapter queue is full (pull-based adapters then stop polling). */
  def push(event: InboundEvent): Boolean

/** A dumb, stateless vendor adapter (ADR-005). All methods block and run on virtual threads. No `(using Ox)` here:
  * contracts depends on upickle only, so adapters fork their gateway from their own scope — the mediator supervises
  * `start`/`stop`.
  */
trait ChatAdapter:
  def vendor: String
  def capabilities: CapabilityProfile
  def start(sink: InboundSink, resumeFrom: Option[String]): Unit
  def stop(): Unit
  def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle
  def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle
  def delete(handle: MessageHandle): Unit
  def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit
  def registerCommands(specs: List[CommandSpec]): Unit
  def resolveChat(identity: PlatformIdentity): ChatRef
  def renderText(text: RichText): List[String]

// ---------- Port the core calls, implemented by the mediator (DESIGN.md section 4.2) ----------

enum Target derives ReadWriter:
  case AccountTarget(accountId: AccountId)
  case ChatTarget(chat: ChatRef)

final case class SendResult(handle: MessageHandle, report: RenderReport) derives ReadWriter

trait ChatPort:
  def send(target: Target, msg: OutboundMessage): SendResult
  def edit(handle: MessageHandle, msg: OutboundMessage): SendResult
  def finalize(handle: MessageHandle, summary: RichText, keep: Option[ChoiceSet]): SendResult
  def delete(handle: MessageHandle): Unit
  def ack(interaction: InteractionHandle, toast: Option[String]): Unit
  def react(handle: MessageHandle, emoji: String, on: Boolean): Unit
  def typing(chat: ChatRef): Unit
  def registerCommands(specs: List[CommandSpec]): Unit
