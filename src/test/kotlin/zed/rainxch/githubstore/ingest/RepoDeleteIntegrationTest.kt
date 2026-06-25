package zed.rainxch.githubstore.ingest

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import zed.rainxch.githubstore.db.DatabaseFactory
import zed.rainxch.githubstore.db.MeilisearchClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Exercises GitHubSearchClient.deleteRepoById / deleteRepoByFullName against a
// real Postgres so the raw DELETE SQL and the FK ON DELETE CASCADE are verified
// end-to-end. Meili is absent in tests (default localhost:7700, connection
// refused) — the delete still succeeds and reports meiliPurged=false, which is
// the documented partial-success contract.
//
// SKIPPED (JUnit Assume) when no Docker daemon is reachable, so a runner without
// Docker can't masquerade as a green pass. CI always has Docker.
class RepoDeleteIntegrationTest {

    private var container: PostgreSQLContainer<*>? = null
    private val dockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }
    private val client = GitHubSearchClient(MeilisearchClient())

    @BeforeTest
    fun setUp() {
        assumeTrue("Docker not available — skipping DB-integration test", dockerAvailable)
        container = PostgreSQLContainer("postgres:17-alpine")
        container!!.start()
        Database.connect(
            url = container!!.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = container!!.username,
            password = container!!.password,
        )
        DatabaseFactory.runMigrations()
        seed()
    }

    @AfterTest
    fun tearDown() {
        container?.stop()
    }

    @Test
    fun `deleteRepoById removes the row, cascades children, returns the id`() = runBlocking {
        assertTrue(rowExists(1L), "seed row should exist before delete")
        assertTrue(signalExists(1L), "seed signal should exist before delete")

        val result = client.deleteRepoById(1L)

        assertNotNull(result, "delete of an existing id must return a result")
        assertEquals(1L, result.id)
        // No Meili server in tests → purge fails gracefully, not the whole op.
        assertEquals(false, result.meiliPurged)
        assertTrue(!rowExists(1L), "repos row must be gone after delete")
        assertTrue(!signalExists(1L), "repo_signals child must cascade-delete")
    }

    @Test
    fun `deleteRepoByFullName removes exactly the matching row`() = runBlocking {
        val result = client.deleteRepoByFullName("b/popular")

        assertNotNull(result, "delete of an existing full_name must return a result")
        assertEquals(2L, result.id)
        assertTrue(!rowExists(2L), "matched row must be gone")
        assertTrue(rowExists(1L), "unrelated row must survive")
    }

    @Test
    fun `deleteRepoById on a missing id returns null and changes nothing`() = runBlocking {
        val result = client.deleteRepoById(999_999L)
        assertNull(result, "missing id must return null")
        assertTrue(rowExists(1L), "no row should be touched")
    }

    @Test
    fun `deleteRepoByFullName on a missing full_name returns null`() = runBlocking {
        val result = client.deleteRepoByFullName("nope/nope")
        assertNull(result)
    }

    private fun rowExists(id: Long): Boolean = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement("SELECT 1 FROM repos WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun signalExists(repoId: Long): Boolean = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement("SELECT 1 FROM repo_signals WHERE repo_id = ?").use { ps ->
            ps.setLong(1, repoId)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    private fun seed() = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.createStatement().use { st ->
            st.execute(
                """
                INSERT INTO repos (id, full_name, owner, name, html_url, stars, forks)
                VALUES
                  (1, 'a/trending', 'a', 'trending', 'https://github.com/a/trending', 5000, 10),
                  (2, 'b/popular', 'b', 'popular', 'https://github.com/b/popular', 9000, 20)
                """.trimIndent()
            )
            // Child row to prove FK ON DELETE CASCADE fires for row 1.
            st.execute(
                "INSERT INTO repo_signals (repo_id, updated_at) VALUES (1, NOW())"
            )
        }
    }
}
