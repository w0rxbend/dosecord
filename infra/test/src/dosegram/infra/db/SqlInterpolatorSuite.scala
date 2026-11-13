package dosegram.infra.db

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
      createProbeTable()
      sql"""INSERT INTO interpolator_probe (id, user_id, note, tags, recorded_at)
            VALUES (${UUID.randomUUID()}, $userId, ${"slept well"}, ${Array("sleep", "run")}, $recordedAt)"""
        .execute()
      val sessionId = UUID.randomUUID()
      sql"""INSERT INTO conversation_sessions (id, principal_key, vendor, chat_id, flow, step, data, expires_at)
            VALUES ($sessionId, 'p1', 'console', 'c1', 'create', 'timezone', ${Jsonb("""{"k": 1}""")}, $recordedAt)"""
        .execute()

      val idBack = sql"SELECT id FROM users WHERE id = $userId".queryOne[UUID]()
      assertEquals(idBack, Some(userId))

      val (tagsBack, atBack) = {
        val rows =
          sql"SELECT tags, recorded_at FROM interpolator_probe WHERE user_id = $userId"
            .query[(Array[String], Instant)]()
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
      createProbeTable()
      sql"""INSERT INTO interpolator_probe (id, user_id, note)
            VALUES (${UUID.randomUUID()}, $userId, ${None: Option[String]})""".execute()
      val note = sql"SELECT note FROM interpolator_probe WHERE user_id = $userId".queryOne[String]()
      assertEquals(note.flatMap(Option(_)), None)
    }

  /** A session-local table with a `text[]` and a nullable column: the baseline no longer has a table with both. */
  private def createProbeTable()(using Connection): Unit =
    val _ = sql"""CREATE TEMP TABLE IF NOT EXISTS interpolator_probe (id uuid PRIMARY KEY, user_id uuid NOT NULL,
                  note text, tags text[] NOT NULL DEFAULT '{}', recorded_at timestamptz)""".execute()

  private given RowMapper[(Array[String], Instant)] = rs => (rs.stringArray("tags"), rs.instant("recorded_at"))
