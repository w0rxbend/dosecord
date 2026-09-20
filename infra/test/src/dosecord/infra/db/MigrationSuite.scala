package dosecord.infra.db

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
      "mood_checkins",
      "habits",
      "habit_checkins",
      "reminders"
    )
    withConnection { conn =>
      given Connection = conn
      val tables = sql"SELECT tablename FROM pg_catalog.pg_tables WHERE schemaname = 'public'".query[String]().toSet
      assert(expectedTables.subsetOf(tables), s"missing tables: ${expectedTables.diff(tables)}")
      val views = sql"SELECT viewname FROM pg_catalog.pg_views WHERE schemaname = 'public'".query[String]().toSet
      assert(views.contains("reminder_occurrences"), "reminder_occurrences view missing")
    }

  test("reminder_occurrences exposes dose rows only"):
    withConnection { conn =>
      given Connection = conn
      val kinds = sql"SELECT DISTINCT kind FROM reminder_occurrences".query[String]()
      assert(kinds.forall(_ == "dose"))
    }
