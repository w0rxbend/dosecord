package dosegram.core.domain.imports

import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepInvalid
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepRules
import dosegram.core.domain.sleep.SleepTier
import dosegram.core.ports.DataDomain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Where an import comes from (ARCHITECTURE section 10.13, `import_jobs.source`). The other sources of the table are
  * Later (PRODUCT_SPEC section 8.17): each needs a real file first.
  */
enum ImportSource(val dbValue: String):
  case DosegramCsv extends ImportSource("dosegram_csv")
  case AppleHealth extends ImportSource("apple_health")

object ImportSource:
  def ofDbValue(value: String): Option[ImportSource] = values.find(_.dbValue == value)

/** The limits of an import (PROPOSED, supervisor decision, 2026-11-14). They guard the service, not the user's data. */
object ImportBounds:

  /** Records one import may stage. */
  val MaxRecords: Int = 100_000

  /** Records or CSV rows one batch may carry; a batch body is at most 2 MB (ARCHITECTURE section 9.4). */
  val MaxBatchRecords: Int = 5_000

  /** The error lines a preview lists; the rest are counted. */
  val MaxListedErrors: Int = 20

  /** A record later than now by more than this is refused as in the future: a device clock may run a little ahead. */
  val FutureSkewMinutes: Long = 5

/** One normalised record of an import: what a CSV row or a device record means, before it is an entry. Imports create
  * no medication, schedule, occurrence or dose action (PRODUCT_SPEC R-IMX-11), so there is no such case.
  */
enum ImportRecord:
  case Water(at: Instant, zone: ZoneId, millilitres: Int)
  case Mood(at: Instant, zone: ZoneId, valence: Valence, energy: Option[Energy])
  case Sleep(interval: SleepInterval, kind: SleepKind, quality: Option[Quality], tier: SleepTier)
  case Meal(at: Instant, zone: ZoneId, mealType: MealType, words: Option[MealText])

  def domain: DataDomain = this match
    case _: Water => DataDomain.Hydration
    case _: Mood  => DataDomain.Mood
    case _: Sleep => DataDomain.Sleep
    case _: Meal  => DataDomain.Nutrition

  /** The local date of the entry; a night's is its sleep day, the date of the wake-up. */
  def localDate: LocalDate = this match
    case Water(at, zone, _)       => LocalDate.ofInstant(at, zone)
    case Mood(at, zone, _, _)     => LocalDate.ofInstant(at, zone)
    case Sleep(interval, _, _, _) => interval.sleepDay
    case Meal(at, zone, _, _)     => LocalDate.ofInstant(at, zone)

  /** What identifies the record (R-IMX-9): its domain, start, end and value. Equal identities are one record, whatever
    * file or device brought it, so a repeated import and a record the user already entered are both duplicates.
    */
  def identity: String = this match
    case Water(at, _, millilitres)    => RecordIdentity.water(at, millilitres)
    case Mood(at, _, valence, energy) => RecordIdentity.mood(at, valence, energy)
    case Sleep(interval, _, _, _)     => RecordIdentity.sleep(interval.onset.at, interval.wake.at)
    case Meal(at, _, mealType, words) => RecordIdentity.meal(at, mealType, words)

