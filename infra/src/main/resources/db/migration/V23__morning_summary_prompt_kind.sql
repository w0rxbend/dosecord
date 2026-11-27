-- V23: the morning summary (supervisor decision, 2026-11-27; BOT_UX section
-- 14.2, VISUALS_AND_ANALYTICS section 10). A report of yesterday, sent at the
-- time the user chose in /reminders, is one more kind of nudge plan. The CHECK
-- is replaced by the same list with 'report_daily' added; no row changes.

-- ---------------------------------------------------------------------------
-- Day profile, nudge plans and prompts.
-- ---------------------------------------------------------------------------

ALTER TABLE prompt_schedules DROP CONSTRAINT prompt_schedules_kind_check;
ALTER TABLE prompt_schedules ADD CONSTRAINT prompt_schedules_kind_check
  CHECK (kind IN ('water','mood','sleep_bedtime','sleep_morning','meal','report_weekly','report_monthly',
    'report_daily'));
