package dosegram.core.ports

import dosegram.contracts.AttachmentRef
import dosegram.contracts.ChatRef
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DayHighlight
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.WeekdayMood
import dosegram.core.domain.insights.YearFigures
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One mood check-in as its image shows it: the captured steps and nothing else (VISUALS VA-BL-01). */
final case class MoodCardView(valence: Valence, energy: Option[Energy])

/** The water of one local day against the user's own goal. */
final case class WaterCardView(date: LocalDate, totalMl: Int, goalMl: Int, entries: Int)

/** The night that ended on a day against the user's own sleep goal; `minutes` is absent when no night is recorded. */
final case class SleepCardView(minutes: Option[Int], goalMinutes: Int)

/** The meals of a day on the daily summary: the planned main meals with an entry against the plan, or, with no planned
  * meal (`planned = 0`), the main meals logged as a count (VISUALS section 7.5).
  */
final case class FoodLineView(logged: Int, planned: Int)

/** IMG-09: the first time of each main meal logged on a day, the snacks, and the line of the day. No meal word, photo
  * or energy value is part of it.
  */
final case class FoodCardView(date: LocalDate, mains: Map[MealType, LocalTime], snacks: Int, line: FoodLineView):
  require(!mains.contains(MealType.Snack), "snacks are counted, not placed on the plate")

/** How one dose of a day is drawn. The names are the status vocabulary of BOT_UX section 22.4. */
enum DoseMark:
  case TakenOnTime, TakenLate, Skipped, NotConfirmed, NoRecord, Open

/** The daily summary. `doses`, `water` and `sleep` are absent for a domain that is not shown; `mood` is absent on a day
  * with no check-in.
  */
final case class DayCardView(
    date: LocalDate,
    mood: Option[MoodCardView],
    doses: Option[List[DoseMark]],
    water: Option[WaterCardView],
    sleep: Option[SleepCardView],
    food: Option[FoodLineView] = None
)

/** A month as the pages of its report draw it (VISUALS section 6.6): the figures, the month's nights on the clock when
  * the sleep consent is given, and the account's current local day, which the calendar marks.
  */
final case class MonthCardView(figures: MonthFigures, sleep: Option[SleepRegularityFigures], today: LocalDate)

/** One day's water in the weekly card, against the user's own goal of that day when there is one. */
final case class WeekDayWater(totalMl: Int, goalMl: Option[Int])

/** One day's mood in the weekly card: the daily mean and the mean energy on the stored 1 to 5 scales. */
final case class WeekDayMood(mean: Double, energyMean: Option[Double])

/** One night in the weekly card, with the user's own sleep goal when there is one. */
final case class WeekDayNight(minutes: Int, goalMinutes: Option[Int])

/** One day's meals in the weekly card: the main meal types with an entry (VISUALS section 7.5) and the snacks. */
final case class WeekDayFood(mainMeals: Int, snacks: Int)

/** IMG-17 (VISUALS section 6.18): the one fact of the last seven days the user chose to share. No case holds a
  * medication, a date, a time or free text (VA-IMG-17a).
  */
enum ShareCardView:
  /** The days, oldest first, with the water goal reached. */
  case WaterGoal(days: List[Boolean])

  /** The days, oldest first, with a night logged. */
  case SleepLogged(days: List[Boolean])

  /** The days, oldest first, with a mood check-in. */
  case MoodLogged(days: List[Boolean])

  /** The mean of the week's check-ins. */
  case WeekMood(meanValence: Double, meanEnergy: Option[Double])

/** IMG-04: seven days from `from`. A domain that is not shown is absent; a day without a record is `None`. */
final case class WeekCardView(
    from: LocalDate,
    doses: Option[List[List[DoseMark]]],
    water: Option[List[Option[WeekDayWater]]],
    mood: Option[List[Option[WeekDayMood]]],
    sleep: Option[List[Option[WeekDayNight]]],
    food: Option[List[Option[WeekDayFood]]] = None
)

/** IMG-15 (VISUALS section 6.16): the count the user keeps, the word of its unit after that count in the account's
  * language ("tablets", "таблеток"), their threshold as words, and the last day the count covers on their schedule;
  * `text` is the card's text equivalent, written by the caller from the same values.
  */
final case class RefillCardView(
    label: String,
    onHand: BigDecimal,
    unit: String,
    thresholdUnits: Option[BigDecimal],
    threshold: String,
    coversUntil: Option[LocalDate],
    text: String
)

/** One domain of IMG-23: its headline figure as the chat and the Mini App print it, and one value per bucket of the
  * period, oldest first, `None` for a bucket without a record.
  */
final case class TrendCardRow(domain: TrendDomain, figure: String, buckets: List[Option[Double]])

