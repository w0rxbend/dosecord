package dosegram.core.ports

/** The repository group of the tracking domains and their derived data (ARCHITECTURE DEP-10, ROADMAP G2.1). [[Tx]]
  * exposes it as one member, so an in-memory double of a suite that records no entry implements one refusal instead of
  * four repositories.
  */
trait TrackingRepositories:
  def hydration: HydrationRepository
  def mood: MoodRepository
  def rollups: RollupRepository
  def doseHistory: DoseHistoryRepository
