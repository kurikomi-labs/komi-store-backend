package zed.rainxch.githubstore.match

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ManifestHint(
    val owner: String? = null,
    val repo: String? = null,
)

@Serializable
data class ExternalMatchCandidateRequest(
    val packageName: String,
    val appLabel: String,
    val signingFingerprint: String? = null,
    val installerKind: String? = null,
    val manifestHint: ManifestHint? = null,
)

@Serializable
data class ExternalMatchRequest(
    val platform: String,
    val candidates: List<ExternalMatchCandidateRequest>,
    // `sources` is additive. Pre-1.9.0 clients omit the field and get the
    // GitHub-only behaviour they shipped with. New clients opt into the
    // forge fan-out by sending e.g. `["github", "codeberg"]`. The set of
    // accepted values is hard-allowlisted in the route handler — backend
    // refuses to fan out to user-supplied arbitrary hosts (SSRF + DoS).
    val sources: List<String> = listOf("github"),
)

@Serializable
data class ExternalMatchCandidate(
    val owner: String,
    val repo: String,
    val confidence: Double,
    // "manifest" | "search" | "fingerprint" | "forgejo_search" — see
    // E1_BACKEND_HANDOFF.md §1 and the forges-integration-brief.md.
    // Stays a flat string so adding a new source is JSON-compatible — old
    // clients exhaustively-match on the known enum + default to "search".
    val source: String,
    val stars: Int? = null,
    val description: String? = null,
    // `null` for GitHub hits (preserved as the canonical default so the
    // existing GitHub flow's wire shape is byte-identical to pre-1.9.0).
    // Forge hits carry the forge's host, e.g. "codeberg.org". The client
    // dispatches subsequent calls (repo detail, releases, raw README)
    // against this host when present.
    @SerialName("source_host") val sourceHost: String? = null,
)

@Serializable
data class ExternalMatchEntry(
    val packageName: String,
    val candidates: List<ExternalMatchCandidate>,
)

@Serializable
data class ExternalMatchResponse(
    val matches: List<ExternalMatchEntry>,
)
