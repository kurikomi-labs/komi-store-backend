package zed.rainxch.githubstore.match

import kotlinx.coroutines.runBlocking
import zed.rainxch.githubstore.match.ExternalMatchScorer.SearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgejoSearchClientTest {

    @Test
    fun `parseTrustedHostsEnv normalises and dedupes`() {
        val parsed = ForgejoSearchClient.parseTrustedHostsEnv(
            "  Codeberg.org , gitea.com , codeberg.org ,",
        )
        assertEquals(setOf("codeberg.org", "gitea.com"), parsed)
    }

    @Test
    fun `parseTrustedHostsEnv falls back to default when blank`() {
        assertEquals(ForgejoSearchClient.DEFAULT_TRUSTED_HOSTS, ForgejoSearchClient.parseTrustedHostsEnv(null))
        assertEquals(ForgejoSearchClient.DEFAULT_TRUSTED_HOSTS, ForgejoSearchClient.parseTrustedHostsEnv("   "))
        assertEquals(ForgejoSearchClient.DEFAULT_TRUSTED_HOSTS, ForgejoSearchClient.parseTrustedHostsEnv(",,, ,"))
    }

    @Test
    fun `untrusted host short-circuits with empty list and no HTTP call`() = runBlocking {
        // Constructor allowlist excludes evil.example, so the search method
        // must refuse to dispatch even if the route layer somehow let it
        // through. This is the defence-in-depth check the route's
        // SSRF-guard rationale relies on.
        val client = ForgejoSearchClient(trustedHosts = setOf("codeberg.org"))
        val hits = client.search("evil.example", "anything")
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `blank query short-circuits to empty list`() = runBlocking {
        val client = ForgejoSearchClient(trustedHosts = setOf("codeberg.org"))
        assertTrue(client.search("codeberg.org", "").isEmpty())
        assertTrue(client.search("codeberg.org", "   ").isEmpty())
    }

    @Test
    fun `SOURCE_TO_HOST maps github to null and forges to canonical hosts`() {
        // Pins the contract the service relies on for source → host
        // routing. If a future refactor changes the map shape, this test
        // catches the silent breakage before the service path 4xx-s.
        //
        // Plain `map["github"]` would also pass if the key were missing
        // (kotlin maps return null on absent keys), which would mask a
        // real "github" key removal. Assert presence explicitly first,
        // then use getValue so a missing key throws — null-on-present and
        // null-on-absent stop being indistinguishable.
        val map = ForgejoSearchClient.SOURCE_TO_HOST
        assertTrue(map.containsKey("github"), "SOURCE_TO_HOST must contain 'github' key")
        assertNull(map.getValue("github"))
        assertEquals("codeberg.org", map.getValue("codeberg"))
        assertEquals("gitea.com", map.getValue("gitea"))
        assertEquals("git.disroot.org", map.getValue("disroot"))
    }

    @Test
    fun `unused SearchHit reference compiles - guards against rename drift`() {
        // ForgejoSearchClient returns ExternalMatchScorer.SearchHit instances
        // so the existing scorer can rank GitHub + forge hits together. Pin
        // the cross-package reference so a scorer rename doesn't silently
        // break the multi-source flow.
        val hit = SearchHit("o", "r", 0, null, false)
        assertEquals("o", hit.owner)
    }
}
