package dosegram.core.scheduling

import dosegram.core.application.insights.ReportComposer
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.reminders.NudgeSlots
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CardPage
import dosegram.core.ports.Clock
import dosegram.core.ports.PromptSchedule
import dosegram.core.ports.UnitOfWork

import java.util.UUID
import scala.util.control.NonFatal

/** The pre-render of the scheduled reports (VISUALS VA-PS-08): in the 30 minutes before a report's send time, its pages
  * are laid out from the records as they are and drawn outside every transaction, each under the key the report looks
  * it up by when it falls due. A page already drawn is not drawn again; a page whose figures changed since is drawn
  * anew under its new key on the next pass, so the prompt loop never waits on a rasteriser and finds the pictures of
  * the records it reports.
  */
final class ReportPrerender(
    uow: UnitOfWork,
    clock: Clock,
    settings: ReminderSettings,
    reports: ReportComposer,
    drawing: CardDrawing,
    onError: (UUID, Throwable) => Unit
):

  /** One pass over every report plan that is on; returns the number of pages drawn. */
  def tick(): Int =
    uow
      .transaction(_.reminders.prompts.enabledOf(PromptKind.Reports))
      .map: plan =>
        try draw(pagesDue(plan))
        catch
          case NonFatal(e) =>
            onError(plan.id, e)
            0
      .sum

  /** The pages of the plan's next report when it falls due within [[ReportComposer.PrepareAhead]], in the account's
    * language; none otherwise.
    */
  private def pagesDue(plan: PromptSchedule): List[CardPage] =
    uow.transaction: tx =>
      Lang.within(tx.accounts.languageOf(plan.accountId)):
        val now = clock.now()
        val zone = settings.zone(tx, plan.accountId)
        val profile = settings.profile(tx, plan.accountId)
        NudgeSlots
          .upcoming(plan.plan, profile, zone, ReminderSettings.seedOf(plan.accountId), now, limit = 1, days = 1)
          .find(slot => !slot.at.isAfter(now.plus(ReportComposer.PrepareAhead)))
          .fold(Nil)(slot => reports.scheduled(tx, plan.kind, plan.accountId, slot.planDate))

  private def draw(pages: List[CardPage]): Int =
    pages.count(page => drawing.ready(page).isEmpty && drawing.draw(page, None).isRight)
