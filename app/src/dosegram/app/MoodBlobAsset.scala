package dosegram.app

import dosegram.api.BlobColours
import dosegram.api.BlobFace
import dosegram.api.BlobState
import dosegram.api.MoodBlobStates
import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.BlobPalette
import dosegram.render.blob.MoodState
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.PathCommand
import dosegram.render.scene.Point
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

/** The global blob asset of `GET /mood/blob-states` (MINI_APP section 7.9): every drawn state of the renderer, read off
  * the very scene nodes the chat image is painted from, in units of the blob radius. The app therefore holds no copy of
  * the formula and cannot drift from the image (VISUALS VA-BL-01). The asset holds no user data.
  *
  * Outlines of one energy level share their number of segments, so the client blends the states that the valence slider
  * moves between number by number (MA-MOOD-5 within an energy level).
  */
object MoodBlobAsset:

  /** The renderer's reference radius: strokes reach their specified width there and none is clamped. */
  private val Radius = 300.0
  private val Frame = BlobFrame(Point(0, 0), Radius)
  private val Revision = "dg-blob-1"

  lazy val states: MoodBlobStates =
    MoodBlobStates(Revision, MoodState.all.toList.map(drawn) :+ noEntry)

  private def drawn(state: MoodState): BlobState =
    val parts = Parts.of(BlobFigure.of(state, Frame, Tokens.of(Theme.Light)))
    val dark = Parts.of(BlobFigure.of(state, Frame, Tokens.of(Theme.Dark)))
    val gradient = parts.body.fill.collect { case Paint.Radial(centre, radius, _, _) =>
      List(unit(centre.x), unit(centre.y), unit(radius))
    }
    BlobState(
      id = state.id,
      outline = outlineOf(parts.body),
      outlineWidth = parts.body.stroke.fold(0.0)(stroke => unit(stroke.width)),
      isDotted = false,
      gradient = gradient.getOrElse(Nil),
      ground = groundOf(parts.ground),
      light = colours(state, Theme.Light, parts),
      dark = colours(state, Theme.Dark, dark),
      face = Some(faceOf(parts))
    )

  private def noEntry: BlobState =
    val light = Parts.of(BlobFigure.noEntry(Frame, Tokens.of(Theme.Light)))
    val dark = Parts.of(BlobFigure.noEntry(Frame, Tokens.of(Theme.Dark)))
    def flat(parts: Parts, theme: Theme): BlobColours =
      val fill = parts.body.fill.collect { case Paint.Solid(colour) => colour.hex }.getOrElse("#000000")
      BlobColours(fill, fill, strokeHex(parts), groundHex(parts), BlobPalette.noEntryFill(theme).hex)
    BlobState(
      id = MoodState.NoEntryId,
      outline = outlineOf(light.body),
      outlineWidth = light.body.stroke.fold(0.0)(stroke => unit(stroke.width)),
      isDotted = true,
      gradient = Nil,
      ground = groundOf(light.ground),
      light = flat(light, Theme.Light),
      dark = flat(dark, Theme.Dark)
    )

  /** The nodes of one drawn blob in paint order: the ground, the body, then the face. */
  private final case class Parts(ground: Node.Ellipse, body: Node.Path, face: Vector[Node])

  private object Parts:
    def of(figure: Node): Parts = figure match
      case Node.Mark(_, (ground: Node.Ellipse) +: (body: Node.Path) +: face) => Parts(ground, body, face)
      case other => throw IllegalStateException(s"the blob figure changed its shape: ${other.getClass.getSimpleName}")

  private def colours(state: MoodState, theme: Theme, themed: Parts): BlobColours =
    BlobColours(
      fillInner = BlobPalette.highlight(state).hex,
      fillOuter = BlobPalette.fill(state).hex,
      outline = strokeHex(themed),
      ground = groundHex(themed),
      background = BlobPalette.background(state, theme).hex
    )

  private def strokeHex(parts: Parts): String = parts.body.stroke.fold("#000000")(_.color.hex)

  private def groundHex(parts: Parts): String =
    parts.ground.fill.collect { case Paint.Solid(colour) => colour.hex }.getOrElse("#000000")

  /** The start point, then six numbers per cubic segment. */
  private def outlineOf(body: Node.Path): List[Double] =
    body.data.commands.toList.flatMap:
      case PathCommand.MoveTo(to)          => coordinates(to)
      case PathCommand.CubicTo(c1, c2, to) => coordinates(c1) ++ coordinates(c2) ++ coordinates(to)
      case PathCommand.Close               => Nil
      case other => throw IllegalStateException(s"a blob outline is cubic only: ${other.getClass.getSimpleName}")

  private def groundOf(ground: Node.Ellipse): List[Double] =
    coordinates(ground.center) ++ List(unit(ground.radiusX), unit(ground.radiusY))

  private def faceOf(parts: Parts): BlobFace =
    val eyes = parts.face.collect { case Node.Ellipse(centre, rx, ry, _, _) =>
      coordinates(centre) ++ List(unit(rx), unit(ry))
    }
    val mouth = parts.face.collectFirst { case Node.Path(data, None, Some(stroke)) => (data, stroke) }
    val brows = parts.face.collect { case Node.Line(from, to, _) => coordinates(from) ++ coordinates(to) }
    val ink = parts.face.collectFirst { case Node.Ellipse(_, _, _, Some(Paint.Solid(colour)), _) => colour.hex }
    BlobFace(
      ink = ink.getOrElse("#000000"),
      eyes = eyes.toList,
      mouth = mouth.toList.flatMap((data, _) => data.points.toList.flatMap(coordinates)),
      strokeWidth = mouth.fold(0.0)((_, stroke) => unit(stroke.width)),
      brows = brows.toList
    )

  private def coordinates(point: Point): List[Double] = List(unit(point.x), unit(point.y))

  /** Four decimals of the radius: finer than a device pixel at any size the app draws. */
  private def unit(value: Double): Double = Math.round(value / Radius * 10000.0) / 10000.0
end MoodBlobAsset
