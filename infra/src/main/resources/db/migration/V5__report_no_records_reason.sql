-- V5: the scheduled reports' suppression reason (ROADMAP G6, VISUALS_AND_ANALYTICS section 10.2).

-- ---------------------------------------------------------------------------
-- A weekly or monthly report whose period has no record sends nothing
-- (VA-PS-05), and its delivery state is recorded without content as
-- "skipped: no records" (VA-PS-11). The prompt loop records that as a
-- suppressed prompt occurrence with the reason 'no_records', which the
-- reason CHECK of V2 does not allow. The CHECK is replaced by the same list
-- with that one value added; no row changes.
-- ---------------------------------------------------------------------------

ALTER TABLE prompt_occurrences DROP CONSTRAINT prompt_occurrences_reason_check;
ALTER TABLE prompt_occurrences ADD CONSTRAINT prompt_occurrences_reason_check
  CHECK (reason IN ('no_consent','unreachable','quiet_hours','paused','budget','goal_reached',
    'recent_entry','no_goal','on_track','ignored','backoff','crowded','catch_up','stale','no_records'));
