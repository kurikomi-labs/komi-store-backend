package zed.rainxch.githubstore

import org.koin.dsl.module
import zed.rainxch.githubstore.announcements.AnnouncementLoader
import zed.rainxch.githubstore.announcements.AnnouncementsRegistry
import zed.rainxch.githubstore.db.EventRepository
import zed.rainxch.githubstore.db.FeedRepository
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.db.ResourceCacheRepository
import zed.rainxch.githubstore.db.SearchMissRepository
import zed.rainxch.githubstore.db.SearchRepository
import zed.rainxch.githubstore.ingest.GitHubDeviceClient
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.ingest.RepoRefreshCoordinator
import zed.rainxch.githubstore.ingest.RepoRefreshWorker
import zed.rainxch.githubstore.ingest.RetentionWorker
import zed.rainxch.githubstore.ingest.SignalAggregationWorker
import zed.rainxch.githubstore.ingest.SnapshotRetentionWorker
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import zed.rainxch.githubstore.feed.FeedService
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry
import zed.rainxch.githubstore.badge.BadgeService
import zed.rainxch.githubstore.badge.FdroidVersionClient
import zed.rainxch.githubstore.match.ExternalMatchService
import zed.rainxch.githubstore.match.ForgejoResourceClient
import zed.rainxch.githubstore.match.ForgejoSearchClient
import zed.rainxch.githubstore.match.FdroidSeedWorker
import zed.rainxch.githubstore.match.ForgejoFdroidSeedWorker
import zed.rainxch.githubstore.match.SigningFingerprintRepository
import zed.rainxch.githubstore.mirrors.MirrorStatusRegistry
import zed.rainxch.githubstore.mirrors.MirrorStatusWorker
import zed.rainxch.githubstore.oauth.OAuthCleanupWorker
import zed.rainxch.githubstore.oauth.OAuthEphemeralStore
import zed.rainxch.githubstore.oauth.OAuthExchangeService
import zed.rainxch.githubstore.oauth.OAuthServiceAuth
import zed.rainxch.githubstore.oauth.PostgresOAuthEphemeralStore

val appModule = module {
    single { EventRepository() }
    single { RepoRepository() }
    single { SearchRepository() }
    single { FeedRepository() }
    single { FeedService(get()) }
    single { SearchMissRepository() }
    single { ResourceCacheRepository() }
    single { MeilisearchClient() }
    single { GitHubSearchClient(get()) }
    single { GitHubDeviceClient() }
    single {
        val searchClient: GitHubSearchClient = get()
        GitHubResourceClient(
            cacheRepository = get(),
            // Share the rotation pool + quiet-window guarantee so resource-
            // proxy routes (/repo, /releases, /readme, /user) follow the
            // same token policy as /search.
            fallbackTokenProvider = searchClient::pickFallbackToken,
            isQuietWindow = searchClient::isQuietWindowNow,
        )
    }
    single { WorkerSupervisor() }
    single { SignalAggregationWorker(get(), get()) }
    single { RepoRefreshWorker(get(), get()) }
    single { RetentionWorker(get()) }
    single { SnapshotRetentionWorker(get()) }
    single { SearchMetricsRegistry() }
    single { FdroidVersionClient(packageId = "zed.rainxch.githubstore") }
    single { BadgeService(repoRepository = get(), resourceClient = get(), fdroidClient = get()) }
    single { SigningFingerprintRepository() }
    // Forgejo / Codeberg search client. Trusted-host allowlist comes from
    // FORGEJO_TRUSTED_HOSTS (comma-separated); falls back to the hardcoded
    // canonical set if unset. Anonymous reads only — no PAT forwarding.
    single {
        ForgejoSearchClient(
            trustedHosts = ForgejoSearchClient.parseTrustedHostsEnv(
                System.getenv("FORGEJO_TRUSTED_HOSTS"),
            ),
        )
    }
    // Forgejo / Codeberg resource client (3.3 / 3.4 host-keyed proxies).
    // Shares the trusted-host allowlist with ForgejoSearchClient and uses
    // the same resource_cache table for repo + license bodies.
    single {
        ForgejoResourceClient(
            cache = get(),
            trustedHosts = ForgejoSearchClient.parseTrustedHostsEnv(
                System.getenv("FORGEJO_TRUSTED_HOSTS"),
            ),
        )
    }
    single {
        ExternalMatchService(
            signingFingerprintRepository = get(),
            cache = get(),
            searchClient = get(),
            forgejoSearchClient = get(),
        )
    }
    single { FdroidSeedWorker(signingFingerprintRepository = get(), supervisor = get()) }
    // Forge F-Droid seed worker. Trusted-host allowlist + index URLs both
    // env-overridable so operators can broaden the crawler without a redeploy.
    single {
        ForgejoFdroidSeedWorker(
            signingFingerprintRepository = get(),
            trustedHosts = ForgejoSearchClient.parseTrustedHostsEnv(
                System.getenv("FORGEJO_TRUSTED_HOSTS"),
            ),
            indexUrls = ForgejoFdroidSeedWorker.parseIndexUrlsEnv(
                System.getenv("FORGEJO_FDROID_INDEX_URLS"),
            ),
            supervisor = get(),
        )
    }
    single { MirrorStatusRegistry() }
    single { MirrorStatusWorker(registry = get(), supervisor = get()) }
    single { AnnouncementLoader() }
    single { AnnouncementsRegistry(loader = get()) }
    single {
        val sc: GitHubSearchClient = get()
        RepoRefreshCoordinator(
            refreshUpstream = sc::refreshRepo,
            persistFn = sc::persist,
        )
    }

    // OAuth web flow. clientId is safe to embed (matches the KMP client's
    // BuildKonfig value); clientSecret comes from the OAuth App settings and
    // must be set in the production .env. callbackUrl is the website's
    // callback handler — the redirect_uri must match what's registered with
    // GitHub for the OAuth app, otherwise GitHub rejects with redirect_uri_mismatch.
    single<OAuthEphemeralStore> { PostgresOAuthEphemeralStore() }
    single {
        // Empty fallbacks are dev-only sentinels — validateProductionEnv
        // refuses to start prod without OAUTH_CLIENT_ID / OAUTH_CLIENT_SECRET /
        // OAUTH_WEB_CALLBACK_URL set, so the "missing-…" strings never run
        // through to a real exchange under APP_ENV=production.
        OAuthExchangeService(
            clientId = System.getenv("OAUTH_CLIENT_ID")?.takeIf { it.isNotBlank() }
                ?: "missing-client-id",
            clientSecret = System.getenv("OAUTH_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
                ?: "missing-client-secret",
            callbackUrl = System.getenv("OAUTH_WEB_CALLBACK_URL")?.takeIf { it.isNotBlank() }
                ?: "https://localhost.invalid/missing-callback",
        )
    }
    single {
        OAuthServiceAuth(
            expectedToken = System.getenv("OAUTH_SERVICE_TOKEN"),
            allowedHostsCsv = System.getenv("OAUTH_SERVICE_ALLOWED_HOSTS"),
        )
    }
    single { OAuthCleanupWorker(store = get(), supervisor = get()) }
}
