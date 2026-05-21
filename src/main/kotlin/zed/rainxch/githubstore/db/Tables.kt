package zed.rainxch.githubstore.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object Repos : Table("repos") {
    val id = long("id")
    val fullName = text("full_name")
    val owner = text("owner")
    val name = text("name")
    val ownerAvatarUrl = text("owner_avatar_url").nullable()
    val description = text("description").nullable()
    val defaultBranch = text("default_branch").nullable()
    val htmlUrl = text("html_url")
    val stars = integer("stars").default(0)
    val forks = integer("forks").default(0)
    val openIssues = integer("open_issues").default(0)
    val licenseSpdxId = text("license_spdx_id").nullable()
    val licenseName = text("license_name").nullable()
    val language = text("language").nullable()
    val topics = array<String>("topics", TextColumnType())
    val latestReleaseDate = timestampWithTimeZone("latest_release_date").nullable()
    val latestReleaseTag = text("latest_release_tag").nullable()
    val hasInstallersAndroid = bool("has_installers_android").default(false)
    val hasInstallersWindows = bool("has_installers_windows").default(false)
    val hasInstallersMacos = bool("has_installers_macos").default(false)
    val hasInstallersLinux = bool("has_installers_linux").default(false)
    val installCount = integer("install_count").default(0)
    val installSuccessRate = float("install_success_rate").nullable()
    val viewCount7d = integer("view_count_7d").default(0)
    val downloadCount = long("download_count").default(0)
    val trendingScore = float("trending_score").nullable()
    val popularityScore = float("popularity_score").nullable()
    val searchScore = float("search_score").nullable()
    val createdAtGh = timestampWithTimeZone("created_at_gh").nullable()
    val updatedAtGh = timestampWithTimeZone("updated_at_gh").nullable()
    // R5/R13: last default-branch commit (GitHub pushed_at), distinct from
    // updatedAtGh (last metadata change). Used by client Heartbeat animation.
    val pushedAtGh = timestampWithTimeZone("pushed_at_gh").nullable()
    val indexedAt = timestampWithTimeZone("indexed_at")

    override val primaryKey = PrimaryKey(id)
}

object RepoCategories : Table("repo_categories") {
    val repoId = long("repo_id").references(Repos.id)
    val category = text("category")
    val platform = text("platform")
    val rank = integer("rank")

    override val primaryKey = PrimaryKey(repoId, category, platform)
}

object RepoTopicBuckets : Table("repo_topic_buckets") {
    val repoId = long("repo_id").references(Repos.id)
    val bucket = text("bucket")
    val platform = text("platform")
    val rank = integer("rank")

    override val primaryKey = PrimaryKey(repoId, bucket, platform)
}

object Events : Table("events") {
    val id = long("id").autoIncrement()
    val ts = timestampWithTimeZone("ts")
    val deviceId = text("device_id")
    val platform = text("platform")
    val appVersion = text("app_version").nullable()
    val eventType = text("event_type")
    val repoId = long("repo_id").nullable()
    val queryHash = text("query_hash").nullable()
    val resultCount = integer("result_count").nullable()
    val success = bool("success").nullable()
    val errorCode = text("error_code").nullable()

    override val primaryKey = PrimaryKey(id)
}

object RepoStatsDaily : Table("repo_stats_daily") {
    val repoId = long("repo_id").references(Repos.id)
    val date = date("date")
    val views = integer("views").nullable()
    val searchesHit = integer("searches_hit").nullable()
    val installsStarted = integer("installs_started").nullable()
    val installsSuccess = integer("installs_success").nullable()

    override val primaryKey = PrimaryKey(repoId, date)
}

object SearchMisses : Table("search_misses") {
    val queryHash = text("query_hash")
    val missCount = integer("miss_count").default(1)
    val lastSeenAt = timestampWithTimeZone("last_seen_at")
    val lastProcessedAt = timestampWithTimeZone("last_processed_at").nullable()
    val resultCount = integer("result_count").nullable()

    override val primaryKey = PrimaryKey(queryHash)
}

object SigningFingerprints : Table("signing_fingerprint") {
    // V17: host column added so the same fingerprint table covers GitHub +
    // forge-distributed APKs. Existing rows backfill to "github.com" so the
    // pre-V17 read path stays correct. PK is (host, fingerprint, owner, repo)
    // so cross-host mirrors (same APK on GitHub AND Codeberg) get distinct
    // rows that §3.6 dedup can merge into available_on lists.
    val host = text("host")
    val fingerprint = text("fingerprint")
    val owner = text("owner")
    val repo = text("repo")
    // Epoch milliseconds. The client's contract on /v1/signing-seeds requires
    // ms not seconds — mixing units silently corrupts the incremental cursor.
    val observedAt = long("observed_at")

    override val primaryKey = PrimaryKey(host, fingerprint, owner, repo)
}

object RepoSignals : Table("repo_signals") {
    val repoId = long("repo_id").references(Repos.id)
    val clickCount30d = integer("click_count_30d").default(0)
    val viewCount30d = integer("view_count_30d").default(0)
    val installStarted30d = integer("install_started_30d").default(0)
    val installSuccess30d = integer("install_success_30d").default(0)
    val installFailed30d = integer("install_failed_30d").default(0)
    val ctrScore = float("ctr_score").default(0f)
    val installSuccessRate = float("install_success_rate").default(0f)
    val lastClickAt = timestampWithTimeZone("last_click_at").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(repoId)
}

// Note: `oauth_ephemeral` (V16) is intentionally NOT declared as an Exposed
// Table here. PostgresOAuthEphemeralStore reaches the table via raw JDBC
// (matching the ResourceCacheRepository convention) because every query is
// either an `INSERT ... ON CONFLICT DO NOTHING` or a `DELETE ... RETURNING`
// — shapes that don't translate cleanly to the Exposed DSL and that the
// store's atomicity guarantees depend on. Adding an Exposed object here
// would be a footgun: a future maintainer might use it for a "convenient"
// SELECT or UPDATE that bypasses the `expires_at > NOW()` filter and start
// serving expired rows.
