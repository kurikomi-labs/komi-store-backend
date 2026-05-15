package zed.rainxch.githubstore.mirrors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MirrorType {
    @SerialName("official") OFFICIAL,
    @SerialName("community") COMMUNITY,
}

@Serializable
enum class MirrorStatus {
    @SerialName("ok") OK,
    @SerialName("degraded") DEGRADED,
    @SerialName("down") DOWN,
    @SerialName("unknown") UNKNOWN,
}

// Ping URL is baked in per preset rather than computed at probe time. Direct
// GitHub pings api.github.com/zen (tiny canary). Community mirrors ping a
// known-stable release-asset checksum file at cli/cli@v2.40.0 -- pinned
// release means the URL won't 404 on us. Range: bytes=0-0 keeps actual
// transfer at 1 byte regardless of whether the mirror honors the Range header.
//
// `trafficKinds` tells the client what kinds of traffic this mirror is fit
// for, since not every mirror covers every kind. Two kinds matter today:
//   - "release_asset": URLs under github.com/.../releases/download/...
//   - "raw_file"     : repo source-tree files (READMEs, icons, raw.githubusercontent
//                      content, anything served by jsDelivr's `/gh/` path)
// Whole-URL proxies serve both. jsDelivr's `/gh/` endpoint serves repo files
// only — sending a release-asset URL there 404s. The client MUST inspect this
// list before routing a download.
data class MirrorPreset(
    val id: String,
    val name: String,
    val urlTemplate: String?,
    val type: MirrorType,
    val pingUrl: String,
    val trafficKinds: List<String>,
)

// Hardcoded catalog. Adding/removing a mirror is a code change + deploy --
// appropriate friction for a vetted addition. Status is per-mirror runtime
// data and lives in MirrorStatusRegistry, not here.
//
// Order matters: clients render the picker in this order. Direct first
// (always works for non-CN users), then ghfast.top + moeyy.xyz (most
// reliable per the April 2026 landscape research), then the long tail.
object MirrorPresets {

    private const val PROBE_ASSET =
        "https://github.com/cli/cli/releases/download/v2.40.0/gh_2.40.0_checksums.txt"

    private val FULL_PROXY_KINDS = listOf("release_asset", "raw_file")

    val ALL: List<MirrorPreset> = listOf(
        MirrorPreset(
            id = "direct",
            name = "Direct GitHub",
            urlTemplate = null,
            type = MirrorType.OFFICIAL,
            pingUrl = "https://api.github.com/zen",
            trafficKinds = FULL_PROXY_KINDS,
        ),
        MirrorPreset(
            id = "ghfast_top",
            name = "ghfast.top",
            urlTemplate = "https://ghfast.top/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://ghfast.top/$PROBE_ASSET",
            trafficKinds = FULL_PROXY_KINDS,
        ),
        MirrorPreset(
            id = "moeyy_xyz",
            name = "github.moeyy.xyz",
            urlTemplate = "https://github.moeyy.xyz/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://github.moeyy.xyz/$PROBE_ASSET",
            trafficKinds = FULL_PROXY_KINDS,
        ),
        MirrorPreset(
            id = "gh_proxy_com",
            name = "gh-proxy.com",
            urlTemplate = "https://gh-proxy.com/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://gh-proxy.com/$PROBE_ASSET",
            trafficKinds = FULL_PROXY_KINDS,
        ),
        MirrorPreset(
            id = "ghps_cc",
            name = "ghps.cc",
            urlTemplate = "https://ghps.cc/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://ghps.cc/$PROBE_ASSET",
            trafficKinds = FULL_PROXY_KINDS,
        ),
        MirrorPreset(
            id = "gh_99988866_xyz",
            name = "gh.api.99988866.xyz",
            urlTemplate = "https://gh.api.99988866.xyz/{url}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://gh.api.99988866.xyz/$PROBE_ASSET",
            trafficKinds = FULL_PROXY_KINDS,
        ),
        // jsDelivr's Fastly-fronted endpoint. jsDelivr publishes per-CDN
        // entrypoints (cdn.jsdelivr.net is multi-CDN, fastly.jsdelivr.net is
        // Fastly-only, gcore.jsdelivr.net is Gcore); the Fastly one is the
        // commonly-used escape hatch when Cloudflare paths are blocked in
        // Mainland China. urlTemplate uses jsDelivr's native `/gh/` path
        // shape — the client dispatches by inspecting placeholders. Marked
        // raw-file-only because jsDelivr does NOT serve release assets
        // (release tarballs aren't under /gh/).
        //
        // Sprint 3 Task #13 originally requested fastgit.cc alongside this,
        // but fastgit.cc could not be verified as a legitimate successor of
        // the (now-defunct) fastgit.org project — no public artifact ties
        // the .cc TLD to the FastGitORG team — so it is intentionally NOT
        // added here. Re-add only with operator sign-off after lineage is
        // confirmed.
        MirrorPreset(
            id = "fastly_jsdelivr",
            name = "fastly.jsdelivr.net",
            urlTemplate = "https://fastly.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}",
            type = MirrorType.COMMUNITY,
            pingUrl = "https://fastly.jsdelivr.net/gh/cli/cli@v2.40.0/LICENSE",
            trafficKinds = listOf("raw_file"),
        ),
    )

    fun byId(id: String): MirrorPreset? = ALL.firstOrNull { it.id == id }
}

@Serializable
data class MirrorListResponse(
    val mirrors: List<MirrorEntry>,
    @SerialName("generated_at") val generatedAt: String,
)

@Serializable
data class MirrorEntry(
    val id: String,
    val name: String,
    @SerialName("url_template") val urlTemplate: String?,
    val type: MirrorType,
    val status: MirrorStatus,
    @SerialName("latency_ms") val latencyMs: Long?,
    @SerialName("last_checked_at") val lastCheckedAt: String?,
    // `traffic_kinds` is additive — pre-1.8.3 clients ignore the unknown field
    // and continue to use whole-URL-proxy mirrors against any github.com URL.
    // 1.8.3+ clients MUST consult this list before routing a download.
    @SerialName("traffic_kinds") val trafficKinds: List<String>,
)
