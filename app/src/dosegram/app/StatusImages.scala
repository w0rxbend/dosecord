package dosegram.app

import dosegram.adapter.telegram.FileIdCache
import dosegram.adapter.telegram.InMemoryFileIdCache
import dosegram.adapter.telegram.MediaStore
import dosegram.contracts.AttachmentRef
import dosegram.contracts.ChatRef
import dosegram.contracts.MediaKind
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HighlightDomain
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.WeekdayMood
import dosegram.core.domain.insights.YearFigures
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardMotion
import dosegram.core.ports.CardPage
import dosegram.core.ports.Clock
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.FoodCardView
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MedicationStripView
import dosegram.core.ports.MonthCardView
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.MoodGalleryView
import dosegram.core.ports.RefillCardView
import dosegram.core.ports.ShareCardView
import dosegram.core.ports.StatusCards
import dosegram.core.ports.TrendsCardView
import dosegram.core.ports.WaterCardView
import dosegram.core.ports.WeekCardView
import dosegram.core.ports.WeekHighlightsView
import dosegram.infra.RenderSettings
import dosegram.infra.crypto.DataKeys
import dosegram.infra.db.PgRenderAssets
import dosegram.infra.db.PgTelegramFileCache
import dosegram.render.CardRenderer
import dosegram.render.Rasteriser
import dosegram.render.blob.BlobFigure
import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.CardContext
import dosegram.render.card.CardOutput
import dosegram.render.card.DayCard
import dosegram.render.card.DayData
import dosegram.render.card.FoodDayCard
import dosegram.render.card.FoodDayData
import dosegram.render.card.GalleryCheckIn
import dosegram.render.card.HighlightFigure
import dosegram.render.card.HighlightObservation
import dosegram.render.card.HighlightRow
import dosegram.render.card.InsightCard
import dosegram.render.card.MainMeal
import dosegram.render.card.MealProgress
import dosegram.render.card.MedicationRow
import dosegram.render.card.MedicationStripCard
import dosegram.render.card.MonthCard
import dosegram.render.card.MonthPages
import dosegram.render.card.MonthPosterCard
import dosegram.render.card.MoodCard
import dosegram.render.card.MoodGalleryCard
import dosegram.render.card.MoodGalleryData
import dosegram.render.card.MoodMotionCard
import dosegram.render.card.MoodWeekdayCard
import dosegram.render.card.MoodWeekdayData
import dosegram.render.card.MoodWeekdayRow
import dosegram.render.card.Privacy
import dosegram.render.card.RefillCard
import dosegram.render.card.RefillData
import dosegram.render.card.ShareCard
import dosegram.render.card.ShareData
import dosegram.render.card.ShareFact
import dosegram.render.card.SleepEvidence
import dosegram.render.card.SleepRegularityCard
import dosegram.render.card.SleepWeekCard
import dosegram.render.card.StripData
import dosegram.render.card.TrendRow
import dosegram.render.card.TrendScale
import dosegram.render.card.TrendsCard
import dosegram.render.card.TrendsData
import dosegram.render.card.WaterCard
import dosegram.render.card.WaterData
import dosegram.render.card.WaterProgress
import dosegram.render.card.WaterWeekCard
import dosegram.render.card.WeekCard
import dosegram.render.card.WeekGlanceCard
import dosegram.render.card.WeekHighlightsCard
import dosegram.render.card.WeekHighlightsData
import dosegram.render.card.YearCard
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.motion.MotionRenderer
import dosegram.render.resvg.ResvgConfig
import dosegram.render.resvg.ResvgRasteriser
import dosegram.render.resvg.ResvgWorkerPool
import dosegram.render.svg.SvgEmitter
import dosegram.render.text.TableTextMeasurer
import dosegram.render.text.TextFitter
import dosegram.render.tokens.Domain
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
  /** Stores the bytes of a drawn image, or of an animation, under the key with their media type. */
  def put(assetKey: String, bytes: Array[Byte], mime: String): Unit
  def holds(assetKey: String): Boolean

  final def put(assetKey: String, png: Array[Byte]): Unit = put(assetKey, png, AssetStore.Png)

object AssetStore:
  val Png = "image/png"
  val Mp4 = "video/mp4"

/** An asset store for the life of one process, for a composition without a database behind its images (the text-only
  * profile and the suites). It holds at most `maximumImages` images and drops the one that was read longest ago.
  */
