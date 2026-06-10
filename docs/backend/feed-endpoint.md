# /v1/feed — anonymous discovery feed

Status: v1 shipped. Companion client work: feed-first home in
`OpenHub-Store/GitHub-Store` (For-You hub card + From-Your-Stars strip +
infinite feed; section-home kept as offline/error fallback layout).

## Contract

```
GET /v1/feed?platform={android|windows|macos|linux}&page={1..25}&limit={1..50}
```

- No auth. No `X-GitHub-Token` — clients MUST NOT attach it. The response is
  identical for every caller on the same `(platform, page, day)`, which is
  what makes the shared edge cache correct and keeps the surface anonymous.
- `platform` optional. Absent = all-platform mix. Client rule (locked with
  the client team): exactly-one platform selected → send it; `All` or
  multi-select → omit and filter cards locally by `availablePlatforms`.
- `page` defaults 1, clamps to 1..25. `limit` defaults 20, clamps 1..50.

Response:

```json
{
  "items": [ RepoResponse... ],
  "page": 1,
  "hasMore": true,
  "generatedAt": "2026-06-10T02:00:01Z",
  "rotation": "2026-06-10"
}
```

- `rotation` is the UTC date the feed was assembled for. Clients key their
  local cache on it (`feed:{platform}:{page}:{rotation}`) so a cached page
  never crosses a rotation boundary. The value is opaque — never parse it.
- `Cache-Control: public, max-age=900, s-maxage=3600`. The app's Ktor client
  has no HttpCache plugin, so `max-age` only serves browsers/proxies; the
  CDN obeys `s-maxage` and absorbs the day's traffic on ~25 pages × 5
  platform variants.

## Assembly pipeline (server)

Built once per `(platform, epochDay)` in `FeedService`, cached in-process,
served as slices. Restart-volatile: a fresh container rebuilds on first
request (4 indexed queries on a 12k-row table).

### 1. Candidate pools (`FeedRepository`)

| Pool | Filter | Order | Cap |
|---|---|---|---|
| TRENDING | `trending_score IS NOT NULL` | trending_score DESC | 100 |
| RELEASES | release within 14 days | latest_release_date DESC | 100 |
| GEMS | stars 50–800, release within 30 days, has search_score | search_score DESC | 100 |
| POPULAR | `popularity_score IS NOT NULL` | popularity_score DESC | 100 |

GEMS is the discovery valve: low-star but actively-released repos that
trending/popular would never surface. The star band excludes megaprojects
(covered by the other pools) and abandoned/empty repos.

### 2. Daily rotation

Within each pool, deterministic shuffle seeded `epochDay * 31 +
platform.hashCode()`. Quality tier is fixed by the SQL caps; the order
inside the tier rotates at midnight UTC. Platforms get independent seeds so
the four feeds aren't shifted copies of each other.

### 3. Mixing (`FeedAssembler`, pure function, unit-tested)

Interleave pattern `[TRENDING, RELEASES, GEMS, POPULAR, RELEASES, TRENDING,
POPULAR, GEMS]` cycled to a target of 500 items, with diversity windows:

- same owner ≥ 8 positions apart
- same primary topic (first canonical `topicCode`) ≥ 4 positions apart
- duplicate repo ids across pools dropped on first placement

Windows are measured in feed positions (position-indexed maps), not in
attribute-carrying placements — a deque of recent values deadlocks when an
attribute is sparse (one topic in the pools would block itself forever).

Termination: two full pattern cycles with zero placements = every queue is
empty or window-blocked → stop. Exhausted pools below target size are fine;
the feed is just shorter and `hasMore` goes false earlier.

## Privacy stance

Tier 0 only: every ranking input (stars, release dates, the scores computed
by `SignalAggregationWorker`) is server-side catalog data. The endpoint
reads no header that identifies the caller and writes nothing per-request.
Personalization, when it ships, happens on-device in the client against
this shared stream ("feed that learns you, without us ever knowing you").

## v1.5+ roadmap

- `feed/{platform}.json` published to `OpenHub-Store/api` by the fetcher
  cron so the client's offline waterfall covers the feed surface. Shape =
  this endpoint's page-1 response with a large `limit`.
- Topic-bucket candidate pool (one slot per canonical topic code) for wider
  long-tail coverage. Needs Kotlin-side bucketing since canonical codes are
  computed from raw topics at response time, not stored.
- Re-weight `SearchScore.compute()` around `download_count` (the dead
  ctr/install_success weights from the E6 telemetry kill). Separate change —
  blast radius includes search + categories.
- Optional opt-in anonymous install counters (Tier 1) if Tier-0 ranking
  quality proves insufficient. Strictly: no device id, daily-bucketed
  counters, path excluded from access logs, in-memory rate limit only.
