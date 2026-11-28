-- V27: the button labels kept with a sent message and the pages of an image
-- waiting to be drawn sealed (ROADMAP G2.4b, ARCHITECTURE section 15.2 and
-- SEC-04; supervisor decision, 2026-11-28).
--
--   rendered_messages.choice_map   the controls of a message the bot sent,
--                                  kept so a tap, a typed number or a typed
--                                  label still resolves after a restart. A
--                                  label can name a medication: the /meds
--                                  toggles read "Pause · <name>" once the
--                                  list has two rows (verification of
--                                  2026-11-28, OPERATIONS section 21).
--   report_requests.params         an image waiting to be drawn: its SVG,
--                                  its text equivalent and its caption, which
--                                  can name a medication (the refill card).
--                                  Sealed with the row's account key. A
--                                  finished request keeps no pages: it is
--                                  emptied here, and the release empties both
--                                  columns when it finishes one.
--
-- As in V13, V19 and V22, each store gets a sealed column next to its
-- plaintext column, which stays, nullable: the start-up step seals every value left in
-- it right after this migration and empties it in the same UPDATE
-- (SealingBackfill, stage 27), and the release writes the sealed column only.
-- The owner of a message's seal is the row's account, or the service key
-- 'transport' for a message sent before the account existed (the onboarding).

ALTER TABLE rendered_messages ADD COLUMN choice_map_enc bytea;

-- The backfill looks for values still in the plaintext column at every start;
-- the table grows with every message, so this keeps that look to the rows it
-- has to seal.
CREATE INDEX ix_rendered_messages_unsealed ON rendered_messages (id) WHERE choice_map IS NOT NULL;

ALTER TABLE report_requests ALTER COLUMN params DROP NOT NULL, ADD COLUMN params_enc bytea,
  ADD CONSTRAINT report_requests_pages_while_unfinished
    CHECK (status IN ('sent', 'text_fallback') OR params IS NOT NULL OR params_enc IS NOT NULL);
UPDATE report_requests SET params = NULL WHERE status IN ('sent', 'text_fallback');
