package zed.rainxch.githubstore.oauth

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Raw JDBC for the same reason ResourceCacheRepository uses raw JDBC: the
// query shapes here (INSERT ... ON CONFLICT DO NOTHING, DELETE ... RETURNING,
// expires_at > NOW()) translate to one trivial SQL line each, and we sidestep
// any Exposed kotlinx-datetime / timestamptz bind surprises.
class PostgresOAuthEphemeralStore : OAuthEphemeralStore {

    override suspend fun setEx(
        namespace: String,
        key: String,
        value: String,
        ttl: Duration,
    ): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val expiresAt = now.plus(ttl)
        val conn = TransactionManager.current().connection.connection as Connection
        conn.prepareStatement(
            """
            INSERT INTO oauth_ephemeral (namespace, key, value, expires_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (namespace, key) DO NOTHING
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, namespace)
            ps.setString(2, key)
            ps.setString(3, value)
            ps.setObject(4, expiresAt)
            ps.executeUpdate() == 1
        }
    }

    override suspend fun getDel(
        namespace: String,
        key: String,
    ): String? = newSuspendedTransaction(Dispatchers.IO) {
        // DELETE ... RETURNING is atomic — single round-trip, no race window
        // between SELECT and DELETE. expires_at filter mirrors a Redis TTL:
        // an expired row that hasn't been swept yet still appears absent.
        val conn = TransactionManager.current().connection.connection as Connection
        conn.prepareStatement(
            """
            DELETE FROM oauth_ephemeral
            WHERE namespace = ? AND key = ? AND expires_at > NOW()
            RETURNING value
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, namespace)
            ps.setString(2, key)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    override suspend fun get(
        namespace: String,
        key: String,
    ): String? = newSuspendedTransaction(Dispatchers.IO) {
        val conn = TransactionManager.current().connection.connection as Connection
        conn.prepareStatement(
            """
            SELECT value FROM oauth_ephemeral
            WHERE namespace = ? AND key = ? AND expires_at > NOW()
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, namespace)
            ps.setString(2, key)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    override suspend fun del(namespace: String, key: String) {
        newSuspendedTransaction(Dispatchers.IO) {
            val conn = TransactionManager.current().connection.connection as Connection
            conn.prepareStatement(
                "DELETE FROM oauth_ephemeral WHERE namespace = ? AND key = ?"
            ).use { ps ->
                ps.setString(1, namespace)
                ps.setString(2, key)
                ps.executeUpdate()
            }
        }
    }

    override suspend fun reapExpired(): Int = newSuspendedTransaction(Dispatchers.IO) {
        val conn = TransactionManager.current().connection.connection as Connection
        conn.prepareStatement(
            "DELETE FROM oauth_ephemeral WHERE expires_at < NOW()"
        ).use { ps ->
            ps.executeUpdate()
        }
    }
}
