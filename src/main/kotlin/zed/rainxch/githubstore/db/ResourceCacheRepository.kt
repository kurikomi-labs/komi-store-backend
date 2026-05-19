package zed.rainxch.githubstore.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

open class ResourceCacheRepository {

    open suspend fun get(key: String): CacheEntry? = newSuspendedTransaction(Dispatchers.IO) {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement(
            """
            SELECT cache_key, body, etag, status, content_type, fetched_at, expires_at
            FROM resource_cache
            WHERE cache_key = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@newSuspendedTransaction null
                CacheEntry(
                    key = rs.getString("cache_key"),
                    body = rs.getString("body"),
                    etag = rs.getString("etag"),
                    status = rs.getInt("status"),
                    contentType = rs.getString("content_type"),
                    fetchedAt = rs.getTimestamp("fetched_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
                    expiresAt = rs.getTimestamp("expires_at").toInstant().atOffset(java.time.ZoneOffset.UTC),
                )
            }
        }
    }

    open suspend fun put(
        key: String,
        body: String,
        etag: String?,
        status: Int,
        contentType: String = "application/json",
        ttlSeconds: Long,
    ) {
        val now = OffsetDateTime.now()
        val expires = now.plusSeconds(ttlSeconds)
        newSuspendedTransaction(Dispatchers.IO) {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            conn.prepareStatement(
                """
                INSERT INTO resource_cache (
                    cache_key, body, etag, status, content_type, content_bytes, fetched_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (cache_key) DO UPDATE SET
                    body          = EXCLUDED.body,
                    etag          = EXCLUDED.etag,
                    status        = EXCLUDED.status,
                    content_type  = EXCLUDED.content_type,
                    content_bytes = EXCLUDED.content_bytes,
                    fetched_at    = EXCLUDED.fetched_at,
                    expires_at    = EXCLUDED.expires_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, key)
                ps.setString(2, body)
                if (etag != null) ps.setString(3, etag) else ps.setNull(3, java.sql.Types.VARCHAR)
                ps.setInt(4, status)
                ps.setString(5, contentType)
                ps.setInt(6, body.length)
                ps.setObject(7, now)
                ps.setObject(8, expires)
                ps.executeUpdate()
            }
        }
    }

    // Called after a 304 revalidation — body + etag unchanged, just push
    // fetched_at and expires_at forward so the next access treats it as fresh.
    suspend fun refreshTtl(key: String, ttlSeconds: Long) {
        val now = OffsetDateTime.now()
        val expires = now.plusSeconds(ttlSeconds)
        newSuspendedTransaction(Dispatchers.IO) {
            val conn = TransactionManager.current().connection.connection as java.sql.Connection
            conn.prepareStatement(
                "UPDATE resource_cache SET fetched_at = ?, expires_at = ? WHERE cache_key = ?"
            ).use { ps ->
                ps.setObject(1, now)
                ps.setObject(2, expires)
                ps.setString(3, key)
                ps.executeUpdate()
            }
        }
    }

    // Housekeeping — drop entries nobody has touched in a long time. Called
    // from the existing RepoRefreshWorker so we don't add a new worker for one
    // cleanup query. Returns the row count swept for logging.
    suspend fun sweepStale(olderThanDays: Long = 30): Int = newSuspendedTransaction(Dispatchers.IO) {
        val cutoff = OffsetDateTime.now().minusDays(olderThanDays)
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement(
            "DELETE FROM resource_cache WHERE fetched_at < ?"
        ).use { ps ->
            ps.setObject(1, cutoff)
            ps.executeUpdate()
        }
    }

    data class CacheEntry(
        val key: String,
        val body: String,
        val etag: String?,
        val status: Int,
        val contentType: String,
        val fetchedAt: OffsetDateTime,
        val expiresAt: OffsetDateTime,
    ) {
        fun isFresh(): Boolean = expiresAt.isAfter(OffsetDateTime.now())
    }
}
