-- V7: the report preparation queue (ROADMAP G4.3; ARCHITECTURE sections 10
-- and 12.1).

-- ---------------------------------------------------------------------------
-- Stores of the edges (docs/ARCHITECTURE.md section 10.17). One row per image
-- answer a chat event asked for: the event lays its cards out on its own
-- transaction and writes the row there; the report worker claims it, draws
-- outside every transaction, puts the messages in the outbox and finishes the
-- row in one transaction. params holds the pages: each card's SVG, its text
-- equivalent and the message that carries it, until the row is finished; then
-- it is '{}'. A finished row is deleted after 24 hours, the window of the daily
-- limit it is counted in; an unfinished one after an hour, like the image bytes
-- of render_assets.
--
-- Not as section 10 lists it: no snapshot_id or priority (one class, the
-- answer to a user action), delivery is always chat_photo, lease_until holds a
-- claim, and the statuses are queued, rendering, sent and text_fallback. params
-- is not encrypted with the account data key (ROADMAP G2.4b).
-- ---------------------------------------------------------------------------

CREATE TABLE report_requests (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  card_kind text NOT NULL, params jsonb NOT NULL,
  delivery text NOT NULL DEFAULT 'chat_photo' CHECK (delivery IN ('chat_photo')),
  status text NOT NULL CHECK (status IN ('queued','rendering','sent','text_fallback')),
  deadline_at timestamptz NOT NULL, lease_until timestamptz, attempts int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  error_class text, render_ms int CHECK (render_ms >= 0),
  created_at timestamptz NOT NULL, finished_at timestamptz,
  CONSTRAINT report_requests_deadline CHECK (deadline_at > created_at),
  CONSTRAINT report_requests_claim CHECK ((status = 'rendering') = (lease_until IS NOT NULL)),
  CONSTRAINT report_requests_finished CHECK ((status IN ('sent','text_fallback')) = (finished_at IS NOT NULL)));
CREATE INDEX ix_report_requests_status_created ON report_requests (status, created_at);
CREATE INDEX ix_report_requests_account_created ON report_requests (account_id, created_at);

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON report_requests TO dosegram_app;
-- The purge deletes a request one hour after it was made.
GRANT DELETE ON report_requests TO dosegram_app;
GRANT SELECT, DELETE ON report_requests TO dosegram_erasure;
