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
import zed.rainxch.githubstore.routes.externalMatchRoutes
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalMatchRouteTest {

    // Stub service — never hits the network or DB. The route's job is to
    // validate the request, fan out, and assemble the response. Service
    // logic is unit-tested in ExternalMatchScorerTest.
    private class StubService : ExternalMatchService(
        signingFingerprintRepository = SigningFingerprintRepository(),
        cache = zed.rainxch.githubstore.db.ResourceCacheRepository(),
        searchClient = zed.rainxch.githubstore.ingest.GitHubSearchClient(
            zed.rainxch.githubstore.db.MeilisearchClient(),
        ),
    ) {
        var lastSources: List<String>? = null

        override suspend fun matchOne(
            req: ExternalMatchCandidateRequest,
            sources: List<String>,
        ): List<ExternalMatchCandidate> {
            lastSources = sources
            return listOf(
                ExternalMatchCandidate(
                    owner = "test-owner",
                    repo = "test-repo",
                    confidence = 0.7,
                    source = "search",
                    stars = 100,
                    description = "stub",
                    sourceHost = null,
                ),
            )
        }
    }

    private fun ApplicationTestBuilder.installPlugins() {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `non-android platform returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"ios","candidates":[{"packageName":"com.foo","appLabel":"Foo"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `empty candidates returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `over 25 candidates returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val candidates = (1..26).joinToString(",") {
            """{"packageName":"com.foo$it","appLabel":"Foo $it"}"""
        }
        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[$candidates]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid package name returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"has spaces","appLabel":"Foo"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid signing fingerprint returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"com.foo","appLabel":"Foo","signingFingerprint":"too-short"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid installer kind returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"com.foo","appLabel":"Foo","installerKind":"unknown_unknown"}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid manifest hint owner returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"com.foo","appLabel":"Foo","manifestHint":{"owner":"not valid","repo":"foo"}}]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `valid request returns 200 with one entry per candidate`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"platform":"android","candidates":[
                    {"packageName":"com.foo","appLabel":"Foo"},
                    {"packageName":"com.bar","appLabel":"Bar","installerKind":"obtainium"}
                ]}""".trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `request without sources field defaults to github-only`() = testApplication {
        installPlugins()
        val stub = StubService()
        application { routing { route("/v1") { externalMatchRoutes(stub) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody("""{"platform":"android","candidates":[{"packageName":"com.foo","appLabel":"Foo"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("github"), stub.lastSources)
    }

    @Test
    fun `request forwards sources list verbatim to service`() = testApplication {
        installPlugins()
        val stub = StubService()
        application { routing { route("/v1") { externalMatchRoutes(stub) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"platform":"android","sources":["github","codeberg","gitea"],
                    "candidates":[{"packageName":"com.foo","appLabel":"Foo"}]}""".trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("github", "codeberg", "gitea"), stub.lastSources)
    }

    @Test
    fun `empty sources array returns 400`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"platform":"android","sources":[],
                    "candidates":[{"packageName":"com.foo","appLabel":"Foo"}]}""".trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `unknown source name returns 400 invalid_source`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"platform":"android","sources":["github","sourcehut"],
                    "candidates":[{"packageName":"com.foo","appLabel":"Foo"}]}""".trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("invalid_source")) {
            "body should name invalid_source: ${response.bodyAsText()}"
        }
    }

    @Test
    fun `too many sources returns 400 too_many_sources`() = testApplication {
        installPlugins()
        application { routing { route("/v1") { externalMatchRoutes(StubService()) } } }

        val response = client.post("/v1/external-match") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"platform":"android",
                    "sources":["github","codeberg","gitea","disroot","github","codeberg","gitea","disroot","github"],
                    "candidates":[{"packageName":"com.foo","appLabel":"Foo"}]}""".trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assert(response.bodyAsText().contains("too_many_sources")) {
            "body should name too_many_sources: ${response.bodyAsText()}"
        }
    }
}
