package dosegram.core.application.capture

import dosegram.contracts.Choice
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.Reply
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.ports.CardPage
import dosegram.core.ports.Clock
import dosegram.core.ports.DoseMark
import dosegram.core.ports.MedicationStripRow
import dosegram.core.ports.MedicationStripView
import dosegram.core.ports.OccurrenceOrigin
import dosegram.core.ports.StoredOccurrence
import dosegram.core.ports.Tx

import java.time.LocalDate
import java.util.UUID

/** `/stats meds` (supervisor decision, 2026-11-27): each medication of the registry over the last 7 or 14 days, in
  * registry order, as text first and the per-medication strip (VISUALS IMG-07) on request. Text and image come from the
  * same doses at the same moment (BUX-STAT-01). The chat draws no name into the image (L1): its rows are numbered as
  * the text rows are.
  */
private[capture] final class MedsChat(parts: CaptureParts, clock: Clock, tokens: Tokens):

  def card(turn: Turn, days: Int): Reply =
    MedsChat.daysOf(days) match
      case None         => turn.toast(StatsCardCopy.usage)
      case Some(window) =>
        val view = MedsChat.view(turn.tx, turn.accountId, today(turn), window)
        val periods = MedsChat.Periods.map: value =>
          Choice.callback(
            StatsCardCopy.medsPeriod(value.toInt),
            tokens.direct(CaptureActions.StatsMeds, turn.accountId, value)
          )
        val image = Option.when(view.rows.nonEmpty)(
          Choice.callback(
            StatsCardCopy.ShowAsImage,
            tokens.direct(CaptureActions.StatsMedsImage, turn.accountId, window.toLong)
          )
        )
        turn.show(turn.message("stats.meds", MedsChat.lines(view), List(periods, image.toList)))

  /** The strip of the same days as new messages, one per page, each captioned with its text equivalent. */
  def image(turn: Turn, days: Int): Reply =
    val pages = MedsChat
      .daysOf(days)
      .map(window => MedsChat.view(turn.tx, turn.accountId, today(turn), window))
      .filter(_.rows.nonEmpty)
      .fold(List.empty[CardPage])(parts.cards.medicationStrip(_, turn.look))
    if pages.isEmpty then turn.toast(StatsCardCopy.imageUnavailable)
    else
      Reply(followUps =
        turn.images(
          parts.delivery,
          pages.zipWithIndex.map: (page, number) =>
            val caption = turn.message(s"stats.meds.page${number + 1}", Nil, Nil)
            page -> caption.copy(body = List(Node.Paragraph(List(Inline.Text(page.altText)))))
        )
      )

  private def today(turn: Turn): LocalDate = clock.now().atZone(turn.zone).toLocalDate

private[capture] object MedsChat:

  /** The periods of `/stats meds`: the last 7 or 14 days, today included (VISUALS section 6.8). */
  val Periods: List[Long] = List(7L, 14L)

  val DefaultDays = 7

  /** `/stats meds`, the argument that opens the view. */
  val Argument = "meds"

  def daysOf(days: Long): Option[Int] = Option.when(Periods.contains(days))(days.toInt)

  /** The registry's medications over `days` ending on `today`: each day's scheduled doses in schedule order and its
    * as-needed logs. A cancelled dose is no dose; a medication is taken as needed when its schedule says so.
    */
  def view(tx: Tx, accountId: UUID, today: LocalDate, days: Int): MedicationStripView =
    val from = today.minusDays(days.toLong - 1)
    val dates = (0 until days).toList.map(offset => from.plusDays(offset.toLong))
    val occurrences = tx.occurrences
      .listForAccountBetween(accountId, from, today)
      .filter(_.status != OccurrenceStatus.Cancelled)
      .groupBy(_.medicationId)
    val rows = tx.medications
      .listForAccount(accountId)
      .map: medication =>
        val own = occurrences.getOrElse(medication.id, Nil).groupBy(_.localDate)
        val asNeeded = tx.schedules
          .listForMedication(medication.id)
          .headOption
          .exists(schedule => schedule.kind == "as_needed" && schedule.status.dbValue == "active")
        val perDay = dates.map(date => own.getOrElse(date, Nil).sortBy(_.scheduledFor))
        MedicationStripRow(
          medication.name,
          asNeeded,
          perDay.map(_.filter(_.origin != OccurrenceOrigin.Manual).flatMap(DoseMarks.markOf)),
          perDay.map(_.count(isLog))
        )
    MedicationStripView(from, days, rows)

  /** The text of the view: the period, one line per medication, and what the numbers are. */
  def lines(view: MedicationStripView): List[String] =
    val title = StatsCardCopy.medsTitle(view.dayCount, view.from.plusDays(view.dayCount.toLong - 1))
    if view.rows.isEmpty then List(title, StatsCardCopy.medsNone)
    else
      val rows = view.rows.zipWithIndex.map((row, index) => line(index + 1, row))
      (title :: rows) :+ StatsCardCopy.medsNote

  private def line(position: Int, row: MedicationStripRow): String =
    if row.asNeeded then StatsCardCopy.medsAsNeededRow(position, row.name, row.logs.sum, row.logs.count(_ > 0))
    else
      val marks = row.days.flatten
      def count(wanted: DoseMark*): Int = marks.count(wanted.contains)
      val tally = StatsCardCopy.MedsTally(
        taken = count(DoseMark.TakenOnTime, DoseMark.TakenLate),
        skipped = count(DoseMark.Skipped),
        missed = count(DoseMark.NotConfirmed),
        open = count(DoseMark.Open),
        noRecord = count(DoseMark.NoRecord)
      )
      StatsCardCopy.medsRow(position, row.name, tally)

  private def isLog(occurrence: StoredOccurrence): Boolean =
    occurrence.origin == OccurrenceOrigin.Manual && occurrence.status == OccurrenceStatus.Taken
