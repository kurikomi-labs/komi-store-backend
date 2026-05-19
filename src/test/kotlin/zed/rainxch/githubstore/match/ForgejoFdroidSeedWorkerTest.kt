package zed.rainxch.githubstore.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForgejoFdroidSeedWorkerTest {

    @Test
    fun `parseIndexUrlsEnv splits + trims + filters non-http`() {
        val parsed = ForgejoFdroidSeedWorker.parseIndexUrlsEnv(
            "https://a.example/index-v2.json , https://b.example/index-v2.json,ftp://c.example, ,",
        )
        assertEquals(
            listOf("https://a.example/index-v2.json", "https://b.example/index-v2.json"),
            parsed,
        )
    }

    @Test
    fun `parseIndexUrlsEnv falls back to default on null or all-empty`() {
        assertEquals(ForgejoFdroidSeedWorker.DEFAULT_INDEX_URLS, ForgejoFdroidSeedWorker.parseIndexUrlsEnv(null))
        assertEquals(ForgejoFdroidSeedWorker.DEFAULT_INDEX_URLS, ForgejoFdroidSeedWorker.parseIndexUrlsEnv("   "))
        assertEquals(ForgejoFdroidSeedWorker.DEFAULT_INDEX_URLS, ForgejoFdroidSeedWorker.parseIndexUrlsEnv(",,,"))
        // All non-http entries → fall back to default (otherwise we'd ship
        // an empty crawler, which would silently no-op forever).
        assertEquals(ForgejoFdroidSeedWorker.DEFAULT_INDEX_URLS, ForgejoFdroidSeedWorker.parseIndexUrlsEnv("ftp://bad,git@host"))
    }

    @Test
    fun `default index URL list is non-empty and uses https`() {
        // Pin the contract the AppModule relies on — the worker starts a
        // crawler loop iff indexUrls is non-empty; an empty default would
        // silently disable the whole feature on a fresh deploy.
        assertTrue(ForgejoFdroidSeedWorker.DEFAULT_INDEX_URLS.isNotEmpty())
        ForgejoFdroidSeedWorker.DEFAULT_INDEX_URLS.forEach {
            assertTrue(it.startsWith("https://"), "default index URL must be https: $it")
        }
    }
}
