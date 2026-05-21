package zed.rainxch.githubstore.topics

/**
 * Maps raw GitHub topic strings to canonical topic codes.
 *
 * 15 codes, chosen from frequency analysis of 11k+ repos in our index
 * plus F-Droid category taxonomy. Excludes programming languages, OS tags,
 * and build tooling — only app-category concepts.
 *
 * Call [resolve] with a repo's raw topics list; returns all matching
 * canonical codes in priority order (most distinctive first). The frontend
 * renders up to 3 as TopicGlyph icons.
 */
object TopicCodeMapper {

    /**
     * Returns canonical topic codes that match the given raw GitHub topics.
     * Order is deterministic (priority order defined below), duplicates removed.
     */
    fun resolve(topics: List<String>): List<String> {
        if (topics.isEmpty()) return emptyList()
        val lower = topics.mapTo(mutableSetOf()) { it.lowercase() }
        return PRIORITY_ORDER.filter { code ->
            MAPPINGS.getValue(code).any { it in lower }
        }
    }

    // ── Canonical codes → raw GitHub topic aliases ─────────────────────────

    private val MAPPINGS: Map<String, Set<String>> = mapOf(

        // User intent: protect identity / stop tracking — broader principle
        "privacy" to setOf(
            "privacy", "privacy-tools", "privacy-focused", "anonymity",
            "no-telemetry", "tracking-protection", "degoogle", "anti-tracking",
            "tracker-blocker", "ungoogled", "de-google",
        ),

        // User intent: harden secrets / authenticate — specific mechanism
        "security" to setOf(
            "security", "encryption", "2fa", "totp", "otp", "pgp", "gpg",
            "e2ee", "end-to-end-encryption", "password-manager", "authenticator",
            "cryptography", "cipher", "keystore", "biometric",
        ),

        // User intent: route / tunnel / block traffic at the network layer
        "networking" to setOf(
            "vpn", "proxy", "shadowsocks", "v2ray", "xray", "vless", "vmess",
            "trojan", "sing-box", "clash", "hysteria", "wireguard",
            "dns", "ad-blocker", "adblock", "adblocker", "firewall",
            "p2p", "torrent", "downloader", "download-manager", "network",
            "ssh", "socks5", "http-proxy", "tor",
        ),

        // User intent: interact with AI models / agents
        "ai" to setOf(
            "ai", "artificial-intelligence", "chatgpt", "llm", "large-language-model",
            "mcp", "agent", "ai-agent", "gemini", "deepseek", "openai",
            "ollama", "claude", "copilot", "gpt", "local-llm", "on-device-ai",
        ),

        // User intent: capture and organise ideas / tasks
        "notes" to setOf(
            "note-taking", "notes-app", "notes", "note", "note-app",
            "markdown", "knowledge-base", "pkm", "second-brain", "zettelkasten",
            "todo", "task-manager", "tasks", "to-do", "journal", "diary",
            "writing", "text-editor", "notetaking", "productivity",
            "local-first", "offline-first",
        ),

        // User intent: listen to music / podcasts / radio
        "audio" to setOf(
            "music-player", "music", "podcast", "podcasts", "radio",
            "audio", "audio-player", "mpd", "scrobbler",
        ),

        // User intent: watch video / streams
        "video" to setOf(
            "video-player", "video", "streaming", "youtube", "iptv",
            "media-player", "danmaku", "online-video", "video-streaming",
        ),

        // User intent: manage or view images / camera
        "photo" to setOf(
            "photo", "photos", "gallery", "camera", "image-viewer",
            "google-photos-alternative", "image-gallery", "photo-gallery",
            "screenshots",
        ),

        // User intent: read long-form content offline
        "reader" to setOf(
            "ebook", "e-reader", "epub", "pdf", "djvu", "cbz", "cbr",
            "book", "manga", "comic", "comics", "rss", "rss-reader",
            "feed-reader", "reading",
        ),

        // User intent: send messages / calls to other people
        "messaging" to setOf(
            "messaging", "chat", "instant-messaging", "im",
            "matrix", "xmpp", "email", "mail", "voip", "sip",
            "sms", "telegram", "signal", "irc", "discord-alternative",
        ),

        // User intent: browse the web
        "browser" to setOf(
            "browser", "web-browser", "firefox-fork",
        ),

        // User intent: run services on own hardware
        "self-hosted" to setOf(
            "self-hosted", "self-hosting", "homeserver", "home-server",
            "self-host",
        ),

        // User intent: back up or sync files across devices
        "backup" to setOf(
            "backup", "sync", "synchronization", "file-sync",
            "cloud-sync", "webdav", "nextcloud", "syncthing",
        ),

        // User intent: interact with social / fediverse networks
        "social" to setOf(
            "social-network", "mastodon", "fediverse", "activitypub",
            "bluesky", "twitter-alternative", "pleroma", "misskey",
            "nostr", "lemmy", "pixelfed",
        ),

        // User intent: customise Android home screen / input
        "launcher" to setOf(
            "launcher", "android-launcher", "home-screen",
        ),
    )

    // Priority order: most distinctive for our FOSS audience first.
    // A repo matching multiple codes shows the highest-priority ones.
    private val PRIORITY_ORDER = listOf(
        "ai",
        "privacy",
        "security",
        "networking",
        "messaging",
        "browser",
        "social",
        "launcher",
        "notes",
        "reader",
        "audio",
        "video",
        "photo",
        "backup",
        "self-hosted",
    )
}
