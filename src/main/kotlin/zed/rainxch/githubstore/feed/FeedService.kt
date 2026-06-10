package zed.rainxch.githubstore.feed

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import zed.rainxch.githubstore.db.FeedRepository
import zed.rainxch.githubstore.model.RepoResponse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

// Assembles and caches the daily feed per platform. The whole feed for one
// (platform, epochDay) is built once — four pool queries + one in-memory
// mix — and every page request all day serves a slice of that cached list.
// Restart-volatile by design: a fresh container rebuilds on first request
// (~4 cheap indexed queries against 12k rows).
class FeedService(private val feedRepository: FeedRepository) {

    private val log = LoggerFactory.getLogger(FeedService::class.java)

    data class AssembledFeed(
        val items: List<RepoResponse>,
        val rotation: String,
        val generatedAt: String,
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
        cache[key]?.let { return it }

        return buildLock.withLock {
            cache[key]?.let { return it }

            val pools = mapOf(
                FeedAssembler.Pool.TRENDING to feedRepository.trendingPool(platform),
                FeedAssembler.Pool.RELEASES to feedRepository.releasesPool(platform),
                FeedAssembler.Pool.GEMS to feedRepository.gemsPool(platform),
                FeedAssembler.Pool.POPULAR to feedRepository.popularPool(platform),
            )
            // Seed mixes epochDay with the platform so the four platform
            // feeds rotate independently rather than being shifted copies.
            val seed = day.toEpochDay() * 31 + (platform?.hashCode() ?: 0)
            val items = FeedAssembler.assemble(pools, seed)

            val assembled = AssembledFeed(
                items = items,
                rotation = day.toString(),
                generatedAt = Instant.now().toString(),
            )
            cache[key] = assembled

            // Evict every other day's entries — at most 5 keys live per day
            // (4 platforms + all), so the map stays tiny across restart-free
            // weeks.
            cache.keys.removeIf { !it.endsWith(":$day") }

            log.info(
                "Feed assembled: key={} items={} pools=[t={} r={} g={} p={}]",
                key, items.size,
                pools.getValue(FeedAssembler.Pool.TRENDING).size,
                pools.getValue(FeedAssembler.Pool.RELEASES).size,
                pools.getValue(FeedAssembler.Pool.GEMS).size,
                pools.getValue(FeedAssembler.Pool.POPULAR).size,
            )
            assembled
        }
    }
}

data class FeedPage(
    val items: List<RepoResponse>,
    val page: Int,
    val hasMore: Boolean,
    val generatedAt: String,
    val rotation: String,
)
