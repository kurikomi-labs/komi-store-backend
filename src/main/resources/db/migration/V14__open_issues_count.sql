-- V14: track open_issues_count on the repos table so the details screen
-- can surface "X open issues" without an extra GitHub passthrough call.
--
-- GitHub's open_issues_count includes both issues and pull requests (GitHub
-- treats PRs as a kind of issue internally). The client surfaces it as the
-- same number the GitHub website shows on the Issues tab; no separation
-- attempted server-side.
--
-- Default 0 for existing rows. Backend writes the real value on:
--   * search passthrough ingest (`GitHubSearchClient.ingestToPostgres`)
--   * POST /v1/repo/{owner}/{name}/refresh
--   * RepoRefreshWorker's hourly cycle
--   * the Python fetcher's daily run (once the fetcher repo wires the field
--     into db_writer.py + meili_sync.py)
--
-- Idempotent: ADD COLUMN IF NOT EXISTS handles re-runs.

ALTER TABLE repos
    ADD COLUMN IF NOT EXISTS open_issues INTEGER NOT NULL DEFAULT 0;
