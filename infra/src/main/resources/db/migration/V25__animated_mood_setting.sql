-- V25: the "Animated mood" setting (VISUALS section 4.6, supervisor decision of
-- 2026-11-28). With it on, the answer to a mood check-in is a short silent
-- loop of the blob (an MP4 sent with sendAnimation) with the still card as its
-- fallback; discreet mode keeps every image still. Off by default, so no
-- existing account changes what it receives.

-- ---------------------------------------------------------------------------
-- Identity: the account's animated mood switch.
-- ---------------------------------------------------------------------------

ALTER TABLE users ADD COLUMN animated_mood boolean NOT NULL DEFAULT false;
