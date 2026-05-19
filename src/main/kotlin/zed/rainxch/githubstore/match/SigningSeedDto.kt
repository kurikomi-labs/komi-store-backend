package zed.rainxch.githubstore.match

import kotlinx.serialization.Serializable

@Serializable
data class SigningSeedRow(
    val fingerprint: String,
    val owner: String,
    val repo: String,
    // Epoch milliseconds. The client uses this as the `since` cursor on the
    // next sync; mixing units silently corrupts incremental fetches. The
    // V8 migration stores this as BIGINT for the same reason.
    val observedAt: Long,
    // V17: host the APK was distributed from. Defaults to "github.com" so
    // pre-V17 callers that omit the field on upsertBatch stay correct, and
    // clients that ignore the field on the seed dump keep working.
    val host: String = "github.com",
)

@Serializable
data class SigningSeedsResponse(
    val rows: List<SigningSeedRow>,
    val nextCursor: String? = null,
)