final class InMemoryAssetStore(maximumImages: Int) extends AssetStore:
  require(maximumImages > 0, "an asset store holds at least one image")

  // Access order: a read moves the image to the young end, so the eldest entry is the one read longest ago.
  private val images = new java.util.LinkedHashMap[String, Array[Byte]](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[String, Array[Byte]]): Boolean = size() > maximumImages

  override def put(assetKey: String, bytes: Array[Byte], mime: String): Unit = images.synchronized:
    images.put(assetKey, bytes)
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
    val key =
      s"mood.${BlobFigure.Revision}.${state.id}.${if reading.isEnergyGiven then "energy" else "plain"}.${keyOf(drawing)}"
    val motion = Option.when(look.isAnimated)(moodMotion(reading, drawing))
    Some(page(look)(MoodCard.render(Some(reading), drawing), Some(key)).copy(motion = motion))

  /** The animated form of the mood card (VISUALS section 4.6). It carries no words, so one picture serves every
    * language and both answers to energy: its key names the drawing and motion revisions, the state and the theme.
    */
  private def moodMotion(reading: MoodReading, context: CardContext): CardMotion =
    val output = MoodMotionCard.render(reading, context)
    val theme = context.theme.toString.toLowerCase(java.util.Locale.ROOT)
    val key = s"mood.motion.${BlobFigure.Revision}.${MoodMotionCard.Revision}.${reading.state.id}.$theme"
    CardMotion(output.frames.toList.map(SvgEmitter.emit), output.framesPerSecond, key, output.fileName)

  override def water(view: WaterCardView, look: CardLook): Option[CardPage] =
    Some(page(look)(WaterCard.render(waterData(view), contextOf(look))))

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
    Some(page(look)(DayCard.render(data, contextOf(look))))

  override def sleep(view: SleepRegularityFigures, look: CardLook): Option[CardPage] =
    Some(page(look)(SleepRegularityCard.render(ReportCards.regularity(view), contextOf(look))))

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
    ).flatten.map(page(look)(_))

  /** IMG-24: the month in one image, when the account shows water, food, sleep or mood. */
  override def monthPoster(view: MonthCardView, look: CardLook): Option[CardPage] =
    ReportCards.monthPoster(view.figures).map(data => page(look)(MonthPosterCard.render(data, contextOf(look))))

  /** IMG-30 (analytics round 6): the one-year card, when water, food, sleep or mood has a record in the year. */
  override def year(view: YearFigures, look: CardLook): Option[CardPage] =
    ReportCards.year(view).map(data => page(look)(YearCard.render(data, contextOf(look))))

  override def insight(view: Comparison.Shown, look: CardLook): Option[CardPage] =
    ReportCards.insight(view).map(data => page(look)(InsightCard.render(data, contextOf(look))))

  override def week(view: WeekCardView, look: CardLook): Option[CardPage] =
    Some(page(look)(WeekCard.render(ReportCards.week(view), contextOf(look))))

  override def share(view: ShareCardView, look: CardLook): Option[CardPage] =
    val fact = view match
      case ShareCardView.WaterGoal(days)        => ShareFact.DayCount(Domain.Water, days.toVector)
      case ShareCardView.SleepLogged(days)      => ShareFact.DayCount(Domain.Sleep, days.toVector)
      case ShareCardView.MoodLogged(days)       => ShareFact.DayCount(Domain.Mood, days.toVector)
      case ShareCardView.WeekMood(mean, energy) => ShareFact.WeekMood(ReportCards.moodState(mean, energy))
    // The user confirmed the share a second time in discreet mode, and the card is its sentence: the caption stays that
    // sentence (VISUALS section 6.18), while the photo still goes behind the spoiler.
    Some(page(look.copy(isDiscreet = false))(ShareCard.render(ShareData(fact), contextOf(look))))

  override def food(view: FoodCardView, look: CardLook): Option[CardPage] =
    val mains = view.mains.collect { case (mealType, at) if mealType.isMain => mainMealOf(mealType) -> at }
    val data = FoodDayData(view.date, mains, view.snacks, MealProgress(view.line.logged, view.line.planned))
    Some(page(look)(FoodDayCard.render(data, contextOf(look))))

  /** IMG-15 (ROADMAP G9.10): the unit word is the user's countable dose unit, or "units", in the account's language. */
  override def refill(view: RefillCardView, look: CardLook): Option[CardPage] =
    val data = RefillData(
      view.label,
      view.onHand,
      view.unit,
      view.thresholdUnits,
      view.threshold,
      view.coversUntil,
      view.text
    )
    Some(page(look)(RefillCard.render(data, contextOf(look))))

  override def weekGlance(view: WeekCardView, look: CardLook): Option[CardPage] =
    Some(page(look)(WeekGlanceCard.render(ReportCards.week(view), contextOf(look))))

  override def waterWeek(view: WeekCardView, look: CardLook): Option[CardPage] =
    ReportCards.week(view).water.map(days => page(look)(WaterWeekCard.render(view.from, days, contextOf(look))))

  override def sleepWeek(view: WeekCardView, look: CardLook): Option[CardPage] =
    ReportCards.week(view).sleep.map(nights => page(look)(SleepWeekCard.render(view.from, nights, contextOf(look))))

  /** IMG-23 (ROADMAP G9.4, G9.5): mood is drawn on its 1 to 5 scale, every other domain as bars from zero. */
  override def trends(view: TrendsCardView, look: CardLook): Option[CardPage] =
    Option.when(view.rows.nonEmpty):
      val rows = view.rows.toVector.map: row =>
        val (domain, scale) = row.domain match
          case TrendDomain.Medication => Domain.Medication -> TrendScale.FromZero
          case TrendDomain.Hydration  => Domain.Water -> TrendScale.FromZero
          case TrendDomain.Mood       => Domain.Mood -> TrendScale.Levels
          case TrendDomain.Sleep      => Domain.Sleep -> TrendScale.FromZero
          case TrendDomain.Nutrition  => Domain.Food -> TrendScale.FromZero
        TrendRow(domain, row.figure, row.buckets.toVector, scale)
      val data = TrendsData(view.window.start, view.window.end, view.byWeek, rows, view.text)
      page(look)(TrendsCard.render(data, contextOf(look)))

  /** IMG-07 (supervisor decision, 2026-11-27): the chat draws no name (L1), so the rows are "Item 1" onward in registry
    * order; each day holds its scheduled doses, then one as-needed glyph per log.
    */
  override def medicationStrip(view: MedicationStripView, look: CardLook): List[CardPage] =
    val rows = view.rows.toVector.map: row =>
      val days = row.days
        .zip(row.logs)
        .toVector
        .map((marks, logs) => marks.toVector.map(statusOf) ++ Vector.fill(logs)(DoseStatus.AsNeeded))
      MedicationRow(row.name, row.asNeeded, days)
    MedicationStripCard.render(StripData(view.from, view.dayCount, rows), contextOf(look)).toList.map(page(look)(_))

  /** IMG-26 (image round 5): each check-in as its captured state, the chat's five steps on the blob's scale. */
  override def moodGallery(view: MoodGalleryView, look: CardLook): Option[CardPage] =
    Option.when(view.entries.nonEmpty):
      val checkIns = view.entries
        .takeRight(MoodGalleryCard.MaxCheckIns)
        .toVector
        .map: entry =>
          val state = MoodState.captured(entry.valence.step, entry.energy.map(_.step))
          GalleryCheckIn(entry.at, MoodReading(state, entry.energy.isDefined))
      page(look)(MoodGalleryCard.render(MoodGalleryData(checkIns), contextOf(look)))

  /** IMG-25 (image round 5): the highest days of each shown domain and the weekly report's observation. */
  override def weekHighlights(view: WeekHighlightsView, look: CardLook): Option[CardPage] =
    Option.when(view.rows.nonEmpty):
      val rows = view.rows.toVector.map: row =>
        val (domain, figure) = row.domain match
          case HighlightDomain.Water => Domain.Water -> ((value: Double) => HighlightFigure.Water(value.toInt))
          case HighlightDomain.Food  => Domain.Food -> ((value: Double) => HighlightFigure.MainMeals(value.toInt))
          case HighlightDomain.Mood  => Domain.Mood -> ((value: Double) => HighlightFigure.Mood(value))
          case HighlightDomain.Sleep => Domain.Sleep -> ((value: Double) => HighlightFigure.Night(value.toInt))
        HighlightRow(domain, row.recorded.toVector, row.top.map((days, value) => days.toVector -> figure(value)))
      val observation = view.observation.map(seen => HighlightObservation(seen.windowDays, seen.sentence))
      page(look)(WeekHighlightsCard.render(WeekHighlightsData(view.from, rows, observation), contextOf(look)))

  override def moodWeekday(window: DateRange, weekdays: List[WeekdayMood], look: CardLook): Option[CardPage] =
    val rows = weekdays.toVector.map(weekday => MoodWeekdayRow(weekday.day, weekday.mean.shown, weekday.days))
    Option.when(rows.size == 7 && rows.exists(_.checkInDays > 0))(
      page(look)(MoodWeekdayCard.render(MoodWeekdayData(window.start, window.end, rows), contextOf(look)))
    )

