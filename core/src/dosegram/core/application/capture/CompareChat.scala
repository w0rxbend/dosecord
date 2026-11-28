package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.Reply
import dosegram.core.application.insights.CompareCards
import dosegram.core.domain.copy.CompareCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.insights.CompareSelection
import dosegram.core.ports.Clock

import java.time.YearMonth

/** `/stats compare` and its card (supervisor decision, 2026-11-28): two periods side by side in text first, one tap to
  * the other fixed pair or to the month picker, and the card on request. Text and image come from the same records at
  * the same moment (BUX-STAT-01).
  */
private[capture] final class CompareChat(parts: CaptureParts, clock: Clock, tokens: Tokens):

  def compare(turn: Turn, selection: CompareSelection): Reply =
    parts.stats.compare(turn.tx, turn.accountId, selection) match
      case Left(_)     => turn.toast(ReminderCopy.staleControlToast)
      case Right(view) =>
        val keepGates = turn.tx.accounts.gamificationOf(turn.accountId)
        val choices = List(
          Choice.callback(CompareCopy.ThisWeek, token(turn, CaptureActions.StatsCompare, CompareSelection.Weeks.code)),
          Choice.callback(CompareCopy.ThisMonth, token(turn, CaptureActions.StatsCompare, CompareSelection.Months.code))
        )
        val pick = Choice.callback(CompareCopy.PickMonths, token(turn, CaptureActions.StatsComparePick, 0))
        val image = Option.when(CompareCards.drawable(view.figures))(
          Choice.callback(StatsCardCopy.ShowAsImage, token(turn, CaptureActions.StatsCompareImage, selection.code))
        )
        val text = CompareCopy.lines(view.figures, view.zone, keepGates)
        turn.show(turn.message("stats.compare", text, List(choices, List(pick), image.toList)))

  /** The tap on a pair; the value is the selection's code. */
  def show(turn: Turn, code: Long): Reply =
    CompareSelection.of(code).fold(turn.toast(ReminderCopy.staleControlToast))(compare(turn, _))

  /** The month picker: value 0 lists the first month, a month's index lists the second against it. The months are the
    * current one and the eleven before it, newest first.
    */
  def pick(turn: Turn, value: Long): Reply =
    val months = CompareSelection.pickable(YearMonth.from(clock.now().atZone(turn.zone)))
    if value == 0 then
      val choices = months.map(month =>
        Choice.callback(
          CompareCopy.monthLabel(month),
          token(turn, CaptureActions.StatsComparePick, MonthAndInsightsChat.indexOf(month))
        )
      )
      turn.show(turn.message("stats.compare.pick", List(CompareCopy.pickFirst), choices.grouped(3).toList))
    else
      MonthAndInsightsChat.monthOf(value.toInt).filter(months.contains) match
        case None        => turn.toast(ReminderCopy.staleControlToast)
        case Some(first) =>
          val choices = months
            .flatMap(second => CompareSelection.twoMonths(first, second).map(second -> _))
            .map((second, selection) =>
              Choice.callback(CompareCopy.monthLabel(second), token(turn, CaptureActions.StatsCompare, selection.code))
            )
          turn.show(turn.message("stats.compare.pick", List(CompareCopy.pickSecond(first)), choices.grouped(3).toList))

  /** IMG-27 as a new message, captioned with its text equivalent. */
  def image(turn: Turn, selection: CompareSelection): Reply =
    parts.stats
      .compare(turn.tx, turn.accountId, selection)
      .toOption
      .flatMap(CompareCards.of)
      .flatMap(parts.cards.compare(_, turn.look)) match
      case Some(card) =>
        val caption = turn.message("stats.compare.image", Nil, Nil)
        val page = card -> caption.copy(body = List(Node.Paragraph(List(Inline.Text(card.altText)))))
        Reply(followUps = turn.images(parts.delivery, List(page)))
      case None => turn.toast(StatsCardCopy.imageUnavailable)

  private def token(turn: Turn, action: dosegram.core.chat.ActionEntry, value: Int): String =
    tokens.direct(action, turn.accountId, value.toLong)

private[capture] object CompareChat:

  /** `/stats compare`, in either shipped language. */
  val Arguments: Set[String] = Set("compare", "порівняння")
