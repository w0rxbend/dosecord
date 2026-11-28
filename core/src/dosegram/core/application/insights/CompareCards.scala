package dosegram.core.application.insights

import dosegram.core.domain.copy.CompareCopy
import dosegram.core.domain.insights.CompareFigures
import dosegram.core.domain.insights.ComparedPeriod
import dosegram.core.domain.insights.PeriodComparisons
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.YearCell
import dosegram.core.ports.CompareCardCell
import dosegram.core.ports.CompareCardColumn
import dosegram.core.ports.CompareCardRow
import dosegram.core.ports.CompareCardView

/** IMG-27 from the figures of a comparison (supervisor decision, 2026-11-28). The card draws every recorded-day count
  * (VISUALS VA-ME-02), so its text equivalent keeps the counts whatever the counts switch says: every number the card
  * draws is in its text (VA-PR-01).
  */
object CompareCards:

  /** The card of `view`; `None` when no shown area has a record in either period. */
  def of(view: CompareView): Option[CompareCardView] =
    val figures = view.figures
    Option.when(drawable(figures)):
      CompareCardView(
        column(figures.frame.earlier),
        column(figures.frame.later),
        figures.rows.map(row =>
          CompareCardRow(
            row.domain,
            cell(row.domain, row.earlier),
            cell(row.domain, row.later),
            row.difference.map(CompareCopy.difference(row.domain, _))
          )
        ),
        CompareCopy.lines(figures, view.zone, keepGates = true).mkString("\n")
      )

  /** Whether the comparison has a card: what "Show as image" is offered for. */
  def drawable(figures: CompareFigures): Boolean = figures.rows.nonEmpty && figures.hasRecords

  private def column(period: ComparedPeriod): CompareCardColumn =
    CompareCardColumn(CompareCopy.label(period.name), period.window)

  private def cell(domain: TrendDomain, cell: YearCell): CompareCardCell =
    val shown = cell.figure.shown
    CompareCardCell(
      shown.map(CompareCopy.cardFigure),
      shown.map(PeriodComparisons.valueOf),
      CompareCopy.recordedDays(domain, cell.recordedDays)
    )
end CompareCards