/** Stage 5 of the pipeline for the report worker (ROADMAP G4.3): lints and rasterises a page, keeps the PNG in the
  * asset store and returns the reference with the page's text equivalent as its alt text. A shared picture that the
  * store already holds is not drawn again; a personal one is keyed by its own bytes, so the same picture is stored once
  * and the key says nothing. A page that cannot be drawn is logged by its template and the kind of failure only.
  */
final class RenderedCardDrawing(
    renderer: CardRenderer,
    motions: MotionRenderer,
    store: AssetStore,
    fileIds: FileIdCache,
    log: String => Unit,
    notices: DrawingNotices
) extends CardDrawing:

  import RenderedStatusCards.*

  /** The still card first; a page with an animated form then becomes the animation, with the still as its fallback, or
    * stays the still when the video cannot be made within its budget (VISUALS section 4.6).
    */
  override def draw(page: CardPage, chat: Option[ChatRef]): Either[String, AttachmentRef] =
    still(page, chat).map(photo => page.motion.flatMap(animated(_, photo, page, chat)).getOrElse(photo))

  /** A shared still that is stored or uploaded, with its animation when that is too; a personal card is never ready,
    * because its key is its drawn bytes.
    */
  override def ready(page: CardPage): Option[AttachmentRef] =
    page.sharedKey
      .filter(isKept(_, MediaKind.Photo))
      .map: key =>
        val photo = reference(key, page)
        page.motion.filter(motion => isKept(motion.sharedKey, MediaKind.Animation)).fold(photo)(animation(_, photo))

  private def isKept(key: String, kind: MediaKind): Boolean = store.holds(key) || fileIds.get(key, kind).isDefined

  private def still(page: CardPage, chat: Option[ChatRef]): Either[String, AttachmentRef] =
    page.sharedKey.filter(store.holds) match
      case Some(key) => Right(reference(key, page))
      case None      =>
        rasterised(page, chat).map: png =>
          val key = page.sharedKey.getOrElse(
            s"card.${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png))}"
          )
          store.put(key, png)
          reference(key, page)

  // An animation is the same for everyone in its state and theme: once it is stored or uploaded it is not drawn again.
  // A video that cannot be made is logged by its template and the kind of failure only, and the still is sent.
  private def animated(
      motion: CardMotion,
      photo: AttachmentRef,
      page: CardPage,
      chat: Option[ChatRef]
  ): Option[AttachmentRef] =
    val isReady = isKept(motion.sharedKey, MediaKind.Animation)
    def drawn: Boolean =
      withNotice(chat)(motions.render(motion.frames.toVector, motion.framesPerSecond)) match
        case Right(video) =>
          store.put(motion.sharedKey, video.toArray, AssetStore.Mp4)
          true
        case Left(failure) =>
          log(s"animation not drawn, still sent: template=${page.templateId} reason=${failure.productPrefix}")
          false
    Option.when(isReady || drawn)(animation(motion, photo))

  private def animation(motion: CardMotion, photo: AttachmentRef): AttachmentRef =
    AttachmentRef(
      MediaKind.Animation,
      motion.sharedKey,
      AssetStore.Mp4,
      photo.altText,
      Some(motion.fileName),
      photo.hasSpoiler,
      photo.caption,
      still = Some(photo)
    )

  // A drawing that is still running after a second shows the upload status in its chat (VISUALS VA-FB-02); the
  // rasteriser's own deadline ends it at five seconds, and the worker then sends the text (VA-FB-01).
  private def rasterised(page: CardPage, chat: Option[ChatRef]): Either[String, Array[Byte]] =
    withNotice(chat):
      renderer.document(page.svg) match
        case Right(image)  => Right(image.toArray)
        case Left(failure) =>
          log(s"status image not drawn: template=${page.templateId} reason=${failure.productPrefix}")
          Left(failure.productPrefix)

  private def withNotice[A](chat: Option[ChatRef])(drawing: => A): A =
    val notice = chat.map(target =>
      Slow.schedule((() => notices.drawing(target)): Runnable, SlowAfterMillis, TimeUnit.MILLISECONDS)
    )
    try drawing
    finally notice.foreach(_.cancel(false))

