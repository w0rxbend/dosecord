package dosegram.app

import dosegram.adapter.telegram.FileIdCache
import dosegram.adapter.telegram.InMemoryFileIdCache
import dosegram.adapter.telegram.MediaStore
import dosegram.contracts.AttachmentRef
import dosegram.contracts.ChatRef
import dosegram.contracts.MediaKind
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.Clock
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.FoodCardView
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MonthCardView
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.RefillCardView
import dosegram.core.ports.StatusCards
import dosegram.core.ports.WaterCardView
import dosegram.core.ports.WeekCardView
import dosegram.infra.RenderSettings
import dosegram.infra.db.PgRenderAssets
import dosegram.infra.db.PgTelegramFileCache
import dosegram.render.CardRenderer
import dosegram.render.Rasteriser
import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.CardContext
import dosegram.render.card.CardOutput
import dosegram.render.card.DayCard
import dosegram.render.card.DayData
import dosegram.render.card.FoodDayCard
import dosegram.render.card.FoodDayData
import dosegram.render.card.InsightCard
import dosegram.render.card.MainMeal
import dosegram.render.card.MealProgress
import dosegram.render.card.MonthCard
import dosegram.render.card.MonthPages
import dosegram.render.card.MoodCard
import dosegram.render.card.Privacy
import dosegram.render.card.RefillCard
import dosegram.render.card.RefillData
import dosegram.render.card.SleepEvidence
import dosegram.render.card.SleepRegularityCard
import dosegram.render.card.WaterCard
import dosegram.render.card.WaterData
import dosegram.render.card.WaterProgress
import dosegram.render.card.WeekCard
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.resvg.ResvgConfig
import dosegram.render.resvg.ResvgRasteriser
import dosegram.render.svg.SvgEmitter
import dosegram.render.text.TableTextMeasurer
import dosegram.render.text.TextFitter
import dosegram.render.tokens.Theme

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import scala.util.control.NonFatal

/** Where the bytes behind an `AttachmentRef.assetKey` live (ADR-015: the asset store owns the bytes): the card side
  * puts an image, the adapter loads it. An image that is gone is sent as its text equivalent by the adapter.
  */
trait AssetStore extends MediaStore:
  def put(assetKey: String, png: Array[Byte]): Unit
  def holds(assetKey: String): Boolean

/** An asset store for the life of one process, for a composition without a database behind its images (the text-only
  * profile and the suites). It holds at most `maximumImages` images and drops the one that was read longest ago.
  */
final class InMemoryAssetStore(maximumImages: Int) extends AssetStore:
  require(maximumImages > 0, "an asset store holds at least one image")

  // Access order: a read moves the image to the young end, so the eldest entry is the one read longest ago.
  private val images = new java.util.LinkedHashMap[String, Array[Byte]](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[String, Array[Byte]]): Boolean = size() > maximumImages

  override def put(assetKey: String, png: Array[Byte]): Unit = images.synchronized:
    images.put(assetKey, png)
    ()

  override def holds(assetKey: String): Boolean = images.synchronized(images.containsKey(assetKey))

  override def load(assetKey: String): Option[Array[Byte]] = images.synchronized(Option(images.get(assetKey)))

/** Who shows "sending a photo" in a chat while its image is drawn (VISUALS VA-FB-02): the adapter of the chat's vendor,
  * attached once it is built, because the adapter itself is built over the image stores. A vendor without one shows
  * nothing.
  *
  * A status is a network call, so it runs on a virtual thread of its own and [[drawing]] returns at once: a slow or
  * hanging Bot API holds up neither the drawing nor the status of another chat. At most `maxUnderway` statuses are
  * underway; one more is dropped, because a status is only a courtesy, and each call is bounded by the vendor client's
  * own timeout. A failure is logged by its kind.
  */