/** IMG-23 (ROADMAP G9.4, G9.5): the trends of a 30-day or 90-day period, by day or by week, one row per domain with a
  * record; `text` is the card's text equivalent, written by the caller from the same values.
  */
final case class TrendsCardView(window: DateRange, byWeek: Boolean, rows: List[TrendCardRow], text: String)

/** One medication of IMG-07 (VISUALS section 6.8): its name, whether it is taken as needed, and for each day of the
  * strip the marks of its scheduled doses in schedule order and the number of as-needed logs.
  */
final case class MedicationStripRow(name: String, asNeeded: Boolean, days: List[List[DoseMark]], logs: List[Int]):
  require(days.size == logs.size, "one entry of marks and one of logs per day")

/** IMG-07: the medications of the registry over the same days from `from`, in registry order. */
final case class MedicationStripView(from: LocalDate, dayCount: Int, rows: List[MedicationStripRow]):
  require(rows.forall(_.days.size == dayCount), "every row needs one entry per day")

// Image round 5 (supervisor decision, 2026-11-28): the blob gallery of `/mood history` (VISUALS IMG-26) and the week
// highlights of the weekly report (VISUALS IMG-25).

/** One check-in of the gallery at its own local time: the captured steps and nothing else (VISUALS VA-BL-01). */
final case class MoodGalleryEntry(at: LocalDateTime, valence: Valence, energy: Option[Energy])

/** IMG-26: the latest check-ins, oldest first. */
final case class MoodGalleryView(entries: List[MoodGalleryEntry])

/** The one observation of the weekly report under the insight rules, as the catalogue words it, and the days of the
  * window it was found in.
  */
final case class WeekObservation(windowDays: Int, sentence: String)

/** IMG-25: the seven days from `from`, the highlight of each shown domain with a record (never medication), and the
  * weekly report's observation when one passed the rules.
  */
final case class WeekHighlightsView(from: LocalDate, rows: List[DayHighlight], observation: Option[WeekObservation])

/** How the account wants its images drawn: the image theme (VISUALS_AND_ANALYTICS section 3.3.3), discreet mode
  * (VA-PV-03) and whether a mood answer moves (VISUALS section 4.6). All are account settings the Mini App changes.
  */
final case class CardLook(theme: ImageTheme, isDiscreet: Boolean, isAnimated: Boolean = false)

object CardLook:

  /** Discreet mode keeps every image still (supervisor decision, 2026-11-28): a moving picture draws the eye to a chat
    * the user asked to keep quiet, so the still card is sent as before.
    */
  def of(settings: AccountSettings): CardLook =
    CardLook(settings.imageTheme, settings.isDiscreet, isAnimated = settings.animatedMood && !settings.isDiscreet)

/** The animated form of a card (VISUALS section 4.6): its frames as SVG documents of profile 1, the frame rate, the key
  * of the picture, which is the same for everyone (a mood state, VA-BL-11), and a neutral file name. The report worker
  * draws it after the still card it belongs to and sends the still in its place when the video cannot be made in time.
  */
final case class CardMotion(frames: List[String], framesPerSecond: Int, sharedKey: String, fileName: String):
  require(frames.nonEmpty, "an animation has frames")
  require(framesPerSecond > 0, "a positive frame rate")

/** One card as an event lays it out (stages 2 to 4 of ARCHITECTURE section 12.1, pure and without input or output): the
  * SVG document of profile 1, its text equivalent, a neutral file name and the spoiler flag. It holds no image bytes
  * (DEP-02): the report worker rasterises it after the event's transaction (ROADMAP G4.3, RND-05). `sharedKey` names a
  * picture that is the same for every account, a mood state (VISUALS VA-BL-11), which is drawn and uploaded once under
  * that key; a personal card has none and is keyed by its bytes. `discreetCaption` is the card's own generic line when
  * it was laid out in discreet mode (VISUALS VA-PV-03): a message that carries the card shows it in place of its own
  * text, so a spoilered photo does not say under it what it shows. `motion` is the card's animated form, laid out only
  * when the account asked for it ([[CardLook.isAnimated]]).
  */
final case class CardPage(
    templateId: String,
    svg: String,
    altText: String,
    fileName: String,
    hasSpoiler: Boolean,
    sharedKey: Option[String] = None,
    discreetCaption: Option[String] = None,
    motion: Option[CardMotion] = None
)

/** The status images of VISUALS_AND_ANALYTICS as the chat flows ask for them, laid out in the account's [[CardLook]]
  * and not yet drawn. `None` means there is no image now: the caller sends its text alone (VISUALS VA-FB-01).
  */
