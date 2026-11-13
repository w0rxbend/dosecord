package dosegram.infra.db

import java.sql.Connection

import scala.language.implicitConversions

class MigrationSuite extends PgSuite:

  test("migrate then validate passes and is idempotent when run twice"):
    val migrator = Migrator(dataSource)
    migrator.validate()
    val second = migrator.migrate()
    assertEquals(second.migrationsExecuted, 0)
    migrator.validate()

  test("every enumerated V1 table and the reminder_occurrences view exists"):
    val expectedTables = Set(
      "users",
      "platform_identities",
      "user_credentials",
      "auth_challenges",
      "recovery_codes",
      "auth_audit_log",
      "medications",
      "medication_schedules",
      "schedule_revisions",
      "dose_occurrences",
      "dose_actions",
      "outbox_messages",
      "rendered_messages",
      "inbound_events",
      "callback_slots",
      "conversation_sessions",
      "form_runs",
      "delivery_channels",
      "domain_events",
      "worker_heartbeat",
      "reminders",
      "render_assets",
      "telegram_file_cache",
      "miniapp_sessions",
      "idempotency_keys"
    )
    withConnection { conn =>
      given Connection = conn
      val tables = sql"SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public'".query[String]().toSet
      assert(expectedTables.subsetOf(tables), s"missing tables: ${expectedTables.diff(tables)}")
      val views = sql"SELECT viewname FROM pg_catalog.pg_views WHERE schemaname = 'public'".query[String]().toSet
      assert(views.contains("reminder_occurrences"), "reminder_occurrences view missing")
    }

  // ROADMAP G0.6, BOT_UX BUX-CMD-08: a domain has no table before it has a consent purpose. Habits are a non-goal
  // (PRODUCT_SPEC N20). Mood has the purpose `mood` (PRODUCT_SPEC R-IDC-6): its table came back with the domain part
  // of G6.1, in the final shape of ARCHITECTURE section 10.8 and in the baseline, which is still edited in place.
  test("the baseline has no table of a domain without a consent purpose"):
    withConnection { conn =>
      given Connection = conn
      val tables = sql"SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public'".query[String]().toSet
      assertEquals(tables.intersect(Set("habits", "habit_checkins")), Set.empty[String])
    }

  // The inherited `/mood` table (level 1 to 10, plaintext note, text tags) was removed in G0.6 and must not return.
  test("mood_checkins has none of the columns of the inherited mood table"):
    withConnection { conn =>
      given Connection = conn
      val columns = sql"""SELECT column_name FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = 'mood_checkins'""".query[String]().toSet
      assertEquals(columns.intersect(Set("mood_level", "note", "tags", "user_id")), Set.empty[String])
      assert(Set("valence", "energy", "tag_ids", "note_enc").subsetOf(columns), s"columns: $columns")
    }

  test("reminder_occurrences exposes dose rows only"):
    withConnection { conn =>
      given Connection = conn
      val kinds = sql"SELECT DISTINCT kind FROM reminder_occurrences".query[String]()
      assert(kinds.forall(_ == "dose"))
    }
