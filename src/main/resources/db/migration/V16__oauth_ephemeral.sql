-- Ephemeral key/value store for the web-OAuth handoff flow.
--
-- Two namespaces share the table:
--   state   key = caller-generated random base64url(32 bytes)
--           value = JSON {"code_challenge":"<base64url(SHA256(verifier))>"}
--   handoff key = backend-generated random base64url(32 bytes)
--           value = GitHub access_token (plain text)
--
-- Every row has a 60-second TTL enforced two ways:
--   1. SELECT/DELETE queries always include `expires_at > NOW()` so a row
--      that slipped past the cleanup worker still appears gone.
--   2. OAuthCleanupWorker runs every five minutes and DELETEs all rows
--      whose `expires_at` is in the past.
--
-- Postgres has no native TTL. The combination above is what every
-- "use Postgres like Redis for one job" pattern looks like.
CREATE TABLE oauth_ephemeral (
    namespace   VARCHAR(16)  NOT NULL,
    key         VARCHAR(128) NOT NULL,
    value       TEXT         NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (namespace, key)
);

-- Cleanup worker scans by expires_at. Index keeps it cheap.
CREATE INDEX idx_oauth_ephemeral_expires_at ON oauth_ephemeral(expires_at);
