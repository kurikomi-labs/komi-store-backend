package zed.rainxch.githubstore.feed

import zed.rainxch.githubstore.model.RepoOwner
import zed.rainxch.githubstore.model.RepoResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeedAssemblerTest {

    private fun repo(
        id: Long,
        owner: String = "owner$id",
        topicCodes: List<String> = emptyList(),
    ) = RepoResponse(
        id = id,
        name = "repo$id",
        fullName = "$owner/repo$id",
        owner = RepoOwner(login = owner, avatarUrl = null),
        description = null,
        defaultBranch = null,
        htmlUrl = "https://github.com/$owner/repo$id",
        stargazersCount = id.toInt(),
        forksCount = 0,
        language = null,
        topics = emptyList(),
        topicCodes = topicCodes,
        releasesUrl = null,
        updatedAt = null,
        createdAt = null,
    )

    private fun pools(vararg ranges: Pair<FeedAssembler.Pool, LongRange>) =
        ranges.associate { (pool, range) -> pool to range.map { repo(it) } }

    @Test
    fun `same seed produces identical feed`() {
        val p = pools(
            FeedAssembler.Pool.TRENDING to 1L..50L,
            FeedAssembler.Pool.RELEASES to 51L..100L,
            FeedAssembler.Pool.GEMS to 101L..150L,
            FeedAssembler.Pool.POPULAR to 151L..200L,
        )
        val a = FeedAssembler.assemble(p, seed = 42L, targetSize = 100)
        val b = FeedAssembler.assemble(p, seed = 42L, targetSize = 100)
        assertEquals(a.map { it.id }, b.map { it.id })
    }

    @Test
    fun `different seed produces different order`() {
        val p = pools(
            FeedAssembler.Pool.TRENDING to 1L..50L,
            FeedAssembler.Pool.RELEASES to 51L..100L,
            FeedAssembler.Pool.GEMS to 101L..150L,
            FeedAssembler.Pool.POPULAR to 151L..200L,
        )
        val a = FeedAssembler.assemble(p, seed = 1L, targetSize = 100)
        val b = FeedAssembler.assemble(p, seed = 2L, targetSize = 100)
        assertNotEquals(a.map { it.id }, b.map { it.id })
    }

    @Test
    fun `no duplicate ids even when pools overlap`() {
        // Same repos present in several pools — feed must dedup by id.
        val shared = (1L..30L).map { repo(it) }
        val p = mapOf(
            FeedAssembler.Pool.TRENDING to shared,
            FeedAssembler.Pool.RELEASES to shared,
            FeedAssembler.Pool.GEMS to shared,
            FeedAssembler.Pool.POPULAR to shared,
        )
        val feed = FeedAssembler.assemble(p, seed = 7L, targetSize = 100)
        assertEquals(feed.map { it.id }.toSet().size, feed.size)
        assertEquals(30, feed.size)
    }

    @Test
    fun `same owner kept at least 8 positions apart`() {
        // Two owners, many repos each — window must spread them.
        val a = (1L..40L).map { repo(it, owner = "alice") }
        val b = (41L..80L).map { repo(it, owner = "bob") }
        val c = (81L..200L).map { repo(it) }
        val p = mapOf(
            FeedAssembler.Pool.TRENDING to a,
            FeedAssembler.Pool.RELEASES to b,
            FeedAssembler.Pool.GEMS to c,
            FeedAssembler.Pool.POPULAR to c.shuffled(kotlin.random.Random(1)),
        )
        val feed = FeedAssembler.assemble(p, seed = 3L, targetSize = 150)
        val positionsByOwner = feed.withIndex().groupBy({ it.value.owner.login }, { it.index })
        for ((owner, positions) in positionsByOwner) {
            positions.zipWithNext().forEach { (prev, next) ->
                assertTrue(
                    next - prev >= 8,
                    "owner $owner at positions $prev and $next — closer than the 8-slot window",
                )
            }
        }
    }

    @Test
    fun `same primary topic kept at least 4 positions apart`() {
        val privacy = (1L..40L).map { repo(it, topicCodes = listOf("privacy")) }
        val other = (41L..200L).map { repo(it) }
        val p = mapOf(
            FeedAssembler.Pool.TRENDING to privacy,
            FeedAssembler.Pool.RELEASES to other.take(50),
            FeedAssembler.Pool.GEMS to other.drop(50).take(50),
            FeedAssembler.Pool.POPULAR to other.drop(100),
        )
        val feed = FeedAssembler.assemble(p, seed = 9L, targetSize = 150)
        val privacyPositions = feed.withIndex()
            .filter { it.value.topicCodes.firstOrNull() == "privacy" }
            .map { it.index }
        privacyPositions.zipWithNext().forEach { (prev, next) ->
            assertTrue(
                next - prev >= 4,
                "privacy repos at positions $prev and $next — closer than the 4-slot window",
            )
        }
    }

    @Test
    fun `terminates when pools exhausted below target`() {
        val p = pools(FeedAssembler.Pool.TRENDING to 1L..10L)
        val feed = FeedAssembler.assemble(p, seed = 5L, targetSize = 500)
        assertEquals(10, feed.size)
    }

    @Test
    fun `empty pools produce empty feed`() {
        val feed = FeedAssembler.assemble(emptyMap(), seed = 1L)
        assertTrue(feed.isEmpty())
    }

    // ── bucketByPrimaryTopic ──────────────────────────────────────────────

    @Test
    fun `bucketByPrimaryTopic takes top-N per primary topic in code order`() {
        val privacy = (1L..10L).map { repo(it, topicCodes = listOf("privacy")) }
        val ai = (11L..20L).map { repo(it, topicCodes = listOf("ai")) }
        val source = (ai + privacy) // deliberately out of code order
        val bucketed = FeedAssembler.bucketByPrimaryTopic(
            source = source,
            codeOrder = listOf("privacy", "ai"),
            perTopic = 3,
        )
        // 3 per bucket, privacy first (code order, not source order).
        assertEquals(6, bucketed.size)
        assertEquals(listOf("privacy", "privacy", "privacy", "ai", "ai", "ai"),
            bucketed.map { it.topicCodes.first() })
        // Top-N within a bucket preserves source order (search_score DESC).
        assertEquals(listOf(11L, 12L, 13L), bucketed.filter { it.topicCodes.first() == "ai" }.map { it.id })
    }

    @Test
    fun `bucketByPrimaryTopic ignores repos with no topic code`() {
        val source = (1L..5L).map { repo(it) } + repo(6L, topicCodes = listOf("ai"))
        val bucketed = FeedAssembler.bucketByPrimaryTopic(source, listOf("ai"), perTopic = 10)
        assertEquals(listOf(6L), bucketed.map { it.id })
    }

    @Test
    fun `bucketByPrimaryTopic skips codes absent from source`() {
        val source = (1L..3L).map { repo(it, topicCodes = listOf("ai")) }
        val bucketed = FeedAssembler.bucketByPrimaryTopic(
            source = source,
            codeOrder = listOf("privacy", "ai", "security"),
            perTopic = 10,
        )
        assertEquals(3, bucketed.size)
        assertTrue(bucketed.all { it.topicCodes.first() == "ai" })
    }

    @Test
    fun `topics pool participates in mix`() {
        val topics = FeedAssembler.bucketByPrimaryTopic(
            source = (1L..40L).map { repo(it, topicCodes = listOf("ai")) },
            codeOrder = listOf("ai"),
            perTopic = 40,
        )
        val p = mapOf(
            FeedAssembler.Pool.TRENDING to (41L..80L).map { repo(it) },
            FeedAssembler.Pool.TOPICS to topics,
        )
        val feed = FeedAssembler.assemble(p, seed = 11L, targetSize = 60)
        // Topic repos must appear — the pattern includes TOPICS slots.
        assertTrue(feed.any { it.id in 1L..40L }, "no topic-pool repo reached the feed")
    }

    @Test
    fun `cross-pool dedup does not starve the long-tail topic repos`() {
        // The TOPICS pool is ranked by search_score, the same axis TRENDING /
        // POPULAR correlate with, so its highest-scored repos overlap those
        // pools and get deduped to their home pool. The whole point of TOPICS
        // is the LONG TAIL — niche-category repos that never trend. This test
        // proves those survive: TOPICS shares its top ids with TRENDING but
        // also carries unique niche-topic repos, and every unique one must
        // still reach the feed.
        val overlapIds = 1L..15L              // present in BOTH trending and topics
        val nicheIds = 100L..130L             // unique to topics (the long tail)

        val trending = (overlapIds).map { repo(it, topicCodes = listOf("ai")) } +
            (200L..260L).map { repo(it) }     // trending's own non-topic bulk

        val topicSource =
            (overlapIds).map { repo(it, topicCodes = listOf("ai")) } +
            nicheIds.mapIndexed { i, id ->
                // spread across several niche codes so the topic window can't
                // block them and each bucket stays small
                repo(id, owner = "niche$id", topicCodes = listOf(listOf("backup", "reader", "audio", "photo")[i % 4]))
            }
        val topics = FeedAssembler.bucketByPrimaryTopic(
            source = topicSource,
            codeOrder = listOf("ai", "backup", "reader", "audio", "photo"),
            perTopic = 8,
        )

        val p = mapOf(
            FeedAssembler.Pool.TRENDING to trending,
            FeedAssembler.Pool.TOPICS to topics,
        )
        val feed = FeedAssembler.assemble(p, seed = 21L, targetSize = 200)
        val feedIds = feed.map { it.id }.toSet()

        // No id appears twice (dedup holds).
        assertEquals(feed.size, feedIds.size, "feed contained a duplicate id")
        // Every unique niche repo reached the feed — dedup only removed the
        // overlapping high-score ids, never the long-tail.
        val missingNiche = nicheIds.filter { it !in feedIds }
        assertTrue(
            missingNiche.isEmpty(),
            "long-tail topic repos were starved out of the feed: $missingNiche",
        )
    }
}
