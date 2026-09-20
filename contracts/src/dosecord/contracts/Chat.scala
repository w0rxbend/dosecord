package dosecord.contracts

import upickle.default.ReadWriter

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

import Json.given

// ---------- Shared value types (DESIGN.md section 4.1) ----------

final case class PlatformIdentity(vendor: String, vendorUserId: String, displayName: Option[String] = None)
    derives ReadWriter:
  require(vendor.nonEmpty, "vendor must not be empty")
  require(vendorUserId.nonEmpty, "vendorUserId must not be empty")

final case class ChatRef(vendor: String, chatId: String, threadId: Option[String] = None) derives ReadWriter:
  require(vendor.nonEmpty, "vendor must not be empty")
  require(chatId.nonEmpty, "chatId must not be empty")

final case class MessageHandle(vendor: String, chatId: String, messageId: String, revision: Int = 0) derives ReadWriter:
  require(vendor.nonEmpty, "vendor must not be empty")
  require(chatId.nonEmpty, "chatId must not be empty")
  require(messageId.nonEmpty, "messageId must not be empty")
  require(revision >= 0, "revision must be >= 0")

/** Stamped by the mediator only; adapters have no field to supply an account id (R8). */
final case class Principal(identityId: IdentityId, accountId: Option[AccountId], linked: Boolean) derives ReadWriter

enum LifecycleState derives ReadWriter:
  case Connected, Disconnected, Resumed

/** A callback already decoded and MAC-verified by the mediator (ADR-006). The raw token never reaches the core.
  */
final case class CallbackRef(actionId: Int, subject: UUID, value: Long, slot: Boolean) derives ReadWriter:
  require(actionId >= 0 && actionId <= 0xffff, "action id is UInt16")
  require(value >= 0, "value is unsigned")

enum Inbound derives ReadWriter:
  case MessageReceived(text: String, replyTo: Option[MessageHandle], truncated: Boolean)
  case CommandInvoked(name: String, args: Map[String, String], raw: String)
  case InteractionSubmitted(callback: CallbackRef, values: List[String], source: Option[MessageHandle])
  case FormSubmitted(formId: String, callback: CallbackRef, fields: Map[String, String])
  case ReactionChanged(emoji: String, target: MessageHandle, added: Boolean)
  case ConversationStarted
  case AccountLinkCompleted(linkId: UUID, accountId: UUID)
  case AdapterLifecycle(state: LifecycleState, fromCursor: Option[String])

final case class InboundEvent(
    eventId: EventId,
    vendor: String,
    vendorEventId: String,
    receivedAt: Instant,
    createdAt: Option[Instant] = None,
    actor: PlatformIdentity,
    chat: ChatRef,
    cursor: Option[String] = None,
    principal: Option[Principal] = None,
    body: Inbound
) derives ReadWriter:
  require(vendor.nonEmpty, "vendor must not be empty")
  require(vendorEventId.nonEmpty, "vendorEventId must not be empty")

/** The one vendor-timing abstraction (DESIGN.md section 4.1). Opaque to the core; the mediator acks through it. Not
  * serialisable by design — it is a live vendor handle, so it is the one adapter-API type excluded from the ReadWriter
  * contract test.
  */
trait InteractionHandle:
  def deferUpdate(): Unit
  def deferReply(ephemeral: Boolean): Unit
  def answer(toast: Option[String]): Unit
  def respond(rendered: RenderedMessage): MessageHandle
  def editSource(rendered: RenderedMessage): Unit
  def openForm(form: RenderedForm): Unit
  def acked: Boolean

// ---------- Outbound message model (DESIGN.md section 4.2) ----------

enum TimeStyle derives ReadWriter:
  case Time, DateTime, Relative

enum Inline derives ReadWriter:
  case Text(s: String)
  case Bold(s: String)
  case Italic(s: String)
  case Code(s: String)
  case Link(label: String, url: String)
  case LineBreak
  case Emoji(name: String)
  case Time(at: Instant, tz: ZoneId, style: TimeStyle)

enum Node derives ReadWriter:
  case Paragraph(inlines: List[Inline])
  case BulletList(items: List[List[Inline]])
  case CodeBlock(s: String)

/** An AST, never a vendor string (DESIGN.md section 4.2); no headings, tables or images. */
type RichText = List[Node]

enum ChoiceStyle derives ReadWriter:
  case Primary, Secondary, Danger, Success

enum ChoiceLayout derives ReadWriter:
  case Buttons, Select

