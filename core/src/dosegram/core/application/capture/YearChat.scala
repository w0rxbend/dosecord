package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.Reply
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.copy.YearCopy
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.YearFigures
import dosegram.core.ports.Clock

/** `/stats year` and its one-year card (supervisor decision, 2026-11-28, analytics round 6): the text first, one tap to
  * the year before when it has a record or to the next year up to the current one, and the card on request. Text and
  * image come from the same records at the same moment (BUX-STAT-01).
  */
private[capture] final class YearChat(parts: CaptureParts, clock: Clock, tokens: Tokens):

  def currentYear(turn: Turn): Int = clock.now().atZone(turn.zone).getYear

  def year(turn: Turn, year: Int): Reply =
    parts.stats.year(turn.tx, turn.accountId, year) match
      case Left(_)     => turn.toast(ReminderCopy.staleControlToast)
      case Right(view) =>
        val figures = view.figures
        val shown = figures.year
        val previous = Option.when(figures.previousHasRecords)(
          Choice.callback(YearCopy.PreviousYear(shown - 1), token(turn, CaptureActions.StatsYear, shown - 1))
        )
        val next = Option.when(shown < view.today.getYear)(
          Choice.callback(YearCopy.NextYear(shown + 1), token(turn, CaptureActions.StatsYear, shown + 1))
        )
        val image = Option.when(YearChat.drawable(figures))(
          Choice.callback(StatsCardCopy.ShowAsImage, token(turn, CaptureActions.StatsYearImage, shown))
        )
        val text =
          if turn.tx.accounts.gamificationOf(turn.accountId) then YearCopy.lines(figures, view.zone)
          else YearCopy.linesOmittingGates(figures, view.zone)
        turn.show(turn.message("stats.year", text, List(previous.toList ++ next.toList, image.toList)))

  /** The one-year card as a new message, captioned with its text equivalent. */
  def yearImage(turn: Turn, year: Int): Reply =
    parts.stats
      .year(turn.tx, turn.accountId, year)
      .toOption
      .flatMap(view => parts.cards.year(view.figures, turn.look)) match
      case Some(card) =>
        val caption = turn.message("stats.year.image", Nil, Nil)
        val page = card -> caption.copy(body = List(Node.Paragraph(List(Inline.Text(card.altText)))))
        Reply(followUps = turn.images(parts.delivery, List(page)))
      case None => turn.toast(StatsCardCopy.imageUnavailable)

  private def token(turn: Turn, action: dosegram.core.chat.ActionEntry, year: Int): String =
    tokens.direct(action, turn.accountId, year.toLong)

private[capture] object YearChat:

  /** `/stats year`. */
  val Argument = "year"

  /** The card draws water, food, sleep and mood, never medication (VISUALS section 7.3, R-GAM-2). */
  val DrawnAreas: Set[TrendDomain] =
    Set(TrendDomain.Hydration, TrendDomain.Nutrition, TrendDomain.Sleep, TrendDomain.Mood)

  def drawable(figures: YearFigures): Boolean =
    figures.recorded.exists((domain, days) => DrawnAreas(domain) && days.nonEmpty)
