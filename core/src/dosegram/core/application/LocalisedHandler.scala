package dosegram.core.application

import dosegram.contracts.InboundEvent
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.Lang
import dosegram.core.ports.Tx

/** Binds the language of one inbound event around the whole handler composition (PRODUCT_SPEC R-LOC-1, ROADMAP G2.22):
  * the account's stored locale, otherwise the locale the sender's client names, otherwise English. Every catalogue
  * entry the handlers produce for the event is then in that language.
  */
final class LocalisedHandler(inner: ChatHandler) extends ChatHandler:
  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val stored = principal.accountId.flatMap(id => tx.accounts.settingsOf(id.uuid)).flatMap(_.locale)
    // The stored code, not `languageOf`: an account without a choice still takes the client's language.
    Lang.within(Lang.resolve(stored, event.localeHint))(inner.handle(event, principal, tx))
