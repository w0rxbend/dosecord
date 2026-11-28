package dosegram.render.blob

import dosegram.render.Samples
import dosegram.render.card.MoodCard
import dosegram.render.card.MoodMotionCard
import dosegram.render.card.Privacy
import dosegram.render.lint.LintRules
import dosegram.render.lint.SceneLint
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.PathCommand
import dosegram.render.scene.Point
import dosegram.render.svg.SvgEmitter
import dosegram.render.svg.SvgProfile
import dosegram.render.text.TableTextMeasurer
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

/** The animated mood blob (VISUALS section 4.6, VA-BL-12 for the video form): the loop, the motion mapping by energy
  * and valence, the safety rules, and every frame inside the blob rules and the SVG profile.
  */
class BlobMotionSuite extends munit.FunSuite:
  private val Frames = 0 until BlobMotion.FrameCount
  private def reading(state: MoodState) = MoodReading(state, isEnergyGiven = true)
  private def motion(state: MoodState, theme: Theme = Theme.Dark) =
    MoodMotionCard.render(reading(state), Samples.context(theme, Privacy.Aggregate))

  test("the loop lasts 3 seconds at 20 frames a second, within the 2 to 3 seconds of the animated form"):
    assertEquals(BlobMotion.FrameCount, 60)
    assertEquals(BlobMotion.FrameCount.toDouble / BlobMotion.FramesPerSecond, 3.0)
    assertEquals(motion(MoodState(4, 3)).frames.size, 60)
    assertEquals(motion(MoodState(4, 3)).framesPerSecond, 20)

  test("frame 0 has the still outline, size and eyes, and the loop joins without a jump"):
    MoodState.all.foreach: state =>
      val first = BlobMotion.pose(state, 0)
      assertEquals(first.copy(lift = 0.0), BlobPose.Rest, state.id)
      // The step from the last frame into the first is no larger than the largest step inside the loop.
      val poses = Frames.map(BlobMotion.pose(state, _)).toVector
      def step(a: BlobPose, b: BlobPose) =
        Vector(StrictMath.abs(a.scale - b.scale), StrictMath.abs(a.lift - b.lift)).max
      val inside = poses.sliding(2).map(pair => step(pair(0), pair(1))).max
      assert(step(poses.last, poses.head) <= inside + 1e-12, state.id)
      val turn = 2 * StrictMath.PI - poses.last.phaseShift
      assert(turn > 0 && turn <= 2 * StrictMath.PI / BlobMotion.FrameCount + 1e-12, s"${state.id}: wobble join $turn")

  test("breathing is 2 to 5 percent by energy, with 1, 1, 2, 2 and 3 cycles a loop"):
    val cycles = Vector(1, 1, 2, 2, 3)
    val amplitude = Vector(0.02, 0.0275, 0.035, 0.0425, 0.05)
    for energy <- 1 to MoodState.EnergyLevels do
      val state = MoodState(5, energy)
      val scales = Frames.map(BlobMotion.pose(state, _).scale)
      assertEqualsDouble(scales.max - 1, amplitude(energy - 1), 0.002, s"E$energy")
      val crossings = (scales :+ scales.head).sliding(2).count(pair => pair(0) < 1 && pair(1) >= 1)
      assertEquals(crossings, cycles(energy - 1), s"E$energy rises through rest once per cycle")

  test("V1 to V3 move with 70 percent of the amplitude; the bounce is 0 to 3 percent and never below the rest"):
    for energy <- 1 to MoodState.EnergyLevels do
      val calm = Frames.map(BlobMotion.pose(MoodState(2, energy), _).scale).max - 1
      val lively = Frames.map(BlobMotion.pose(MoodState(6, energy), _).scale).max - 1
      assertEqualsDouble(calm / lively, 0.7, 0.03, s"E$energy")
      val lifts = Frames.map(BlobMotion.pose(MoodState(6, energy), _).lift)
      assert(lifts.min >= 0, s"E$energy")
      assertEqualsDouble(lifts.max, Vector(0.0, 0.0, 0.01, 0.02, 0.03)(energy - 1), 0.001, s"E$energy")

  test("one blink a loop: the eyes close for 100 ms at 1.9 s and nowhere else"):
    MoodState.all.foreach: state =>
      val closed = Frames.filter(BlobMotion.pose(state, _).eyeOpenness < 1).toVector
      assertEquals(closed, Vector(38, 39), state.id)

  test("no flashing: the background, fill and highlight colours are the same in every frame"):
    MoodState.all.foreach: state =>
      val frames = motion(state).frames
      assertEquals(frames.map(_.background).distinct.size, 1, state.id)
      val paints = frames.map: scene =>
        Node.flatten(scene.nodes).collect { case path: Node.Path => path.fill }.flatten.collect {
          case Paint.Radial(_, _, inner, outer) => (inner, outer)
        }
      assertEquals(paints.distinct.size, 1, state.id)

  test("the frames of a state differ from each other and from every other state"):
    val first = motion(MoodState(4, 3)).frames.map(SvgEmitter.emit)
    assert(first.distinct.size >= BlobMotion.FrameCount - 1, s"${first.distinct.size} distinct frames")
    assertNotEquals(SvgEmitter.emit(motion(MoodState(4, 4)).frames(10)), first(10))

  test("the same state always yields the same frames (deterministic goldens)"):
    assertEquals(motion(MoodState(6, 5)).frames.map(SvgEmitter.emit), motion(MoodState(6, 5)).frames.map(SvgEmitter.emit))

  test("the face stays inside the outline in every frame (VA-BL-09)"):
    MoodState.all.foreach: state =>
      Frames.foreach: frame =>
        val pose = BlobMotion.pose(state, frame)
        val outline = BlobGeometry.samples(state, pose.phaseShift)
        BlobFigure.facePoints(state).foreach: feature =>
          val toward = outline.minBy: vertex =>
            val difference = StrictMath.abs(StrictMath.atan2(vertex.y, vertex.x) - StrictMath.atan2(feature.y, feature.x))
            StrictMath.min(difference, 2 * StrictMath.PI - difference)
          assert(
            StrictMath.hypot(feature.x, feature.y) + 0.05 <= StrictMath.hypot(toward.x, toward.y),
            s"${state.id} frame $frame"
          )

  test("every frame stays on its 512 px canvas, passes the scene lints and SVG profile 1"):
    for
      theme <- Theme.values.toVector
      state <- Vector(MoodState(1, 5), MoodState(4, 3), MoodState(7, 5), MoodState(1, 1))
      scene <- motion(state, theme).frames
    do
      assertEquals(scene.size.width, 512.0)
      assertEquals(scene.size.height, 512.0)
      val points = Node.flatten(scene.nodes).collect { case path: Node.Path => path.data.points }.flatten
      assert(points.forall(point => point.x > 0 && point.x < 512 && point.y > 0 && point.y < 512), state.id)
      val rules = LintRules(Tokens.of(theme).palette ++ BlobPalette.all, Set(scene.background), TableTextMeasurer)
      assertEquals(SceneLint.violations(scene, rules), Vector.empty, state.id)
      assertEquals(SvgProfile.violations(SvgEmitter.emit(scene)), Vector.empty, state.id)

  test("the animation carries no words and has the still card's text equivalent (VA-BL-04: names in the caption)"):
    val state = MoodState(6, 4)
    val animated = motion(state)
    val still = MoodCard.render(Some(reading(state)), Samples.context(Theme.Dark, Privacy.Aggregate))
    assert(animated.frames.forall(scene => Node.flatten(scene.nodes).collect { case text: Node.Text => text }.isEmpty))
    assertEquals(animated.text, still.text)
    assertEquals(animated.fileName, "dosegram-mood.mp4")
    assert(!animated.hasSpoiler)

  test("the still blob is unchanged by the pose machinery"):
    val state = MoodState(3, 2)
    val frame = BlobFrame(Point(540, 480), 300)
    assertEquals(BlobFigure.of(state, frame, Tokens.Dark, BlobPose.Rest), BlobFigure.of(state, frame, Tokens.Dark))
    assertEquals(BlobGeometry.outline(state, 0.0), BlobGeometry.outline(state))
    assert(BlobGeometry.outline(state, 1.0).commands.exists(_.isInstanceOf[PathCommand.CubicTo]))
    assertNotEquals(BlobGeometry.outline(state, 1.0), BlobGeometry.outline(state))
