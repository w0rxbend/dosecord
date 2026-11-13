-- V3: the durable inbox of inbound updates (ROADMAP G1.7, ADR-017).

-- ---------------------------------------------------------------------------
-- Async edges: inbound_inbox (docs/ARCHITECTURE.md sections 7.2.1 to 7.2.3).
-- Every update a long poll or the webhook receives is stored here, keyed like
-- inbound_events, before anything processes it: the poll offset moves and the
-- webhook answers 200 only after this insert. chat_key orders the updates of
-- one chat by sequence (the update_id); the processor takes, per chat, the
-- lowest pending row. body is the minimal update the adapter decodes; it is
-- deleted when the update is processed, and at the latest 24 hours after
-- receipt, quarantined rows included. A row without its body lives 7 days.
-- ---------------------------------------------------------------------------

CREATE TABLE inbound_inbox (vendor text NOT NULL, vendor_event_id text NOT NULL,
  chat_key text NOT NULL, sequence bigint NOT NULL, body text,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','processed','quarantined','expired')),
  attempts int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  received_at timestamptz NOT NULL, finished_at timestamptz,
  PRIMARY KEY (vendor, vendor_event_id),
  CHECK (status = 'quarantined' OR (status = 'pending') = (body IS NOT NULL)),
  CHECK ((status = 'pending') = (finished_at IS NULL)));
CREATE INDEX ix_inbound_inbox_pending ON inbound_inbox (vendor, chat_key, sequence) WHERE status = 'pending';
CREATE INDEX ix_inbound_inbox_received ON inbound_inbox (received_at);
-- The 7-day deletion of inbound_events reads by receipt.
CREATE INDEX ix_inbound_events_received ON inbound_events (received_at);

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON inbound_inbox TO dosegram_app;
-- The deleting job of the inbox empties both stores of inbound updates.
GRANT DELETE ON inbound_inbox, inbound_events TO dosegram_app;
