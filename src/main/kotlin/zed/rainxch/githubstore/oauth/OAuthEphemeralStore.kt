package zed.rainxch.githubstore.oauth

import java.time.Duration

// SETEX + GETDEL semantics on top of whatever ephemeral KV the operator picked.
// PostgresOAuthEphemeralStore is the only production impl today; the interface
// exists so a Redis-backed swap-in stays a one-line Koin change if the OAuth
// flow ever grows past single-instance scale.
interface OAuthEphemeralStore {
    /**
     * Atomic insert. Returns false if the key already exists in the namespace
     * (caller should reject the request — duplicate state on the wire usually
     * means replay or browser-back, never a legitimate flow).
     */
    suspend fun setEx(namespace: String, key: String, value: String, ttl: Duration): Boolean

    /**
     * Atomic delete-and-return. Returns the value if the key existed AND was
     * unexpired, null otherwise. Single-use semantics: a second call always
     * returns null because the row is gone after the first.
     */
    suspend fun getDel(namespace: String, key: String): String?

    /**
     * Read without delete. Used by `/v1/oauth/exchange` to compare the stored
     * code_challenge against the verifier the website forwards. The row is
     * deleted in the same handler via [del] once verification passes — keeping
     * read and delete separate lets the handler distinguish "state missing"
     * from "PKCE mismatch" for accurate error responses.
     */
    suspend fun get(namespace: String, key: String): String?

    /** Hard delete, no return. Used to retire a state row after exchange. */
    suspend fun del(namespace: String, key: String)

    /**
     * DELETE every row whose `expires_at < now`. Returns the count for logging.
     * Called periodically by [OAuthCleanupWorker]; never called from a request
     * handler.
     */
    suspend fun reapExpired(): Int

    companion object {
        const val NAMESPACE_STATE = "state"
        const val NAMESPACE_HANDOFF = "handoff"
    }
}
