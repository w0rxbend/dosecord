package dosegram.core.application.capture

import dosegram.core.ports.ConsentPurpose

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

private[capture] object Pending:

  def of(kind: Int, argument: Int): Option[Pending] =
    kind match
      case 1 => Some(WaterCard)
      case 2 => Some(WaterAdd(argument))
      case 3 => Some(MoodPrompt)
      case 4 => Some(MoodCheckIn(argument))
      case 5 => Some(Stats)
      case 6 => Some(WaterImage)
      case 7 => Some(DayImage)
      case _ => None

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

/** The value of a `consent.step` token: stage in bits 0-1, purpose in bits 2-5, the pending kind in bits 6-9 and its
  * argument in bits 10-25.
  */
private[capture] final case class ConsentRequest(stage: ConsentStage, purpose: ConsentPurpose, pending: Pending):

  def value: Long =
    stage.code.toLong | (ConsentRequest.codeOf(purpose).toLong << 2) | (pending.kind.toLong << 6) |
      (pending.argument.toLong << 10)

  def at(next: ConsentStage): ConsentRequest = copy(stage = next)

private[capture] object ConsentRequest:

  private val ArgumentMask = 0xffffL

  /** The purposes a chat consent exists for; the numbers are the wire form. */
  private val purposeCodes: Map[ConsentPurpose, Int] = Map(
    ConsentPurpose.Medication -> 1,
    ConsentPurpose.Hydration -> 2,
    ConsentPurpose.Mood -> 3,
    ConsentPurpose.CrossDomainPatterns -> 4,
    ConsentPurpose.ImagesInChat -> 5
  )

  def codeOf(purpose: ConsentPurpose): Int = purposeCodes(purpose)

  def purposeOf(code: Long): Option[ConsentPurpose] = purposeCodes.collectFirst { case (purpose, `code`) => purpose }

  def of(value: Long): Option[ConsentRequest] =
    for
      stage <- ConsentStage.values.find(_.code == (value & 0x3L))
      purpose <- purposeOf((value >> 2) & 0xfL)
      pending <- Pending.of(((value >> 6) & 0xfL).toInt, ((value >> 10) & ArgumentMask).toInt)
    yield ConsentRequest(stage, purpose, pending)
