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

/** IMG-04: seven days from `from`. A domain that is not shown is absent; a day without a record is `None`. */
final case class WeekCardView(
    from: LocalDate,
    doses: Option[List[List[DoseMark]]],
    water: Option[List[Option[WeekDayWater]]],
    mood: Option[List[Option[WeekDayMood]]],
    sleep: Option[List[Option[WeekDayNight]]]
)

/** How the account wants its images drawn: the image theme (VISUALS_AND_ANALYTICS section 3.3.3) and discreet mode
  * (VA-PV-03). Both are account settings the Mini App changes.
  */
final case class CardLook(theme: ImageTheme, isDiscreet: Boolean)

object CardLook:
  def of(settings: AccountSettings): CardLook = CardLook(settings.imageTheme, settings.isDiscreet)

/** The status images of VISUALS_AND_ANALYTICS as the chat flows ask for them (ARCHITECTURE DEP-02: the core never holds
  * image bytes). An implementation renders the card, keeps the bytes where the adapter's asset lookup finds them and
  * returns the reference with the text equivalent as its alt text, drawn in the account's [[CardLook]]. `None` means
  * there is no image now: the caller sends its text alone (VISUALS VA-FB-01). `chat` is the chat the image is for,
  * where a slow drawing shows an upload status (VA-FB-02); without it no status is shown.
  */
trait StatusCards:
  def mood(view: MoodCardView, look: CardLook, chat: Option[ChatRef] = None): Option[AttachmentRef]
  def water(view: WaterCardView, look: CardLook, chat: Option[ChatRef] = None): Option[AttachmentRef]
  def day(view: DayCardView, look: CardLook, chat: Option[ChatRef] = None): Option[AttachmentRef]

  /** IMG-12, the sleep regularity chart of the recent nights. */
  def sleep(view: SleepRegularityFigures, look: CardLook, chat: Option[ChatRef] = None): Option[AttachmentRef]

  /** IMG-05: the pages of a month in order; empty when no page can be drawn now. */
  def month(view: MonthCardView, look: CardLook, chat: Option[ChatRef] = None): List[AttachmentRef] = Nil

  /** IMG-13: one comparison the insight engine passed; `None` for a rule the card cannot draw. */
  def insight(view: Comparison.Shown, look: CardLook, chat: Option[ChatRef] = None): Option[AttachmentRef] = None

  /** IMG-04: the weekly card. */
  def week(view: WeekCardView, look: CardLook, chat: Option[ChatRef] = None): Option[AttachmentRef] = None

  /** IMG-09, the food day plate. */
  def food(view: FoodCardView, look: CardLook, chat: Option[ChatRef] = None): Option[AttachmentRef]

object StatusCards:

  /** No renderer is wired: every flow answers with its text. */
  val textOnly: StatusCards = new StatusCards:
    override def mood(view: MoodCardView, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] = None
    override def water(view: WaterCardView, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] = None
    override def day(view: DayCardView, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] = None
    override def sleep(view: SleepRegularityFigures, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] =
      None
    override def food(view: FoodCardView, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] = None