final class DrawingNotices(log: String => Unit, maxUnderway: Int = DrawingNotices.MaxUnderway):
  require(maxUnderway > 0, "at least one status can be underway")

  private val byVendor = ConcurrentHashMap[String, ChatRef => Unit]()
  private val underway = Semaphore(maxUnderway)

  def attach(vendor: String, notice: ChatRef => Unit): Unit =
    byVendor.put(vendor, notice)
    ()

  def drawing(chat: ChatRef): Unit =
    Option(byVendor.get(chat.vendor)).foreach: notice =>
      if underway.tryAcquire() then
        Thread
          .ofVirtual()
          .start: () =>
            try notice(chat)
            catch case NonFatal(error) => log(s"drawing status failed: ${error.getClass.getSimpleName}")
            finally underway.release()
        ()
      else log(s"drawing status dropped: $maxUnderway underway")

object DrawingNotices:

  /** Twice the two rasterisers of ARCHITECTURE RND-07: a status per drawing underway, and room for the ones whose
    * drawing has ended but whose call has not returned yet.
    */
  val MaxUnderway = 4

/** The status cards of the chat flows, laid out by the report renderer (ADR-022): a core view becomes the renderer's
  * view model and its scene is emitted as SVG with the card's own text equivalent. Nothing is rasterised here: the
  * report worker draws the page after the event (ROADMAP G4.3, [[RenderedCardDrawing]]).
  */
final class RenderedStatusCards extends StatusCards:

  import RenderedStatusCards.*

  /** A mood state is the same picture for everyone (VISUALS VA-BL-11), so its key names the state and it is drawn once.
    */
  override def mood(view: MoodCardView, look: CardLook): Option[CardPage] =
    val state = MoodState.captured(view.valence.step, view.energy.map(_.step))
    val reading = MoodReading(state, isEnergyGiven = view.energy.isDefined)
    val drawing = contextOf(look)
    val key = s"mood.${state.id}.${if reading.isEnergyGiven then "energy" else "plain"}.${keyOf(drawing)}"
    Some(page(MoodCard.render(Some(reading), drawing), Some(key)))

  override def water(view: WaterCardView, look: CardLook): Option[CardPage] =
    Some(page(WaterCard.render(waterData(view), contextOf(look))))

  override def day(view: DayCardView, look: CardLook): Option[CardPage] =
    val data = DayData(
      date = view.date,
      mood = view.mood.map: mood =>
        MoodReading(MoodState.captured(mood.valence.step, mood.energy.map(_.step)), mood.energy.isDefined),
      medication = view.doses.map(_.map(statusOf).toVector),
      water = view.water.map(water => WaterProgress(water.totalMl, water.goalMl)),
      food = view.food.map(line => MealProgress(line.logged, line.planned)),
      sleep = view.sleep.map(sleep =>
        sleep.minutes.fold(SleepEvidence.NoRecord)(SleepEvidence.Recorded(_, sleep.goalMinutes))
      )
    )
    Some(page(DayCard.render(data, contextOf(look))))

  override def sleep(view: SleepRegularityFigures, look: CardLook): Option[CardPage] =
    Some(page(SleepRegularityCard.render(ReportCards.regularity(view), contextOf(look))))

  /** IMG-05: page 1, then pages 2 and 3 when they have a panel. */
  override def month(view: MonthCardView, look: CardLook): List[CardPage] =
    val context = contextOf(look)
    val figures = view.figures
    val days = ReportCards.monthDays(figures, view)
    val nights = ReportCards.monthNights(figures, view)
    List(
      Some(MonthCard.render(ReportCards.monthPage(figures), context)),
      Option.unless(days.isEmpty)(MonthPages.days(days, context)),
      Option.unless(nights.isEmpty)(MonthPages.nights(nights, context))
    ).flatten.map(page(_))

  override def insight(view: Comparison.Shown, look: CardLook): Option[CardPage] =
    ReportCards.insight(view).map(data => page(InsightCard.render(data, contextOf(look))))

  override def week(view: WeekCardView, look: CardLook): Option[CardPage] =
    Some(page(WeekCard.render(ReportCards.week(view), contextOf(look))))

  override def food(view: FoodCardView, look: CardLook): Option[CardPage] =
    val mains = view.mains.collect { case (mealType, at) if mealType.isMain => mainMealOf(mealType) -> at }
    val data = FoodDayData(view.date, mains, view.snacks, MealProgress(view.line.logged, view.line.planned))
    Some(page(FoodDayCard.render(data, contextOf(look))))

  /** IMG-15 (ROADMAP G9.10): the unit word is the user's countable dose unit, or "units". */
  override def refill(view: RefillCardView, look: CardLook): Option[CardPage] =
    val data = RefillData(
      view.label,
      view.onHand,
      view.unit.getOrElse("units"),
      view.thresholdUnits,
      view.threshold,
      view.coversUntil,
      view.text
    )
    Some(page(RefillCard.render(data, contextOf(look))))

