package zed.rainxch.githubstore.match

import kotlinx.coroutines.runBlocking
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.match.ExternalMatchScorer.SearchHit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Service-level pin for the multi-source fan-out. Doesn't touch DB or
// network — overrides the service's `matchOne` calls into the search path
// by injecting a fake ForgejoSearchClient and using a stub service that
// short-circuits the manifest + fingerprint strategies.
class ExternalMatchServiceMultiSourceTest {

    private class FakeForgejo(
        private val perHostHits: Map<String, List<SearchHit>>,
    ) : ForgejoSearchClient(trustedHosts = perHostHits.keys + setOf("codeberg.org", "gitea.com")) {
        // ExternalMatchService.searchAndScoreAcrossSources fans out forge
        // calls in parallel via coroutineScope/async. ConcurrentLinkedQueue
        // is the cheapest lock-free recorder that gives well-defined behaviour
        // under concurrent appends — a plain MutableList loses entries (or
        // throws ConcurrentModificationException) when two coroutines race.
        val calls: ConcurrentLinkedQueue<Pair<String, String>> = ConcurrentLinkedQueue()

        override suspend fun search(
            host: String,
            query: String,
            limit: Int,
            mode: String?,
        ): List<SearchHit> {
            calls += host to query
            return perHostHits[host].orEmpty()
        }
    }

    // Test service that skips manifest + fingerprint + GitHub-search so we
    // can isolate the cross-source merge logic. Calls only the forge fan-out
    // branch of the service by sources=["codeberg"].
    //
    // Production matchOne() consults a Postgres-backed ResourceCacheRepository
    // before invoking searchAndScoreAcrossSources(). The default
    // ResourceCacheRepository opens a newSuspendedTransaction on every get/put
    // — without a configured database the test would block trying to acquire
    // a connection. NoopCache returns null on get and silently drops put, so
    // the service always hits the cold path and exercises the fan-out logic.
    private class NoopCache : ResourceCacheRepository() {
        override suspend fun get(key: String): CacheEntry? = null
        override suspend fun put(
            key: String,
            body: String,
            etag: String?,
            status: Int,
            contentType: String,
            ttlSeconds: Long,
        ) { /* no-op */ }
    }

    private class SearchOnlyService(
        forgejo: ForgejoSearchClient,
    ) : ExternalMatchService(
        signingFingerprintRepository = SigningFingerprintRepository(),
        cache = NoopCache(),
        searchClient = GitHubSearchClient(MeilisearchClient()),
        forgejoSearchClient = forgejo,
    )

    @Test
    fun `sources = codeberg fans out only to codeberg and returns source_host = codeberg dot org`() = runBlocking {
        val forgejo = FakeForgejo(
            mapOf(
                "codeberg.org" to listOf(
                    SearchHit("Freeyourgadget", "Gadgetbridge", 1700, "Pebble + Mi Band etc", true),
                ),
            ),
        )
        val service = SearchOnlyService(forgejo)

        val matches = service.matchOne(
            req = ExternalMatchCandidateRequest(
                packageName = "nodomain.freeyourgadget.gadgetbridge",
                appLabel = "Gadgetbridge",
                // No manifest hint, no fingerprint -> falls through to search.
            ),
            sources = listOf("codeberg"),
        )

        assertEquals(1, forgejo.calls.size, "should call codeberg exactly once")
        assertEquals("codeberg.org", forgejo.calls.first().first)
        assertEquals(1, matches.size)
        val first = matches.first()
        assertEquals("Freeyourgadget", first.owner)
        assertEquals("Gadgetbridge", first.repo)
        assertEquals("forgejo_search", first.source)
        assertEquals("codeberg.org", first.sourceHost)
    }

    @Test
    fun `sources with both forges fans out to both and merges flat`() = runBlocking {
        val forgejo = FakeForgejo(
            mapOf(
                "codeberg.org" to listOf(
                    SearchHit("alice", "tool", 500, "codeberg one", true),
                ),
                "gitea.com" to listOf(
                    SearchHit("bob", "tool", 1500, "gitea one", true),
                ),
            ),
        )
        val service = SearchOnlyService(forgejo)

        val matches = service.matchOne(
            req = ExternalMatchCandidateRequest(
                packageName = "com.example.tool",
                appLabel = "Tool",
            ),
            sources = listOf("codeberg", "gitea"),
        )

        val hosts = forgejo.calls.map { it.first }.toSet()
        assertEquals(setOf("codeberg.org", "gitea.com"), hosts, "both forges queried")
        val sourceHosts = matches.map { it.sourceHost }.toSet()
        assertTrue(sourceHosts.containsAll(setOf("codeberg.org", "gitea.com")), "both source_hosts in flat list: $sourceHosts")
        assertTrue(matches.all { it.source == "forgejo_search" }, "all forge hits source = forgejo_search")
    }

    @Test
    fun `unknown sources are silently skipped in service - route layer is the gatekeeper`() = runBlocking {
        // Defence-in-depth: even if a malformed source reaches the service,
        // it's a skip, not a crash. The route's allowlist is the contract
        // boundary; this test pins the service's tolerant fallback.
        val forgejo = FakeForgejo(emptyMap())
        val service = SearchOnlyService(forgejo)

        val matches = service.matchOne(
            req = ExternalMatchCandidateRequest(
                packageName = "com.example.foo",
                appLabel = "Foo",
            ),
            sources = listOf("totally-not-a-real-source"),
        )
        assertTrue(matches.isEmpty())
        assertTrue(forgejo.calls.isEmpty())
    }
}
