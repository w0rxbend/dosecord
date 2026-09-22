package dosecord.infra.db

import dosecord.core.ports.MoodCheckinRepository
import dosecord.core.ports.NewMoodCheckin

import java.sql.Connection
import scala.language.implicitConversions

/** `mood_checkins` (ROADMAP M0.12d): note only when supplied, trailing `#tags` into `tags text[]`. */
final class PgMoodCheckinRepository(conn: Connection) extends MoodCheckinRepository:
  private given Connection = conn

  override def insert(row: NewMoodCheckin): Unit =
    sql"""INSERT INTO mood_checkins (id, user_id, mood_level, note, tags, platform_identity_id, recorded_at)
          VALUES (${row.id}, ${row.accountId}, ${row.moodLevel.value}, ${row.note}, ${row.tags.toArray},
                  ${row.platformIdentityId}, ${row.recordedAt})""".execute()
    ()
