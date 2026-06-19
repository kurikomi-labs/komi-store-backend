package zed.rainxch.githubstore.feed

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.EligibleRepo
import zed.rainxch.githubstore.db.FeedRepository
import zed.rainxch.githubstore.model.RepoResponse
import zed.rainxch.githubstore.ranking.FeedRankScorer
import zed.rainxch.githubstore.topics.TopicCodeMapper
import zed.rainxch.githubstore.util.FeatureFlags
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

// Assembles and caches the daily feed per platform. The shuffle SEED is
// daily (rotation tag = UTC date, stable client cache keys, one big CDN
// window), but the assembled snapshot is rebuilt every REFRESH_INTERVAL so
// data the VPS new-releases cron lands intraday (every 3h) reaches the feed
// the same day instead of waiting for the next rotation. Same seed +
// mostly-same pool data = stable order with fresh releases folded in — not
// a reshuffle, so mid-pagination tear stays negligible.
// Restart-volatile by design: a fresh container rebuilds on first request
// (~4 cheap indexed queries against 12k rows).
class FeedService(private val feedRepository: FeedRepository) {

    private val log = LoggerFactory.getLogger(FeedService::class.java)

    data class AssembledFeed(
        val items: List<RepoResponse>,
        val rotation: String,
        val generatedAt: String,
        val builtAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, AssembledFeed>()
    // One assembly at a time per process. Concurrent first-requests for the
    // same key would otherwise all run the pool queries; with the lock the
    // losers find the cache populated when their turn comes.
    private val buildLock = Mutex()

    suspend fun page(platform: String?, page: Int, limit: Int): FeedPage {
        val today = LocalDate.now(ZoneOffset.UTC)
        val feed = feedFor(platform, today)

        val fromIndex = (page - 1) * limit
        val slice = if (fromIndex >= feed.items.size) emptyList() else {
            feed.items.subList(fromIndex, minOf(fromIndex + limit, feed.items.size))
        }
        return FeedPage(
            items = slice,
            page = page,
            hasMore = fromIndex + limit < feed.items.size,
            generatedAt = feed.generatedAt,
            rotation = feed.rotation,
        )
    }

    private suspend fun feedFor(platform: String?, day: LocalDate): AssembledFeed {
        val key = "${platform ?: "all"}:$day"
        cache[key]?.let { if (!it.isStale()) return it }

        return buildLock.withLock {
            cache[key]?.let { if (!it.isStale()) return it }

            // cache[key] is day-scoped (the key carries the date), so a non-null
            // entry here can only be today's stale rebuild — NOT the first build
            // of this UTC day. The first build is the only one that ages cooldown
            // and writes exposure (brief §7); intraday rebuilds must stay
            // set-stable, which the eligiblePool today-mask guarantees.
            val firstBuildOfDay = cache[key] == null

            val assembled =
                if (FeatureFlags.feedV2Ranking) buildV2(platform, day, firstBuildOfDay)
                else buildLegacy(platform, day)

            cache[key] = assembled
            // Evict every other day's entries — at most 5 keys live per day
            // (4 platforms + all), so the map stays tiny across restart-free weeks.
            cache.keys.removeIf { !it.endsWith(":$day") }
            assembled
        }
    }

    // Feed-v2: one eligible-set query → momentum+cooldown re-rank → diversity
    // placement. Daily rotation is carried by the cooldown baked into the
    // ranking (no seed shuffle). Velocity is null until history matures, which
    // FeedRankScorer treats as zero momentum → ranking falls back to the
    // popularity+recency base, so the feed is never empty during cold start.
    private suspend fun buildV2(platform: String?, day: LocalDate, firstBuildOfDay: Boolean): AssembledFeed {
        val todayEpochDay = day.toEpochDay().toInt()
        val eligible = feedRepository.eligiblePool(platform, todayEpochDay)
        val byId = eligible.associateBy { it.repo.id }
        val ranked = FeedRankScorer.rank(eligible.map { it.toScorerInput() })
        val ordered = ranked.mapNotNull { byId[it.id]?.repo }
        val items = FeedAssembler.applyDiversity(ordered, targetSize = FEED_TARGET_SIZE)

        if (firstBuildOfDay && items.isNotEmpty()) {
            // Placed-only (diversity drops some eligible repos), first-build-of-
            // day-gated. The DB upsert also guards on last_shown < today, so a
            // mid-day restart can't double-count. Never surfaced — a failure logs
            // and the feed still serves.
            runCatching { feedRepository.recordExposure(platform, items.map { it.id }, todayEpochDay) }
                .onFailure { log.warn("recordExposure failed for platform={} (feed still served)", platform ?: "all", it) }
        }

        log.info(
            "Feed v2 assembled: key={}:{} eligible={} placed={} firstBuild={}",
            platform ?: "all", day, eligible.size, items.size, firstBuildOfDay,
        )
        return AssembledFeed(
            items = items,
            rotation = day.toString(),
            generatedAt = Instant.now().toString(),
            builtAtMs = System.currentTimeMillis(),
        )
    }

    // Legacy v1.5 path: four capped quality-axis pools + topic buckets,
    // interleaved by a daily-seeded shuffle. Reachable via FEED_V2_RANKING=false
    // for instant rollback.
    private suspend fun buildLegacy(platform: String?, day: LocalDate): AssembledFeed {
        val pools = mapOf(
            FeedAssembler.Pool.TRENDING to feedRepository.trendingPool(platform),
            FeedAssembler.Pool.RELEASES to feedRepository.releasesPool(platform),
            FeedAssembler.Pool.GEMS to feedRepository.gemsPool(platform),
            FeedAssembler.Pool.POPULAR to feedRepository.popularPool(platform),
            FeedAssembler.Pool.TOPICS to FeedAssembler.bucketByPrimaryTopic(
                source = feedRepository.topicSourcePool(platform),
                codeOrder = TopicCodeMapper.canonicalCodes,
            ),
        )
        // Seed mixes epochDay with the platform so the four platform feeds
        // rotate independently rather than being shifted copies.
        val seed = day.toEpochDay() * 31 + (platform?.hashCode() ?: 0)
        val items = FeedAssembler.assemble(pools, seed)
        log.info(
            "Feed assembled (legacy): key={}:{} items={} pools=[t={} r={} g={} p={} tc={}]",
            platform ?: "all", day, items.size,
            pools.getValue(FeedAssembler.Pool.TRENDING).size,
            pools.getValue(FeedAssembler.Pool.RELEASES).size,
            pools.getValue(FeedAssembler.Pool.GEMS).size,
            pools.getValue(FeedAssembler.Pool.POPULAR).size,
            pools.getValue(FeedAssembler.Pool.TOPICS).size,
        )
        return AssembledFeed(
            items = items,
            rotation = day.toString(),
            generatedAt = Instant.now().toString(),
            builtAtMs = System.currentTimeMillis(),
        )
    }

    private fun EligibleRepo.toScorerInput() = FeedRankScorer.Input(
        id = repo.id,
        starVelocityEwma = starVelocityEwma,
        dlVelocityEwma = dlVelocityEwma,
        stars = repo.stargazersCount.toLong(),
        downloads = repo.downloadCount,
        daysSinceRelease = repo.releaseRecency?.toDouble(),
        daysSinceShown = daysSinceShown,
        shownCount = shownCount,
    )
}

data class FeedPage(
    val items: List<RepoResponse>,
    val page: Int,
    val hasMore: Boolean,
    val generatedAt: String,
    val rotation: String,
)

// 3h matches the VPS new-releases cron cadence — refreshing faster would
// re-query identical data; slower would let same-day releases sit unseen.
private const val REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L

// Distinct daily feed slots assembled per platform. The eligible set (~1–3k) is
// much larger, which is what lets cooldown rotate the catalog across days.
private const val FEED_TARGET_SIZE = 500

private fun FeedService.AssembledFeed.isStale(): Boolean =
    System.currentTimeMillis() - builtAtMs > REFRESH_INTERVAL_MS
