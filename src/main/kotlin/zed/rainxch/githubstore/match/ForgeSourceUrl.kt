package zed.rainxch.githubstore.match

// Parses an F-Droid sourceCode URL into (host, owner, repo) iff the host is
// in the trusted-forge allowlist. Mirrors GithubSourceUrl's contract but
// generalised across Forgejo / Gitea hosts so ForgejoFdroidSeedWorker can
// reuse the same shape-validation logic across multiple source repositories.
//
// Accepted shapes (per trusted host):
//   https://<host>/owner/repo
//   https://<host>/owner/repo/
//   https://<host>/owner/repo.git
//   https://<host>/owner/repo/src/branch/main/path
//   http://<host>/owner/repo
//
// Rejects: hosts not in the allowlist, URLs with a port, paths with fewer
// than two segments (catches user pages with no repo), and the obvious
// SSRF-shaped suspicious values (userinfo, @, IDN punycode prefix).
object ForgeSourceUrl {

    // Owner + repo character class kept aligned with GithubSourceUrl's so a
    // user/owner string that's legal on GitHub is also legal here. Forgejo's
    // own owner-name rules are slightly more permissive than GitHub's, but
    // we'd rather miss a rare exotic name than admit characters that could
    // confuse a downstream URL composer.
    private val PATH_RE = Regex(
        "^/([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))/([A-Za-z0-9._-]{1,100})(?:\\.git)?(?:[/?#].*)?$",
    )

    data class Match(val host: String, val owner: String, val repo: String)

    fun parse(url: String, trustedHosts: Set<String>): Match? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        // Reject obvious URL-shape weirdness early. Userinfo (`user@`) and
        // explicit ports give the upstream library more attack surface than
        // we need — the F-Droid index never uses them.
        if ('@' in trimmed || "://" !in trimmed) return null

        val parsed = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (parsed.port != -1) return null

        val host = parsed.host?.lowercase() ?: return null
        if (host !in trustedHosts) return null

        val path = parsed.rawPath ?: return null
        val match = PATH_RE.matchEntire(path) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null

        return Match(host = host, owner = owner, repo = repo)
    }
}
