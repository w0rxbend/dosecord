package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.Reply
import dosegram.core.application.insights.ComparisonWindow
import dosegram.core.application.insights.ReportViews
import dosegram.core.domain.copy.InsightCopy
import dosegram.core.domain.copy.MonthCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.ReportCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.Insight
import dosegram.core.ports.Clock

import java.time.LocalDate
import java.time.YearMonth

/** The month in `/stats` and the `/insights` card (BOT_UX section 12.5; VISUALS_AND_ANALYTICS sections 6.6 and 8.5):
  * the text first, one tap to another month, and the pages or the pattern card on request. Text and images come from
  * the same records at the same moment (BUX-STAT-01).
  */
private[capture] final class MonthAndInsightsChat(parts: CaptureParts, clock: Clock, tokens: Tokens):

  private val views = ReportViews(parts.stats)

  /** The text of a month; the current month is the month so far. */
  def month(turn: Turn, index: Int): Reply =
    val month = MonthAndInsightsChat.monthOf(index)
    parts.stats.month(turn.tx, turn.accountId, month) match
      case Left(_)     => turn.toast(ReminderCopy.staleControlToast)
      case Right(view) =>
        val current = YearMonth.from(today(turn))
        val previous = Choice.callback(ReportCopy.PreviousMonth, monthToken(turn, CaptureActions.StatsMonth, index - 1))
        val next = Option.when(month.isBefore(current))(
          Choice.callback(ReportCopy.NextMonth, monthToken(turn, CaptureActions.StatsMonth, index + 1))
        )
        val image = Option.when(view.figures.isReportable)(
          Choice.callback(StatsCardCopy.ShowAsImage, monthToken(turn, CaptureActions.StatsMonthImage, index))
        )
        val rows = List(previous :: next.toList, image.toList)
        turn.show(turn.message("stats.month", MonthCopy.lines(view.figures, view.zone), rows))

  /** The pages of a month as new messages, each with its own caption. */
  def monthImage(turn: Turn, index: Int): Reply =
    parts.stats.month(turn.tx, turn.accountId, MonthAndInsightsChat.monthOf(index)) match
      case Left(_)                                   => turn.toast(ReminderCopy.staleControlToast)
      case Right(view) if !view.figures.isReportable => turn.toast(ReportCopy.imageUnavailable)
      case Right(view)                               =>
        val pages = parts.cards.month(views.month(turn.tx, turn.accountId, view.figures, today(turn)), turn.look)
        if pages.isEmpty then turn.toast(ReportCopy.imageUnavailable)
        else
          Reply(followUps =
            turn.images(
              parts.delivery,
              pages.zipWithIndex.map { (page, number) =>
                val caption = turn.message(s"stats.month.page${number + 1}", Nil, Nil)
                page -> caption.copy(body = List(Node.Paragraph(List(Inline.Text(page.altText)))))
              }
            )
          )

  /** `/insights`: the patterns that passed, the rules close to their data, and the controls. */
  def insights(turn: Turn): Reply =
    parts.stats.insights(turn.tx, turn.accountId, ComparisonWindow.Days28) match
      case Left(_)     => turn.toast(ReminderCopy.staleControlToast)
      case Right(view) =>
        val image = Option.when(drawable(view.list.shown).nonEmpty)(
          Choice.callback(
            StatsCardCopy.ShowAsImage,
            tokens.direct(CaptureActions.InsightsImage, turn.accountId, 0)
          )
        )
        val compare = Option.unless(view.crossDomain)(
          Choice.callback(
            InsightCopy.CompareAcrossAreas,
            tokens.direct(CaptureActions.InsightsCompare, turn.accountId, 0)
          )
        )
        turn.show(
          turn.message(
            "insights.card",
            InsightCopy.lines(view.list, view.crossDomain),
            List(image.toList ++ compare.toList)
          )
        )

  /** The pattern card of the first pattern in rank order that the card can draw. */
  def insightImage(turn: Turn): Reply =
    parts.stats.insights(turn.tx, turn.accountId, ComparisonWindow.Days28).toOption.flatMap { view =>
      drawable(view.list.shown).iterator.flatMap(parts.cards.insight(_, turn.look)).nextOption()
    } match
      case Some(card) =>
        val caption = turn.message("insights.image", Nil, Nil)
        val page = card -> caption.copy(body = List(Node.Paragraph(List(Inline.Text(card.altText)))))
        Reply(followUps = turn.images(parts.delivery, List(page)))
      case None => turn.toast(InsightCopy.imageUnavailable)

  private def today(turn: Turn): LocalDate = clock.now().atZone(turn.zone).toLocalDate

  /** The current month's button value. */
  def currentIndex(turn: Turn): Int = MonthAndInsightsChat.indexOf(YearMonth.from(today(turn)))

  private def drawable(shown: List[Insight]): List[Comparison.Shown] =
    shown.collect { case Insight.Pattern(comparison, _) => comparison }

  private def monthToken(turn: Turn, action: dosegram.core.chat.ActionEntry, index: Int): String =
    tokens.direct(action, turn.accountId, index.toLong)

private[capture] object MonthAndInsightsChat:

  /** A month as the value of a button: `year * 12 + month - 1`. */
  def indexOf(month: YearMonth): Int = month.getYear * 12 + month.getMonthValue - 1

  def monthOf(index: Int): YearMonth = YearMonth.of(index / 12, index % 12 + 1)
