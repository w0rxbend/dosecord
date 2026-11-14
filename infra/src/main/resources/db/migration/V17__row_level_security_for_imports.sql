-- V17: row-level security for the import tables (ROADMAP G2.3, G10.5;
-- ARCHITECTURE DATA-02, section 15.3; supervisor decision, 2026-11-14).
--
-- V15 (imports) and V14 (row-level security) were built on parallel tracks
-- and merged together. V15 creates import_jobs and import_staging after V14
-- defined dosegram_protect_account_table, but does not call it, and a
-- committed migration is never edited (ADR-002 addendum of 2026-11-13). This
-- migration gives both tables the four policies of every account-owned table.
-- Neither table names a row without an account, so neither is nullable.

SELECT dosegram_protect_account_table('import_jobs', 'account_id', false);
SELECT dosegram_protect_account_table('import_staging', 'account_id', false);