object RenderedStatusCards:

  /** VISUALS VA-FB-02: the status is shown when a drawing is expected to exceed one second. */
  private[app] val SlowAfterMillis = 1000L

  // One timer thread for every drawing of the process; it only ever sends a status.
  private[app] val Slow: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())

  /** Stage 4: the scene as SVG of profile 1, with the card's text equivalent, file name and spoiler flag. */
  private def page(look: CardLook)(card: CardOutput, sharedKey: Option[String] = None): CardPage =
    CardPage(
      card.scene.templateId,
      SvgEmitter.emit(card.scene),
      card.text.altText,
      card.fileName,
      card.hasSpoiler,
      sharedKey,
      Option.when(look.isDiscreet)(card.text.caption)
    )

  private[app] def reference(key: String, page: CardPage): AttachmentRef =
    AttachmentRef(
      MediaKind.Photo,
      key,
      "image/png",
      page.altText,
      Some(page.fileName),
      page.hasSpoiler,
      page.discreetCaption
    )

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
    def durable(dataSource: DataSource, botId: Long, clock: Clock, keys: DataKeys): Stores =
      Stores(
        PgAssetStore(PgRenderAssets(dataSource, keys), clock),
        PgFileIdCache(PgTelegramFileCache(dataSource, botId, keys), clock)
      )

  /** No renderer: every image is its text equivalent. */
  def textOnly: StatusImages =
    val stores = Stores.inMemory
    StatusImages(stores.assets, stores.fileIds, StatusCards.textOnly)

  /** How long the report worker may spend on one animation before it sends the still card instead (supervisor decision
    * of 2026-11-28): a loop took about 2 seconds on the development host (PERFORMANCE.md), and the request's own
    * deadline (VISUALS VA-FB-01) is 5 seconds.
    */
  val MotionBudgetMillis: Long = 4000

  /** `motionBudgetMillis` is [[MotionBudgetMillis]] in production; a suite on a loaded build host gives more, as the
    * pixel tests give their rasteriser a minute.
    */
  def over(
      rasteriser: Rasteriser,
      stores: Stores,
      log: String => Unit,
      motionBudgetMillis: Long = MotionBudgetMillis
  ): StatusImages =
    val notices = DrawingNotices(log)
    val motions = MotionRenderer(rasteriser, motionBudgetMillis)
    val drawing = RenderedCardDrawing(CardRenderer(rasteriser), motions, stores.assets, stores.fileIds, log, notices)
    StatusImages(stores.assets, stores.fileIds, RenderedStatusCards(), notices, drawing)

  /** The renderer the settings name, or text only without them. A binary that cannot run fails the start: a bot that
    * silently never draws is worse than one that does not start.
    */
  def from(
      render: Option[RenderSettings],
      stores: Stores,
      log: String => Unit,
      motionBudgetMillis: Long = MotionBudgetMillis
  ): StatusImages =
    render match
      case None           => StatusImages(stores.assets, stores.fileIds, StatusCards.textOnly)
      case Some(settings) =>
        val binary = Path.of(settings.resvgPath)
        val fonts = Path.of(settings.fontsDir)
        if !Files.isExecutable(binary) then throw IllegalArgumentException("RESVG_PATH does not name an executable")
        if !Files.isDirectory(fonts) then throw IllegalArgumentException("RENDER_FONTS_DIR does not name a directory")
        val emptyResources = Files.createTempDirectory("dosegram-resvg-empty")
        settings.workerPath.map(Path.of(_)) match
          case None =>
            over(ResvgRasteriser(ResvgConfig.standard(binary, fonts, emptyResources)), stores, log, motionBudgetMillis)
          case Some(worker) =>
            if !Files.isExecutable(worker) then
              throw IllegalArgumentException("RENDER_WORKER_PATH does not name an executable")
            over(ResvgWorkerPool(ResvgConfig.standard(worker, fonts, emptyResources)), stores, log, motionBudgetMillis)
