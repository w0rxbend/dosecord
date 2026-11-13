package dosegram.app

import dosegram.adapter.telegram.MediaStore
import dosegram.contracts.AttachmentRef
import dosegram.contracts.MediaKind
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.StatusCards
import dosegram.core.ports.WaterCardView
import dosegram.infra.RenderSettings
import dosegram.render.CardRenderer
import dosegram.render.Rasteriser
import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.CardContext
import dosegram.render.card.CardOutput
import dosegram.render.card.DayCard
import dosegram.render.card.DayData
import dosegram.render.card.MoodCard
import dosegram.render.card.Privacy
import dosegram.render.card.WaterCard
import dosegram.render.card.WaterData
import dosegram.render.card.WaterProgress
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.resvg.ResvgConfig
import dosegram.render.resvg.ResvgRasteriser
import dosegram.render.text.TableTextMeasurer
import dosegram.render.text.TextFitter
import dosegram.render.tokens.Theme

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** The bytes behind an `AttachmentRef.assetKey` for the life of this process (ADR-015: the asset store owns the bytes).
  * It holds at most `maximumImages` images and drops the one that was read longest ago; an image that is gone, or one
  * asked for after a restart, is sent as its text equivalent by the adapter.
  */
final class InMemoryAssetStore(maximumImages: Int) extends MediaStore:
  require(maximumImages > 0, "an asset store holds at least one image")

  // Access order: a read moves the image to the young end, so the eldest entry is the one read longest ago.
  private val images = new java.util.LinkedHashMap[String, Array[Byte]](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[String, Array[Byte]]): Boolean = size() > maximumImages

  def put(assetKey: String, png: Array[Byte]): Unit = images.synchronized:
    images.put(assetKey, png)
    ()

  def holds(assetKey: String): Boolean = images.synchronized(images.containsKey(assetKey))

  override def load(assetKey: String): Option[Array[Byte]] = images.synchronized(Option(images.get(assetKey)))

/** The status cards of the chat flows, drawn by the report renderer (ADR-022): a core view becomes the renderer's view
  * model, the card is rasterised, the PNG goes into the asset store, and the reference carries the card's own text
  * equivalent as its alt text. A card that cannot be drawn is logged by its template and the kind of failure only, and
  * the flow sends its text (VISUALS VA-FB-01).
  */
final class RenderedStatusCards(renderer: CardRenderer, store: InMemoryAssetStore, log: String => Unit)
    extends StatusCards:

  import RenderedStatusCards.*

  /** A mood state is the same picture for everyone (VISUALS VA-BL-11), so its key names the state and it is drawn once.
    */
  override def mood(view: MoodCardView): Option[AttachmentRef] =
    val state = MoodState.captured(view.valence.step, view.energy.map(_.step))
    val reading = MoodReading(state, isEnergyGiven = view.energy.isDefined)
    val key = s"mood.${state.id}.${if reading.isEnergyGiven then "energy" else "plain"}.light"
    val card = MoodCard.render(Some(reading), context)
    if store.holds(key) then Some(reference(key, card))
    else
      rasterised(card).map: png =>
        store.put(key, png)
        reference(key, card)

  override def water(view: WaterCardView): Option[AttachmentRef] =
    personal(WaterCard.render(waterData(view), context))

  override def day(view: DayCardView): Option[AttachmentRef] =
    val data = DayData(
      date = view.date,
      mood = view.mood.map: mood =>
        MoodReading(MoodState.captured(mood.valence.step, mood.energy.map(_.step)), mood.energy.isDefined),
      medication = view.doses.map(_.map(statusOf).toVector),
      water = view.water.map(water => WaterProgress(water.totalMl, water.goalMl)),
      food = None,
      sleep = None
    )
    personal(DayCard.render(data, context))

  /** A personal card is keyed by its own bytes: the same picture is stored once, and the key says nothing. */
  private def personal(card: CardOutput): Option[AttachmentRef] =
    rasterised(card).map: png =>
      val key = s"card.${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png))}"
      store.put(key, png)
      reference(key, card)

  private def rasterised(card: CardOutput): Option[Array[Byte]] =
    renderer.render(card) match
      case Right(image)  => Some(image.png.toArray)
      case Left(failure) =>
        log(s"status image not drawn: template=${card.scene.templateId} reason=${failure.productPrefix}")
        None

  private def reference(key: String, card: CardOutput): AttachmentRef =
    AttachmentRef(MediaKind.Photo, key, "image/png", card.text.altText, Some(card.fileName))

object RenderedStatusCards:

  /** Light theme and no names: the account settings for the image theme and discreet mode do not exist yet. */
  private val context = CardContext(Theme.Light, Privacy.Aggregate, EnglishImageCopy, TextFitter(TableTextMeasurer))

  private def waterData(view: WaterCardView): WaterData =
    WaterData(
      view.date,
      WaterProgress(view.totalMl, view.goalMl),
      view.entries,
      lastEntry = None,
      usesDrinkFactors = false
    )

  private def statusOf(mark: DoseMark): DoseStatus =
    mark match
      case DoseMark.TakenOnTime  => DoseStatus.TakenOnTime
      case DoseMark.TakenLate    => DoseStatus.TakenLate
      case DoseMark.Skipped      => DoseStatus.Skipped
      case DoseMark.NotConfirmed => DoseStatus.Missed
      case DoseMark.NoRecord     => DoseStatus.Unknown
      case DoseMark.Open         => DoseStatus.Open

/** The image side of one process: the store the adapters load attachments from, and the cards the chat flows ask for.
  * Both halves share the store, so an image a flow made is the image the adapter uploads.
  */
final class StatusImages(val store: InMemoryAssetStore, val cards: StatusCards)

object StatusImages:

  /** Mood states are 70 images at most; the rest are the personal cards of the last few minutes. */
  private val AssetCapacity = 256

  /** No renderer: every image is its text equivalent. */
  def textOnly: StatusImages = StatusImages(InMemoryAssetStore(AssetCapacity), StatusCards.textOnly)

  def over(rasteriser: Rasteriser, log: String => Unit): StatusImages =
    val store = InMemoryAssetStore(AssetCapacity)
    StatusImages(store, RenderedStatusCards(CardRenderer(rasteriser), store, log))

  /** The renderer the settings name, or text only without them. A binary that cannot run fails the start: a bot that
    * silently never draws is worse than one that does not start.
    */
  def from(render: Option[RenderSettings], log: String => Unit): StatusImages =
    render match
      case None           => textOnly
      case Some(settings) =>
        val binary = Path.of(settings.resvgPath)
        val fonts = Path.of(settings.fontsDir)
        if !Files.isExecutable(binary) then throw IllegalArgumentException("RESVG_PATH does not name an executable")
        if !Files.isDirectory(fonts) then throw IllegalArgumentException("RENDER_FONTS_DIR does not name a directory")
        val emptyResources = Files.createTempDirectory("dosegram-resvg-empty")
        over(ResvgRasteriser(ResvgConfig.standard(binary, fonts, emptyResources)), log)
