package dosegram.core.domain.sleep

import dosegram.core.domain.EntrySource

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A night, or an explicit nap (PRODUCT_SPEC R-SLP-1, R-SLP-7). The `dbValue` strings match `sleep_episodes.kind`. */
enum SleepKind(val dbValue: String):
  case Main extends SleepKind("main")
  case Nap extends SleepKind("nap")

/** Where one edge of an episode comes from (R-SLP-1): a tap at that moment, a time the user entered afterwards, a bound
  * of a quiet window the user confirmed, or an imported device value.
  */
enum EdgeSource(val dbValue: String):
  case CheckIn extends EdgeSource("checkin")
  case Retrospective extends EdgeSource("retrospective")
  case Passive extends EdgeSource("passive")
  case Sensor extends EdgeSource("sensor")

/** How the episode as a whole came to be (ARCHITECTURE section 10.9, `origin`). */
enum SleepOrigin(val dbValue: String):
  case CheckIn extends SleepOrigin("checkin")
  case Retrospective extends SleepOrigin("retrospective")
  case PassiveConfirmed extends SleepOrigin("passive_confirmed")
  case SelfReportCorrected extends SleepOrigin("self_report_corrected")

/** The quality class of an episode. Tier 1, the passive quiet window, is not here on purpose: it is a [[QuietWindow]],
  * never an episode, so no duration or average can be computed from it (VISUALS VA-ME-60).
  */
enum SleepTier(val number: Int):
  case Explicit extends SleepTier(2)
  case Sensor extends SleepTier(3)

/** The user's own rating of a night, from 1 (very poorly) to 5 (very well) (R-SLP-1). */
opaque type Quality = Int

object Quality:
  val steps: List[Quality] = List(1, 2, 3, 4, 5)
  def of(step: Int): Option[Quality] = Option.when(step >= 1 && step <= 5)(step)
  extension (quality: Quality) def step: Int = quality

/** The presentation bands of R-SLP-12. `Hidden` is below 0.25: nothing is shown. */
enum ConfidenceBand:
  case High, Medium, Low, Hidden

/** How close a record is likely to be to what happened, in hundredths from 0 to 100 (R-SLP-12). It is not a probability
  * of a medical fact and it is never shown as a score.
  */
opaque type Confidence = Int

object Confidence:

  /** Both edges given by the user, by a tap or by an entered time. */
  val BothExplicit: Confidence = 90

  /** A quiet window the user confirmed without change. */
  val ConfirmedWindow: Confidence = 80

  /** A window with both edges passive never reaches the medium band (R-SLP-12, ROADMAP conflict X-09). */
  val PassiveCap: Confidence = 49

  def ofHundredths(hundredths: Int): Option[Confidence] = Option.when(hundredths >= 0 && hundredths <= 100)(hundredths)

  /** A computed value rounded down, so a rounding step never lifts a value over a band or over the cap. */
  def floorOf(value: Double): Confidence = Math.clamp(Math.floor(value * 100.0).toLong, 0L, 100L).toInt

  extension (confidence: Confidence)
    def hundredths: Int = confidence

    def band: ConfidenceBand =
      if confidence >= 80 then ConfidenceBand.High
      else if confidence >= 50 then ConfidenceBand.Medium
      else if confidence >= 25 then ConfidenceBand.Low
      else ConfidenceBand.Hidden

/** One edge of an episode: the instant, the zone in force at that instant and where the instant comes from. */
final case class SleepEdge(at: Instant, zone: ZoneId, source: EdgeSource)

/** Onset and wake. The duration is the difference of the two instants, so a daylight-saving night has its true length
  * whatever the clock showed (R-SLP-1, AC-SLP-2).
  */
final case class SleepInterval(onset: SleepEdge, wake: SleepEdge):
  def duration: Duration = Duration.between(onset.at, wake.at)

  /** The local date of the wake instant in the zone in force at that instant (R-SLP-5). */
  def sleepDay: LocalDate = LocalDate.ofInstant(wake.at, wake.zone)

  def overlaps(other: SleepInterval): Boolean = onset.at.isBefore(other.wake.at) && other.onset.at.isBefore(wake.at)

