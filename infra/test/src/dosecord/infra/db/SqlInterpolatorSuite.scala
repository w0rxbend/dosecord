package dosecord.infra.db

import java.sql.Connection
import java.time.Instant
import java.util.UUID

import scala.language.implicitConversions

class SqlInterpolatorSuite extends PgSuite:

  test("round-trips uuid, timestamptz, jsonb and text[]"):
    withConnection { conn =>
      given Connection = conn
      val userId = UUID.randomUUID()
      val recordedAt = Instant.parse("2026-09-20T07:15:30.123456Z")

      sql"INSERT INTO users (id, timezone, status) VALUES ($userId, 'UTC', 'active')".execute()
      sql"""INSERT INTO mood_checkins (id, user_id, mood_level, note, tags, recorded_at)
            VALUES (${UUID.randomUUID()}, $userId, 8, ${"slept well"}, ${Array("sleep", "run")}, $recordedAt)"""
        .execute()
      val sessionId = UUID.randomUUID()
      sql"""INSERT INTO conversation_sessions (id, principal_key, vendor, chat_id, flow, step, data, expires_at)
            VALUES ($sessionId, 'p1', 'console', 'c1', 'create', 'timezone', ${Jsonb("""{"k": 1}""")}, $recordedAt)"""
        .execute()

      val idBack = sql"SELECT id FROM users WHERE id = $userId".queryOne[UUID]()
      assertEquals(idBack, Some(userId))

      val (tagsBack, atBack) = {
        val rows =
          sql"SELECT tags, recorded_at FROM mood_checkins WHERE user_id = $userId".query[(Array[String], Instant)]()
        rows.head
      }
      assertEquals(tagsBack.toList, List("sleep", "run"))
      assertEquals(atBack, recordedAt)

      val dataBack = sql"SELECT data FROM conversation_sessions WHERE id = $sessionId".queryOne[Jsonb]()
      assertEquals(dataBack, Some(Jsonb("""{"k": 1}""")))
    }

  test("Option binds map Some to the value and None to NULL"):
    withConnection { conn =>
      given Connection = conn
      val userId = UUID.randomUUID()
      sql"INSERT INTO users (id, timezone, status) VALUES ($userId, 'UTC', 'active')".execute()
      sql"""INSERT INTO mood_checkins (id, user_id, mood_level, note)
            VALUES (${UUID.randomUUID()}, $userId, 5, ${None: Option[String]})""".execute()
      val note = sql"SELECT note FROM mood_checkins WHERE user_id = $userId".queryOne[String]()
      assertEquals(note.flatMap(Option(_)), None)
    }

  private given RowMapper[(Array[String], Instant)] = rs => (rs.stringArray("tags"), rs.instant("recorded_at"))
