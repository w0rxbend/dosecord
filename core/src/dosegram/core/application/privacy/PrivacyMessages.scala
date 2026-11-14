package dosegram.core.application.privacy

import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.PrivacyCopy
import dosegram.core.ports.DataDomain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The buttons of the deletion (ActionRegistry ids 300 to 306). */
object PrivacyActions:
  val DeleteMenu: ActionEntry = named("privacy.delete_menu")
  val DeleteDomainAsk: ActionEntry = named("privacy.delete_domain_ask")
  val DeleteDomain: ActionEntry = named("privacy.delete_domain")
  val Keep: ActionEntry = named("privacy.keep")
  val DeleteAccountAsk: ActionEntry = named("privacy.delete_account_ask")
  val DeleteAccountConfirm: ActionEntry = named("privacy.delete_account_confirm")
  val DeleteAccount: ActionEntry = named("privacy.delete_account")

  val all: Set[ActionEntry] =
    Set(DeleteMenu, DeleteDomainAsk, DeleteDomain, Keep, DeleteAccountAsk, DeleteAccountConfirm, DeleteAccount)

  private def named(name: String): ActionEntry =
    ActionRegistry.byName(name).getOrElse(throw IllegalStateException(s"action '$name' is missing from the registry"))

/** What a confirmation button carries: the minute it was shown, whether the Mini App asked for it (the tap is then the
  * step-up of MINI_APP section 8.8), and its target, a domain or the account. A confirmation counts for ten minutes
  * (research brief 06 section 12.3).
  */
final case class Confirmation(shownMinute: Long, fromApp: Boolean, domain: Option[DataDomain]):
  def value: Long =
    (shownMinute << 4) | (if fromApp then 8L else 0L) | domain.fold(Confirmation.Account)(_.ordinal.toLong)

  def isFresh(now: Instant): Boolean =
    val shown = Instant.ofEpochSecond(shownMinute * 60)
    !shown.isAfter(now) && Duration.between(shown, now).compareTo(Confirmation.Lifetime) <= 0

object Confirmation:
  val Lifetime: Duration = Duration.ofMinutes(10)
  private val Account = 7L

  def shown(at: Instant, fromApp: Boolean, domain: Option[DataDomain]): Confirmation =
    Confirmation(at.getEpochSecond / 60, fromApp, domain)

  def of(value: Long): Option[Confirmation] =
    val target = value & 7L
    val domain = Option.when(target < DataDomain.values.length)(DataDomain.fromOrdinal(target.toInt))
    Option.when(domain.isDefined || target == Account)(Confirmation(value >> 4, (value & 8L) != 0, domain))

/** The messages of the deletion (BOT_UX section 20.3). The destructive button is never the first one (BUX-DEL-03).
  * `key` keys and correlates one message.
  */
final class PrivacyMessages(codec: CallbackCodec):
  import PrivacyActions.*

  /** "Delete data": one button per domain and "Delete my account" (BUX-DEL-01). */
  def menu(accountId: UUID, key: String): OutboundMessage =
    val domains =
      DataDomain.values.toList.map(d => List(button(accountId, PrivacyCopy.domainLabel(d), DeleteDomainAsk, d.ordinal)))
    message(
      "privacy.delete.menu",
      key,
      List(PrivacyCopy.menu),
      domains :+ List(danger(accountId, PrivacyCopy.DeleteMyAccount, DeleteAccountAsk, 0L))
    )

  def domainConfirm(accountId: UUID, confirmation: Confirmation, domain: DataDomain, key: String): OutboundMessage =
    val lines =
      if confirmation.fromApp then PrivacyCopy.domainConfirmFromApp(domain) else PrivacyCopy.domainConfirm(domain)
    message(
      "privacy.delete.domain",
      key,
      lines,
      List(
        List(button(accountId, PrivacyCopy.KeepTheData, Keep, 0L)),
        List(danger(accountId, PrivacyCopy.DeleteThisData, DeleteDomain, confirmation.value))
      )
    )

  /** The first confirmation of the account deletion: what is deleted, and "Continue". */
  def accountConfirm(accountId: UUID, confirmation: Confirmation, key: String): OutboundMessage =
    val lines = if confirmation.fromApp then PrivacyCopy.accountConfirmFromApp else PrivacyCopy.accountConfirm
    message(
      "privacy.delete.account",
      key,
      lines,
      List(
        List(button(accountId, PrivacyCopy.KeepMyAccount, Keep, 0L)),
        List(button(accountId, PrivacyCopy.Continue, DeleteAccountConfirm, confirmation.value))
      )
    )

  /** The second confirmation: the tap on its danger button deletes the account. */
  def accountFinal(accountId: UUID, confirmation: Confirmation, key: String): OutboundMessage =
    message(
      "privacy.delete.account.final",
      key,
      PrivacyCopy.accountFinal,
      List(
        List(button(accountId, PrivacyCopy.KeepMyAccount, Keep, 0L)),
        List(danger(accountId, PrivacyCopy.DeleteMyAccount, DeleteAccount, confirmation.value))
      )
    )

  def text(kind: String, key: String, line: String): OutboundMessage = message(kind, key, List(line), Nil)

  /** The "Delete data" button of the `/privacy` card. */
  def menuButton(accountId: UUID): Choice = button(accountId, PrivacyCopy.DeleteData, DeleteMenu, 0L)

  /** "Keep the data" and "Delete this data" under a withdrawal (BUX-CONS-06). */
  def afterWithdrawal(accountId: UUID, domain: DataDomain): List[List[Choice]] =
    List(
      List(button(accountId, PrivacyCopy.KeepTheData, Keep, 0L)),
      List(button(accountId, PrivacyCopy.DeleteThisData, DeleteDomainAsk, domain.ordinal.toLong))
    )

  private def button(accountId: UUID, label: String, action: ActionEntry, value: Long): Choice =
    Choice.callback(label, codec.encode(CallbackMode.Direct, action, accountId, value).wire)

  private def danger(accountId: UUID, label: String, action: ActionEntry, value: Long): Choice =
    button(accountId, label, action, value).copy(style = ChoiceStyle.Danger)

  private def message(kind: String, key: String, lines: List[String], rows: List[List[Choice]]): OutboundMessage =
    OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks = rows.zipWithIndex.map((row, index) => Block.Choices(ChoiceSet(s"$kind.$index", choices = row))),
      dedupeKey = s"$kind:$key",
      correlationId = key
    )
end PrivacyMessages
