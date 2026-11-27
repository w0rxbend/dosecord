package dosegram.render.blob

/** The motion of the animated mood blob (VISUALS section 4.6): a loop of [[LoopSeconds]] seconds at [[FramesPerSecond]]
  * frames, in which the blob breathes, bounces with its breath, wobbles by one full turn of its harmonic phases and
  * blinks once. Every property completes a whole number of cycles, so the last frame leads into the first without a
  * join. The pose of a frame is a function of the mood state and the frame number only (VA-BL-01).
  */
object BlobMotion:
  val LoopSeconds: Int = 3
  val FramesPerSecond: Int = 20
  val FrameCount: Int = LoopSeconds * FramesPerSecond

  // By energy level E1 to E5.
  private val BreathingAmplitude = Vector(0.02, 0.0275, 0.035, 0.0425, 0.05)
  private val BreathingCycles = Vector(1, 1, 2, 2, 3)
  private val Bounce = Vector(0.0, 0.0, 0.01, 0.02, 0.03)
  // V1 to V3 move with 70 percent of the amplitudes and an eased, slower start and end of the loop.
  private val LastLowValence = 3
  private val LowValenceShare = 0.7
  // One blink of 100 ms at 1.9 s, the eyes nearly closed.
  private val BlinkFrom = 1.9
  private val BlinkSeconds = 0.1
  private val BlinkOpenness = 0.1
  private val FullTurn = 2 * StrictMath.PI

  /** The pose of frame `frame` of the loop, 0 until [[FrameCount]]. */
  def pose(state: MoodState, frame: Int): BlobPose =
    require(frame >= 0 && frame < FrameCount, s"frame out of the loop: $frame")
    val isLow = state.valence <= LastLowValence
    val share = if isLow then LowValenceShare else 1.0
    val linear = frame.toDouble / FrameCount
    val progress = if isLow then smoothstep(linear) else linear
    val breath = StrictMath.sin(FullTurn * BreathingCycles(state.energy - 1) * progress)
    BlobPose(
      scale = 1 + share * BreathingAmplitude(state.energy - 1) * breath,
      lift = share * Bounce(state.energy - 1) * (1 + breath) / 2,
      phaseShift = FullTurn * progress,
      eyeOpenness = if isBlinking(frame) then BlinkOpenness else 1.0
    )

  private def isBlinking(frame: Int): Boolean =
    val seconds = frame.toDouble / FramesPerSecond
    seconds >= BlinkFrom - 1e-9 && seconds < BlinkFrom + BlinkSeconds - 1e-9

  // Zero speed at both ends of the loop, so the eased motion still joins without a jump.
  private def smoothstep(t: Double): Double = t * t * (3 - 2 * t)
