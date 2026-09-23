package dosecord.contracts

import upickle.default.ReadWriter

import java.util.UUID

import Json.given

/** One catch-up digest line (DESIGN.md section 7.5): the occurrence and the epoch its state carried when the loop
  * folded it into the digest. The dispatcher re-reads the row before rendering and drops items whose epoch moved on or
  * whose occurrence resolved.
  */
final case class DigestItem(occurrenceId: UUID, epoch: Int) derives ReadWriter

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

  /** The catch-up digest (DESIGN.md section 7.5, ROADMAP M1.8): one send per account per 15-minute bucket after
    * downtime. `bucket` is `floor(first next_action_at / 15 min)`; merging rows concatenate `items` under the same
    * `send_key` (`digest:$account:$bucket`).
    */
  case Digest(accountId: UUID, bucket: Long, items: List[DigestItem])

object LoopDispatch:
  def toJson(dispatch: LoopDispatch): String = upickle.default.write(dispatch)
  def fromJson(json: String): LoopDispatch = upickle.default.read[LoopDispatch](json)
