-- V26: the "What's new" note after a release (supervisor decision,
-- 2026-11-28, analytics round 6; BOT_UX section 6.5). An account is told once,
-- after its next command, which commands a release added, and can turn the
-- note off. `whats_new_seen` is the last release the account was told about.
-- Every account that exists when this runs gets 0, so it hears of the first
-- release; the default is then dropped, so an account created later starts
-- at NULL and is set to the current release without a note: a new account
-- has nothing to catch up on. `whats_new_enabled` is the account's switch, on
-- by default. No row changes otherwise.

-- ---------------------------------------------------------------------------
-- Identity: the account's "What's new" note.
-- ---------------------------------------------------------------------------

ALTER TABLE users ADD COLUMN whats_new_seen integer DEFAULT 0;
ALTER TABLE users ALTER COLUMN whats_new_seen DROP DEFAULT;
ALTER TABLE users ADD COLUMN whats_new_enabled boolean NOT NULL DEFAULT true;
