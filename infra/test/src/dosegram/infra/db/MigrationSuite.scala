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
      "reminders"
    )
    withConnection { conn =>
      given Connection = conn
      val tables = sql"SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public'".query[String]().toSet
      assert(expectedTables.subsetOf(tables), s"missing tables: ${expectedTables.diff(tables)}")
      val views = sql"SELECT viewname FROM pg_catalog.pg_views WHERE schemaname = 'public'".query[String]().toSet
      assert(views.contains("reminder_occurrences"), "reminder_occurrences view missing")
    }

  // ROADMAP G0.6, BOT_UX BUX-CMD-08: a domain has no table before it has a consent purpose. Mood returns with its
  // own migration in G6.1; habits are a non-goal (PRODUCT_SPEC N20).
  test("the baseline has no table of a domain without a consent purpose"):
    withConnection { conn =>
      given Connection = conn
      val tables = sql"SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public'".query[String]().toSet
      assertEquals(tables.intersect(Set("mood_checkins", "habits", "habit_checkins")), Set.empty[String])
    }

  test("reminder_occurrences exposes dose rows only"):
    withConnection { conn =>
      given Connection = conn
      val kinds = sql"SELECT DISTINCT kind FROM reminder_occurrences".query[String]()
      assert(kinds.forall(_ == "dose"))
    }
