-- V6: the export jobs and their download tickets (ROADMAP G3.10, G10.4; PRODUCT_SPEC section 8.17).

-- ---------------------------------------------------------------------------
-- import-export (docs/ARCHITECTURE.md sections 9.7 and 10.13). One row per
-- export the user asked for: the full archive (full_zip) or the record
-- summary (record_summary_pdf, its period in params). A request from the Mini
-- App waits for the confirming tap in the chat (awaiting_confirmation); a
-- request from the chat is confirmed by its own tap and starts queued. The
-- worker builds the file and keeps its bytes in content for at most 15
-- minutes after ready_at; the deleting job then empties content and the row
-- reads expired. The row itself stays as the history of the account's
-- exports, which the export of the account includes.
--
-- export_download_tickets: the SHA-256 of a download ticket, never the ticket.
-- A ticket is bound to one export, valid for 15 minutes and for at most 5
-- requests from the address prefix of its first request (section 9.7).
--
-- Not yet as section 10.13 specifies: content is not encrypted with the
-- account data key; that arrives with ROADMAP G2.4b.
-- ---------------------------------------------------------------------------

CREATE TABLE export_jobs (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  kind text NOT NULL CHECK (kind IN ('full_zip','record_summary_pdf')),
  params jsonb NOT NULL DEFAULT '{}',
  status text NOT NULL CHECK (status IN ('awaiting_confirmation','queued','ready','downloaded','expired','failed')),
  file_name text, mime text, byte_size int CHECK (byte_size > 0), content bytea,
  created_at timestamptz NOT NULL, confirmed_at timestamptz, ready_at timestamptz, expires_at timestamptz,
  downloaded_at timestamptz,
  CONSTRAINT export_jobs_content CHECK (content IS NULL OR (status IN ('ready','downloaded') AND byte_size IS NOT NULL)),
  CONSTRAINT export_jobs_lifetime CHECK (expires_at IS NULL OR (ready_at IS NOT NULL
    AND expires_at > ready_at AND expires_at <= ready_at + interval '15 minutes')));
CREATE INDEX ix_export_jobs_account_created ON export_jobs (account_id, created_at);
CREATE INDEX ix_export_jobs_queued ON export_jobs (created_at) WHERE status = 'queued';

CREATE TABLE export_download_tickets (ticket_hash bytea PRIMARY KEY,
  export_id uuid NOT NULL REFERENCES export_jobs, account_id uuid NOT NULL REFERENCES users,
  created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, ip_prefix text,
  requests int NOT NULL DEFAULT 0 CHECK (requests BETWEEN 0 AND 5),
  CONSTRAINT export_download_tickets_lifetime CHECK (expires_at > created_at
    AND expires_at <= created_at + interval '15 minutes'));
CREATE INDEX ix_export_download_tickets_expires ON export_download_tickets (expires_at);

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON export_jobs, export_download_tickets TO dosegram_app;
-- The deleting job removes expired tickets; a job row stays, without its bytes.
GRANT DELETE ON export_download_tickets TO dosegram_app;
GRANT SELECT, DELETE ON export_jobs, export_download_tickets TO dosegram_erasure;
