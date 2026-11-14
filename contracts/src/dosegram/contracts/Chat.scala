package dosegram.contracts

import upickle.default.ReadWriter
import upickle.default.readwriter

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

/** A callback already decoded and MAC-verified by the mediator (ADR-006); the mediator re-verifies `raw` against the
  * claimed fields before anything reaches the core. The decoded form never carries the MAC itself.
  */
final case class CallbackRef(actionId: Int, subject: UUID, value: Long, slot: Boolean, raw: String) derives ReadWriter:
  require(actionId >= 0 && actionId <= 0xffff, "action id is UInt16")
  require(value >= 0, "value is unsigned")
  require(raw.nonEmpty, "raw token must not be empty")

/** The shared grammar of deep-link payloads and Mini App routes: 1 to 64 characters of `A-Z a-z 0-9 _ -`. */
private[contracts] object LinkGrammar:
  val MaxChars = 64

  def fits(text: String): Boolean =
    text.nonEmpty && text.length <= MaxChars && text.forall(allowed)

  private def allowed(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'

/** The payload of the deep link that started a conversation (ADR-015). It is attacker-controlled: it selects a screen
  * or carries an opaque single-use token, never an identifier that grants access.
  */
final case class StartPayload(value: String) derives ReadWriter:
  require(LinkGrammar.fits(value), "start payload must be 1-64 chars of A-Z a-z 0-9 _ -")

object StartPayload:
  /** For untrusted vendor text: `None` when it is outside the grammar. */
  def parse(text: String): Option[StartPayload] =
    Option.when(LinkGrammar.fits(text))(StartPayload(text))

enum MediaKind derives ReadWriter:
  case Photo, Document

/** An opaque handle to media a user sent; the bytes stay with the vendor until an adapter fetches them. */
final case class MediaRef(mediaId: String) derives ReadWriter:
  require(mediaId.nonEmpty, "mediaId must not be empty")

enum Inbound derives ReadWriter:
  case MessageReceived(text: String, replyTo: Option[MessageHandle], truncated: Boolean)
  case CommandInvoked(name: String, args: Map[String, String], raw: String)
  case InteractionSubmitted(callback: CallbackRef, source: Option[MessageHandle])
  case FormSubmitted(formId: String, callback: CallbackRef, fields: Map[String, String])
  case ConversationStarted(payload: Option[StartPayload])
  case MediaReceived(kind: MediaKind, media: MediaRef, caption: Option[String])
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
    body: Inbound,
    // The shipped locale the sender's client names, for a sender whose account has not chosen one (PRODUCT_SPEC
    // R-LOC-1). Optional and possibly stale; the account's own setting always wins.
    localeHint: Option[String] = None,
    // The live vendor handle the mediator acks through (DESIGN.md section 4.1); never serialised — see the wire
    // surrogate in the companion.
    interaction: Option[InteractionHandle] = None
):
  require(vendor.nonEmpty, "vendor must not be empty")
  require(vendorEventId.nonEmpty, "vendorEventId must not be empty")

object InboundEvent:
  private final case class Wire(
      eventId: EventId,
      vendor: String,
      vendorEventId: String,
      receivedAt: Instant,
      createdAt: Option[Instant],
      actor: PlatformIdentity,
      chat: ChatRef,
      cursor: Option[String],
      principal: Option[Principal],
      body: Inbound,
      localeHint: Option[String] = None
  ) derives ReadWriter

  given ReadWriter[InboundEvent] = readwriter[Wire].bimap(
    e =>
      Wire(
        e.eventId,
        e.vendor,
        e.vendorEventId,
        e.receivedAt,
        e.createdAt,
        e.actor,
        e.chat,
        e.cursor,
        e.principal,
        e.body,
        e.localeHint
      ),
    w =>
      InboundEvent(
        w.eventId,
        w.vendor,
        w.vendorEventId,
        w.receivedAt,
        w.createdAt,
        w.actor,
        w.chat,
        w.cursor,
        w.principal,
        w.body,
        localeHint = w.localeHint
      )
  )

