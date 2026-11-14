package dosegram.core.ports

import dosegram.contracts.AttachmentRef
import dosegram.contracts.ChatRef
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.MonthFigures
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType

import java.time.LocalDate
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

/** IMG-04: seven days from `from`. A domain that is not shown is absent; a day without a record is `None`. */
final case class WeekCardView(
    from: LocalDate,
    doses: Option[List[List[DoseMark]]],
    water: Option[List[Option[WeekDayWater]]],
    mood: Option[List[Option[WeekDayMood]]],
    sleep: Option[List[Option[WeekDayNight]]],
    food: Option[List[Option[WeekDayFood]]] = None
)

/** How the account wants its images drawn: the image theme (VISUALS_AND_ANALYTICS section 3.3.3) and discreet mode
  * (VA-PV-03). Both are account settings the Mini App changes.
  */
final case class CardLook(theme: ImageTheme, isDiscreet: Boolean)

object CardLook:
  def of(settings: AccountSettings): CardLook = CardLook(settings.imageTheme, settings.isDiscreet)

/** One card as an event lays it out (stages 2 to 4 of ARCHITECTURE section 12.1, pure and without input or output): the
  * SVG document of profile 1, its text equivalent, a neutral file name and the spoiler flag. It holds no image bytes
  * (DEP-02): the report worker rasterises it after the event's transaction (ROADMAP G4.3, RND-05). `sharedKey` names a
  * picture that is the same for every account, a mood state (VISUALS VA-BL-11), which is drawn and uploaded once under
  * that key; a personal card has none and is keyed by its bytes.
  */
final case class CardPage(
    templateId: String,
    svg: String,
    altText: String,
    fileName: String,
    hasSpoiler: Boolean,
    sharedKey: Option[String] = None
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

  /** IMG-13: one comparison the insight engine passed; `None` for a rule the card cannot draw. */
  def insight(view: Comparison.Shown, look: CardLook): Option[CardPage] = None

  /** IMG-04: the weekly card. */
  def week(view: WeekCardView, look: CardLook): Option[CardPage] = None

  /** IMG-09, the food day plate. */
  def food(view: FoodCardView, look: CardLook): Option[CardPage]

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

object CardDrawing:

  /** No rasteriser is wired: every page is answered with its text equivalent. */
  val unavailable: CardDrawing = (_, _) => Left("Unavailable")
