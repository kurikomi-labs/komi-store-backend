package zed.rainxch.githubstore.match

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import zed.rainxch.githubstore.db.SigningFingerprints
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
open class SigningFingerprintRepository {

    /**
     * Triple of (host, owner, repo) returned for each matching fingerprint.
     * Multiple rows for the same fingerprint are normal post-V17 when an
     * APK ships from more than one forge (e.g. GitHub + Codeberg mirror).
     * The §3.6 cross-forge dedup pass groups these into `available_on`.
     */
    data class HostedRepo(val host: String, val owner: String, val repo: String)

    open suspend fun lookup(fingerprint: String): List<HostedRepo> =
        newSuspendedTransaction(Dispatchers.IO) {
            SigningFingerprints
                .selectAll()
                .where { SigningFingerprints.fingerprint eq fingerprint }
                .map {
                    HostedRepo(
                        host = it[SigningFingerprints.host],
                        owner = it[SigningFingerprints.owner],
                        repo = it[SigningFingerprints.repo],
                    )
                }
        }

    // Seed dump for /v1/signing-seeds. Returns rows ordered by (observedAt,
    // fingerprint, owner, repo) so the cursor only needs to encode the most
    // recent boundary -- never the row offset, which would skip rows under
    // concurrent writes.
    open suspend fun page(
        sinceMs: Long?,
        cursor: PageCursor?,
        limit: Int,
    ): SigningSeedPage = newSuspendedTransaction(Dispatchers.IO) {
        val rows = SigningFingerprints
            .selectAll()
            .where {
                var clause: Op<Boolean> = Op.TRUE
                if (sinceMs != null) {
                    clause = clause and (SigningFingerprints.observedAt greaterEq sinceMs)
                }
                if (cursor != null) {
                    // Composite seek: rows strictly after (observedAt, fingerprint,
                    // owner, repo). Standard "row-value comparison" pattern, expanded
                    // to ANDs/ORs since Exposed has no native row-value comparator.
                    val c = cursor
                    val seek = (SigningFingerprints.observedAt greater c.observedAt) or
                        ((SigningFingerprints.observedAt eq c.observedAt) and
                            (SigningFingerprints.fingerprint greater c.fingerprint)) or
                        ((SigningFingerprints.observedAt eq c.observedAt) and
                            (SigningFingerprints.fingerprint eq c.fingerprint) and
                            (SigningFingerprints.owner greater c.owner)) or
                        ((SigningFingerprints.observedAt eq c.observedAt) and
                            (SigningFingerprints.fingerprint eq c.fingerprint) and
                            (SigningFingerprints.owner eq c.owner) and
                            (SigningFingerprints.repo greater c.repo))
                    clause = clause and seek
                }
                clause
            }
            .orderBy(
                SigningFingerprints.observedAt to SortOrder.ASC,
                SigningFingerprints.fingerprint to SortOrder.ASC,
                SigningFingerprints.owner to SortOrder.ASC,
                SigningFingerprints.repo to SortOrder.ASC,
            )
            .limit(limit + 1) // peek one extra to know if there's more
            .map {
                SigningSeedRow(
                    fingerprint = it[SigningFingerprints.fingerprint],
                    owner = it[SigningFingerprints.owner],
                    repo = it[SigningFingerprints.repo],
                    observedAt = it[SigningFingerprints.observedAt],
                    host = it[SigningFingerprints.host],
                )
            }

        val hasMore = rows.size > limit
        val pageRows = if (hasMore) rows.dropLast(1) else rows
        val nextCursor = if (hasMore && pageRows.isNotEmpty()) {
            val last = pageRows.last()
            PageCursor(last.observedAt, last.fingerprint, last.owner, last.repo)
        } else null

        SigningSeedPage(rows = pageRows, nextCursor = nextCursor)
    }

    open suspend fun upsertBatch(rows: List<SigningSeedRow>) {
        if (rows.isEmpty()) return
        newSuspendedTransaction(Dispatchers.IO) {
            upsertBatchInCurrentTransaction(rows)
        }
    }

    /**
     * Same INSERT … ON CONFLICT DO NOTHING as `upsertBatch`, but runs in
     * the caller's open transaction rather than opening a new one. Used by
     * seed workers that need to atomically pair the upsert with a
     * `pg_try_advisory_xact_lock` — the xact lock is released when its
     * containing transaction commits, so the upsert MUST share the same
     * transaction or the lock and the write race against each other.
     */
    fun upsertBatchInCurrentTransaction(rows: List<SigningSeedRow>) {
        if (rows.isEmpty()) return
        // ignore=true means "skip on PK conflict" -- the F-Droid ingester
        // re-runs daily and we don't need to update observed_at on rows
        // that were already seen. New (host, fingerprint, owner, repo) tuples
        // land normally; existing ones are no-ops.
        SigningFingerprints.batchInsert(rows, ignore = true) { row ->
            this[SigningFingerprints.host] = row.host
            this[SigningFingerprints.fingerprint] = row.fingerprint
            this[SigningFingerprints.owner] = row.owner
            this[SigningFingerprints.repo] = row.repo
            this[SigningFingerprints.observedAt] = row.observedAt
        }
    }

    open suspend fun isEmpty(): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        SigningFingerprints.selectAll().limit(1).empty()
    }

    data class SigningSeedPage(
        val rows: List<SigningSeedRow>,
        val nextCursor: PageCursor?,
    )

    data class PageCursor(
        val observedAt: Long,
        val fingerprint: String,
        val owner: String,
        val repo: String,
    ) {
        fun encode(): String {
            val raw = "$observedAt|$fingerprint|$owner|$repo"
            return Base64.UrlSafe.encode(raw.toByteArray()).trimEnd('=')
        }

        companion object {
            fun decode(token: String): PageCursor? = runCatching {
                val padded = token.padEnd(((token.length + 3) / 4) * 4, '=')
                val raw = Base64.UrlSafe.decode(padded).decodeToString()
                val parts = raw.split('|', limit = 4)
                if (parts.size != 4) return@runCatching null
                PageCursor(
                    observedAt = parts[0].toLong(),
                    fingerprint = parts[1],
                    owner = parts[2],
                    repo = parts[3],
                )
            }.getOrNull()
        }
    }
}