/** The live handle of one tapped control (ADR-015): a single-shot answer and an edit of the message that carried the
  * control. Opaque to the core; the mediator acks through it. Not serialisable by design, so it is the one adapter-API
  * type excluded from the ReadWriter contract test.
  */
trait InteractionHandle:
  /** Single shot: the first answer wins and later calls are no-ops. */
  def answer(toast: Option[String]): Unit
  def editSource(rendered: RenderedMessage): Unit

  /** Whether the single answer is already spent. */
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

/** An AST, never a vendor string (DESIGN.md section 4.2); no headings, tables or images. An image travels beside the
  * text as an [[AttachmentRef]].
  */
type RichText = List[Node]

/** Product limits on tappable controls (ADR-015). They are our own bounds, not platform maxima. */
object ControlLimits:
  val MaxLabelChars = 32
  val MaxButtonsPerRow = 8
  val MaxButtonsPerMessage = 24

  /** Shortens a label built from user text (a medication name) to the label limit. */
  def fitLabel(text: String): String =
    if text.length <= MaxLabelChars then text else text.take(MaxLabelChars - 1) + "…"

enum ChoiceStyle derives ReadWriter:
  case Primary, Secondary, Danger, Success

/** A Mini App route (MINI_APP section 3.4): `<screen>` or `<screen>_<argument>`. The contract checks the grammar only;
  * the screen codes belong to the Mini App.
  */
final case class AppRoute(wire: String) derives ReadWriter:
  require(LinkGrammar.fits(wire), "app route must be 1-64 chars of A-Z a-z 0-9 _ -")

/** What a tapped choice does (ADR-015). Only `Callback` comes back as an inbound event; the other two open something on
  * the user's device and the bot never hears of the tap.
  */
enum ChoiceAction derives ReadWriter:
  /** `token` is the wire form of a CallbackToken (core/chat) — a 49-char `dc:` string, kept as a plain string here so
    * contracts never depends on core.
    */
  case Callback(token: String)
  case OpenApp(route: AppRoute)
  case OpenUrl(url: String)

final case class Choice(
    label: String,
    action: ChoiceAction,
    style: ChoiceStyle = ChoiceStyle.Secondary,
    emoji: Option[String] = None,
    hotkeys: List[String] = Nil
) derives ReadWriter:
  require(
    label.nonEmpty && label.length <= ControlLimits.MaxLabelChars,
    s"choice label must be 1-${ControlLimits.MaxLabelChars} chars"
  )
  action match
    case ChoiceAction.OpenUrl(url) => require(url.startsWith("https://"), "a URL choice opens https links only")
    case _                         => ()

object Choice:
  def callback(label: String, token: String, style: ChoiceStyle = ChoiceStyle.Secondary): Choice =
    Choice(label, ChoiceAction.Callback(token), style)

  def openApp(label: String, route: AppRoute): Choice = Choice(label, ChoiceAction.OpenApp(route))

  def openUrl(label: String, url: String): Choice = Choice(label, ChoiceAction.OpenUrl(url))

/** `numberHotkeys = false` marks a set on a prompt that takes typed input (ADR-015 addendum 2): a typed number there is
  * the user's input ("9" is a time), never the number of a choice. Its choices are still tapped, or typed by label.
  */
final case class ChoiceSet(
    id: String,
    prompt: Option[RichText] = None,
    choices: List[Choice],
    minSelect: Int = 1,
    maxSelect: Int = 1,
    ttl: Option[Duration] = None,
    numberHotkeys: Boolean = true
) derives ReadWriter:
  require(id.nonEmpty, "choice set id must not be empty")
  require(
    choices.nonEmpty && choices.length <= ControlLimits.MaxButtonsPerMessage,
    s"1-${ControlLimits.MaxButtonsPerMessage} choices"
  )
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
    options: List[Choice] = Nil,
    /** Pre-filled value (M1.9: a form step re-rendered after `[Back]` carries the previous answers). */
    value: Option[String] = None
) derives ReadWriter:
  require(key.nonEmpty, "field key must not be empty")

