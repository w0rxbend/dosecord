package dosecord.contracts

import upickle.default.ReadWriter

import java.util.UUID

import Json.given

/** The payload of the outbox rows the reminder loop enqueues (DESIGN.md section 7.4, ADR-004). Opaque to the core's
  * outbox port; the dispatcher's renderer (M1.7) interprets it at send time and re-reads the occurrence row, so the
  * payload carries identity and intent, not rendered copy. Serialisation lives in contracts so the core stays free of a
  * JSON library (R56).
  */
enum LoopDispatch derives ReadWriter:

  /** A reminder send. `kind` is the M1.3 `ReminderKind` tag: `initial`, `repeat` or `snooze_wake`. */
  case Reminder(occurrenceId: UUID, chatId: Option[String], kind: String, reminderSeq: Int, silent: Boolean)

  /** Finalize the controls of the occurrence's previously sent messages; `reason` is `superseded` or `resolved`. */
  case FinalizeControls(occurrenceId: UUID, reason: String)

  /** The missed notice (ADR-012: deferred to quiet end via the outbox row's `next_attempt_at`, never the status). */
  case MissedNotice(occurrenceId: UUID, chatId: Option[String], silent: Boolean)

object LoopDispatch:
  def toJson(dispatch: LoopDispatch): String = upickle.default.write(dispatch)
  def fromJson(json: String): LoopDispatch = upickle.default.read[LoopDispatch](json)
