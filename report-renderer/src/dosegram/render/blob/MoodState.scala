package dosegram.render.blob

/** One of the 35 drawn mood states (VISUALS section 4.3): valence level V1 to V7 and energy level E1 to E5. The
  * seven-by-five grid is a drawing device; stored check-ins use five steps on each axis.
  */
final case class MoodState(valence: Int, energy: Int):
  require(valence >= 1 && valence <= MoodState.ValenceLevels, s"valence level out of range: $valence")
  require(energy >= 1 && energy <= MoodState.EnergyLevels, s"energy level out of range: $energy")

  def id: String = s"blob.v$valence.e$energy"

object MoodState:
  val ValenceLevels: Int = 7
  val EnergyLevels: Int = 5
  val NoEntryId: String = "blob.none"
  private val DefaultEnergy = 3

  val all: Vector[MoodState] =
    for
      valence <- (1 to ValenceLevels).toVector
      energy <- 1 to EnergyLevels
    yield MoodState(valence, energy)

  /** Quantises valence in [-1, 1] and energy in [0, 1] with the equal-width bins of VISUALS section 4.2. */
  def quantised(valence: Double, energy: Double): MoodState =
    MoodState(
      StrictMath.min(ValenceLevels, StrictMath.floor((valence + 1) * 3.5).toInt + 1),
      StrictMath.min(EnergyLevels, StrictMath.floor(energy * 5).toInt + 1)
    )

  /** The state of a stored check-in: a five-step valence tap and an optional five-step energy tap. The taps map to -1,
    * -0.5, 0, 0.5, 1, so they reach V1, V2, V4, V6 and V7; a missing energy tap is drawn at the middle level.
    */
  def captured(valenceStep: Int, energyStep: Option[Int]): MoodState =
    require(valenceStep >= 1 && valenceStep <= 5, s"valence step out of range: $valenceStep")
    MoodState(quantised((valenceStep - 3) / 2.0, 0.5).valence, energyStep.getOrElse(DefaultEnergy))

/** What the mood blob shows: a state and whether the user gave the energy answer, which decides if energy is named
  * beside the blob (PRODUCT_SPEC R-MOO-1). Nothing else is an input to the blob (VA-BL-01).
  */
final case class MoodReading(state: MoodState, isEnergyGiven: Boolean)
