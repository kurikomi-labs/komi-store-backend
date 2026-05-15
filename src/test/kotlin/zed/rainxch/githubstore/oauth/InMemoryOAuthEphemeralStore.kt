package zed.rainxch.githubstore.oauth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

// Test double for OAuthEphemeralStore. Same SETEX/GETDEL semantics as the
// Postgres impl but lives in-process. Production code MUST use
// PostgresOAuthEphemeralStore — this exists only because the real impl
// requires a live Postgres + the V16 migration, which we don't spin up
// for the route-level unit tests.
class InMemoryOAuthEphemeralStore(
    private val clock: () -> Instant = Instant::now,
) : OAuthEphemeralStore {

    private data class Entry(val value: String, val expiresAt: Instant)

    private val mutex = Mutex()
    private val rows = HashMap<Pair<String, String>, Entry>()

    override suspend fun setEx(
        namespace: String,
        key: String,
        value: String,
        ttl: Duration,
    ): Boolean = mutex.withLock {
        purgeExpired()
        val k = namespace to key
        if (rows.containsKey(k)) return@withLock false
        rows[k] = Entry(value, clock().plus(ttl))
        true
    }

    override suspend fun getDel(namespace: String, key: String): String? = mutex.withLock {
        purgeExpired()
        rows.remove(namespace to key)?.value
    }

    override suspend fun get(namespace: String, key: String): String? = mutex.withLock {
        purgeExpired()
        rows[namespace to key]?.value
    }

    override suspend fun del(namespace: String, key: String) {
        mutex.withLock { rows.remove(namespace to key) }
    }

    override suspend fun reapExpired(): Int = mutex.withLock {
        val before = rows.size
        purgeExpired()
        before - rows.size
    }

    private fun purgeExpired() {
        val now = clock()
        rows.entries.removeAll { it.value.expiresAt.isBefore(now) || it.value.expiresAt == now }
    }
}
