package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Reply
import dosegram.core.application.reports.ShareFacts
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.ShareCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ShareCardView

/** The shareable card in chat (VISUALS section 6.18, IMG-17; ROADMAP G12.11, manual-forward fallback). "Share a card"
  * on the seven-day `/stats` lists the facts of those days the user may share, each by the sentence its card says,
  * under the warning of VA-IMG-17b; a tap sends that card to the user's own chat to forward. In discreet mode a second
  * tap confirms first. Medication is never a fact, and with the gamification switch off the day counts are not offered
  * either (PRODUCT_SPEC R-GAM-5): only the week's mood is.
  */
private[capture] final class ShareChat(parts: CaptureParts, tokens: Tokens):
  import ShareChat.*

  private val shareFacts = ShareFacts(parts.stats)

  /** The button on the seven-day `/stats`, only where the card could be sent as an image. */
  def button(turn: Turn): Option[Choice] =
    Option.when(turn.tx.consents.isGranted(turn.accountId, ConsentPurpose.ImagesInChat)):
      Choice.callback(ShareCopy.Share, tokens.direct(CaptureActions.StatsShare, turn.accountId, 0))

  def picker(turn: Turn): Reply =
    facts(turn) match
      case Nil   => turn.toast(ShareCopy.nothing)
      case facts =>
        val rows = facts.map: (code, fact) =>
          List(Choice.callback(label(fact), tokens.direct(CaptureActions.StatsShareSend, turn.accountId, code.toLong)))
        turn.show(turn.message("stats.share", List(ShareCopy.pick), rows))

  /** The tap on a fact: `value` is its code, plus [[Confirmed]] once discreet mode was confirmed. */
  def send(turn: Turn, value: Long): Reply =
    val code = (value & CodeMask).toInt
    val confirmed = (value & Confirmed) != 0
    facts(turn).toMap.get(code) match
      case None                                          => turn.toast(ReminderCopy.staleControlToast)
      case Some(_) if turn.look.isDiscreet && !confirmed =>
        val again = Choice.callback(
          ShareCopy.SendAnyway,
          tokens.direct(CaptureActions.StatsShareSend, turn.accountId, code.toLong | Confirmed)
        )
        turn.show(turn.message("stats.share", List(ShareCopy.discreet), List(List(again))))
      case Some(_) if !turn.tx.consents.isGranted(turn.accountId, ConsentPurpose.ImagesInChat) =>
        turn.toast(StatsCardCopy.imageUnavailable)
      case Some(fact) =>
        parts.cards.share(fact, turn.look) match
          case Some(card) =>
            val caption = turn.message("stats.share_card", List(card.altText), Nil)
            Reply(followUps = turn.images(parts.delivery, List(card -> caption)))
          case None => turn.toast(StatsCardCopy.imageUnavailable)

  /** The facts of the last seven days, in a fixed order, each under the code its button carries. */
  private def facts(turn: Turn): List[(Int, ShareCardView)] = shareFacts.of(turn.tx, turn.accountId)

private[capture] object ShareChat:
  private val CodeMask = 0xfL
  private val Confirmed = 0x10L

  private def label(fact: ShareCardView): String = ShareFacts.label(fact)
end ShareChat
