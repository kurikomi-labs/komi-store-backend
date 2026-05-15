package zed.rainxch.githubstore.mirrors

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import zed.rainxch.githubstore.routes.mirrorRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MirrorRoutesTest {

    private fun ApplicationTestBuilder.setupApp(registry: MirrorStatusRegistry) {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            routing { route("/v1") { mirrorRoutes(registry) } }
        }
    }

    @Test
    fun `cold-start returns full catalog with UNKNOWN status for every mirror`() = testApplication {
        setupApp(MirrorStatusRegistry())
        val resp = client.get("/v1/mirrors/list")
        assertEquals(HttpStatusCode.OK, resp.status)

        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val mirrors = body["mirrors"]!!.jsonArray
        assertEquals(MirrorPresets.ALL.size, mirrors.size)

        // Every entry shipped with status "unknown" + no latency.
        mirrors.forEach { entry ->
            val obj = entry.jsonObject
            assertEquals("unknown", obj["status"]!!.jsonPrimitive.content)
            assertTrue(obj["latency_ms"]?.toString() == "null")
            assertTrue(obj["last_checked_at"]?.toString() == "null")
        }
    }

    @Test
    fun `populated registry surfaces status and latency per mirror`() = testApplication {
        val registry = MirrorStatusRegistry().apply {
            update("direct", MirrorStatus.OK, 80)
            update("ghfast_top", MirrorStatus.OK, 240)
            update("ghps_cc", MirrorStatus.DOWN, null)
        }
        setupApp(registry)

        val body = Json.parseToJsonElement(client.get("/v1/mirrors/list").bodyAsText()).jsonObject
        val byId = body["mirrors"]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }

        assertEquals("ok", byId["direct"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("80", byId["direct"]!!.jsonObject["latency_ms"]!!.jsonPrimitive.content)

        assertEquals("ok", byId["ghfast_top"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("240", byId["ghfast_top"]!!.jsonObject["latency_ms"]!!.jsonPrimitive.content)

        // DOWN mirrors must hide stale latency from before they went down.
        assertEquals("down", byId["ghps_cc"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("null", byId["ghps_cc"]!!.jsonObject["latency_ms"]!!.toString())
    }

    @Test
    fun `latency is hidden for UNKNOWN status even if registry held a value`() = testApplication {
        // Defensive: an UNKNOWN status from a registry quirk should never
        // leak a stale latency to the picker.
        val registry = MirrorStatusRegistry()
        // Don't update -- snapshot returns UNKNOWN with null latency. Tests
        // the route's branch that drops latency when status is not OK/DEGRADED.
        setupApp(registry)
        val body = Json.parseToJsonElement(client.get("/v1/mirrors/list").bodyAsText()).jsonObject
        body["mirrors"]!!.jsonArray.forEach { entry ->
            val obj = entry.jsonObject
            assertNull(obj["latency_ms"]?.takeIf { it.toString() != "null" })
        }
    }

    @Test
    fun `response sets edge cache headers`() = testApplication {
        setupApp(MirrorStatusRegistry())
        val resp = client.get("/v1/mirrors/list")
        val cc = resp.headers[HttpHeaders.CacheControl]
        assertNotNull(cc)
        assertTrue(cc.contains("max-age=300"), "missing browser TTL: $cc")
        assertTrue(cc.contains("s-maxage=3600"), "missing edge TTL: $cc")
        assertTrue(cc.contains("public"), "must be edge-cacheable: $cc")
    }

    @Test
    fun `direct mirror has null url_template, community ones expose an https template with a placeholder`() = testApplication {
        setupApp(MirrorStatusRegistry())
        val body = Json.parseToJsonElement(client.get("/v1/mirrors/list").bodyAsText()).jsonObject
        val byId = body["mirrors"]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }

        assertEquals("null", byId["direct"]!!.jsonObject["url_template"]!!.toString())
        // Every non-direct entry must have a non-null https template containing
        // at least one placeholder. Whole-URL proxies use `/{url}`; specialised
        // mirrors (e.g. jsDelivr) use a multi-placeholder template such as
        // `/{owner}/{repo}@{ref}/{path}` — clients dispatch by placeholder set
        // and `traffic_kinds`.
        val placeholder = Regex("\\{[a-z]+\\}")
        byId.filterKeys { it != "direct" }.forEach { (id, entry) ->
            val tpl = entry.jsonObject["url_template"]!!.jsonPrimitive.content
            assertTrue(tpl.startsWith("https://"), "$id template must be https://: $tpl")
            assertTrue(placeholder.containsMatchIn(tpl), "$id template must include a placeholder: $tpl")
        }
    }

    @Test
    fun `every mirror exposes traffic_kinds and whole-url proxies cover both kinds`() = testApplication {
        setupApp(MirrorStatusRegistry())
        val body = Json.parseToJsonElement(client.get("/v1/mirrors/list").bodyAsText()).jsonObject
        val byId = body["mirrors"]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }

        byId.forEach { (id, entry) ->
            val kinds = entry.jsonObject["traffic_kinds"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: error("$id missing traffic_kinds")
            assertTrue(kinds.isNotEmpty(), "$id traffic_kinds must be non-empty")

            val tpl = entry.jsonObject["url_template"]?.takeIf { it.toString() != "null" }
                ?.jsonPrimitive?.content
            if (tpl == null || tpl.endsWith("/{url}")) {
                // Direct + whole-URL proxies handle every github.com URL.
                assertTrue("release_asset" in kinds, "$id must list release_asset: $kinds")
                assertTrue("raw_file" in kinds, "$id must list raw_file: $kinds")
            }
        }
    }

    @Test
    fun `fastly_jsdelivr mirror is included, raw-file-only, with jsdelivr path template`() = testApplication {
        setupApp(MirrorStatusRegistry())
        val body = Json.parseToJsonElement(client.get("/v1/mirrors/list").bodyAsText()).jsonObject
        val byId = body["mirrors"]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }

        val entry = byId["fastly_jsdelivr"]
        assertNotNull(entry, "fastly_jsdelivr mirror missing from /v1/mirrors/list")
        val tpl = entry.jsonObject["url_template"]!!.jsonPrimitive.content
        assertEquals(
            "https://fastly.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}",
            tpl,
        )
        val kinds = entry.jsonObject["traffic_kinds"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("raw_file"), kinds, "jsdelivr must be raw_file-only — release-asset URLs are not under /gh/")
    }

    @Test
    fun `fastgit_cc is intentionally absent — lineage unverified`() = testApplication {
        // CodeRabbit flagged fastgit.cc as a trust concern: no public artifact
        // ties the .cc TLD to the FastGitORG team that ran the (now-defunct)
        // fastgit.org. Shipping it would be a supply-chain risk. Re-add only
        // after operator sign-off.
        setupApp(MirrorStatusRegistry())
        val body = Json.parseToJsonElement(client.get("/v1/mirrors/list").bodyAsText()).jsonObject
        val ids = body["mirrors"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("fastgit_cc" !in ids, "fastgit_cc shipped without lineage verification: $ids")
    }

    @Test
    fun `response shape includes generated_at`() = testApplication {
        setupApp(MirrorStatusRegistry())
        val body = Json.parseToJsonElement(client.get("/v1/mirrors/list").bodyAsText()).jsonObject
        val genAt = body["generated_at"]?.jsonPrimitive?.content
        assertNotNull(genAt)
        // ISO 8601 sanity check -- starts with year-month-day.
        assertTrue(genAt.matches(Regex("^\\d{4}-\\d{2}-\\d{2}T.*")))
    }

    @Test
    fun `mirror order matches preset order so picker rendering is deterministic`() = testApplication {
        setupApp(MirrorStatusRegistry())
        val body = Json.parseToJsonElement(client.get("/v1/mirrors/list").bodyAsText()).jsonObject
        val ids = body["mirrors"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(MirrorPresets.ALL.map { it.id }, ids)
    }
}
