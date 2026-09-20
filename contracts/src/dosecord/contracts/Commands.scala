package dosecord.contracts

import upickle.default.ReadWriter
import upickle.default.readwriter

import java.time.LocalDate
import scala.util.matching.Regex

import Json.given

/** User-chosen handle: 3-80 chars, letters/digits plus `.`, `_`, `-`, never a vendor-style `@handle` (M4.1 asks for it
  * lazily at the first Link or Export; K8: never the vendor nickname).
  */
opaque type Handle = String
object Handle:
  val MinLength = 3
  val MaxLength = 80
  private val Pattern: Regex = "^[A-Za-z0-9][A-Za-z0-9._-]*$".r

  def parse(value: String): Either[String, Handle] =
    if value.length < MinLength || value.length > MaxLength then
      Left(s"handle must be $MinLength-$MaxLength chars, got ${value.length}")
    else if !Pattern.matches(value) then Left("handle charset is [A-Za-z0-9._-] and must start with a letter or digit")
    else Right(value)

  def unsafe(value: String): Handle =
    parse(value).fold(msg => throw IllegalArgumentException(msg), identity)

  given ReadWriter[Handle] =
    readwriter[String].bimap(identity, value => parse(value).fold(msg => throw IllegalArgumentException(msg), identity))

  extension (handle: Handle) def value: String = handle

/** Wall-clock "HH:MM" local time, 24-hour. Rejects words like "noon" (C17: contract-level validation).
  */
opaque type HhMm = String
object HhMm:
  private val Pattern: Regex = "^([01][0-9]|2[0-3]):[0-5][0-9]$".r

  def parse(value: String): Either[String, HhMm] =
    if Pattern.matches(value) then Right(value) else Left(s"expected HH:MM (24-hour), got: $value")

  def unsafe(value: String): HhMm =
    parse(value).fold(msg => throw IllegalArgumentException(msg), identity)

  given ReadWriter[HhMm] =
    readwriter[String].bimap(identity, value => parse(value).fold(msg => throw IllegalArgumentException(msg), identity))

  extension (t: HhMm)
    def value: String = t
    def hour: Int = t.take(2).toInt
    def minute: Int = t.drop(3).toInt

/** Mood check-in level, bounded 1-10 (salvaged from the retired prototype's validation).
  */
opaque type MoodLevel = Int
object MoodLevel:
  val Min = 1
  val Max = 10

  def parse(value: Int): Either[String, MoodLevel] =
    if value >= Min && value <= Max then Right(value) else Left(s"mood level must be in $Min..$Max, got $value")

  def unsafe(value: Int): MoodLevel =
    parse(value).fold(msg => throw IllegalArgumentException(msg), identity)

  given ReadWriter[MoodLevel] =
    readwriter[Int].bimap(identity, value => parse(value).fold(msg => throw IllegalArgumentException(msg), identity))

  extension (level: MoodLevel) def value: Int = level

enum Weekday derives ReadWriter:
  case Mon, Tue, Wed, Thu, Fri, Sat, Sun

/** Payload defaults salvaged from the retired prototype (ROADMAP section 2): offset 0, snooze [10,30,60], miss 120, max 3.
  */
final case class ReminderPolicyData(
    initialOffsetMinutes: Int = 0,
    snoozeOptionsMinutes: List[Int] = List(10, 30, 60),
    missAfterMinutes: Int = 120,
    maxReminders: Int = 3,
    discreet: Boolean = false
) derives ReadWriter:
  require(initialOffsetMinutes >= -1440 && initialOffsetMinutes <= 1440, "initial offset out of bounds")
  require(snoozeOptionsMinutes.nonEmpty, "at least one snooze option")
  require(snoozeOptionsMinutes.forall(m => m > 0 && m <= 1440), "snooze options must be positive and <= 1440")
  require(snoozeOptionsMinutes.distinct.size == snoozeOptionsMinutes.size, "duplicate snooze options")
  require(missAfterMinutes > 0 && missAfterMinutes <= 10080, "missAfterMinutes must be in 1..10080")
  require(maxReminders >= 1 && maxReminders <= 10, "maxReminders must be in 1..10")

final case class MedicationData(
    name: String,
    doseAmount: Option[String] = None,
    doseUnit: Option[String] = None,
    instructions: Option[String] = None
) derives ReadWriter:
  require(name.trim.nonEmpty, "medication name must not be blank")

final case class FixedTimeScheduleData(
    timezone: String,
    days: List[Weekday],
    times: List[HhMm],
    startDate: LocalDate,
    endDate: Option[LocalDate] = None
) derives ReadWriter:
  require(times.nonEmpty, "at least one time")
  require(days.distinct.size == days.size, "duplicate days")
  require(times.distinct.size == times.size, "duplicate times")
  require(endDate.forall(!_.isBefore(startDate)), "endDate before startDate")

/** Command payloads, sealed replacement for the retired prototype's `registry.py`. The `.v1` suffix of each case's
  * `Type` is the single version source (R61).
  */
enum Command derives ReadWriter:
  case IdentityStartRequested(locale: Option[String] = None, timezone: Option[String] = None)
  case IdentitySignupRequested(handle: Handle, displayName: Option[String] = None, timezone: String = "UTC")
  case MedicationScheduleCreateRequested(
      medication: MedicationData,
      schedule: FixedTimeScheduleData,
      reminderPolicy: ReminderPolicyData = ReminderPolicyData()
  )
  case MoodCheckinRecordRequested(moodLevel: MoodLevel, note: Option[String] = None, tags: List[String] = Nil)
  case MedicationIntakeMarkTakenRequested(
      medicationName: String,
      dosage: Option[String] = None,
      note: Option[String] = None
  )
  case HabitCheckinRecordRequested(habitName: String, durationMinutes: Option[Int] = None, note: Option[String] = None)

object Command:
  val IdentityStartRequestedType = "dosecord.identity.start_requested.v1"
  val IdentitySignupRequestedType = "dosecord.identity.signup_requested.v1"
  val MedicationScheduleCreateRequestedType = "dosecord.medication.schedule_create_requested.v1"
  val MoodCheckinRecordRequestedType = "dosecord.mood.checkin_record_requested.v1"
  val MedicationIntakeMarkTakenRequestedType = "dosecord.medication.intake_mark_taken_requested.v1"
  val HabitCheckinRecordRequestedType = "dosecord.habit.checkin_record_requested.v1"

  def messageType(command: Command): String = command match
    case _: Command.IdentityStartRequested             => IdentityStartRequestedType
    case _: Command.IdentitySignupRequested            => IdentitySignupRequestedType
    case _: Command.MedicationScheduleCreateRequested  => MedicationScheduleCreateRequestedType
    case _: Command.MoodCheckinRecordRequested         => MoodCheckinRecordRequestedType
    case _: Command.MedicationIntakeMarkTakenRequested => MedicationIntakeMarkTakenRequestedType
    case _: Command.HabitCheckinRecordRequested        => HabitCheckinRecordRequestedType

  val allTypes: List[String] = List(
    IdentityStartRequestedType,
    IdentitySignupRequestedType,
    MedicationScheduleCreateRequestedType,
    MoodCheckinRecordRequestedType,
    MedicationIntakeMarkTakenRequestedType,
    HabitCheckinRecordRequestedType
  )