object ImportRecord:

  /** A drink inside the entry bounds of PRODUCT_SPEC section 8.8. */
  def water(at: Instant, zone: ZoneId, millilitres: Int, now: Instant): Either[ImportProblem, Water] =
    for
      _ <- notLater(at, now)
      _ <- Either.cond(HydrationBounds.isEntry(millilitres), (), ImportProblem.OutOfRange)
    yield Water(micros(at), zone, millilitres)

  /** A check-in with its steps of R-MOO-1 and R-MOO-2. */
  def mood(at: Instant, zone: ZoneId, valence: Int, energy: Option[Int], now: Instant): Either[ImportProblem, Mood] =
    for
      _ <- notLater(at, now)
      step <- Valence.of(valence).toRight(ImportProblem.OutOfRange)
      level <- energy.fold(Right(None))(value => Energy.of(value).map(Some(_)).toRight(ImportProblem.OutOfRange))
    yield Mood(micros(at), zone, step, level)

  /** A night or a nap inside the bounds of R-SLP-2. */
  def sleep(
      interval: SleepInterval,
      kind: SleepKind,
      quality: Option[Int],
      tier: SleepTier,
      now: Instant
  ): Either[ImportProblem, Sleep] =
    val skewed = now.plus(ImportBounds.FutureSkewMinutes, ChronoUnit.MINUTES)
    for
      _ <- SleepRules.check(kind, interval, skewed).left.map(problemOf)
      rating <- quality.fold(Right(None))(step => Quality.of(step).map(Some(_)).toRight(ImportProblem.OutOfRange))
    yield
      val exact = SleepInterval(
        interval.onset.copy(at = micros(interval.onset.at)),
        interval.wake.copy(at = micros(interval.wake.at))
      )
      Sleep(exact, kind, rating, tier)

  /** A meal with its words of R-NUT-1: blank words are none, longer words are refused rather than cut. */
  def meal(at: Instant, zone: ZoneId, mealType: MealType, words: String, now: Instant): Either[ImportProblem, Meal] =
    for
      _ <- notLater(at, now)
      text <- MealText.of(words).left.map(_ => ImportProblem.TextTooLong)
    yield Meal(micros(at), zone, mealType, text)

  private def notLater(at: Instant, now: Instant): Either[ImportProblem, Unit] =
    Either.cond(!at.isAfter(now.plus(ImportBounds.FutureSkewMinutes, ChronoUnit.MINUTES)), (), ImportProblem.InFuture)

  private def problemOf(invalid: SleepInvalid): ImportProblem = invalid match
    case SleepInvalid.WakeNotAfterOnset => ImportProblem.WakeNotAfterOnset
    case SleepInvalid.InFuture          => ImportProblem.InFuture
    case _: SleepInvalid.TooShort       => ImportProblem.TooShort
    case _: SleepInvalid.TooLong        => ImportProblem.TooLong

  private def micros(at: Instant): Instant = at.truncatedTo(ChronoUnit.MICROS)

/** The identity strings of [[ImportRecord.identity]], also computed for the entries the account already holds. The
  * database stores instants to the microsecond, so the identity does too.
  */
object RecordIdentity:
  private def at(instant: Instant): String = instant.truncatedTo(ChronoUnit.MICROS).toString

  def water(occurredAt: Instant, millilitres: Int): String = s"hydration|${at(occurredAt)}|$millilitres"

  def mood(occurredAt: Instant, valence: Valence, energy: Option[Energy]): String =
    s"mood|${at(occurredAt)}|${valence.step}|${energy.fold("")(_.step.toString)}"

  def sleep(onset: Instant, wake: Instant): String = s"sleep|${at(onset)}|${at(wake)}"

  def meal(occurredAt: Instant, mealType: MealType, words: Option[MealText]): String =
    s"nutrition|${at(occurredAt)}|${mealType.dbValue}|${words.fold("")(_.value)}"

  def of(entry: HydrationEntry): String = water(entry.occurredAt, entry.amount.millilitres)
  def of(checkin: MoodCheckin): String = mood(checkin.occurredAt, checkin.valence, checkin.energy)
  def of(episode: SleepEpisode): String = sleep(episode.interval.onset.at, episode.interval.wake.at)
  def of(meal: MealEntry): String = this.meal(meal.occurredAt, meal.mealType, meal.text)

/** Why one row or record was refused. Codes only: a preview never echoes a value of the file (ARCHITECTURE API-14). */
enum ImportProblem(val code: String):
  case MissingField extends ImportProblem("missing_field")
  case InvalidTime extends ImportProblem("invalid_time")
  case InvalidNumber extends ImportProblem("invalid_number")
  case OutOfRange extends ImportProblem("out_of_range")
  case InvalidKind extends ImportProblem("invalid_kind")
  case UnknownZone extends ImportProblem("unknown_zone")
  case InFuture extends ImportProblem("in_future")
  case WakeNotAfterOnset extends ImportProblem("wake_not_after_onset")
  case TooShort extends ImportProblem("too_short")
  case TooLong extends ImportProblem("too_long")
  case TextTooLong extends ImportProblem("text_too_long")
  case Malformed extends ImportProblem("malformed")

object ImportProblem:
  def ofCode(code: String): Option[ImportProblem] = values.find(_.code == code)
