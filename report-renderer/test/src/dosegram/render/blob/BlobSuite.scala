package dosegram.render.blob

import dosegram.render.color.Oklch
import dosegram.render.scene.PathCommand
import dosegram.render.scene.PathData
import dosegram.render.scene.Point
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The blob acceptance checks of VISUALS section 4.10 that need no rasteriser: VA-BL-09 (bounds, no self-intersection,
  * face inside the outline) and VA-BL-10 (monotonicity), over all 35 states and the "no entry" shape.
  */
class BlobSuite extends munit.ScalaCheckSuite:
  private val anyState: Gen[MoodState] = Gen.oneOf(MoodState.all)
  private val FlattenSteps = 16

  // The outline as a dense polyline: every cubic segment sampled at equal parameter steps.
  private def flattened(path: PathData): Vector[Point] =
    val start = path.commands.collectFirst { case PathCommand.MoveTo(to) => to }.get
    val cubics = path.commands.collect { case cubic: PathCommand.CubicTo => cubic }
    cubics
      .scanLeft((start, Vector.empty[Point])) { case ((from, _), PathCommand.CubicTo(c1, c2, to)) =>
        val points = (1 to FlattenSteps).toVector.map { step =>
          val t = step.toDouble / FlattenSteps
          val u = 1 - t
          Point(
            u * u * u * from.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * to.x,
            u * u * u * from.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * to.y
          )
        }
        (to, points)
      }
      .flatMap(_._2)

  private def angle(point: Point): Double = StrictMath.atan2(point.y, point.x)

  // Star-shaped about the centre: the polar angle only ever advances, so the outline cannot cross itself.
  private def isStarShaped(polyline: Vector[Point]): Boolean =
    (polyline :+ polyline.head).sliding(2).forall { pair =>
      val turn = angle(pair(1)) - angle(pair(0))
      val wrapped = if turn < -StrictMath.PI then turn + 2 * StrictMath.PI else turn
      wrapped > 0 && wrapped < StrictMath.PI / 2
    }

  // The distance from the centre to the outline in the direction of `point`, by the nearest polyline vertex.
  private def outlineRadiusToward(polyline: Vector[Point], point: Point): Double =
    val nearest = polyline.minBy { vertex =>
      val difference = StrictMath.abs(angle(vertex) - angle(point))
      StrictMath.min(difference, 2 * StrictMath.PI - difference)
    }
    StrictMath.hypot(nearest.x, nearest.y)

  property("the outline is one closed path that starts and ends at the same point"):
    forAll(anyState) { state =>
      val commands = BlobGeometry.outline(state).commands
      val start = commands.head match
        case PathCommand.MoveTo(to) => to
        case other                  => fail(s"path starts with $other")
      val end = commands.init.last match
        case PathCommand.CubicTo(_, _, to) => to
        case other                         => fail(s"path ends with $other")
      commands.last == PathCommand.Close && end == start && commands.count(_.isInstanceOf[PathCommand.MoveTo]) == 1
    }

  property("every outline coordinate is a finite number"):
    forAll(anyState) { state =>
      BlobGeometry.outline(state).points.forall(point => !point.x.isNaN && !point.y.isNaN && !point.x.isInfinite && !point.y.isInfinite)
    }

  property("the outline stays inside a circle of 1.25 radii and outside 0.6 radii (VA-BL-09)"):
    forAll(anyState) { state =>
      flattened(BlobGeometry.outline(state)).forall { point =>
        val radius = StrictMath.hypot(point.x, point.y)
        radius <= 1.25 && radius >= 0.6
      }
    }

  property("the outline never crosses itself (VA-BL-09)"):
    forAll(anyState)(state => isStarShaped(flattened(BlobGeometry.outline(state))))

  property("the face lies inside the outline with a margin of 0.05 radii (VA-BL-09)"):
    forAll(anyState) { state =>
      val polyline = flattened(BlobGeometry.outline(state))
      BlobFigure.facePoints(state).forall { feature =>
        StrictMath.hypot(feature.x, feature.y) + 0.05 <= outlineRadiusToward(polyline, feature)
      }
    }

  test("the no-entry outline is closed, bounded and does not cross itself"):
    val polyline = flattened(BlobGeometry.noEntryOutline)
    assert(polyline.forall(point => StrictMath.hypot(point.x, point.y) <= 1.25))
    assert(isStarShaped(polyline))
    assertEquals(BlobGeometry.noEntryOutline.commands.last, PathCommand.Close)

  test("a state has the same outline in every build: phases come from FNV-1a and SplitMix64"):
    assertEquals(Fnv1a.hash64(""), 0xcbf29ce484222325L)
    assertEquals(Fnv1a.hash64("a"), 0xaf63dc4c8601ec8cL)
    assertEquals(SplitMix64.first(0).value, 0xe220a8397b1dcdafL)
    assertEquals(SplitMix64.first(0).next.value, 0x6e789e6aa1b965f4L)
    assertEquals(BlobGeometry.phases("blob.v4.e3"), BlobGeometry.phases("blob.v4.e3"))
    assertNotEquals(BlobGeometry.phases("blob.v4.e3"), BlobGeometry.phases("blob.v4.e4"))
    assert(BlobGeometry.phases("blob.v4.e3").forall(phase => phase >= 0 && phase < 2 * StrictMath.PI))

  test("all 35 states are distinct in outline and in colour"):
    assertEquals(MoodState.all.size, 35)
    assertEquals(MoodState.all.map(BlobGeometry.outline).distinct.size, 35)
    assertEquals(MoodState.all.map(BlobPalette.fill).distinct.size, 35)

  test("for fixed energy, the mouth curve and the fill lightness rise strictly with valence (VA-BL-10)"):
    for energy <- 1 to MoodState.EnergyLevels do
      val states = (1 to MoodState.ValenceLevels).map(MoodState(_, energy))
      val curves = states.map(state => BlobFigure.mouthCurve(state.valence))
      val lightness = states.map(state => Oklch.from(BlobPalette.fill(state)).lightness)
      assert(curves.sliding(2).forall(pair => pair(0) < pair(1)), s"mouth at E$energy: $curves")
      assert(lightness.sliding(2).forall(pair => pair(0) < pair(1)), s"lightness at E$energy: $lightness")

  test("for fixed valence, amplitude, eye opening and chroma never fall as energy rises (VA-BL-10)"):
    for valence <- 1 to MoodState.ValenceLevels do
      val states = (1 to MoodState.EnergyLevels).map(MoodState(valence, _))
      val amplitude = states.map(state => BlobGeometry.shape(state.energy).amplitude)
      val opening = states.map(state => BlobFigure.eyeOpening(state.energy))
      val chroma = states.map(state => Oklch.from(BlobPalette.fill(state)).chroma)
      assert(amplitude.sliding(2).forall(pair => pair(0) <= pair(1)))
      assert(opening.sliding(2).forall(pair => pair(0) <= pair(1)))
      assert(chroma.sliding(2).forall(pair => pair(0) <= pair(1) + 0.004), s"chroma at V$valence: $chroma")

  test("tense brows only at the tense corner, tired brows only at the heavy corner, and only heavy states slump"):
    def withBrows(kind: BrowKind): Vector[String] = MoodState.all.filter(BlobFigure.browKind(_).contains(kind)).map(_.id)
    assertEquals(withBrows(BrowKind.Tense), Vector("blob.v1.e4", "blob.v1.e5", "blob.v2.e4", "blob.v2.e5"))
    assertEquals(withBrows(BrowKind.Tired).toSet, (for v <- 1 to 3; e <- 1 to 3 yield s"blob.v$v.e$e").toSet)
    assert(MoodState.all.filter(_.valence >= 4).forall(!BlobFigure.hasBrows(_)), "no brows from neutral up")
    assertEquals(MoodState.all.filter(BlobGeometry.slump(_) > 0).map(_.valence).distinct, Vector(1, 2, 3))
    assert(MoodState.all.filter(BlobGeometry.slump(_) > 0).forall(_.energy <= 3))
    for energy <- 1 to 3 do
      val slumps = (1 to 3).map(valence => BlobGeometry.slump(MoodState(valence, energy)))
      assert(slumps.sliding(2).forall(pair => pair(0) > pair(1)), s"the lower the valence the heavier: $slumps")

  test("a tense brow lowers its inner end and a tired brow raises it, never both on one face"):
    MoodState.all.foreach { state =>
      BlobFigure.face(state).brows.foreach { (outer, inner) =>
        BlobFigure.browKind(state) match
          case Some(BrowKind.Tense) => assert(inner.y > outer.y, state.id)
          case Some(BrowKind.Tired) => assert(inner.y < outer.y, state.id)
          case None                 => fail(s"${state.id} has a brow without a kind")
      }
    }

  test("cheeks belong to the pleasant states, glints to open eyes, and both stay off the mini blobs"):
    MoodState.all.foreach { state =>
      val face = BlobFigure.face(state)
      assertEquals(face.cheeks.nonEmpty, state.valence >= 6, state.id)
      assertEquals(face.eyes.forall(_.glint.isDefined), state.energy >= 2, state.id)
      face.eyes.foreach(eye => eye.glint.foreach(glint => assert(glint.radius < eye.radiusX, state.id)))
    }
    val tokens = dosegram.render.tokens.Tokens.Dark
    def leaves(radius: Double): Int =
      dosegram.render.scene.Node.flatten(Vector(BlobFigure.of(MoodState(7, 5), BlobFrame(Point(0, 0), radius), tokens))).size
    assert(leaves(BlobFigure.DetailFromRadiusPx) > leaves(BlobFigure.DetailFromRadiusPx - 1), "details only on large blobs")

  test("the face is expressive: at least eight distinct expressions over the 35 states"):
    // An expression is what the face shows besides colour: eye opening, mouth bend, brows, cheeks.
    val expressions = MoodState.all.map { state =>
      val face = BlobFigure.face(state)
      (face.eyes.head.radiusY, face.mouth(1).y, face.browKind, face.cheeks.nonEmpty)
    }
    assert(expressions.distinct.size >= 8, s"${expressions.distinct.size} expressions")

  test("the cheek blush differs from its fill in lightness, so it reads in greyscale too"):
    MoodState.all.filter(BlobFigure.hasCheeks).foreach { state =>
      val fill = Oklch.from(BlobPalette.fill(state)).lightness
      val cheek = Oklch.from(BlobPalette.cheek(state)).lightness
      assert(fill - cheek >= 0.05, s"${state.id}: fill $fill, cheek $cheek")
    }

  test("the face ink keeps at least 4.3:1 contrast against the fill"):
    MoodState.all.foreach { state =>
      val contrast = BlobPalette.faceInk(state).contrast(BlobPalette.fill(state))
      assert(contrast >= 4.3, s"${state.id}: $contrast")
    }

  test("a five-step check-in maps to the valence levels V1, V2, V4, V6 and V7"):
    assertEquals((1 to 5).map(step => MoodState.captured(step, None).valence), Vector(1, 2, 4, 6, 7))
    assertEquals(MoodState.captured(3, None).energy, 3)
    assertEquals(MoodState.captured(3, Some(5)).energy, 5)
    assertEquals(MoodState.quantised(-1, 0), MoodState(1, 1))
    assertEquals(MoodState.quantised(1, 1), MoodState(7, 5))
    assertEquals(MoodState.quantised(0.15, 0.5), MoodState(5, 3))
