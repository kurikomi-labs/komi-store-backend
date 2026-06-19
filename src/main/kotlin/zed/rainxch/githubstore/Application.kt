package zed.rainxch.githubstore

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.sentry.Sentry
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import zed.rainxch.githubstore.announcements.AnnouncementsRegistry
import zed.rainxch.githubstore.db.DatabaseFactory
import zed.rainxch.githubstore.ingest.RepoRefreshWorker
import zed.rainxch.githubstore.ingest.RetentionWorker
import zed.rainxch.githubstore.match.FdroidSeedWorker
import zed.rainxch.githubstore.match.ForgejoFdroidSeedWorker
import zed.rainxch.githubstore.mirrors.MirrorStatusWorker
import zed.rainxch.githubstore.ingest.SignalAggregationWorker
import zed.rainxch.githubstore.ingest.WorkerSupervisor
import zed.rainxch.githubstore.routes.configureRouting

fun main() {
    validateProductionEnv()

    val sentryDsn = System.getenv("SENTRY_DSN")
    if (!sentryDsn.isNullOrBlank()) {
        Sentry.init { options ->
            options.dsn = sentryDsn
            options.tracesSampleRate = 0.01
            options.environment = System.getenv("APP_ENV") ?: "production"
            options.release = "github-store-backend@${BuildInfo.version}"
            // Scrub credential-bearing headers from every event before they
            // leave the process. Sentry's default scrubber catches a few
            // common ones; this list is what we explicitly forward and what
            // an attacker could set to test the pipeline.
            options.setBeforeSend { event, _ ->
                try {
                    event.request?.headers?.let { headers ->
                        listOf(
                            "Authorization", "X-GitHub-Token", "X-Admin-Token",
                            "X-Oauth-Service-Token",
                            "Cookie", "Set-Cookie", "X-Forwarded-For",
                            "CF-Connecting-IP",
                        ).forEach { headers.remove(it) }
                    }
                    event.message?.let { msg ->
                        msg.formatted = scrubSentryMessage(msg.formatted)
                    }
                    event.exceptions?.forEach { ex ->
                        ex.value = scrubSentryMessage(ex.value)
                    }
                    event.breadcrumbs?.forEach { crumb ->
                        crumb.message = scrubSentryMessage(crumb.message)
                    }
                } catch (_: Throwable) {
                    // Sentry instrumentation must never throw -- swallow and ship
                    // whatever we have rather than crash the request handler.
                }
                event
            }
        }
    }

    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        host = "0.0.0.0",
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(appModule)
    }

    configureSerialization()
    configureHTTP()
    DatabaseFactory.init()
    configureRouting()

    // Start background workers after routing is configured
    val signalAggregationWorker by inject<SignalAggregationWorker>()
    signalAggregationWorker.start()

    val repoRefreshWorker by inject<RepoRefreshWorker>()
    repoRefreshWorker.start()

    val retentionWorker by inject<RetentionWorker>()
    retentionWorker.start()

    val snapshotRetentionWorker by inject<zed.rainxch.githubstore.ingest.SnapshotRetentionWorker>()
    snapshotRetentionWorker.start()

    val fdroidSeedWorker by inject<FdroidSeedWorker>()
    fdroidSeedWorker.start()

    val forgejoFdroidSeedWorker by inject<ForgejoFdroidSeedWorker>()
    forgejoFdroidSeedWorker.start()

    val mirrorStatusWorker by inject<MirrorStatusWorker>()
    mirrorStatusWorker.start()

    val oauthCleanupWorker by inject<zed.rainxch.githubstore.oauth.OAuthCleanupWorker>()
    oauthCleanupWorker.start()

    // Synchronous load -- no coroutine. The set is small (handful of files
    // bundled in the JAR) and we want the startup log line before serving.
    // Pre-start, the registry returns an empty list, so a request that
    // somehow lands here before this call is non-fatal.
    val announcementsRegistry by inject<AnnouncementsRegistry>()
    announcementsRegistry.start()

    val workerSupervisor by inject<WorkerSupervisor>()
    monitor.subscribe(ApplicationStopping) {
        workerSupervisor.cancelAll()
    }
}

// Strip long single-quoted spans (commonly the user-input value JDBC and
// serialization errors splat into messages) and cap overall length so we
// don't ship 10KB of stacktrace-embedded payloads to Sentry.
private val SENTRY_QUOTED_SPAN = Regex("'[^']{32,}?'")
private const val SENTRY_MESSAGE_MAX = 200

private fun scrubSentryMessage(value: String?): String? {
    if (value == null) return null
    val stripped = SENTRY_QUOTED_SPAN.replace(value, "'<redacted>'")
    return if (stripped.length > SENTRY_MESSAGE_MAX) {
        stripped.take(SENTRY_MESSAGE_MAX) + "…"
    } else {
        stripped
    }
}

// Under APP_ENV=production, refuse to start unless the critical secrets are
// set explicitly. Otherwise dev defaults (password "githubstore", "devkey",
// etc.) could silently bind a production deploy to a local Postgres/Meili
// that happens to be reachable. Required list is intentionally narrow to
// keep dev iteration friction-free.
private fun validateProductionEnv() {
    if (System.getenv("APP_ENV") != "production") return
    val required = listOf(
        "DATABASE_URL",
        "DATABASE_PASSWORD",
        "MEILI_URL",
        "MEILI_MASTER_KEY",
        "OAUTH_CLIENT_ID",
        // OAuth client secret is required for the web-flow exchange path.
        // Without it, /v1/oauth/exchange would call GitHub with an empty
        // secret and every flow would 400. Distinct from CLIENT_ID — only
        // the backend needs the secret.
        "OAUTH_CLIENT_SECRET",
        // Shared secret on /v1/oauth/state and /v1/oauth/exchange. Missing
        // env makes both endpoints return 401 service_auth_required on
        // every request — useless service.
        "OAUTH_SERVICE_TOKEN",
        // Host allowlist for the S2S OAuth endpoints. Required in production
        // alongside OAUTH_SERVICE_TOKEN — empty in prod means every S2S call
        // gets 401, which is a silent failure mode validateProductionEnv
        // exists to prevent.
        "OAUTH_SERVICE_ALLOWED_HOSTS",
        // GitHub OAuth redirect_uri. Must EXACTLY match the value registered
        // with the OAuth App or every exchange call fails with
        // redirect_uri_mismatch — same fail-fast rationale as the others.
        "OAUTH_WEB_CALLBACK_URL",
        // Pepper for SHA-256 hashing of device IDs before they hit Postgres.
        // Required in prod so a stolen DB dump can't be brute-forced into a
        // device-ID lookup table without also stealing the env.
        "DEVICE_ID_PEPPER",
    )
    val missing = required.filter { System.getenv(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        System.err.println(
            "FATAL: missing required env vars under APP_ENV=production: $missing"
        )
        throw IllegalStateException(
            "Missing required env vars: $missing. " +
                "Set them in /opt/github-store-backend/.env before deploy."
        )
    }
}
