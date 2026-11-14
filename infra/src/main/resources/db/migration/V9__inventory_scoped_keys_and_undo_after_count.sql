-- V9: inventory idempotency keys per medication, and Undo after a new count
-- (ROADMAP G9.10; PRODUCT_SPEC R-REG-12, R-REG-14; supervisor decision,
-- 2026-11-14). V8 is frozen: this migration only adds to it.

-- ---------------------------------------------------------------------------
-- An idempotency key of inventory_events belongs to one account's medication.
-- V8 made it unique across every account, so the same key sent for another
-- medication was skipped as a repeat, or refused by the constraint. Every
-- stored key is unique across the table, so it is unique per medication too.
-- ---------------------------------------------------------------------------

ALTER TABLE inventory_events DROP CONSTRAINT inventory_events_idempotency_key_key;
CREATE UNIQUE INDEX ux_inventory_events_idempotency_key
  ON inventory_events (account_id, medication_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- R-REG-12 with the rule for a new count (supervisor decision, 2026-11-14):
-- a count the user sets after a dose is what the user has from then on, the
-- dose already included. A dose that leaves taken gives back its last
-- deduction only when no set is later than that deduction; otherwise it gives
-- nothing back, so Undo never lifts the count above what the user entered. A
-- refill adds to the count and does not replace it, so it does not stop the
-- give-back. "Later" compares the occurred_at times, which the application
-- binds from its clock (ADR-011). Everything else is V8's function unchanged.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION dosegram_inventory_on_dose() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  inv medication_inventory%ROWTYPE;
  taken numeric(12,3);
  owed numeric(12,3);
  owed_at timestamptz;
BEGIN
  SELECT * INTO inv FROM medication_inventory WHERE medication_id = NEW.medication_id FOR UPDATE;
  IF NOT FOUND THEN
    RETURN NULL;
  END IF;
  IF NEW.status = 'taken' AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM 'taken') THEN
    taken := LEAST(inv.per_intake, inv.on_hand);
    UPDATE medication_inventory SET on_hand = on_hand - taken,
      count_off = count_off OR inv.on_hand < inv.per_intake,
      version = version + 1, updated_at = NEW.updated_at
      WHERE medication_id = NEW.medication_id;
    INSERT INTO inventory_events (id, account_id, medication_id, kind, quantity, on_hand_after, occurrence_id, occurred_at)
      VALUES (gen_random_uuid(), NEW.account_id, NEW.medication_id, 'intake', taken, inv.on_hand - taken, NEW.id,
              NEW.updated_at);
  ELSIF TG_OP = 'UPDATE' AND OLD.status = 'taken' AND NEW.status IS DISTINCT FROM 'taken' THEN
    -- Owed only while the dose has more deductions than restores: a dose taken before the count existed owes nothing.
    IF (SELECT count(*) FILTER (WHERE kind = 'intake') > count(*) FILTER (WHERE kind = 'restore')
        FROM inventory_events WHERE occurrence_id = NEW.id) THEN
      SELECT e.quantity, e.occurred_at INTO owed, owed_at FROM inventory_events e
        WHERE e.occurrence_id = NEW.id AND e.kind = 'intake'
        ORDER BY e.occurred_at DESC, e.id LIMIT 1;
    END IF;
    IF owed IS NOT NULL AND EXISTS (SELECT 1 FROM inventory_events s
        WHERE s.medication_id = NEW.medication_id AND s.kind = 'set' AND s.occurred_at > owed_at) THEN
      owed := NULL;
    END IF;
    IF owed IS NOT NULL THEN
      UPDATE medication_inventory SET on_hand = on_hand + owed, version = version + 1, updated_at = NEW.updated_at
        WHERE medication_id = NEW.medication_id;
      INSERT INTO inventory_events (id, account_id, medication_id, kind, quantity, on_hand_after, occurrence_id, occurred_at)
        VALUES (gen_random_uuid(), NEW.account_id, NEW.medication_id, 'restore', owed, inv.on_hand + owed, NEW.id,
                NEW.updated_at);
    END IF;
  END IF;
  RETURN NULL;
END $$;
