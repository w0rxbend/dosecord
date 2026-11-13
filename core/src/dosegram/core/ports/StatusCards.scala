package dosegram.core.ports

import dosegram.contracts.AttachmentRef
import dosegram.contracts.ChatRef
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence

import java.time.LocalDate

/** One mood check-in as its image shows it: the captured steps and nothing else (VISUALS VA-BL-01). */
final case class MoodCardView(valence: Valence, energy: Option[Energy])

/** The water of one local day against the user's own goal. */
final case class WaterCardView(date: LocalDate, totalMl: Int, goalMl: Int, entries: Int)

/** The night that ended on a day against the user's own sleep goal; `minutes` is absent when no night is recorded. */
final case class SleepCardView(minutes: Option[Int], goalMinutes: Int)

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
    sleep: Option[SleepCardView]
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

object StatusCards:

  /** No renderer is wired: every flow answers with its text. */
  val textOnly: StatusCards = new StatusCards:
    override def mood(view: MoodCardView, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] = None
    override def water(view: WaterCardView, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] = None
    override def day(view: DayCardView, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] = None
    override def sleep(view: SleepRegularityFigures, look: CardLook, chat: Option[ChatRef]): Option[AttachmentRef] =
      None
