-- V16: bedtime mode, nap check-ins and the gamification switch (ROADMAP G7.2,
-- G9.4; PRODUCT_SPEC R-SLP-7, R-SLP-23, R-GAM-5; supervisor decision,
-- 2026-11-14). Each CHECK is replaced by the same list with values added; no
-- row changes.

-- ---------------------------------------------------------------------------
-- Day profile, nudge plans and prompts.
-- ---------------------------------------------------------------------------
-- Between "Going to sleep" and "I'm up" nudges and reports are held (R-SLP-23,
-- BOT_UX BUX-SLEEP-01). The prompt loop records such a slot as suppressed
-- with the reason 'bedtime', so the delivery state says why (VA-PS-11).

ALTER TABLE prompt_occurrences DROP CONSTRAINT prompt_occurrences_reason_check;
ALTER TABLE prompt_occurrences ADD CONSTRAINT prompt_occurrences_reason_check
  CHECK (reason IN ('no_consent','unreachable','quiet_hours','paused','budget','goal_reached',
    'recent_entry','no_goal','on_track','ignored','backoff','crowded','catch_up','stale','no_records',
    'bedtime'));

-- ---------------------------------------------------------------------------
-- Tracking entries: sleep.
-- ---------------------------------------------------------------------------
-- A nap is recorded from an explicit "Nap" check-in (R-SLP-7): the tap that
-- starts it is kept as it happened, like "Going to sleep", and "I'm up"
-- closes it as a nap.

ALTER TABLE sleep_checkins DROP CONSTRAINT sleep_checkins_kind_check;
ALTER TABLE sleep_checkins ADD CONSTRAINT sleep_checkins_kind_check
  CHECK (kind IN ('going_to_sleep','im_up','nap_start'));

-- ---------------------------------------------------------------------------
-- Identity: the account's gamification switch.
-- ---------------------------------------------------------------------------
-- One account setting turns the weekly consistency counts and the logging
-- milestones off on every surface (R-GAM-5, ARCHITECTURE section 10.2
-- `gamification_enabled`). On by default, as the architecture lists it.

ALTER TABLE users ADD COLUMN gamification_enabled boolean NOT NULL DEFAULT true;
