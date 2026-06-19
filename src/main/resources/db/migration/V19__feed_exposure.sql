-- Global (NOT per-user) rotation ledger for /v1/feed (feed v2). Keyed on
-- repo_id + platform only -- no device, user, or IP -- so it is fully
-- compatible with the anonymous, CDN-shared, byte-identical-per-(platform,day)
-- feed invariant. Read at snapshot-build time to compute the cooldown factor
-- (least-recently AND least-frequently shown). Written once per UTC day per
-- platform with the repos actually PLACED in the assembled feed.
--
-- Shipped in Phase 0 alongside repo_daily_snapshot so migration numbering stays
-- sequential; it stays empty until the Phase 1 feed-v2 scoring reads/writes it.
-- Every statement is IF NOT EXISTS so a partially-applied first run re-runs clean.
CREATE TABLE IF NOT EXISTS feed_exposure (
    repo_id             BIGINT      NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    platform            TEXT        NOT NULL DEFAULT 'all',
    last_shown_epochday INTEGER,
    shown_count         INTEGER     NOT NULL DEFAULT 0,
    last_position       INTEGER,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repo_id, platform)
);

CREATE INDEX IF NOT EXISTS idx_feed_exposure_lookup
    ON feed_exposure (platform, last_shown_epochday);
