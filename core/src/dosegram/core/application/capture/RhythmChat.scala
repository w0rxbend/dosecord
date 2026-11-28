package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.Reply
import dosegram.core.application.insights.RhythmCards
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.RhythmCopy
import dosegram.core.domain.copy.StatsCardCopy

/** `/stats rhythm` and its card (supervisor decision, 2026-11-28): when in the local day the records of each shown area
  * fall over the last 30 days, in text first, and the card on request. Text and image come from the same records at the
  * same moment (BUX-STAT-01).
  */
private[capture] final class RhythmChat(parts: CaptureParts, tokens: Tokens):

  def rhythm(turn: Turn): Reply =
    parts.stats.rhythm(turn.tx, turn.accountId) match
      case Left(_)     => turn.toast(ReminderCopy.staleControlToast)
      case Right(view) =>
        val keepGates = turn.tx.accounts.gamificationOf(turn.accountId)
        val image = Option.when(view.figures.hasPattern)(
          Choice.callback(StatsCardCopy.ShowAsImage, tokens.direct(CaptureActions.StatsRhythmImage, turn.accountId, 0))
        )
        val text = RhythmCopy.lines(view.figures, view.zone, keepGates)
        turn.show(turn.message("stats.rhythm", text, List(image.toList)))

  /** IMG-28 as a new message, captioned with its text equivalent. */
  def image(turn: Turn): Reply =
    parts.stats
      .rhythm(turn.tx, turn.accountId)
      .toOption
      .flatMap(RhythmCards.of)
      .flatMap(parts.cards.rhythm(_, turn.look)) match
      case Some(card) =>
        val caption = turn.message("stats.rhythm.image", Nil, Nil)
        val page = card -> caption.copy(body = List(Node.Paragraph(List(Inline.Text(card.altText)))))
        Reply(followUps = turn.images(parts.delivery, List(page)))
      case None => turn.toast(StatsCardCopy.imageUnavailable)

private[capture] object RhythmChat:

  /** `/stats rhythm`, in either shipped language. */
  val Arguments: Set[String] = Set("rhythm", "ритм")
