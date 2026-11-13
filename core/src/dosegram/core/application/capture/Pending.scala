package dosegram.core.application.capture

import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps

import java.time.Instant

/** What the user asked for when a consent was missing (BOT_UX BUX-CONS-04). It travels in the buttons of the consent
  * card and runs after the confirming tap, so the user does not repeat the input. `kind` and `argument` are the wire
  * form and must not change for a case once a card with it has been sent.
  */
private[capture] enum Pending(val kind: Int, val argument: Int):
  case WaterCard extends Pending(1, 0)
  case WaterAdd(millilitres: Int) extends Pending(2, millilitres)
  case MoodPrompt extends Pending(3, 0)
  case MoodCheckIn(step: Int) extends Pending(4, step)
  case Stats extends Pending(5, 0)
  case WaterImage extends Pending(6, 0)
  case DayImage extends Pending(7, 0)

  case SleepCard extends Pending(9, 0)

  /** A typed night or nap; it is typed again after the consent, because the times do not fit in a button. */
  case SleepTyped extends Pending(10, 0)
  case SleepImage extends Pending(11, 0)

  /** A medication command or button; the screen opens after the consent. */
  case Medication(screen: MedicationScreen) extends Pending(8, screen.code)

/** The medication screens a consent can lead to; `command` is the command that opens the screen. The codes are the wire
  * form.
  */
enum MedicationScreen(val code: Int, val command: String):
  case Today extends MedicationScreen(1, "today")
  case Medications extends MedicationScreen(2, "meds")
  case History extends MedicationScreen(3, "history")

private[capture] object Pending:

  def of(kind: Int, argument: Int): Option[Pending] =
    kind match
      case 1  => Some(WaterCard)
      case 2  => Some(WaterAdd(argument))
      case 3  => Some(MoodPrompt)
      case 4  => Some(MoodCheckIn(argument))
      case 5  => Some(Stats)
      case 6  => Some(WaterImage)
      case 7  => Some(DayImage)
      case 8  => MedicationScreen.values.find(_.code == argument).map(Medication(_))
      case 9  => Some(SleepCard)
      case 10 => Some(SleepTyped)
      case 11 => Some(SleepImage)
      case _  => None

/** The two stages of a consent (BUX-CONS-02) and the two ways out of them. The codes are the wire form. */
private[capture] enum ConsentStage(val code: Int):
  /** Show stage 1 (also "Go back" from stage 2). */
  case Ask extends ConsentStage(0)

  /** "I agree": show stage 2. */
  case Agree extends ConsentStage(1)

  /** "Confirm": record the consent and run the pending input. */
  case Confirm extends ConsentStage(2)

  /** "Not now". */
  case Decline extends ConsentStage(3)

/** The value of a `consent.step` token: stage in bits 0-1, purpose in bits 2-5, the pending kind in bits 6-9, its
  * argument in bits 10-25 and the minute of the "I agree" tap in bits 26-47. `agreedMinute` is 0 until that tap.
  */
private[capture] final case class ConsentRequest(
    stage: ConsentStage,
    purpose: ConsentPurpose,
    pending: Pending,
    agreedMinute: Int
):

  def value: Long =
    stage.code.toLong | (ConsentRequest.codeOf(purpose).toLong << 2) | (pending.kind.toLong << 6) |
      (pending.argument.toLong << 10) | (agreedMinute.toLong << 26)

  def at(next: ConsentStage): ConsentRequest = copy(stage = next)

  /** The request as the stage 2 card carries it: with the time of the first tap. */
  def agreedAt(tap: Instant): ConsentRequest = copy(agreedMinute = ConsentRequest.minuteOf(tap))

  /** Both taps for the ledger. The first one comes back to the minute: the token has 22 bits for it, which hold the
    * epoch minute modulo about eight years, and the confirming tap tells which eight years.
    */
  def taps(confirmedAt: Instant): ConsentTaps =
    val confirmedMinute = confirmedAt.getEpochSecond / 60
    val minutesBetween = (confirmedMinute - agreedMinute) & ConsentRequest.MinuteMask
    ConsentTaps(Instant.ofEpochSecond((confirmedMinute - minutesBetween) * 60), confirmedAt)

private[capture] object ConsentRequest:

  private val ArgumentMask = 0xffffL
  private val MinuteMask = 0x3fffffL

  /** A request before the first tap. */
  def asking(purpose: ConsentPurpose, pending: Pending): ConsentRequest =
    ConsentRequest(ConsentStage.Ask, purpose, pending, agreedMinute = 0)

  private def minuteOf(tap: Instant): Int = ((tap.getEpochSecond / 60) & MinuteMask).toInt

  /** The purposes a chat consent exists for; the numbers are the wire form. */
  private val purposeCodes: Map[ConsentPurpose, Int] = Map(
    ConsentPurpose.Medication -> 1,
    ConsentPurpose.Hydration -> 2,
    ConsentPurpose.Mood -> 3,
    ConsentPurpose.CrossDomainPatterns -> 4,
    ConsentPurpose.ImagesInChat -> 5,
    ConsentPurpose.Sleep -> 6
  )

  def codeOf(purpose: ConsentPurpose): Int = purposeCodes(purpose)

  def purposeOf(code: Long): Option[ConsentPurpose] = purposeCodes.collectFirst { case (purpose, `code`) => purpose }

  def of(value: Long): Option[ConsentRequest] =
    for
      stage <- ConsentStage.values.find(_.code == (value & 0x3L))
      purpose <- purposeOf((value >> 2) & 0xfL)
      pending <- Pending.of(((value >> 6) & 0xfL).toInt, ((value >> 10) & ArgumentMask).toInt)
    yield ConsentRequest(stage, purpose, pending, ((value >> 26) & MinuteMask).toInt)
