package zed.rainxch.githubstore.util

import zed.rainxch.githubstore.db.SearchMissRepository
import java.security.MessageDigest

object FeatureFlags {
    val disableLiveGitHubPassthrough: Boolean
        get() = System.getenv("DISABLE_LIVE_GITHUB_PASSTHROUGH")?.equals("true", ignoreCase = true) == true

    val disableBadgeFetch: Boolean
        get() = disableLiveGitHubPassthrough ||
            (System.getenv("DISABLE_BADGE_FETCH")?.equals("true", ignoreCase = true) == true)

    // Feed-v2 momentum + cooldown ranking. Default ON; set FEED_V2_RANKING=false
    // to fall back to the legacy capped-pool assembler (instant rollback, no
    // redeploy). Velocity strengthens as repo_daily_snapshot history accrues —
    // until then the scorer ranks on the popularity+recency base with cooldown
    // rotation, already a strict improvement over the memoryless pools.
    val feedV2Ranking: Boolean
        get() = System.getenv("FEED_V2_RANKING")?.equals("false", ignoreCase = true) != true
}

// Short opaque tag for log lines that previously printed the raw query.
// Reuses SearchMissRepository.canonicalize so the same query produces the
// same tag across the codebase. SHA-256 truncated to 16 hex chars (64 bits)
// — enough to be practically collision-free for our query volume, not
// enough to invert.
fun queryHash(query: String): String {
    val canonical = SearchMissRepository.canonicalize(query)
    val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }.take(16)
}
