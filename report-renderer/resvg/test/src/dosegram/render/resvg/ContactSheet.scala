package dosegram.render.resvg

import dosegram.render.CardRenderer
import dosegram.render.Fixture
import dosegram.render.Samples
import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.CardOutput
import dosegram.render.card.MoodCard
import dosegram.render.card.Privacy
import dosegram.render.scene.Box
import dosegram.render.scene.Node
import dosegram.render.scene.Placement
import dosegram.render.scene.Point
import dosegram.render.scene.Scene
import dosegram.render.scene.Size
import dosegram.render.svg.SvgEmitter
import dosegram.render.tokens.TextStyle
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

import java.nio.file.Files
import java.nio.file.Path

/** One rendered variant of a fixture page, named for the file it is written to. */
final case class SheetEntry(name: String, card: CardOutput)

/** The review sheet (research brief 04 section 12, layer 6): every fixture in its light, dark and discreet variant,
  * each at 360 px display width, and all 36 blob states in both themes, composed from the scenes themselves and
  * rasterised once.
  */
object ContactSheet:
  private val Scale = 1.0 / 3
  private val TileWidth = 360.0
  private val TileHeight = 450.0
  private val CaptionPx = 56.0
  private val GapPx = 24.0
  private val Columns = 9
  private val BlobScale = 1.0 / 6
  private val BlobTile = 180.0

  val Variants: Vector[(String, Theme, Privacy)] =
    Vector(("light", Theme.Light, Privacy.Named), ("dark", Theme.Dark, Privacy.Named), ("discreet", Theme.Dark, Privacy.Discreet))

  /** Every fixture page in every variant, in fixture order. */
  def entries(fixtures: Vector[Fixture]): Vector[SheetEntry] =
    for
      fixture <- fixtures
      (variant, theme, privacy) <- Variants
      (card, page) <- fixture.render(Samples.context(theme, privacy)).zipWithIndex
    yield SheetEntry(s"${fixture.name.replace('/', '-')}-$variant${if page == 0 then "" else s"-p${page + 1}"}", card)

  def sheet(entries: Vector[SheetEntry]): Scene =
    val tokens = Tokens.Dark
    val pitchX = TileWidth + GapPx
    val pitchY = TileHeight + CaptionPx + GapPx
    val rows = (entries.size + Columns - 1) / Columns
    val blobsTop = GapPx + rows * pitchY + GapPx
    val tiles = entries.zipWithIndex.flatMap { (entry, index) =>
      val origin = Point(GapPx + (index % Columns) * pitchX, GapPx + (index / Columns) * pitchY)
      Vector(
        tile(entry.card.scene, origin, Scale),
        Node.label(entry.name, origin.moved(0, TileHeight + 38), TextStyle(TextStyle.Label.role, 500, 26), tokens.ink2)
      )
    }
    val size = Size(GapPx + Columns * pitchX, blobsTop + MoodState.ValenceLevels * BlobTile + GapPx)
    Scene("sheet.contact", size, tokens.panel, tiles ++ blobGrid(Theme.Light, Point(GapPx, blobsTop)) ++ blobGrid(Theme.Dark, Point(GapPx + 6 * BlobTile, blobsTop)))

  // Rows are valence V1 to V7, columns are energy E1 to E5: along a row only liveliness changes, along a column only
  // pleasantness (VISUALS section 4.5).
  private def blobGrid(theme: Theme, origin: Point): Vector[Node] =
    MoodState.all.map { state =>
      val card = MoodCard.render(Some(MoodReading(state, isEnergyGiven = true)), Samples.context(theme, Privacy.Named))
      tile(card.scene, origin.moved((state.energy - 1) * BlobTile, (state.valence - 1) * BlobTile), BlobScale)
    }

  private def tile(scene: Scene, origin: Point, scale: Double): Node =
    val backdrop = Node.filledRect(Box(0, 0, scene.size.width, scene.size.height), 0, scene.background)
    Node.Group(Placement(origin.x, origin.y, scale), backdrop +: scene.nodes)

/** Writes every fixture as its own PNG and the overview sheet into the directory given as the first argument:
  * `./mill report-renderer.resvg.test.runMain dosegram.render.resvg.renderContactSheet out/report-renderer/preview`.
  */
@main def renderContactSheet(outputDir: String): Unit =
  val binary = ResvgBinary.locate.getOrElse(throw IllegalStateException("resvg is not on the PATH and DOSEGRAM_RESVG is not set"))
  val directory = Files.createDirectories(Path.of(outputDir))
  val renderer = CardRenderer(ResvgRasteriser(ResvgBinary.config(binary)))
  val entries = ContactSheet.entries(Samples.all)
  entries.foreach { entry =>
    renderer.render(entry.card) match
      case Right(image)  => Files.write(directory.resolve(s"${entry.name}.png"), image.png.toArray)
      case Left(failure) => throw IllegalStateException(s"${entry.name} failed to render: $failure")
  }
  val sheetConfig = ResvgBinary.config(binary).copy(timeoutMillis = 60000, maximumPngBytes = 64 * 1024 * 1024)
  ResvgRasteriser(sheetConfig).rasterise(SvgEmitter.emit(ContactSheet.sheet(entries)), 1) match
    case Right(png)    => Files.write(directory.resolve("contact-sheet.png"), png.toArray)
    case Left(failure) => throw IllegalStateException(s"the contact sheet failed to render: $failure")
  println(s"wrote ${entries.size} images and contact-sheet.png to $directory")
