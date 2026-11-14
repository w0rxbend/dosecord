package dosegram.core.application

import dosegram.contracts.Block
import dosegram.contracts.CallbackRef
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.Inline
import dosegram.contracts.MessageHandle
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.LanguageCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.Clock
import dosegram.core.ports.Tx

import java.util.UUID

/** The language choice in the chat (BOT_UX BUX-LANG-02; ROADMAP G2.22): `/language` and the "Language" entry of the
  * account menu show one button per shipped language; a tap stores the choice, which from then on wins over the
  * client's language, and the answer is written in the chosen language. The Mini App reads the same setting.
  */
final class LanguageChat(codec: CallbackCodec, clock: Clock, inner: ChatHandler) extends ChatHandler:

  import LanguageChat.*

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val answer = event.body match
      case Inbound.CommandInvoked(name, _, _) if name == CommandRegistry.Language.name =>
        Some(principal.accountId.fold(toast(IdentityCopy.createFirst))(account => picker(event, account.uuid, None)))
      case Inbound.InteractionSubmitted(ref, source) if isLanguageAction(ref) =>
        Some(
          principal.accountId
            .filter(_.uuid == ref.subject)
            .fold(stale)(account => tap(event, account.uuid, ref, source, tx))
        )
      case _ => None
    answer.getOrElse(inner.handle(event, principal, tx))

  private def tap(event: InboundEvent, account: UUID, ref: CallbackRef, source: Option[MessageHandle], tx: Tx): Reply =
    if ref.actionId == Open.id then picker(event, account, source)
    else
      Lang.values.lift(ref.value.toInt) match
        case Some(lang) => choose(event, account, lang, source, tx)
        case None       => stale

  private def choose(event: InboundEvent, account: UUID, lang: Lang, source: Option[MessageHandle], tx: Tx): Reply =
    val settings = tx.accounts.settingsOf(account).getOrElse(AccountSettings.Default)
    tx.accounts.saveSettings(account, settings.copy(locale = Some(lang.code)), clock.now())
    show(source, message(event, "language.changed", List(LanguageCopy.changed(lang)), Nil))

  private def picker(event: InboundEvent, account: UUID, source: Option[MessageHandle]): Reply =
    val choices = Lang.values.toList.map: lang =>
      Choice.callback(LanguageCopy.name(lang), codec.encode(CallbackMode.Direct, Choose, account, lang.ordinal).wire)
    show(source, message(event, "language.picker", List(LanguageCopy.prompt), choices))

  private def message(event: InboundEvent, kind: String, lines: List[String], choices: List[Choice]): OutboundMessage =
    OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks = Option.when(choices.nonEmpty)(Block.Choices(ChoiceSet(kind, choices = choices))).toList,
      dedupeKey = s"$kind:${event.vendor}:${event.vendorEventId}",
      correlationId = s"${event.vendor}:${event.vendorEventId}"
    )

  private def show(source: Option[MessageHandle], message: OutboundMessage): Reply =
    source match
      case Some(handle) => Reply(replace = Some(message.copy(replaces = Some(handle))))
      case None         => Reply(followUps = List(message))

  private def toast(text: String): Reply = Reply(toast = Some(text))
  private def stale: Reply = toast(ReminderCopy.staleControlToast)

object LanguageChat:
  val Open: ActionEntry = named("language.open")
  val Choose: ActionEntry = named("language.set")

  /** The callback of the account menu's "Language" entry. */
  def openToken(codec: CallbackCodec, account: UUID): String = codec.encode(CallbackMode.Direct, Open, account, 0).wire

  private def isLanguageAction(ref: CallbackRef): Boolean = ref.actionId == Open.id || ref.actionId == Choose.id

  private def named(name: String): ActionEntry =
    ActionRegistry.byName(name).getOrElse(throw IllegalStateException(s"action $name is not registered"))
