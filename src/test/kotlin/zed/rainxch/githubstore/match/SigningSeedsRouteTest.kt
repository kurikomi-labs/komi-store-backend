package zed.rainxch.githubstore.match

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import zed.rainxch.githubstore.routes.signingSeedsRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SigningSeedsRouteTest {

    private class FakeRepo(
        private val pages: List<List<SigningSeedRow>> = emptyList(),
    ) : SigningFingerprintRepository() {
        var lastSinceMs: Long? = null
        var lastCursor: PageCursor? = null
        var lastLimit: Int = -1
        var pageIndex = 0

        override suspend fun page(sinceMs: Long?, cursor: PageCursor?, limit: Int): SigningSeedPage {
            lastSinceMs = sinceMs
            lastCursor = cursor
            lastLimit = limit
            val current = pages.getOrElse(pageIndex) { emptyList() }
            pageIndex++
            val nextCursor = if (pageIndex < pages.size && current.isNotEmpty()) {
                val last = current.last()
                PageCursor(last.observedAt, last.fingerprint, last.owner, last.repo)
            } else null
            return SigningSeedPage(rows = current, nextCursor = nextCursor)
        }
    }

    // Returns the same rows on every call. Used for cache-validation tests
    // where repeated requests must yield byte-identical responses (so the
    // ETag stays stable).
    private class StaticFakeRepo(
        private val rows: List<SigningSeedRow>,
    ) : SigningFingerprintRepository() {
        override suspend fun page(sinceMs: Long?, cursor: PageCursor?, limit: Int): SigningSeedPage =
            SigningSeedPage(rows = rows, nextCursor = null)
    }

    private fun ApplicationTestBuilder.installPlugins() {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `missing platform query parameter returns 400`() = testApplication {
        val repo = FakeRepo()
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `non-android platform returns 400`() = testApplication {
        val repo = FakeRepo()
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds?platform=ios")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `non-numeric since returns 400`() = testApplication {
        val repo = FakeRepo()
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds?platform=android&since=yesterday")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `negative since returns 400`() = testApplication {
        val repo = FakeRepo()
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds?platform=android&since=-1")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid cursor returns 400`() = testApplication {
        val repo = FakeRepo()
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds?platform=android&cursor=not-a-real-token!!!")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `valid request without since returns rows and no next cursor on small dataset`() = testApplication {
        val repo = FakeRepo(pages = listOf(listOf(
            SigningSeedRow("AB:CD", "octocat", "hello-world", 100L),
            SigningSeedRow("EF:01", "rsms", "inter", 200L),
        )))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds?platform=android")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"fingerprint\":\"AB:CD\""))
        assertTrue(body.contains("\"observedAt\":100"))
        assertTrue(body.contains("\"observedAt\":200"))
        // Repository call inspection
        assertNull(repo.lastSinceMs)
        assertNull(repo.lastCursor)
    }

    @Test
    fun `since and cursor are forwarded to the repository correctly`() = testApplication {
        val cursor = SigningFingerprintRepository.PageCursor(
            observedAt = 999L,
            fingerprint = "FP",
            owner = "o",
            repo = "r",
        )
        val repo = FakeRepo(pages = listOf(emptyList()))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get(
            "/v1/signing-seeds?platform=android&since=1714521600000&cursor=${cursor.encode()}",
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1714521600000L, repo.lastSinceMs)
        assertEquals(cursor, repo.lastCursor)
    }

    @Test
    fun `limit query parameter is clamped to a sane range`() = testApplication {
        val repo = FakeRepo(pages = listOf(emptyList()))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        client.get("/v1/signing-seeds?platform=android&limit=99999")
        assert(repo.lastLimit <= 5000) { "limit not clamped: ${repo.lastLimit}" }

        client.get("/v1/signing-seeds?platform=android&limit=0")
        assert(repo.lastLimit >= 1) { "limit not clamped to >=1: ${repo.lastLimit}" }
    }

    @Test
    fun `response carries long-lived Cache-Control and ETag headers`() = testApplication {
        val repo = FakeRepo(pages = listOf(listOf(
            SigningSeedRow("AB:CD", "octocat", "hello-world", 100L),
        )))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds?platform=android")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "public, max-age=86400, s-maxage=604800, stale-while-revalidate=86400",
            response.headers[HttpHeaders.CacheControl],
        )
        val etag = response.headers[HttpHeaders.ETag]
        assertNotNull(etag, "ETag header missing")
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""), "ETag must be a quoted string: $etag")
    }

    @Test
    fun `matching If-None-Match returns 304 Not Modified`() = testApplication {
        val repo = StaticFakeRepo(listOf(
            SigningSeedRow("AB:CD", "octocat", "hello-world", 100L),
        ))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val first = client.get("/v1/signing-seeds?platform=android")
        val etag = first.headers[HttpHeaders.ETag]
        assertNotNull(etag)

        val second = client.get("/v1/signing-seeds?platform=android") {
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
        assertEquals(etag, second.headers[HttpHeaders.ETag])
        assertEquals(
            "public, max-age=86400, s-maxage=604800, stale-while-revalidate=86400",
            second.headers[HttpHeaders.CacheControl],
        )
    }

    @Test
    fun `wildcard If-None-Match returns 304`() = testApplication {
        val repo = FakeRepo(pages = listOf(listOf(
            SigningSeedRow("AB:CD", "octocat", "hello-world", 100L),
        )))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds?platform=android") {
            header(HttpHeaders.IfNoneMatch, "*")
        }
        assertEquals(HttpStatusCode.NotModified, response.status)
    }

    @Test
    fun `weak-prefix If-None-Match still matches our strong tag`() = testApplication {
        val repo = StaticFakeRepo(listOf(
            SigningSeedRow("AB:CD", "octocat", "hello-world", 100L),
        ))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val first = client.get("/v1/signing-seeds?platform=android")
        val etag = first.headers[HttpHeaders.ETag]
        assertNotNull(etag)

        val second = client.get("/v1/signing-seeds?platform=android") {
            header(HttpHeaders.IfNoneMatch, "W/$etag")
        }
        assertEquals(HttpStatusCode.NotModified, second.status)
    }

    @Test
    fun `different page contents produce different ETags`() = testApplication {
        val repo = FakeRepo(pages = listOf(
            listOf(SigningSeedRow("AB:CD", "octocat", "hello-world", 100L)),
            listOf(SigningSeedRow("EF:01", "rsms", "inter", 200L)),
        ))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val first = client.get("/v1/signing-seeds?platform=android")
        val second = client.get("/v1/signing-seeds?platform=android")
        val firstTag = first.headers[HttpHeaders.ETag]
        val secondTag = second.headers[HttpHeaders.ETag]
        assertNotNull(firstTag)
        assertNotNull(secondTag)
        assert(firstTag != secondTag) { "ETag should change when rows change: $firstTag" }
    }

    @Test
    fun `non-matching If-None-Match still returns 200 with body`() = testApplication {
        val repo = FakeRepo(pages = listOf(listOf(
            SigningSeedRow("AB:CD", "octocat", "hello-world", 100L),
        )))
        installPlugins()
        application { routing { route("/v1") { signingSeedsRoutes(repo) } } }

        val response = client.get("/v1/signing-seeds?platform=android") {
            header(HttpHeaders.IfNoneMatch, "\"deadbeefdeadbeef\"")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"fingerprint\":\"AB:CD\""))
    }
}