/** `callback` is the wire form of a CallbackToken (core/chat) — a 49-char `dc:` string, kept as a plain string here so
  * contracts never depends on core.
  */
final case class Choice(
    label: String,
    callback: String,
    style: ChoiceStyle = ChoiceStyle.Secondary,
    emoji: Option[String] = None,
    hotkeys: List[String] = Nil
) derives ReadWriter:
  require(label.nonEmpty && label.length <= 80, "choice label must be 1-80 chars")

final case class ChoiceSet(
    id: String,
    prompt: Option[RichText] = None,
    choices: List[Choice],
    layout: ChoiceLayout = ChoiceLayout.Buttons,
    minSelect: Int = 1,
    maxSelect: Int = 1,
    ttl: Option[Duration] = None
) derives ReadWriter:
  require(id.nonEmpty, "choice set id must not be empty")
  require(choices.nonEmpty && choices.length <= 25, "1-25 choices (Discord select limit)")
  require(minSelect >= 0 && maxSelect >= 1 && minSelect <= maxSelect, "invalid select bounds")
  require(maxSelect <= choices.length, "maxSelect exceeds the choice count")

enum FieldType derives ReadWriter:
  case Text, Number, Time, Date, Choice

final case class Field(
    key: String,
    label: String,
    tpe: FieldType,
    required: Boolean = true,
    placeholder: Option[String] = None,
    options: List[Choice] = Nil
) derives ReadWriter:
  require(key.nonEmpty, "field key must not be empty")

final case class Form(id: String, title: String, fields: List[Field], submit: String) derives ReadWriter:
  require(id.nonEmpty, "form id must not be empty")
  require(title.nonEmpty && title.length <= 45, "form title must be 1-45 chars (Discord modal cap)")
  require(fields.nonEmpty && fields.length <= 5, "1-5 fields (Discord modal cap)")

enum NoticeLevel derives ReadWriter:
  case Info, Success, Warning

final case class Notice(level: NoticeLevel, text: RichText) derives ReadWriter

enum Block derives ReadWriter:
  case Choices(cs: ChoiceSet)
  case FormBlock(f: Form)
  case NoticeBlock(n: Notice)

enum Visibility derives ReadWriter:
  case Persistent, Ephemeral

enum Importance derives ReadWriter:
  case InteractionReply, Reminder, Info, Bulk

final case class OutboundMessage(
    body: RichText,
    blocks: List[Block] = Nil,
    visibility: Visibility = Visibility.Persistent,
    importance: Importance = Importance.Info,
    replaces: Option[MessageHandle] = None,
    deleteAfter: Option[Duration] = None,
    discreet: Boolean = false,
    silent: Boolean = false,
    dedupeKey: String,
    correlationId: String
) derives ReadWriter:
  require(dedupeKey.nonEmpty, "dedupeKey must not be empty")
  require(correlationId.nonEmpty, "correlationId must not be empty")

// ---------- Capability profile (DESIGN.md section 4.3) ----------

enum EditCapability derives ReadWriter:
  case AnyAge
  case Window(limit: Duration)
  case NoEdit

enum DeleteCapability derives ReadWriter:
  case AnyAge
  case Window(limit: Duration)
  case NoDelete

enum DmInitiation derives ReadWriter:
  case Always, AfterUserStart, SharedGuildOrInstall, Invite

enum Markup derives ReadWriter:
  case DiscordMd, TelegramHtml, ZulipMd, MatrixHtml, Plain

final case class CapabilityProfile(
    nativeCommands: Boolean,
    buttons: Boolean,
    select: Boolean,
    modal: Boolean,
    ephemeral: Boolean,
    editOwn: EditCapability,
    deleteOwn: DeleteCapability,
    botReactions: Boolean,
    reactionEvents: Boolean,
    transientAck: Boolean,
    deferrable: Boolean,
    ackDeadline: Option[Duration],
    polls: Boolean,
    silentDelivery: Boolean,
    canInitiateDm: DmInitiation,
    maxText: Int,
    maxChoicesPerRow: Int,
    maxRows: Int,
    callbackBudgetBytes: Option[Int],
    eventsPerMessageBudget: Int,
    markup: Markup
) derives ReadWriter:
  require(maxText > 0, "maxText must be positive")
  require(maxChoicesPerRow >= 0 && maxRows >= 0, "row budgets must be >= 0")
  require(eventsPerMessageBudget >= 1, "eventsPerMessageBudget must be >= 1")
