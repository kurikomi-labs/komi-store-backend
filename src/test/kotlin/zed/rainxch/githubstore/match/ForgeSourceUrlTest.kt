package zed.rainxch.githubstore.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForgeSourceUrlTest {

    private val trusted = setOf("codeberg.org", "gitea.com", "git.disroot.org")

    @Test
    fun `parses simple codeberg url`() {
        val m = ForgeSourceUrl.parse("https://codeberg.org/Freeyourgadget/Gadgetbridge", trusted)
        assertEquals(ForgeSourceUrl.Match("codeberg.org", "Freeyourgadget", "Gadgetbridge"), m)
    }

    @Test
    fun `parses trailing-slash + dot-git suffix`() {
        assertEquals(
            ForgeSourceUrl.Match("codeberg.org", "alice", "tool"),
            ForgeSourceUrl.parse("https://codeberg.org/alice/tool/", trusted),
        )
        assertEquals(
            ForgeSourceUrl.Match("codeberg.org", "alice", "tool"),
            ForgeSourceUrl.parse("https://codeberg.org/alice/tool.git", trusted),
        )
    }

    @Test
    fun `parses tree path subpath`() {
        assertEquals(
            ForgeSourceUrl.Match("codeberg.org", "alice", "tool"),
            ForgeSourceUrl.parse("https://codeberg.org/alice/tool/src/branch/main/README", trusted),
        )
    }

    @Test
    fun `case-insensitive host match`() {
        assertEquals(
            ForgeSourceUrl.Match("codeberg.org", "alice", "tool"),
            ForgeSourceUrl.parse("https://CODEBERG.ORG/alice/tool", trusted),
        )
    }

    @Test
    fun `rejects untrusted host`() {
        assertNull(ForgeSourceUrl.parse("https://example.evil/alice/tool", trusted))
        // github.com NOT in the forge trusted-host set — GitHub goes through
        // the dedicated FdroidSeedWorker, not the forge path.
        assertNull(ForgeSourceUrl.parse("https://github.com/alice/tool", trusted))
    }

    @Test
    fun `rejects urls with user info or explicit port`() {
        // SSRF-shape hardening — F-Droid index never produces these.
        assertNull(ForgeSourceUrl.parse("https://user@codeberg.org/alice/tool", trusted))
        assertNull(ForgeSourceUrl.parse("https://codeberg.org:8443/alice/tool", trusted))
    }

    @Test
    fun `rejects non-http schemes`() {
        assertNull(ForgeSourceUrl.parse("git@codeberg.org:alice/tool.git", trusted))
        assertNull(ForgeSourceUrl.parse("file:///etc/passwd", trusted))
        assertNull(ForgeSourceUrl.parse("ftp://codeberg.org/alice/tool", trusted))
    }

    @Test
    fun `rejects urls with too few path segments`() {
        // Owner-only (user page, no repo) is not a valid sourceCode URL.
        assertNull(ForgeSourceUrl.parse("https://codeberg.org/alice", trusted))
        assertNull(ForgeSourceUrl.parse("https://codeberg.org/", trusted))
    }

    @Test
    fun `rejects blank or malformed url`() {
        assertNull(ForgeSourceUrl.parse("", trusted))
        assertNull(ForgeSourceUrl.parse("   ", trusted))
        assertNull(ForgeSourceUrl.parse("not-a-url", trusted))
    }
}
