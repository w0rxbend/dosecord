package dosecord.contracts

import upickle.default.ReadWriter

import java.time.Instant
import java.util.UUID

import Json.given

/** Domain facts appended to `domain_events` (DESIGN.md section 8). Adapters emit only intents; the core emits facts
  * (R56). The `.v1` suffix of each case's `Type` is the single version source (R61).
  */
enum Event derives ReadWriter:
  case AccountCreated(accountId: AccountId, identityId: IdentityId, vendor: String)
  case PlatformLinked(linkId: UUID, accountId: AccountId, identityId: IdentityId, vendor: String)
  case MoodCheckinRecorded(accountId: AccountId, moodLevel: MoodLevel, note: Option[String], tags: List[String])
  case ScheduleCreated(accountId: AccountId, medicationId: UUID, scheduleId: UUID, timezone: String)
  case DoseDue(accountId: AccountId, occurrenceId: UUID, reminderSeq: Int)
  case IntakeTaken(accountId: AccountId, occurrenceId: UUID, effectiveAt: Instant, takenLate: Boolean)
  case HabitCheckinRecorded(accountId: AccountId, habitId: UUID, status: String, value: Option[String])

object Event:
  val AccountCreatedType = "dosecord.identity.account_created.v1"
  val PlatformLinkedType = "dosecord.identity.platform_linked.v1"
  val MoodCheckinRecordedType = "dosecord.mood.checkin_recorded.v1"
  val ScheduleCreatedType = "dosecord.medication.schedule_created.v1"
  val DoseDueType = "dosecord.medication.dose_due.v1"
  val IntakeTakenType = "dosecord.medication.intake_taken.v1"
  val HabitCheckinRecordedType = "dosecord.habit.checkin_recorded.v1"

  def messageType(event: Event): String = event match
    case _: Event.AccountCreated       => AccountCreatedType
    case _: Event.PlatformLinked       => PlatformLinkedType
    case _: Event.MoodCheckinRecorded  => MoodCheckinRecordedType
    case _: Event.ScheduleCreated      => ScheduleCreatedType
    case _: Event.DoseDue              => DoseDueType
    case _: Event.IntakeTaken          => IntakeTakenType
    case _: Event.HabitCheckinRecorded => HabitCheckinRecordedType

  val allTypes: List[String] = List(
    AccountCreatedType,
    PlatformLinkedType,
    MoodCheckinRecordedType,
    ScheduleCreatedType,
    DoseDueType,
    IntakeTakenType,
    HabitCheckinRecordedType
  )