final case class Form(id: String, title: String, fields: List[Field], submit: String) derives ReadWriter:
  require(id.nonEmpty, "form id must not be empty")
  require(title.nonEmpty, "form title must not be empty")
  require(fields.nonEmpty, "a form has at least one field")

enum NoticeLevel derives ReadWriter:
  case Info, Success, Warning

final case class Notice(level: NoticeLevel, text: RichText) derives ReadWriter

enum Block derives ReadWriter:
  case Choices(cs: ChoiceSet)
  case FormBlock(f: Form)
  case NoticeBlock(n: Notice)

/** Bytes the core never holds, sent with a message (ADR-015). `assetKey` is opaque: the adapter resolves it through the
  * asset store and keys its upload cache by it. `altText` is the text equivalent that every image has; a profile
  * without media shows it in place of the image. `hasSpoiler` asks a profile that can blur an image until it is tapped
  * to do so (discreet mode, BOT_UX BUX-IMG-05); it is a preview control, not protection.
  */
final case class AttachmentRef(
    kind: MediaKind,
    assetKey: String,
    mimeType: String,
    altText: String,
    fileName: Option[String] = None,
    hasSpoiler: Boolean = false
) derives ReadWriter:
  require(assetKey.nonEmpty, "assetKey must not be empty")
  require(mimeType.nonEmpty, "mimeType must not be empty")
  require(
    altText.nonEmpty && altText.length <= AttachmentRef.MaxAltTextChars,
    s"altText must be 1-${AttachmentRef.MaxAltTextChars} chars"
  )

object AttachmentRef:
  val MaxAltTextChars = 1024

enum Importance derives ReadWriter:
  case InteractionReply, Reminder, Info, Bulk

final case class OutboundMessage(
    body: RichText,
    blocks: List[Block] = Nil,
    importance: Importance = Importance.Info,
    replaces: Option[MessageHandle] = None,
    deleteAfter: Option[Duration] = None,
    discreet: Boolean = false,
    silent: Boolean = false,
    /** The message body is the caption. A message with an attachment is always a new message, never an edit. */
    attachment: Option[AttachmentRef] = None,
    dedupeKey: String,
    correlationId: String
) derives ReadWriter:
  require(dedupeKey.nonEmpty, "dedupeKey must not be empty")
  require(correlationId.nonEmpty, "correlationId must not be empty")

object OutboundMessage:
  /** Serialisation lives in contracts so the core stays free of a JSON library. */
  def toJson(message: OutboundMessage): String = upickle.default.write(message)
  def fromJson(json: String): OutboundMessage = upickle.default.read[OutboundMessage](json)

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
  case Always, AfterUserStart

enum Markup derives ReadWriter:
  case TelegramHtml, Plain

final case class CapabilityProfile(
    nativeCommands: Boolean,
    buttons: Boolean,
    /** Photo and document messages; without it the renderer shows the attachment's text equivalent. */
    media: Boolean,
    editOwn: EditCapability,
    deleteOwn: DeleteCapability,
    transientAck: Boolean,
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
  require(
    maxChoicesPerRow >= 0 && maxChoicesPerRow <= ControlLimits.MaxButtonsPerRow && maxRows >= 0,
    s"row budgets must be >= 0 and a row holds at most ${ControlLimits.MaxButtonsPerRow} buttons"
  )
  require(eventsPerMessageBudget >= 1, "eventsPerMessageBudget must be >= 1")

// ---------- Handler reply (DESIGN.md section 4.6 step 7) ----------

/** What a core handler returns for one inbound event. Stored as the `inbound_events.reply` document and replayed
  * verbatim on a duplicate delivery (C3); `sessionPatch` arrives with the wizard engine (M0.12b).
  */
final case class Reply(
    replace: Option[OutboundMessage] = None,
    followUps: List[OutboundMessage] = Nil,
    toast: Option[String] = None,
    domainEvents: List[Event] = Nil
) derives ReadWriter

object Reply:
  val empty: Reply = Reply()

  /** Serialisation lives in contracts so the core stays free of a JSON library. */
  def toJson(reply: Reply): String = upickle.default.write(reply)
  def fromJson(json: String): Reply = upickle.default.read[Reply](json)
