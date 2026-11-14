-- V18: the reason of an inbox failure, and narrower grants of the serving
-- roles (supervisor decisions, 2026-11-15).
--
-- inbound_inbox.failure_reason. The live inbox quarantined two button taps on
-- 2026-11-14 after three attempts and kept no reason. Each failed attempt now
-- stores a fixed code that names no content: the SQL state of a database error
-- (sql_23505) or the class of another error (error_IllegalStateException),
-- never an error message, which may quote a value. The check refuses anything
-- else.

ALTER TABLE inbound_inbox ADD COLUMN failure_reason text
  CHECK (failure_reason ~ '^[A-Za-z0-9_]{1,64}$');

-- Grants. The blanket grant of V1 gave the application role SELECT, INSERT and
-- UPDATE on every table that existed then, flyway_schema_history included.
-- Serving code never reads or writes the migration history: only the schema
-- owner, which applies the migrations, does.
REVOKE ALL ON flyway_schema_history FROM dosegram_app, dosegram_worker, dosegram_erasure;

-- audit_log is append-only for the application role: it inserts the row of
-- each audited action and reads none (ROADMAP G2.16). The worker role, which
-- builds the full export of one account, reads that account's rows; it had
-- the read through its membership of the application role until now.
REVOKE SELECT, UPDATE, DELETE, TRUNCATE ON audit_log FROM dosegram_app;
GRANT SELECT ON audit_log TO dosegram_worker;
