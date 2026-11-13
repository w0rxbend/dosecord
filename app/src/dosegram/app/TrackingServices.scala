package dosegram.app

import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.insights.RollupService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.mood.MoodService
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentLedger
import dosegram.core.ports.IdSource
import dosegram.infra.db.PgConsentLedger
import dosegram.infra.db.PgTrackingUnitOfWork

import java.security.SecureRandom
import javax.sql.DataSource

/** The tracking use cases over Postgres (ARCHITECTURE section 11.4), assembled once per ingress: the chat flows and the
  * Mini App API call the same services, so a figure is computed by one piece of code on every surface.
  */
final class TrackingServices(
    val hydration: HydrationService,
    val mood: MoodService,
    val stats: StatsQueryService,
    val consents: ConsentLedger
)

object TrackingServices:

  def over(dataSource: DataSource, clock: Clock): TrackingServices =
    val uow = PgTrackingUnitOfWork(dataSource)
    val ids = IdSource.uuidV7(SecureRandom())
    val consents = PgConsentLedger(dataSource, ids)
    TrackingServices(
      HydrationService(uow, consents, clock, ids),
      MoodService(uow, consents, clock, ids),
      StatsQueryService(uow, RollupService(uow, clock), consents, clock),
      consents
    )
