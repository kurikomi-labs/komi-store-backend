package zed.rainxch.githubstore.db

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assume.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

// Executes the actual FeedRepository pool SQL and SearchRepository sort SQL
// against a real Postgres. The WHERE-glue 500 (#29) — `WHEREtrending_score`
// — slipped past CI because every SQL string only fails when a real planner
// parses it; the FeedAssembler unit tests never touch the DB. This closes
// that gap for the whole query surface that feeds the feed + search.
//
// Reports SKIPPED (via JUnit Assume) — not a silent no-op PASS — when no
// Docker daemon is reachable, so a misconfigured runner can't masquerade as
// green. A local `./gradlew test` without Docker shows these as skipped. CI
// runners always have Docker, so the smoke runs there for real.
class FeedRepositoryIntegrationTest {

    private var container: PostgreSQLContainer<*>? = null
    private val dockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    @BeforeTest
    fun setUp() {
        // assumeTrue aborts setUp + the test (reported SKIPPED) when Docker is
        // absent; tearDown still runs (container is null → no-op stop).
        assumeTrue("Docker not available — skipping DB-integration smoke", dockerAvailable)
        // Assign before start() so a start() failure still leaves a handle for
        // tearDown to stop (Ryuk is the backstop, but don't rely on it alone).
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
    fun `feed pools execute without SQL errors and respect filters`() {
        val feed = FeedRepository()
        runBlocking {
            // Each call would throw PSQLException on a malformed query — the
            // exact failure mode of #29. Reaching the asserts means the SQL
            // parsed and ran.
            val trending = feed.trendingPool(platform = null)
            val releases = feed.releasesPool(platform = null)
            val gems = feed.gemsPool(platform = null)
            val popular = feed.popularPool(platform = null)

            // Seeded data: trending/popular rows have their scores; gems row
            // is in the 50–800 star band with a recent release.
            assertTrue(trending.any { it.fullName == "a/trending" }, "trending pool missed its row")
            assertTrue(popular.any { it.fullName == "b/popular" }, "popular pool missed its row")
            assertTrue(gems.any { it.fullName == "c/gem" }, "gems pool missed its row")
            assertTrue(releases.any { it.fullName == "d/fresh" }, "releases pool missed its fresh row")

            // Platform filter must also parse + apply.
            val androidTrending = feed.trendingPool(platform = "android")
            assertTrue(androidTrending.all { it.hasInstallersAndroid }, "platform filter leaked a non-android row")
        }
    }

    @Test
    fun `search sort paths execute without SQL errors`() {
        val search = SearchRepository()
        runBlocking {
            // Browse mode (empty query) + every non-relevance sort + both
            // directions. These are the exact ORDER BY strings #711 reworked.
            for (sort in listOf("stars", "releases", "updated")) {
                for (order in listOf("asc", "desc")) {
                    val rows = search.search(query = "", platform = null, sort = sort, order = order, limit = 10)
                    assertTrue(rows.size >= 1, "sort=$sort order=$order returned nothing on seeded data")
                }
            }
            // Text-match path with relevance. The tsv_search trigger indexes
            // full_name + description + topics (NOT name), so the query must
            // hit a description word, not the repo name. "discoverable" stems
            // to 'discover' and is seeded into row 1's description.
            val matched = search.search(query = "discoverable", platform = null, sort = "relevance", limit = 10)
            assertTrue(matched.isNotEmpty(), "relevance search found no match for a seeded description term")
        }
    }

    private fun seed() = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        // Minimal rows that land in each pool. tsv_search is trigger-populated
        // from full_name + description + topics (not name). Row 1's description
        // carries "discoverable" so the relevance text-match assertion has a
        // deterministic hit independent of how full_name tokenizes.
        conn.createStatement().use { st ->
            st.execute(
                """
                INSERT INTO repos (id, full_name, owner, name, description, html_url, stars, forks,
                    trending_score, popularity_score, search_score, download_count,
                    latest_release_date, topics,
                    has_installers_android, has_installers_windows, has_installers_macos, has_installers_linux)
                VALUES
                  (1, 'a/trending', 'a', 'trending', 'a discoverable privacy tool', 'https://github.com/a/trending', 5000, 10,
                    0.9, 0.5, 0.8, 100000, NOW() - INTERVAL '2 days', ARRAY['privacy','android'],
                    true, false, false, false),
                  (2, 'b/popular', 'b', 'popular', 'popular ai assistant', 'https://github.com/b/popular', 9000, 20,
                    0.4, 0.95, 0.85, 500000, NOW() - INTERVAL '20 days', ARRAY['ai'],
                    false, true, false, false),
                  (3, 'c/gem', 'c', 'gem', 'a hidden notes app', 'https://github.com/c/gem', 300, 5,
                    NULL, NULL, 0.6, 2000, NOW() - INTERVAL '5 days', ARRAY['notes'],
                    true, false, false, true),
                  (4, 'd/fresh', 'd', 'fresh', 'fresh messaging client', 'https://github.com/d/fresh', 1200, 8,
                    0.3, 0.2, 0.4, 8000, NOW() - INTERVAL '1 day', ARRAY['messaging'],
                    false, false, true, false)
                """.trimIndent()
            )
        }
    }
}
