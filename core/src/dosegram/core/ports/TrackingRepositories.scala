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
  def sleep: SleepRepository

  /** Meals (ROADMAP G8.1). A double that records no meal keeps this default, which fails naming the group. */
  def meals: MealRepository =
    throw UnsupportedOperationException("the meals repository is not on this transaction")
