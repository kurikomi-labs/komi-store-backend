package zed.rainxch.githubstore.db

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class MeilisearchClient(
    private val url: String = System.getenv("MEILI_URL") ?: "http://localhost:7700",
    private val apiKey: String = System.getenv("MEILI_MASTER_KEY") ?: "devkey",
) {
    // Lazy so the CIO engine + non-daemon selector threads only spawn on
    // first request. Tests that construct MeilisearchClient just to satisfy
    // a downstream service's constructor (without ever calling a method on
    // it) avoid the engine entirely, so the JVM exits cleanly at test end
    // instead of hanging on selector threads until the gradle test timeout.
    private val client: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                })
            }
        }
    }

    private val indexName = "repos"

    suspend fun search(
        query: String,
        platform: String? = null,
        hasInstallers: Boolean = true,
        sort: String = "relevance",
        limit: Int = 20,
        offset: Int = 0,
    ): MeiliSearchResult {
        val filter = buildList {
            if (platform != null && hasInstallers) {
                add("has_installers_$platform = true")
            }
        }

        // `recent` is the legacy name; `releases` is the new public name --
        // both alias the same release-date sort. `updated` is the new
        // GitHub-parity sort by repo-level updated_at_gh.
        val sortList = when (sort) {
            "stars" -> listOf("stars:desc")
            "recent", "releases" -> listOf("latest_release_date:desc")
            "updated" -> listOf("updated_at_gh:desc")
            else -> emptyList() // relevance = Meilisearch default ranking
        }

        val response = client.post("$url/indexes/$indexName/search") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(MeiliSearchRequest(
                q = query,
                filter = filter,
                sort = sortList,
                limit = limit,
                offset = offset,
            ))
        }

        return response.body<MeiliSearchResult>()
    }

    suspend fun addDocuments(docs: List<MeiliRepoHit>) {
        client.post("$url/indexes/$indexName/documents") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(docs)
        }
    }

    // Partial update by primary key — PUT /documents is Meili's add-or-update
    // (unspecified fields PRESERVED). POST is add-or-REPLACE (unspecified fields
    // deleted). Used by SignalAggregationWorker to refresh search_score
    // without wiping every other field on the document.
    suspend fun updateScores(updates: List<MeiliScoreUpdate>) {
        if (updates.isEmpty()) return
        client.put("$url/indexes/$indexName/documents") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(updates)
        }
    }

    // Hard-delete a single document by its primary key (the GitHub repo id).
    // DELETE /indexes/{index}/documents/{id} enqueues an async task and returns
    // 202 — the doc is gone once Meili processes it (eventually consistent, same
    // model as add/update). Used by the admin repo-delete endpoint to purge a
    // stale row's search doc after its Postgres row is removed; without it a
    // deleted/recreated repo's old id lingers in search and 404s on lookup.
    suspend fun deleteDocument(id: Long) {
        client.delete("$url/indexes/$indexName/documents/$id") {
            header("Authorization", "Bearer $apiKey")
        }
    }

    suspend fun isHealthy(): Boolean = try {
        val response = client.get("$url/health") {
            header("Authorization", "Bearer $apiKey")
        }
        response.status == HttpStatusCode.OK
    } catch (_: Exception) {
        false
    }
}

@Serializable
data class MeiliSearchRequest(
    val q: String,
    val filter: List<String> = emptyList(),
    val sort: List<String> = emptyList(),
    val limit: Int = 20,
    val offset: Int = 0,
)

@Serializable
data class MeiliSearchResult(
    val hits: List<MeiliRepoHit> = emptyList(),
    val estimatedTotalHits: Int = 0,
    val offset: Int = 0,
    val limit: Int = 20,
    val processingTimeMs: Int = 0,
)

@Serializable
data class MeiliScoreUpdate(
    val id: Long,
    val search_score: Double,
)

@Serializable
data class MeiliRepoHit(
    val id: Long = 0,
    val full_name: String = "",
    val owner: String = "",
    val name: String = "",
    val owner_avatar_url: String? = null,
    val description: String? = null,
    val default_branch: String? = null,
    val html_url: String = "",
    val stars: Int = 0,
    val forks: Int = 0,
    val open_issues: Int = 0,
    val license_spdx_id: String? = null,
    val license_name: String? = null,
    val language: String? = null,
    val latest_release_date: String? = null,
    val latest_release_tag: String? = null,
    val topics: List<String> = emptyList(),
    val download_count: Long = 0,
    val has_installers_android: Boolean = false,
    val has_installers_windows: Boolean = false,
    val has_installers_macos: Boolean = false,
    val has_installers_linux: Boolean = false,
    val trending_score: Double? = null,
    val popularity_score: Double? = null,
    // R5/R13: last commit timestamp; piped from GitHub pushed_at.
    val pushed_at: String? = null,
    // Must be populated on every addDocuments() call — Meili's POST /documents
    // *replaces* the doc, so omitting this field wipes the SignalAggregationWorker's
    // most recent score. Null here is "no signal yet," not "no longer ranked."
    val search_score: Double? = null,
)