/** Stage 5 of the pipeline for the report worker (ROADMAP G4.3): lints and rasterises a page, keeps the PNG in the
  * asset store and returns the reference with the page's text equivalent as its alt text. A shared picture that the
  * store already holds is not drawn again; a personal one is keyed by its own bytes, so the same picture is stored once
  * and the key says nothing. A page that cannot be drawn is logged by its template and the kind of failure only.
  */
final class RenderedCardDrawing(
    renderer: CardRenderer,
    store: AssetStore,
    log: String => Unit,
    notices: DrawingNotices
) extends CardDrawing:

  import RenderedStatusCards.*

  override def draw(page: CardPage, chat: Option[ChatRef]): Either[String, AttachmentRef] =
    page.sharedKey.filter(store.holds) match
      case Some(key) => Right(reference(key, page))
      case None      =>
        rasterised(page, chat).map: png =>
          val key = page.sharedKey.getOrElse(
            s"card.${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png))}"
          )
          store.put(key, png)
          reference(key, page)

  // A drawing that is still running after a second shows the upload status in its chat (VISUALS VA-FB-02); the
  // rasteriser's own deadline ends it at five seconds, and the worker then sends the text (VA-FB-01).
  private def rasterised(page: CardPage, chat: Option[ChatRef]): Either[String, Array[Byte]] =
    val notice = chat.map(target =>
      Slow.schedule((() => notices.drawing(target)): Runnable, SlowAfterMillis, TimeUnit.MILLISECONDS)
    )
    try
      renderer.document(page.svg) match
        case Right(image)  => Right(image.toArray)
        case Left(failure) =>
          log(s"status image not drawn: template=${page.templateId} reason=${failure.productPrefix}")
          Left(failure.productPrefix)
    finally notice.foreach(_.cancel(false))

object RenderedStatusCards:

  /** VISUALS VA-FB-02: the status is shown when a drawing is expected to exceed one second. */
  private[app] val SlowAfterMillis = 1000L

  // One timer thread for every drawing of the process; it only ever sends a status.
  private[app] val Slow: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())

  /** Stage 4: the scene as SVG of profile 1, with the card's text equivalent, file name and spoiler flag. */
  private def page(card: CardOutput, sharedKey: Option[String] = None): CardPage =
    CardPage(
      card.scene.templateId,
      SvgEmitter.emit(card.scene),
      card.text.altText,
      card.fileName,
      card.hasSpoiler,
      sharedKey
    )

  private[app] def reference(key: String, page: CardPage): AttachmentRef =
    AttachmentRef(MediaKind.Photo, key, "image/png", page.altText, Some(page.fileName), page.hasSpoiler)

  private val fitter = TextFitter(TableTextMeasurer)

  /** The account's look as the renderer draws it. No name is ever drawn in the chat (L1); discreet mode is L1 with its
    * neutral labels (VA-PV-03). `auto` follows the colour scheme the Mini App last reported, and no report is kept yet,
    * so it is the default, dark (VISUALS section 3.3.3).
    */
  private def contextOf(look: CardLook): CardContext =
    val theme = look.theme match
      case ImageTheme.Light                  => Theme.Light
      case ImageTheme.Dark | ImageTheme.Auto => Theme.Dark
    val copy = Lang.current match
      case Lang.En => EnglishImageCopy
      case Lang.Uk => UkrainianImageCopy
    CardContext(theme, if look.isDiscreet then Privacy.Discreet else Privacy.Aggregate, copy, fitter)

  /** Theme and privacy are part of every cache key (VISUALS section 3.3.3). */
  private def keyOf(context: CardContext): String =
    val theme = context.theme.toString.toLowerCase(java.util.Locale.ROOT)
    // The words are part of a shared card, so a language other than English is part of its key (ROADMAP G2.22).
    val language = if context.copy == EnglishImageCopy then theme else s"$theme.${Lang.current.code}"
    if context.privacy == Privacy.Discreet then s"$language.discreet" else language

  private def mainMealOf(mealType: MealType): MainMeal =
    mealType match
      case MealType.Breakfast => MainMeal.Breakfast
      case MealType.Lunch     => MainMeal.Lunch
      case MealType.Dinner    => MainMeal.Dinner
      case MealType.Snack     => throw IllegalArgumentException("a snack has no slot of its own on the plate")

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

