package dosecord.core.ports

import dosecord.contracts.MoodLevel

import java.time.Instant
import java.util.UUID

/** What a `/mood` check-in writes into `mood_checkins` (R37): the note is stored only when supplied, trailing `#tags`
  * land in `tags text[]`.
  */
final case class NewMoodCheckin(
    id: UUID,
    accountId: UUID,
    moodLevel: MoodLevel,
    note: Option[String],
    tags: List[String],
    platformIdentityId: Option[UUID],
    recordedAt: Instant
)

/** `mood_checkins` (ROADMAP M0.12d). */
trait MoodCheckinRepository:
  def insert(row: NewMoodCheckin): Unit
