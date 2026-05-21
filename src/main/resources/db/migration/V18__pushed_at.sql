-- R5/R13: Add pushed_at_gh to distinguish last-commit timestamp (GitHub's
-- pushed_at / default-branch HEAD) from updated_at_gh (last metadata change).
-- Clients use this for the Heartbeat animation period.
ALTER TABLE repos ADD COLUMN pushed_at_gh TIMESTAMPTZ;
