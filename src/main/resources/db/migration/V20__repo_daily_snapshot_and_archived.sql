-- Daily time-series of cumulative public GitHub counters, one row per repo per
-- UTC day. Velocity (stars/day, downloads/day) is DERIVED by differencing
-- consecutive rows -- GitHub exposes downloads only as a running total, there
-- is no download-history endpoint, so the velocity signal cannot exist without
-- our own capture. Store RAW cumulative values (NOT pre-computed deltas) so the
-- same table dual-serves the future paid maintainer-analytics dashboard with no
-- re-capture. Append-only per (repo_id, snapshot_date).
--
-- Written catalog-wide by the Python fetcher's 02:00 UTC full run (zero extra
-- GitHub calls -- it reads the values it already persisted). star_velocity_ewma
-- / dl_velocity_ewma are filled later by the backend VelocityAggregationWorker
-- (Phase 1); left NULL at capture time.
--
-- Every statement is IF NOT EXISTS: the no-Flyway runner applies one migration
-- per transaction and swallows duplicate-object SQLSTATEs, but a table-level
-- skip would not cover the indexes if a first apply partially failed.
CREATE TABLE IF NOT EXISTS repo_daily_snapshot (
    repo_id              BIGINT      NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    snapshot_date        DATE        NOT NULL,
    stars                INTEGER     NOT NULL,
    download_count       BIGINT      NOT NULL,
    forks                INTEGER,
    open_issues          INTEGER,
    latest_release_date  TIMESTAMPTZ,
    star_velocity_ewma   REAL,
    dl_velocity_ewma     REAL,
    captured_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repo_id, snapshot_date)
);

-- Trailing-window velocity reads ("this repo's last N snapshots").
CREATE INDEX IF NOT EXISTS idx_repo_daily_snapshot_repo_date
    ON repo_daily_snapshot (repo_id, snapshot_date DESC);

-- Dashboard "all repos on day X" / retention sweeps by date.
CREATE INDEX IF NOT EXISTS idx_repo_daily_snapshot_date
    ON repo_daily_snapshot (snapshot_date);

-- Quality gate needs to exclude archived repos. The fetcher populates this from
-- GitHub's repo.archived (Phase 0 db_writer change); default FALSE until the
-- first fetch run touches each row.
ALTER TABLE repos ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index matches the feed gate's `WHERE archived = FALSE` filter.
CREATE INDEX IF NOT EXISTS idx_repos_archived ON repos (archived) WHERE archived = FALSE;