/** The image side of one process: the store the adapters load attachments from, the `file_id` cache of the uploads, the
  * cards the chat flows lay out and the drawing the report worker runs. Both halves share the store, so an image the
  * worker drew is the image the adapter uploads.
  */
final class StatusImages(
    val store: AssetStore,
    val fileIds: FileIdCache,
    val cards: StatusCards,
    val notices: DrawingNotices = DrawingNotices(_ => ()),
    val drawing: CardDrawing = CardDrawing.unavailable
)

object StatusImages:

  /** Mood states are 70 images at most; the rest are the personal cards of the last few minutes. */
  private val AssetCapacity = 256

  /** Where the bytes and the file ids of the images are kept. */
  final case class Stores(assets: AssetStore, fileIds: FileIdCache)

  object Stores:

    /** Stores of one process: an image and its file id are gone with it. */
    def inMemory: Stores = Stores(InMemoryAssetStore(AssetCapacity), InMemoryFileIdCache())

    /** The durable stores (`render_assets`, `telegram_file_cache`). `botId` is the bot whose file ids these are; a
      * process without that adapter never asks for one.
      */
    def durable(dataSource: DataSource, botId: Long, clock: Clock): Stores =
      Stores(
        PgAssetStore(PgRenderAssets(dataSource), clock),
        PgFileIdCache(PgTelegramFileCache(dataSource, botId), clock)
      )

  /** No renderer: every image is its text equivalent. */
  def textOnly: StatusImages =
    val stores = Stores.inMemory
    StatusImages(stores.assets, stores.fileIds, StatusCards.textOnly)

  def over(rasteriser: Rasteriser, stores: Stores, log: String => Unit): StatusImages =
    val notices = DrawingNotices(log)
    val drawing = RenderedCardDrawing(CardRenderer(rasteriser), stores.assets, log, notices)
    StatusImages(stores.assets, stores.fileIds, RenderedStatusCards(), notices, drawing)

  /** The renderer the settings name, or text only without them. A binary that cannot run fails the start: a bot that
    * silently never draws is worse than one that does not start.
    */
  def from(render: Option[RenderSettings], stores: Stores, log: String => Unit): StatusImages =
    render match
      case None           => StatusImages(stores.assets, stores.fileIds, StatusCards.textOnly)
      case Some(settings) =>
        val binary = Path.of(settings.resvgPath)
        val fonts = Path.of(settings.fontsDir)
        if !Files.isExecutable(binary) then throw IllegalArgumentException("RESVG_PATH does not name an executable")
        if !Files.isDirectory(fonts) then throw IllegalArgumentException("RENDER_FONTS_DIR does not name a directory")
        val emptyResources = Files.createTempDirectory("dosegram-resvg-empty")
        over(ResvgRasteriser(ResvgConfig.standard(binary, fonts, emptyResources)), stores, log)
