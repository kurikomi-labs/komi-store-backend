-- V17: extend signing_fingerprint with a `host` column so non-GitHub forge
-- fingerprints (Codeberg-hosted F-Droid APKs etc.) can land in the same
-- table the /v1/external-match fingerprint path already consults.
--
-- Forges-integration brief §3.2 (Tier 1 follow-up to multi-source
-- external-match). Backfill existing rows with 'github.com' — every row
-- that was inserted before this migration came from the GitHub-only
-- F-Droid seed worker, so the backfill default is safe and correct.
--
-- PK is widened to (host, fingerprint, owner, repo) so the same APK that
-- ships from two forges (e.g. official GitHub + Codeberg mirror) yields
-- two distinct rows, which is what /v1/external-match's cross-host dedup
-- in §3.6 will use to detect mirrored releases.

ALTER TABLE signing_fingerprint
    ADD COLUMN IF NOT EXISTS host TEXT NOT NULL DEFAULT 'github.com';

-- Drop the old PK + recreate with host as the leading discriminator.
-- Wrapped in a DO block so the migration is idempotent on a database
-- that has already been upgraded.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'signing_fingerprint'::regclass
          AND contype  = 'p'
          AND conname  = 'signing_fingerprint_pkey'
    ) THEN
        BEGIN
            ALTER TABLE signing_fingerprint DROP CONSTRAINT signing_fingerprint_pkey;
        EXCEPTION WHEN undefined_object THEN
            -- Already dropped; continue.
            NULL;
        END;
    END IF;
END $$;

ALTER TABLE signing_fingerprint
    ADD PRIMARY KEY (host, fingerprint, owner, repo);

-- The (fingerprint) lookup index from V12 still works for the hot /v1/external-match
-- path — the PK's leading host column means a `WHERE fingerprint = ?` query
-- scans the dedicated index, not the PK. Keep it.
--
-- A new index lets §3.6 cross-host dedup find every (fingerprint, owner, repo)
-- triple across hosts in a single bounded scan.
CREATE INDEX IF NOT EXISTS signing_fingerprint_fp_or_idx
    ON signing_fingerprint (fingerprint, owner, repo);
