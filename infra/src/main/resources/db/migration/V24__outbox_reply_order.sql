-- V24: a strict order for the messages of one answer (verification of
-- 2026-11-28, leftover 1). An answer of several messages (an image and its
-- text, the pages of a strip or a month, a receipt and the next step) was put
-- in the outbox as rows due at one instant, and a claimed batch has no order
-- among them; a row that failed and was retried went after the rows behind
-- it. Each row after the first now names the row before it, and the claim
-- takes a row only once that row has left the queue (sent, dead, failed for
-- good or cancelled). No existing row changes: NULL follows nothing.

-- ---------------------------------------------------------------------------
-- Async edges as tables (ADR-003)
-- ---------------------------------------------------------------------------

ALTER TABLE outbox_messages ADD COLUMN follows uuid;
