-- V9: medication inventory with its refill notice, and a pause with a resume
-- date (ROADMAP G9.10, G9.12; PRODUCT_SPEC R-REG-5, R-REG-11 to R-REG-16).

-- ---------------------------------------------------------------------------
-- Medications, schedules, occurrences (docs/ARCHITECTURE.md section 10.17).
--
-- medication_inventory: at most one row per medication, made when the user
-- first enters a count. on_hand never goes below zero (R-REG-16): a deduction
-- that would pass zero stores zero and sets count_off, which owes the user one
-- "The count may be off" notice. The threshold is in days of supply or in
-- units, never both (R-REG-11). notices_stopped is "Stop reminding for this
-- pack": a refill or a new count clears it. version moves on every write by
-- the application (an edit carries the version it read).
--
-- inventory_events: append-only history of every change of on_hand. A set or
-- a refill carries the idempotency key of the request that made it, so a
-- repeated request changes nothing; an intake or a restore names the dose.
-- ---------------------------------------------------------------------------

CREATE TABLE medication_inventory (medication_id uuid PRIMARY KEY REFERENCES medications,
  account_id uuid NOT NULL REFERENCES users,
  on_hand numeric(12,3) NOT NULL CHECK (on_hand >= 0),
  per_intake numeric(12,3) NOT NULL CHECK (per_intake > 0),
  threshold_days int CHECK (threshold_days BETWEEN 1 AND 365),
  threshold_units numeric(12,3) CHECK (threshold_units >= 0),
  last_refill numeric(12,3) CHECK (last_refill > 0),
  count_off boolean NOT NULL DEFAULT false,
  notices_stopped boolean NOT NULL DEFAULT false,
  last_notice_at timestamptz,
  version int NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at timestamptz NOT NULL,
  CONSTRAINT medication_inventory_one_threshold CHECK (threshold_days IS NULL OR threshold_units IS NULL));
CREATE INDEX ix_medication_inventory_account ON medication_inventory (account_id);

CREATE TABLE inventory_events (id uuid PRIMARY KEY, account_id uuid NOT NULL REFERENCES users,
  medication_id uuid NOT NULL REFERENCES medications,
  kind text NOT NULL CHECK (kind IN ('set','refill','intake','restore')),
  quantity numeric(12,3) NOT NULL CHECK (quantity >= 0),
  on_hand_after numeric(12,3) NOT NULL CHECK (on_hand_after >= 0),
  occurrence_id uuid REFERENCES dose_occurrences,
  idempotency_key text UNIQUE,
  occurred_at timestamptz NOT NULL,
  CONSTRAINT inventory_events_source CHECK ((kind IN ('intake','restore')) = (occurrence_id IS NOT NULL)));
CREATE INDEX ix_inventory_events_medication ON inventory_events (medication_id, occurred_at);
CREATE INDEX ix_inventory_events_occurrence ON inventory_events (occurrence_id) WHERE occurrence_id IS NOT NULL;
CREATE TRIGGER inventory_events_append_only BEFORE UPDATE OR DELETE ON inventory_events
  FOR EACH ROW EXECUTE FUNCTION dosegram_guard_append_only();

-- R-REG-12: a dose that becomes taken deducts per_intake, scheduled or as
-- needed alike, on every write path (reminder tap, command, manual log,
-- correction); a dose that leaves taken (Undo, a correction) gives back what
-- its last deduction took. The time is the occurrence's updated_at, which the
-- application binds from its clock (ADR-011).
CREATE FUNCTION dosegram_inventory_on_dose() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  inv medication_inventory%ROWTYPE;
  taken numeric(12,3);
  owed numeric(12,3);
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
      SELECT e.quantity INTO owed FROM inventory_events e
        WHERE e.occurrence_id = NEW.id AND e.kind = 'intake'
        ORDER BY e.occurred_at DESC, e.id LIMIT 1;
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
CREATE TRIGGER dose_occurrences_inventory_insert AFTER INSERT ON dose_occurrences
  FOR EACH ROW WHEN (NEW.status = 'taken') EXECUTE FUNCTION dosegram_inventory_on_dose();
CREATE TRIGGER dose_occurrences_inventory_update AFTER UPDATE OF status ON dose_occurrences
  FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status AND (OLD.status = 'taken' OR NEW.status = 'taken'))
  EXECUTE FUNCTION dosegram_inventory_on_dose();

-- R-REG-5: a pause may carry a resume date; the schedule resumes at local
-- midnight of that date in its zone. NULL on every schedule that is not
-- paused, or is paused until the user resumes it.
ALTER TABLE medication_schedules ADD COLUMN resume_on date;
CREATE INDEX ix_medication_schedules_resume_on ON medication_schedules (resume_on)
  WHERE status = 'paused' AND resume_on IS NOT NULL;
-- A schedule that leaves the pause by any path (resume in the chat or the app,
-- archive, a re-added medication) forgets its resume date, so a later pause
-- without a date never resumes on an old one.
CREATE FUNCTION dosegram_schedule_forget_resume_on() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status <> 'paused' THEN
    NEW.resume_on := NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER medication_schedules_forget_resume_on BEFORE UPDATE OF status ON medication_schedules
  FOR EACH ROW EXECUTE FUNCTION dosegram_schedule_forget_resume_on();

-- ---------------------------------------------------------------------------
-- Grants. The blanket grant of V1 covers only the tables that existed then.
-- ---------------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE ON medication_inventory TO dosegram_app;
GRANT SELECT, INSERT ON inventory_events TO dosegram_app;
-- Inventory and its history: deleted by erasure only.
GRANT SELECT, DELETE ON medication_inventory, inventory_events TO dosegram_erasure;
