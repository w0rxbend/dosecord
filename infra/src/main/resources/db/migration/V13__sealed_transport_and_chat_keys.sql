-- V13: the transport stores sealed, and the chat of a row kept as a key
-- (ARCHITECTURE section 15.2, SEC-01, SEC-16; findings 1 and 2 of the
-- verification of 2026-11-14; supervisor decision, 2026-11-14).
--
-- outbox_messages.payload and inbound_events.reply carry the text of the
-- messages the bot sends, medication names and instructions among them. Both
-- get a sealed column. The owner of the seal is the row's account, or the
-- service key 'transport' for a row written before the account existed
-- (onboarding). The plaintext columns stay, nullable, so the previous release
-- can still read its own schema: the start-up step seals every value left in
-- them right after this migration and empties it in the same UPDATE
-- (SealingBackfill, SealedColumns.emptiedOnSeal); no later migration is needed
-- for that.
--
-- chat_key: the chat a row was written for, while the chat has an identity.
-- Account deletion deletes by account and by the account's chats, so the rows
-- of the onboarding, which have no account id, go with the account.
--
-- asset_keys: the images a queued message names, read from the payload when
-- it is written. The erasure job found them with a JSON path over the payload,
-- which is sealed now.

-- ---------------------------------------------------------------------------
-- Delivery (outbox_messages, inbound_events).
-- ---------------------------------------------------------------------------

ALTER TABLE outbox_messages ALTER COLUMN payload DROP NOT NULL,
  ADD COLUMN payload_enc bytea, ADD COLUMN chat_key text,
  ADD COLUMN asset_keys text[] NOT NULL DEFAULT '{}',
  ADD CONSTRAINT outbox_messages_payload_present CHECK (payload IS NOT NULL OR payload_enc IS NOT NULL);

-- The rows the previous releases wrote: their images, and the chat of a row without an account.
UPDATE outbox_messages
  SET asset_keys = ARRAY(SELECT DISTINCT k #>> '{}' FROM jsonb_path_query(payload, 'lax $.**.assetKey') AS k)
  WHERE payload IS NOT NULL AND jsonb_path_exists(payload, 'lax $.**.assetKey');
UPDATE outbox_messages SET chat_key = payload #>> '{deliveryChat,chatId}'
  WHERE account_id IS NULL AND payload #>> '{deliveryChat,chatId}' IS NOT NULL;

CREATE INDEX ix_outbox_chat ON outbox_messages (vendor, chat_key) WHERE chat_key IS NOT NULL;
CREATE INDEX ix_outbox_unsealed ON outbox_messages (id) WHERE payload IS NOT NULL;

ALTER TABLE inbound_events ADD COLUMN reply_enc bytea, ADD COLUMN chat_key text;

-- Every update went through inbound_inbox (V3), which keeps its chat under the same key until both are deleted.
UPDATE inbound_events e SET chat_key = i.chat_key
  FROM inbound_inbox i
  WHERE e.account_id IS NULL AND i.vendor = e.vendor AND i.vendor_event_id = e.vendor_event_id
    AND i.chat_key <> 'none';

CREATE INDEX ix_inbound_events_chat ON inbound_events (vendor, chat_key) WHERE chat_key IS NOT NULL;
CREATE INDEX ix_inbound_events_unsealed ON inbound_events (vendor, vendor_event_id) WHERE reply IS NOT NULL;