/** The input guards of R-SLP-2 and the windows of the check-in flow. They are guards against mistyped input, not
  * guidance about sleep.
  */
object SleepBounds:
  val MainMinimum: Duration = Duration.ofHours(1)
  val MainMaximum: Duration = Duration.ofHours(18)
  val NapMinimum: Duration = Duration.ofMinutes(5)
  val NapMaximum: Duration = Duration.ofHours(6)

  /** A "Going to sleep" older than this is no longer open: it is not counted and the night is entered by hand. */
  val OpenLimit: Duration = Duration.ofHours(18)
  val UndoWindow: Duration = Duration.ofMinutes(15)

  /** One step of "earlier" or "later" in chat (R-SLP-3). */
  val AdjustStep: Duration = Duration.ofMinutes(15)
  val MinGoalMinutes: Int = 60
  val MaxGoalMinutes: Int = 1080

  def isGoal(minutes: Int): Boolean = minutes >= MinGoalMinutes && minutes <= MaxGoalMinutes

/** Why two instants are not an episode (R-SLP-2). */
enum SleepInvalid:
  case WakeNotAfterOnset
  case InFuture
  case TooShort(kind: SleepKind, minimum: Duration)
  case TooLong(kind: SleepKind, maximum: Duration)

object SleepRules:

  /** R-SLP-2 for one episode on its own; the overlap with other episodes is checked where they are stored. */
  def check(kind: SleepKind, interval: SleepInterval, now: Instant): Either[SleepInvalid, Unit] =
    val (minimum, maximum) = kind match
      case SleepKind.Main => (SleepBounds.MainMinimum, SleepBounds.MainMaximum)
      case SleepKind.Nap  => (SleepBounds.NapMinimum, SleepBounds.NapMaximum)
    val length = interval.duration
    if !interval.wake.at.isAfter(interval.onset.at) then Left(SleepInvalid.WakeNotAfterOnset)
    else if interval.wake.at.isAfter(now) then Left(SleepInvalid.InFuture)
    else if length.compareTo(minimum) < 0 then Left(SleepInvalid.TooShort(kind, minimum))
    else if length.compareTo(maximum) > 0 then Left(SleepInvalid.TooLong(kind, maximum))
    else Right(())

/** One immutable sleep episode (ARCHITECTURE section 10.9). A correction, a rating and an adjustment are new rows that
  * supersede the old one, so the history of a night stays truthful (R-SLP-6).
  */
final case class SleepEpisode(
    id: UUID,
    accountId: UUID,
    kind: SleepKind,
    interval: SleepInterval,
    origin: SleepOrigin,
    tier: SleepTier,
    confidence: Confidence,
    quality: Option[Quality],
    source: EntrySource,
    clientKey: Option[String],
    supersedesId: Option[UUID],
    createdAt: Instant,
    voidedAt: Option[Instant]
):
  def isLive: Boolean = voidedAt.isEmpty
  def sleepDay: LocalDate = interval.sleepDay
  def duration: Duration = interval.duration

/** The taps of the check-in flow (R-SLP-3). The `dbValue` strings match `sleep_checkins.kind`. */
enum CheckinKind(val dbValue: String):
  case GoingToSleep extends CheckinKind("going_to_sleep")
  case ImUp extends CheckinKind("im_up")

/** One tap, kept as it happened. An episode is made from taps; a tap alone is never a sleep record. */
final case class SleepCheckin(
    id: UUID,
    accountId: UUID,
    kind: CheckinKind,
    occurredAt: Instant,
    zone: ZoneId,
    source: EntrySource,
    clientKey: Option[String],
    createdAt: Instant,
    voidedAt: Option[Instant]
):
  def isLive: Boolean = voidedAt.isEmpty
  def localDate: LocalDate = LocalDate.ofInstant(occurredAt, zone)

/** The user's own duration goal from `effectiveFrom` on; `None` means "no goal" from that day (R-SLP-19). */
final case class SleepGoalChange(effectiveFrom: LocalDate, targetMinutes: Option[Int])
