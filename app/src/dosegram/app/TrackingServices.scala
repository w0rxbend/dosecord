package dosegram.app

import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.insights.RollupService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.mood.MoodService
import dosegram.core.application.sleep.SleepService
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.core.ports.UnitOfWork

import java.security.SecureRandom

/** The tracking use cases (ARCHITECTURE section 11.4), assembled once per ingress: the chat flows and the Mini App API
  * call the same services, so a figure is computed by one piece of code on every surface. A use case runs on the
  * transaction its caller hands it; `rollups` opens its own only to drain the queue.
  */
final class TrackingServices(
    val hydration: HydrationService,
    val mood: MoodService,
    val sleep: SleepService,
    val stats: StatsQueryService,
    val rollups: RollupService
)

object TrackingServices:

  def over(uow: UnitOfWork, clock: Clock): TrackingServices =
    val ids = IdSource.uuidV7(SecureRandom())
    val rollups = RollupService(uow, clock)
    TrackingServices(
      HydrationService(clock, ids),
      MoodService(clock, ids),
      SleepService(clock, ids),
      StatsQueryService(rollups, clock),
      rollups
    )
