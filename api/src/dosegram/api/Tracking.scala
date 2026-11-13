package dosegram.api

import dosegram.core.application.hydration.HydrationRefusal
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.insights.StatsRefusal
import dosegram.core.application.mood.MoodRefusal
import dosegram.core.application.mood.MoodService
import dosegram.core.application.sleep.SleepService
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodLevel
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.ConsentPurpose

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.zip.CRC32

/** The use cases of the tracking domains that the API calls (ARCHITECTURE section 11.4). The composition root builds
  * them; this module never sees their storage (DEP-05). Each one checks the consent of its domain itself.
  */
final case class TrackingUseCases(
    stats: StatsQueryService,
    hydration: HydrationService,
    mood: MoodService,
    sleep: SleepService
)

/** A refusal of a use case as the problem document of ARCHITECTURE section 9.4. An account that is gone and a record of
  * another account answer like a record that does not exist (API-11).
  */
object Refusals:

  def of(refusal: HydrationRefusal): ApiFailure = refusal match
    case HydrationRefusal.ConsentMissing => ApiFailure.consentRequired(ConsentPurpose.Hydration)
    case HydrationRefusal.UnknownAccount | HydrationRefusal.UnknownEntry => ApiFailure.notFound()
    case HydrationRefusal.DailyLimitReached(_)                           => ApiFailure.quotaExceeded()
    case HydrationRefusal.GoalOutOfBounds(_, _)                          => ApiFailure.invalid("goalMl", "out_of_range")
    case HydrationRefusal.PresetsInvalid                                 => ApiFailure.invalid("presetsMl", "invalid")
    // The API deletes an entry of any age; only the chat's Undo has a window.
    case HydrationRefusal.UndoWindowPassed => ApiFailure.notFound()

  def of(refusal: MoodRefusal): ApiFailure = refusal match
    case MoodRefusal.ConsentMissing                              => ApiFailure.consentRequired(ConsentPurpose.Mood)
    case MoodRefusal.UnknownAccount | MoodRefusal.UnknownCheckin => ApiFailure.notFound()
    case MoodRefusal.TooManyTags(_)                              => ApiFailure.invalid("tags", "too_many")
    case MoodRefusal.UndoWindowPassed                            => ApiFailure.notFound()

  def of(refusal: StatsRefusal): ApiFailure = refusal match
    case StatsRefusal.ConsentMissing(purpose) => ApiFailure.consentRequired(purpose)
    case StatsRefusal.UnknownAccount          => ApiFailure.notFound()

/** `?from=&to=` on local dates (ARCHITECTURE section 9.4): both or neither, at most 366 days. */
object Ranges:
  val MaxDays = 366

  def of(from: Option[LocalDate], to: Option[LocalDate], whenAbsent: => DateRange): Either[ApiFailure, DateRange] =
    (from, to) match
      case (None, None)                                    => Right(whenAbsent)
      case (Some(_), None)                                 => Left(ApiFailure.invalid("to", "required"))
      case (None, Some(_))                                 => Left(ApiFailure.invalid("from", "required"))
      case (Some(start), Some(end)) if end.isBefore(start) => Left(ApiFailure.invalid("to", "before_from"))
      case (Some(start), Some(end))                        =>
        val range = DateRange.between(start, end)
        if range.days > MaxDays then Left(ApiFailure.invalid("to", "range_too_long")) else Right(range)

/** The version behind the `ETag` of a resource whose rows have no version column (API-17): a checksum of what the
  * resource holds. It changes with any change of the content, whoever made it, the chat included.
  */
object ContentVersion:

  def of(parts: String*): Long =
    val checksum = CRC32()
    checksum.update(parts.mkString("\u0000").getBytes(UTF_8))
    checksum.getValue

/** The names of the drawn blob states (VISUALS_AND_ANALYTICS section 4.3): `blob.v<1-7>.e<1-5>`. A stored valence step
  * reaches the levels V1, V2, V4, V6 and V7; a check-in without the energy step is drawn at the middle energy level.
  */
object BlobStates:
  val NoEntry = "blob.none"
  private val MiddleEnergy = 3

  def of(valence: Valence, energy: Option[Energy]): String =
    of(MoodLevel.ofMean(valence.step.toDouble), energy.map(_.step))

  def of(level: MoodLevel, energyStep: Option[Int]): String =
    s"blob.v${level.ordinal + 1}.e${energyStep.getOrElse(MiddleEnergy)}"
