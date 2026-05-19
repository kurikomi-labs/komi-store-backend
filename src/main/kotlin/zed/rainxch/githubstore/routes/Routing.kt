package zed.rainxch.githubstore.routes

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import zed.rainxch.githubstore.announcements.AnnouncementsRegistry
import zed.rainxch.githubstore.db.MeilisearchClient
import zed.rainxch.githubstore.db.RepoRepository
import zed.rainxch.githubstore.db.SearchMissRepository
import zed.rainxch.githubstore.db.SearchRepository
import zed.rainxch.githubstore.ingest.GitHubDeviceClient
import zed.rainxch.githubstore.ingest.GitHubResourceClient
import zed.rainxch.githubstore.ingest.GitHubSearchClient
import zed.rainxch.githubstore.ingest.RepoRefreshCoordinator
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import zed.rainxch.githubstore.metrics.SearchMetricsRegistry
import zed.rainxch.githubstore.badge.BadgeService
import zed.rainxch.githubstore.match.ExternalMatchService
import zed.rainxch.githubstore.match.ForgejoResourceClient
import zed.rainxch.githubstore.match.ForgejoSearchClient
import zed.rainxch.githubstore.match.SigningFingerprintRepository
import zed.rainxch.githubstore.mirrors.MirrorStatusRegistry
import zed.rainxch.githubstore.oauth.OAuthEphemeralStore
import zed.rainxch.githubstore.oauth.OAuthExchangeService
import zed.rainxch.githubstore.oauth.OAuthServiceAuth

fun Application.configureRouting() {
    val repoRepository by inject<RepoRepository>()
    val searchRepository by inject<SearchRepository>()
    val searchMissRepository by inject<SearchMissRepository>()
    val meilisearchClient by inject<MeilisearchClient>()
    val githubSearchClient by inject<GitHubSearchClient>()
    val deviceClient by inject<GitHubDeviceClient>()
    val resourceClient by inject<GitHubResourceClient>()
    val searchMetrics by inject<SearchMetricsRegistry>()
    val badgeService by inject<BadgeService>()
    val workerSupervisor by inject<WorkerSupervisor>()
    val signingFingerprintRepository by inject<SigningFingerprintRepository>()
    val externalMatchService by inject<ExternalMatchService>()
    val forgejoResourceClient by inject<ForgejoResourceClient>()
    val forgejoSearchClient by inject<ForgejoSearchClient>()
    val mirrorStatusRegistry by inject<MirrorStatusRegistry>()
    val announcementsRegistry by inject<AnnouncementsRegistry>()
    val repoRefreshCoordinator by inject<RepoRefreshCoordinator>()
    val oauthStore by inject<OAuthEphemeralStore>()
    val oauthExchangeService by inject<OAuthExchangeService>()
    val oauthServiceAuth by inject<OAuthServiceAuth>()

    routing {
        rootRoutes()
        route("/v1") {
            healthRoutes(meilisearchClient, announcementsRegistry)
            eventRoutes()
            categoryRoutes(repoRepository)
            topicRoutes(repoRepository)
            // Tombstones for pre-1.6 auth paths under /repo/. Declared before
            // repoRoutes so the static segments win over /repo/{owner}/{name}.
            deprecationRoutes()
            repoRoutes(repoRepository, resourceClient, forgejoResourceClient)
            rateLimit(RateLimitName("search")) {
                searchRoutes(meilisearchClient, searchRepository, githubSearchClient, searchMissRepository, searchMetrics)
                releasesRoutes(resourceClient)
                readmeRoutes(resourceClient)
                userRoutes(resourceClient)
                userReposRoutes(resourceClient)
                userStarredRoutes(resourceClient)
                repoRefreshRoutes(repoRefreshCoordinator, repoRepository)
            }
            authRoutes(deviceClient)
            // OAuth web flow. Each sub-route applies its own rate-limit
            // bucket inside oauthRoutes() — wrapping the whole block in
            // multiple nested rateLimit() calls would have charged every
            // request against every bucket.
            oauthRoutes(oauthStore, oauthExchangeService, oauthServiceAuth)
            internalRoutes(searchMetrics, workerSupervisor, githubSearchClient)
            rateLimit(RateLimitName("signing-seeds")) {
                signingSeedsRoutes(signingFingerprintRepository)
            }
            rateLimit(RateLimitName("external-match")) {
                externalMatchRoutes(externalMatchService)
            }
            rateLimit(RateLimitName("mirrors-list")) {
                mirrorRoutes(mirrorStatusRegistry)
            }
            announcementsRoutes(announcementsRegistry)
            rateLimit(RateLimitName("badges")) {
                badgeRoutes(badgeService)
            }
        }
    }
}