trait StatusCards:
  def mood(view: MoodCardView, look: CardLook): Option[CardPage]
  def water(view: WaterCardView, look: CardLook): Option[CardPage]
  def day(view: DayCardView, look: CardLook): Option[CardPage]

  /** IMG-12, the sleep regularity chart of the recent nights. */
  def sleep(view: SleepRegularityFigures, look: CardLook): Option[CardPage]

  /** IMG-05: the pages of a month in order; empty when no page can be laid out. */
  def month(view: MonthCardView, look: CardLook): List[CardPage] = Nil

  /** IMG-24, the month in one image, sent after the pages of IMG-05; `None` when no area it draws is shown. */
  def monthPoster(view: MonthCardView, look: CardLook): Option[CardPage] = None

  /** IMG-30, the one-year card of `/stats year`; `None` when no area it draws has a record in the year. */
  def year(view: YearFigures, look: CardLook): Option[CardPage] = None

  /** IMG-13: one comparison the insight engine passed; `None` for a rule the card cannot draw. */
  def insight(view: Comparison.Shown, look: CardLook): Option[CardPage] = None

  /** IMG-04: the weekly card. */
  def week(view: WeekCardView, look: CardLook): Option[CardPage] = None

  /** IMG-17, the shareable card. */
  def share(view: ShareCardView, look: CardLook): Option[CardPage] = None

  /** IMG-09, the food day plate. */
  def food(view: FoodCardView, look: CardLook): Option[CardPage]

  /** IMG-15, the refill card of one medication (ROADMAP G9.10). */
  def refill(view: RefillCardView, look: CardLook): Option[CardPage] = None

  // The week cards of the image round of 2026-11-15 (VISUALS IMG-19 to IMG-22). The three week cards draw the same
  // seven days as IMG-04; a domain the view does not show has no card.

  /** IMG-19, the seven days of every shown domain as one grid of cells. */
  def weekGlance(view: WeekCardView, look: CardLook): Option[CardPage] = None

  /** IMG-20, the water of the seven days against the user's own goal. */
  def waterWeek(view: WeekCardView, look: CardLook): Option[CardPage] = None

  /** IMG-21, the main sleep of the seven nights against the user's own goal. */
  def sleepWeek(view: WeekCardView, look: CardLook): Option[CardPage] = None

  /** IMG-22, mood by weekday over `window`, Monday to Sunday (VISUALS section 7.6). */
  def moodWeekday(window: DateRange, weekdays: List[WeekdayMood], look: CardLook): Option[CardPage] = None

  /** IMG-23, the trends of a period, one row per domain on its own scale. */
  def trends(view: TrendsCardView, look: CardLook): Option[CardPage] = None

  /** IMG-07, the per-medication strip: one page per six medications at most; empty when none can be laid out. */
  def medicationStrip(view: MedicationStripView, look: CardLook): List[CardPage] = Nil

  /** IMG-26, the blob gallery of `/mood history`; `None` when no check-in can be drawn. */
  def moodGallery(view: MoodGalleryView, look: CardLook): Option[CardPage] = None

  /** IMG-25, the week highlights, the second photo of the weekly report. */
  def weekHighlights(view: WeekHighlightsView, look: CardLook): Option[CardPage] = None

object StatusCards:

  /** No renderer is wired: every flow answers with its text. */
  val textOnly: StatusCards = new StatusCards:
    override def mood(view: MoodCardView, look: CardLook): Option[CardPage] = None
    override def water(view: WaterCardView, look: CardLook): Option[CardPage] = None
    override def day(view: DayCardView, look: CardLook): Option[CardPage] = None
    override def sleep(view: SleepRegularityFigures, look: CardLook): Option[CardPage] = None
    override def food(view: FoodCardView, look: CardLook): Option[CardPage] = None

/** Stage 5 and the asset store (ARCHITECTURE section 12.1): rasterises a laid-out card and keeps its bytes where the
  * adapter loads them, outside every transaction. While a slow drawing runs, `chat` shows "sending a photo" (VISUALS
  * VA-FB-02). `Left` names the kind of failure only, never SVG or rasteriser text, which can quote user text
  * (VA-PV-09).
  */
trait CardDrawing:
  def draw(page: CardPage, chat: Option[ChatRef]): Either[String, AttachmentRef]

  /** The image of `page` when it needs no drawing: a shared picture already stored or uploaded, and for a page with an
    * animated form its video too (or its still alone when only the still is). A late request sends such a page as its
    * image, because the 5-second deadline of VISUALS VA-FB-01 bounds a render (supervisor decision, 2026-11-28).
    */
  def ready(page: CardPage): Option[AttachmentRef] = None

object CardDrawing:

  /** No rasteriser is wired: every page is answered with its text equivalent. */
  val unavailable: CardDrawing = (_, _) => Left("Unavailable")
