package zed.rainxch.githubstore.feed

import zed.rainxch.githubstore.model.RepoResponse
import kotlin.random.Random

// Pure mixing logic — no DB, no clock, no I/O. Pools in, ordered feed out.
// Deterministic for a given (pools, seed) so the same epochDay produces the
// same feed for every request all day: that's what makes the endpoint
// CDN-cacheable with a whole-day shared cache entry.
object FeedAssembler {

    // Interleave pattern, one pool pick per step, cycled until target size.
    // Trending and releases carry the most slots — they're the strongest
    // freshness/quality signals. Gems get every 4th-ish slot so low-star
    // discoveries surface without dominating.
    private val PATTERN = listOf(
        Pool.TRENDING, Pool.RELEASES, Pool.GEMS, Pool.POPULAR,
        Pool.RELEASES, Pool.TRENDING, Pool.POPULAR, Pool.GEMS,
    )

    // Same-owner repos must sit at least this many positions apart. Catches
    // the "one prolific org floods the feed" failure mode.
    private const val OWNER_WINDOW = 8

    // Same primary-topic repos must sit at least this many positions apart.
    // Primary topic = first canonical topicCode; repos without one are
    // exempt from the window (no signal to dedup on).
    private const val TOPIC_WINDOW = 4

    enum class Pool { TRENDING, RELEASES, GEMS, POPULAR }

    fun assemble(
        pools: Map<Pool, List<RepoResponse>>,
        seed: Long,
        targetSize: Int = 500,
    ): List<RepoResponse> {
        // Shuffle within each pool so the feed rotates daily while staying
        // inside the quality tier the SQL pre-selected. Seeded Random keeps
        // it deterministic per day.
        val rng = Random(seed)
        val queues = Pool.entries.associateWith {
            ArrayDeque(pools[it].orEmpty().shuffled(rng))
        }

        val result = ArrayList<RepoResponse>(targetSize)
        val usedIds = HashSet<Long>()
        // Windows are measured in FEED POSITIONS, not in placements that
        // happen to carry the attribute. A deque of "recent values" would
        // deadlock on sparse attributes: if only one topic exists in the
        // pools, it never gets evicted by other topics and blocks forever.
        // Position-indexed maps let every window expire as the feed grows
        // regardless of what lands in between.
        val lastOwnerPos = HashMap<String, Int>()
        val lastTopicPos = HashMap<String, Int>()

        var patternIdx = 0
        var consecutiveMisses = 0
        // 2 full pattern cycles with zero placements = every queue is either
        // empty or fully blocked by dedup windows. Stop instead of spinning.
        val missLimit = PATTERN.size * 2

        while (result.size < targetSize && consecutiveMisses < missLimit) {
            val pool = PATTERN[patternIdx % PATTERN.size]
            patternIdx++

            val placed = tryPlaceFrom(queues.getValue(pool), result, usedIds, lastOwnerPos, lastTopicPos)
            consecutiveMisses = if (placed) 0 else consecutiveMisses + 1
        }

        return result
    }

    private fun tryPlaceFrom(
        queue: ArrayDeque<RepoResponse>,
        result: MutableList<RepoResponse>,
        usedIds: MutableSet<Long>,
        lastOwnerPos: MutableMap<String, Int>,
        lastTopicPos: MutableMap<String, Int>,
    ): Boolean {
        val nextPos = result.size
        // Scan the queue head for the first item that passes the dedup
        // windows. Blocked items stay in place (their window clears as the
        // feed grows); already-used items are dropped permanently.
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.id in usedIds) {
                iterator.remove()
                continue
            }
            val owner = candidate.owner.login.lowercase()
            val ownerLast = lastOwnerPos[owner]
            if (ownerLast != null && nextPos - ownerLast < OWNER_WINDOW) continue
            val primaryTopic = candidate.topicCodes.firstOrNull()
            val topicLast = primaryTopic?.let { lastTopicPos[it] }
            if (topicLast != null && nextPos - topicLast < TOPIC_WINDOW) continue

            iterator.remove()
            usedIds.add(candidate.id)
            result.add(candidate)
            lastOwnerPos[owner] = nextPos
            if (primaryTopic != null) lastTopicPos[primaryTopic] = nextPos
            return true
        }
        return false
    }
}
