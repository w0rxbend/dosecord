package dosegram.core.ports

import dosegram.contracts.AttachmentRef
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence

import java.time.LocalDate

/** One mood check-in as its image shows it: the captured steps and nothing else (VISUALS VA-BL-01). */
final case class MoodCardView(valence: Valence, energy: Option[Energy])

/** The water of one local day against the user's own goal. */
final case class WaterCardView(date: LocalDate, totalMl: Int, goalMl: Int, entries: Int)

/** How one dose of a day is drawn. The names are the status vocabulary of BOT_UX section 22.4. */
enum DoseMark:
  case TakenOnTime, TakenLate, Skipped, NotConfirmed, NoRecord, Open

/** The daily summary. `doses` and `water` are absent for a domain that is not shown; `mood` is absent on a day with no
  * check-in.
  */
final case class DayCardView(
    date: LocalDate,
    mood: Option[MoodCardView],
    doses: Option[List[DoseMark]],
    water: Option[WaterCardView]
)

/** The status images of VISUALS_AND_ANALYTICS as the chat flows ask for them (ARCHITECTURE DEP-02: the core never holds
  * image bytes). An implementation renders the card, keeps the bytes where the adapter's asset lookup finds them and
  * returns the reference with the text equivalent as its alt text. `None` means there is no image now: the caller sends
  * its text alone (VISUALS VA-FB-01).
  */
trait StatusCards:
  def mood(view: MoodCardView): Option[AttachmentRef]
  def water(view: WaterCardView): Option[AttachmentRef]
  def day(view: DayCardView): Option[AttachmentRef]

object StatusCards:

  /** No renderer is wired: every flow answers with its text. */
  val textOnly: StatusCards = new StatusCards:
    override def mood(view: MoodCardView): Option[AttachmentRef] = None
    override def water(view: WaterCardView): Option[AttachmentRef] = None
    override def day(view: DayCardView): Option[AttachmentRef] = None
