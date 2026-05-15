package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import zed.rainxch.githubstore.mirrors.MirrorEntry
import zed.rainxch.githubstore.mirrors.MirrorListResponse
import zed.rainxch.githubstore.mirrors.MirrorPresets
import zed.rainxch.githubstore.mirrors.MirrorStatus
import zed.rainxch.githubstore.mirrors.MirrorStatusRegistry
import java.time.Instant

// GET /v1/mirrors/list -- catalog of GitHub release-asset mirrors with
// runtime health. Always returns 200 + the full preset list; status fields
// reflect the latest probe from MirrorStatusWorker.
//
// Cache headers match the worker cycle: edge caches for 1h (s-maxage),
// browsers for 5min (max-age). Cloudflare's "respect origin TTL" rule we
// added earlier honors this without a per-route Page Rule.
fun Route.mirrorRoutes(registry: MirrorStatusRegistry) {
    get("/mirrors/list") {
        val now = Instant.now()
        val entries = MirrorPresets.ALL.map { preset ->
            val snap = registry.snapshot(preset.id)
            MirrorEntry(
                id = preset.id,
                name = preset.name,
                urlTemplate = preset.urlTemplate,
                type = preset.type,
                status = snap.status,
                // Hide latency for DOWN/UNKNOWN to keep the picker UI honest --
                // a stale latency from before the mirror went down is misleading.
                latencyMs = if (snap.status == MirrorStatus.OK || snap.status == MirrorStatus.DEGRADED) snap.latencyMs else null,
                lastCheckedAt = snap.lastCheckedAt?.toString(),
                trafficKinds = preset.trafficKinds,
            )
        }

        call.response.header(
            HttpHeaders.CacheControl,
            "public, max-age=300, s-maxage=3600",
        )
        call.respond(
            MirrorListResponse(
                mirrors = entries,
                generatedAt = now.toString(),
            ),
        )
    }
}
