package dosegram.core.application.insights

import dosegram.core.domain.copy.RhythmCopy
import dosegram.core.ports.RhythmCardRow
import dosegram.core.ports.RhythmCardView

/** IMG-28 from the figures of the rhythm (supervisor decision, 2026-11-28). The card draws every recorded-day count
  * (VISUALS VA-ME-02), so its text equivalent keeps the counts whatever the counts switch says: every number the card
  * draws is in its text (VA-PR-01).
  */
object RhythmCards:

  /** The card of `view`; `None` when no shown area has met its gate. */
  def of(view: RhythmView): Option[RhythmCardView] =
    val figures = view.figures
    Option.when(figures.hasPattern):
      val rows = figures.rows.map: row =>
        val pattern = row.pattern.shown
        RhythmCardRow(
          row.domain,
          pattern.map(_.band.counts.toList),
          pattern.fold(RhythmCopy.belowGate(row.domain, row.recordedDays))(RhythmCopy.cardSummary),
          RhythmCopy.recordedDays(row.domain, row.recordedDays)
        )
      RhythmCardView(figures.window, rows, RhythmCopy.lines(figures, view.zone, keepGates = true).mkString("\n"))
end RhythmCards
