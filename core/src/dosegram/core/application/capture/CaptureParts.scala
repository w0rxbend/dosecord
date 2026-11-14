package dosegram.core.application.capture

import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.insights.RollupService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.medication.MedicationWithdrawal
import dosegram.core.application.mood.MoodService
import dosegram.core.application.nutrition.NutritionService
import dosegram.core.application.sleep.SleepService
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.core.ports.StatusCards
import dosegram.core.ports.UnitOfWork
import dosegram.core.scheduling.ScheduleLifecycle

/** What the capture flows of the chat are assembled from: the use cases of the tracking domains and the status images.
  * The consent ledger that gates them is on the transaction; `withdrawal` is what ends with the medication consent. The
  * composition root builds one and hands it to [[dosegram.core.application.Application.withCapture]].
  */
final case class CaptureParts(
    hydration: HydrationService,
    mood: MoodService,
    sleep: SleepService,
    stats: StatsQueryService,
    cards: StatusCards,
    withdrawal: MedicationWithdrawal,
    nutrition: NutritionService
)

object CaptureParts:

  /** The parts over one unit of work, as every composition builds them. */
  def over(uow: UnitOfWork, clock: Clock, ids: IdSource, cards: StatusCards): CaptureParts =
    CaptureParts(
      HydrationService(clock, ids),
      MoodService(clock, ids),
      SleepService(clock, ids),
      StatsQueryService(RollupService(uow, clock), clock),
      cards,
      MedicationWithdrawal(ScheduleLifecycle(uow, clock)),
      NutritionService(clock, ids)
    )
