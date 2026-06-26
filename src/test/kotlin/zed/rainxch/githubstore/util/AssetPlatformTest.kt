package zed.rainxch.githubstore.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssetPlatformTest {

    @Test
    fun alpine_nfpm_apks_are_not_android() {
        assertFalse(AssetPlatform.isAndroidApk("task_3.51.1_linux_arm64.apk"))
        assertFalse(AssetPlatform.isAndroidApk("task_3.51.1_linux_386.apk"))
        assertFalse(AssetPlatform.isAndroidApk("task_3.51.1_linux_amd64.apk"))
        assertFalse(AssetPlatform.isAndroidApk("k8sgpt_amd64.apk"))
        assertFalse(AssetPlatform.isAndroidApk("spicedb_1.54.0_linux_arm64.apk"))
        assertFalse(AssetPlatform.isAndroidApk("kubefwd_linux_amd64.apk"))
    }

    @Test
    fun genuine_android_apks_are_android() {
        assertTrue(AssetPlatform.isAndroidApk("app-arm64-v8a-release.apk"))
        assertTrue(AssetPlatform.isAndroidApk("app-x86_64-release.apk"))
        assertTrue(AssetPlatform.isAndroidApk("v2rayNG_2.2.5_universal.apk"))
        assertTrue(AssetPlatform.isAndroidApk("v2rayNG_2.2.5_x86_64.apk"))
        assertTrue(AssetPlatform.isAndroidApk("v2rayNG_2.2.5_arm64-v8a.apk"))
        assertTrue(AssetPlatform.isAndroidApk("Magisk-v30.7.apk"))
        assertTrue(AssetPlatform.isAndroidApk("app-release.apk"))
    }

    @Test
    fun non_apk_is_not_android_apk() {
        assertFalse(AssetPlatform.isAndroidApk("tool_linux_amd64.deb"))
        assertFalse(AssetPlatform.isAndroidApk("tool.AppImage"))
        assertFalse(AssetPlatform.isAndroidApk("app.exe"))
    }

    @Test
    fun installable_extension_sets_match_client_contract() {
        assertTrue(AssetPlatform.isWindowsInstaller("Setup.exe"))
        assertTrue(AssetPlatform.isWindowsInstaller("app.msi"))
        assertFalse(AssetPlatform.isWindowsInstaller("app.msix"))
        assertFalse(AssetPlatform.isWindowsInstaller("app_windows_amd64.zip"))

        assertTrue(AssetPlatform.isMacosInstaller("App.dmg"))
        assertTrue(AssetPlatform.isMacosInstaller("App.pkg"))
        assertFalse(AssetPlatform.isMacosInstaller("task_darwin_arm64.tar.gz"))

        assertTrue(AssetPlatform.isLinuxInstaller("app.AppImage"))
        assertTrue(AssetPlatform.isLinuxInstaller("app_amd64.deb"))
        assertTrue(AssetPlatform.isLinuxInstaller("app.x86_64.rpm"))
        assertTrue(AssetPlatform.isLinuxInstaller("app-x86_64.pkg.tar.zst"))
        assertFalse(AssetPlatform.isLinuxInstaller("app.flatpak"))
        assertFalse(AssetPlatform.isLinuxInstaller("app.snap"))
    }

    @Test
    fun pkg_tar_zst_routes_to_linux_not_macos() {
        assertFalse(AssetPlatform.isMacosInstaller("app-x86_64.pkg.tar.zst"))
        assertTrue(AssetPlatform.isLinuxInstaller("app-x86_64.pkg.tar.zst"))
    }

    @Test
    fun go_task_alpine_repo_is_linux_not_android() {
        val flags = AssetPlatform.installFlags(
            listOf(
                "task_3.51.1_linux_386.apk",
                "task_3.51.1_linux_amd64.apk",
                "task_3.51.1_linux_arm.apk",
                "task_3.51.1_linux_arm64.apk",
                "task_3.51.1_linux_riscv64.apk",
                "task_3.51.1_linux_amd64.deb",
                "task_3.51.1_linux_amd64.rpm",
                "task_darwin_arm64.tar.gz",
                "task_windows_amd64.zip",
            ),
        )
        assertEquals(false, flags["android"])
        assertEquals(true, flags["linux"])
        assertEquals(false, flags["windows"])
        assertEquals(false, flags["macos"])
    }

    @Test
    fun immich_real_android_repo_stays_android() {
        val flags = AssetPlatform.installFlags(
            listOf(
                "app-arm64-v8a-release.apk",
                "app-x86_64-release.apk",
                "app-release.apk",
            ),
        )
        assertEquals(true, flags["android"])
        assertEquals(false, flags["linux"])
        assertEquals(false, flags["windows"])
        assertEquals(false, flags["macos"])
    }

    @Test
    fun alpine_apk_only_repo_yields_no_installers() {
        // An nfpm Alpine `.apk` is not a client-installable Linux asset (the
        // client's Linux set is .appimage/.deb/.rpm/.pkg.tar.zst). A repo that
        // ships ONLY Alpine `.apk`s + archives is installable on nothing — it
        // must NOT be advertised as Android (the bug) nor as Linux.
        val flags = AssetPlatform.installFlags(
            listOf(
                "task_3.51.1_linux_386.apk",
                "task_3.51.1_linux_amd64.apk",
                "task_3.51.1_linux_arm64.apk",
                "task_darwin_arm64.tar.gz",
                "task_windows_amd64.zip",
            ),
        )
        assertEquals(false, flags["android"])
        assertEquals(false, flags["linux"])
        assertEquals(false, flags["windows"])
        assertEquals(false, flags["macos"])
    }

    @Test
    fun archives_alone_yield_no_installers() {
        val flags = AssetPlatform.installFlags(
            listOf("app.zip", "app.tar.gz", "app.gz", "app.tgz"),
        )
        assertEquals(false, flags["android"])
        assertEquals(false, flags["windows"])
        assertEquals(false, flags["macos"])
        assertEquals(false, flags["linux"])
    }
}
