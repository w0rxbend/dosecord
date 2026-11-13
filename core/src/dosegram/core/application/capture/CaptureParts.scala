package dosegram.core.application.capture

import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.mood.MoodService
import dosegram.core.ports.StatusCards

/** What the capture flows of the chat are assembled from: the use cases of the tracking domains and the status images.
  * The consent ledger that gates them is on the transaction. The composition root builds one and hands it to
  * [[dosegram.core.application.Application.withCapture]].
  */
final case class CaptureParts(
    hydration: HydrationService,
    mood: MoodService,
    stats: StatsQueryService,
    cards: StatusCards
)
