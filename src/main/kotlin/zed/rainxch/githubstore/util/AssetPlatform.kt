package zed.rainxch.githubstore.util

// Asset-name → installable-platform classification. MUST stay byte-for-byte
// equivalent to the client's `core.domain.utils.AssetPlatform` + the
// `isAssetInstallable` extension sets in AndroidInstaller / DesktopInstaller.
// The discovery feed carries no asset list, so these flags ARE the client's
// only source of truth for installability — any divergence makes the feed
// advertise something the client then refuses to install.
object AssetPlatform {

    // goreleaser/nfpm emit Alpine Linux packages with a `.apk` extension that
    // is NOT an Android package. An Alpine `.apk` always carries an OS / Go-arch
    // token (`linux`, `amd64`, `386`); a real Android APK never does (its ABIs
    // are arm64-v8a / armeabi-v7a / x86 / x86_64 / universal). This discriminator
    // is what separates the two without downloading the bytes.
    private val alpineApkSignature = Regex("(^|[^a-z0-9])(linux|amd64|386)([^a-z0-9]|$)")

    fun isAndroidApk(assetName: String): Boolean {
        val lower = assetName.lowercase()
        if (!lower.endsWith(".apk")) return false
        return !alpineApkSignature.containsMatchIn(lower)
    }

    fun isWindowsInstaller(assetName: String): Boolean {
        val lower = assetName.lowercase()
        return lower.endsWith(".exe") || lower.endsWith(".msi")
    }

    fun isMacosInstaller(assetName: String): Boolean {
        val lower = assetName.lowercase()
        // `.pkg.tar.zst` ends in `.zst`, so it never matches the bare `.pkg`
        // macOS installer here — it routes to Linux below.
        return lower.endsWith(".dmg") || lower.endsWith(".pkg")
    }

    fun isLinuxInstaller(assetName: String): Boolean {
        val lower = assetName.lowercase()
        return lower.endsWith(".appimage") ||
            lower.endsWith(".deb") ||
            lower.endsWith(".rpm") ||
            lower.endsWith(".pkg.tar.zst")
    }

    fun installFlags(assetNames: List<String>): Map<String, Boolean> = mapOf(
        "android" to assetNames.any { isAndroidApk(it) },
        "windows" to assetNames.any { isWindowsInstaller(it) },
        "macos" to assetNames.any { isMacosInstaller(it) },
        "linux" to assetNames.any { isLinuxInstaller(it) },
    )
}
