package zed.rainxch.githubstore.db

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
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
// Skips (passes as a no-op) when no Docker daemon is reachable so a local
// `./gradlew test` without Docker doesn't fail. CI runners always have Docker.
class FeedRepositoryIntegrationTest {

    private var container: PostgreSQLContainer<*>? = null
    private val dockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    @BeforeTest
    fun setUp() {
        if (!dockerAvailable) return
        val c = PostgreSQLContainer("postgres:17-alpine")
        c.start()
        container = c
        Database.connect(
            url = c.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = c.username,
            password = c.password,
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
        if (!dockerAvailable) return
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
        if (!dockerAvailable) return
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
            // Text-match path with relevance.
            val matched = search.search(query = "trending", platform = null, sort = "relevance", limit = 10)
            assertTrue(matched.isNotEmpty(), "relevance search found no match for a seeded name")
        }
    }

    private fun seed() = transaction {
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        // Minimal rows that land in each pool. tsv_search is a generated/
        // trigger column in the real schema, populated from name/description.
        conn.createStatement().use { st ->
            st.execute(
                """
                INSERT INTO repos (id, full_name, owner, name, html_url, stars, forks,
                    trending_score, popularity_score, search_score, download_count,
                    latest_release_date, topics,
                    has_installers_android, has_installers_windows, has_installers_macos, has_installers_linux)
                VALUES
                  (1, 'a/trending', 'a', 'trending', 'https://github.com/a/trending', 5000, 10,
                    0.9, 0.5, 0.8, 100000, NOW() - INTERVAL '2 days', ARRAY['privacy','android'],
                    true, false, false, false),
                  (2, 'b/popular', 'b', 'popular', 'https://github.com/b/popular', 9000, 20,
                    0.4, 0.95, 0.85, 500000, NOW() - INTERVAL '20 days', ARRAY['ai'],
                    false, true, false, false),
                  (3, 'c/gem', 'c', 'gem', 'https://github.com/c/gem', 300, 5,
                    NULL, NULL, 0.6, 2000, NOW() - INTERVAL '5 days', ARRAY['notes'],
                    true, false, false, true),
                  (4, 'd/fresh', 'd', 'fresh', 'https://github.com/d/fresh', 1200, 8,
                    0.3, 0.2, 0.4, 8000, NOW() - INTERVAL '1 day', ARRAY['messaging'],
                    false, false, true, false)
                """.trimIndent()
            )
        }
    }
}
