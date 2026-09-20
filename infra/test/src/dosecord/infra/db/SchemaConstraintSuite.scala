package dosecord.infra.db

import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

import scala.language.implicitConversions

class SchemaConstraintSuite extends PgSuite:

  private val base = Instant.parse("2026-09-20T09:00:00Z")

  private def insertOccurrence(
      scheduleId: UUID,
      revision: Int,
      slotKey: String,
      status: String,
      takenAt: Option[Instant] = None
  )(using Connection): UUID =
    val id = UUID.randomUUID()
    val nextAction = Option.when(Set("pending", "due", "snoozed")(status))(base)
    sql"""INSERT INTO dose_occurrences
            (id, account_id, medication_id, schedule_id, revision, origin, local_date, slot_key, tz,
             scheduled_for, due_window_start, due_window_end, miss_deadline, status, taken_at,
             next_action_at, dose_snapshot)
          VALUES ($id, ${UUID.randomUUID()}, ${UUID.randomUUID()}, $scheduleId, $revision, 'scheduled',
                  DATE '2026-09-20', $slotKey, 'Europe/Kyiv',
                  TIMESTAMPTZ '2026-09-20 09:00:00+00', TIMESTAMPTZ '2026-09-20 09:00:00+00',
                  TIMESTAMPTZ '2026-09-20 10:00:00+00', TIMESTAMPTZ '2026-09-20 11:00:00+00',
                  ${literal(status)}, $takenAt,
                  $nextAction, ${Jsonb("{}")})""".execute()
    id

  /** SQL text fragment as a bind-free literal (enum values, which pgjdbc cannot bind from a String without a cast).
    */
  private def literal(value: String): SqlBind =
    (ps, i, _, _) => ps.setObject(i, value, java.sql.Types.OTHER)

  private def insertAction(occurrenceId: UUID)(using Connection): UUID =
    val id = UUID.randomUUID()
    sql"""INSERT INTO dose_actions
            (id, occurrence_id, account_id, seq, action, actor_type, occurred_at,
             prior_status, new_status, correlation_id)
          VALUES ($id, $occurrenceId, ${UUID.randomUUID()}, 1, ${literal("reminder_sent")},
                  'system', $base, ${literal("pending")}, ${literal("due")}, ${UUID.randomUUID().toString})""".execute()
    id

  test("an invalid status value is rejected"):
    withConnection { conn =>
      given Connection = conn
      val e = intercept[SQLException]:
        insertOccurrence(UUID.randomUUID(), 1, "t0900", "bogus")
      assert(e.getMessage.contains("occ_status"), e.getMessage)
    }

  test("mood_level = 11 is rejected"):
    withConnection { conn =>
      given Connection = conn
      val userId = UUID.randomUUID()
      sql"INSERT INTO users (id, timezone, status) VALUES ($userId, 'UTC', 'active')".execute()
      val e = intercept[SQLException]:
        sql"""INSERT INTO mood_checkins (id, user_id, mood_level)
              VALUES (${UUID.randomUUID()}, $userId, 11)""".execute()
      assert(e.getMessage.contains("mood_level"), e.getMessage)
    }

  test("a second live occurrence for one slot is rejected across revisions"):
    withConnection { conn =>
      given Connection = conn
      val scheduleId = UUID.randomUUID()
      insertOccurrence(scheduleId, 1, "t0900", "pending")
      val e = intercept[SQLException]:
        insertOccurrence(scheduleId, 2, "t0900", "pending")
      assert(e.getMessage.contains("uq_occ_live_slot"), e.getMessage)
    }

  test("taken without taken_at is rejected"):
    withConnection { conn =>
      given Connection = conn
      val e = intercept[SQLException]:
        insertOccurrence(UUID.randomUUID(), 1, "t0900", "taken")
      assert(e.getMessage.contains("occ_taken_at"), e.getMessage)
    }

  test("an UPDATE on dose_actions is rejected"):
    withConnection { conn =>
      given Connection = conn
      val occId = insertOccurrence(UUID.randomUUID(), 1, "t0900", "pending")
      insertAction(occId)
      val e = intercept[SQLException]:
        sql"UPDATE dose_actions SET note = ${"tampered"} WHERE occurrence_id = $occId".execute()
      assert(e.getMessage.contains("append-only"), e.getMessage)
    }

  test("a DELETE on dose_actions is rejected without the erasure GUC"):
    withConnection { conn =>
      given Connection = conn
      val occId = insertOccurrence(UUID.randomUUID(), 1, "t0900", "pending")
      insertAction(occId)
      val e = intercept[SQLException]:
        sql"DELETE FROM dose_actions WHERE occurrence_id = $occId".execute()
      assert(e.getMessage.contains("append-only"), e.getMessage)
    }

  test("a DELETE on dose_actions is rejected with the GUC under the app role"):
    withFreshConnection { conn =>
      given Connection = conn
      val occId = insertOccurrence(UUID.randomUUID(), 1, "t0900", "pending")
      insertAction(occId)
      conn.createStatement().execute("SET dosecord.erasure = 'on'")
      val e = intercept[SQLException]:
        sql"DELETE FROM dose_actions WHERE occurrence_id = $occId".execute()
      assert(e.getMessage.contains("append-only"), e.getMessage)
    }

  test("a DELETE on dose_actions is accepted with the GUC under the erasure role"):
    withFreshConnection { conn =>
      given Connection = conn
      val occId = insertOccurrence(UUID.randomUUID(), 1, "t0900", "pending")
      insertAction(occId)
      conn.createStatement().execute("SET ROLE dosecord_erasure")
      conn.createStatement().execute("SET dosecord.erasure = 'on'")
      val deleted = sql"DELETE FROM dose_actions WHERE occurrence_id = $occId".execute()
      assertEquals(deleted, 1)
      val remaining = sql"SELECT count(*) FROM dose_actions WHERE occurrence_id = $occId".queryOne[Long]()
      assertEquals(remaining, Some(0L))
    }

  test("an UPDATE on auth_audit_log is rejected"):
    withConnection { conn =>
      given Connection = conn
      sql"""INSERT INTO auth_audit_log (id, kind, outcome)
            VALUES (${UUID.randomUUID()}, 'link', 'success')""".execute()
      val e = intercept[SQLException]:
        sql"UPDATE auth_audit_log SET outcome = ${"failure"}".execute()
      assert(e.getMessage.contains("append-only"), e.getMessage)
    }
