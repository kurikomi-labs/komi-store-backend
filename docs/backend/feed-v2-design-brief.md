# FEED-V2 DESIGN BRIEF — Momentum + Memory Discovery Feed

Status: decision-ready, owner review required before implementation.
Author basis: research synthesis (ranking algorithms, velocity signal, GitHub API limits, rotation/coverage, analytics product) + verification against the live tree (backend feed subsystem, ranking, workers, migrations, Hikari config; Python fetcher `db_writer.py` + `fetch_all_categories.py`).
Scope: design document, not code. Nothing here is built yet. Package namespace is `zed.rainxch.githubstore`.

**Verification note (read first).** Two live-code facts shape this whole design and were checked, not assumed:

1. **`SearchScore.compute()` on `main` is the OLD additive signature** — `SearchScore.kt:17-26`, `compute(stars, ctr=0.0, installSuccessRate=0.0, daysSinceRelease)` → `0.40·starFactor + 0.30·ctr + 0.20·install + 0.10·recencyFactor`. The download-aware re-weight (PR #34) is **not yet merged to main**, so this is what the code computes. Called from the feed with only `(stars, daysSinceRelease)`, ctr/install are 0, so it collapses to `0.40·starFactor + 0.10·recencyFactor`, **capped at 0.50, with no download term**. Consequence: this brief does **not** route the popularity sub-term through `SearchScore.compute` (see §4b) — and that decision holds even after #34 merges.
2. **The fetcher writes neither `archived` nor `open_issues`** — `db_writer.py:77-140` `_upsert_repo`'s INSERT/ON CONFLICT/params carry `forks` and `download_count` (GREATEST-clamped, `:115`) but no `archived`, no `open_issues`. `open_issues` is set only by the three backend `/repo` ingest paths (V14), touching a fraction of the 12k catalog. So capturing those columns is **not free** and requires a Phase-0 fetcher change (§6, §10).

---

## 1. Problem statement & goals

### Why today's feed repeats

The feed is a memoryless top-N reshuffle. Each of the four candidate pools is a static `ORDER BY <score> DESC LIMIT 100` over *current* column values (`FeedRepository.kt:21-57`, shared `poolQuery` at `:59-105`):

- `trendingPool` → `WHERE trending_score IS NOT NULL ORDER BY trending_score DESC`
- `releasesPool` → `latest_release_date` within 14 days, `ORDER BY latest_release_date DESC`
- `gemsPool` → `stars BETWEEN 50 AND 800`, release within 30 days, `ORDER BY search_score DESC`
- `popularPool` → `WHERE popularity_score IS NOT NULL ORDER BY popularity_score DESC`

Four pools × 100 = ~400 candidates. The daily seed (`FeedService.kt:71`, `day.toEpochDay() * 31 + (platform?.hashCode() ?: 0)`) only drives `shuffled(rng)` inside `FeedAssembler.assemble` (`FeedAssembler.kt:42`, `targetSize = 500`). The seed **reorders the same ~400 repos; it never changes which repos are eligible.** Genuinely-good repos ranked 401st+ are permanently invisible because the SQL never `SELECT`s them.

Two compounding facts:
1. **No memory.** Nothing records what was shown, so there's no mechanism to demote a repo that appeared yesterday.
2. **Totals-only scoring.** `trending_score`, `popularity_score`, `search_score` are lagging popularity metrics computed from current totals. They favor old megaprojects and barely move day to day.

### Goals (priority order)

1. **Quality #1.** Never surface a repo below the bar (no installer for platform, no real signal, abandoned, archived). Quality is enforced *structurally* (a binary gate), not by weight-tuning.
2. **Engagement.** Surface what is *gaining momentum now* — the zero-telemetry leading signal — not just what is biggest.
3. **No-repeat.** A repo shown today should be demoted tomorrow so the feed walks the catalog.
4. **Coverage.** Over a rotation window, every eligible repo surfaces at least once; the very best may recur, junk never enters. (See §5 for what mechanism actually delivers this — cooldown alone does not.)

All four must hold **without breaking the byte-identical-per-(platform, page, UTC-day) CDN/anonymity invariant** (`Never reads X-GitHub-Token`, `max-age=900, s-maxage=3600`). Every new signal is computed off the request path and frozen into columns/snapshots before serving.

---

## 2. Core insight: rotation is free because the quality pool dwarfs one day's feed

The genuinely-good pool — installers for the platform, real stars **or** downloads, active release cadence, not archived — is estimated at **~1,000–3,000 repos** out of ~12k. One day's feed surfaces **~400–500** slots.

Because the quality pool (E ≈ 1–3k) is **much larger than one day's feed** (F ≈ 400–500), rotation and quality are **not in tension**. Memory (a global exposure ledger) lets the feed walk the quality pool across many days without dropping any repo below the bar — it changes *which* eligible repos get today's slots.

**Hard constraint on this claim (see §4c, §5, §11):** coverage of the long tail is only real if (a) the candidate fetch is sized to the eligible set, not to four 100-row pools, and (b) the cooldown term reads `shown_count` (least-frequently-shown), not just `last_shown` (least-recently-shown). A recency-only soft multiplier on a frozen base score does **not** round-robin a tail — simulation showed ~750/2000 repos permanently un-surfaceable that way. Both fixes are baked into this design.

---

## 3. The velocity signal

Velocity (rate of change) is the leading engagement proxy obtainable with **zero client telemetry**, from public GitHub counters the fetcher already pulls. It needs a daily time-series because GitHub exposes downloads **only as a running total** — there is no download-history endpoint in the REST API.

### Algorithm family choice

- **Age-decay rankers** (HN `(points−1)^0.8/(age+2)^1.8`; Reddit hot `log10(s)+t/45000`) score a static count against a *single submission age*. **Wrong for us** — repos have no single submission time; they live for years and re-release. Do not port these.
- **Velocity/momentum rankers** (GitHub Trending, OSSInsight "stars/day", star-history) compute a **delta over a rolling window**. **Correct family.** Trending's insight is *relative* velocity: a repo normally gaining 2★/day jumping to 10 is hotter than one going 50→60.

### Raw deltas (from `repo_daily_snapshot` — never from `repos.download_count`)

`repos.download_count` is GREATEST-clamped by the fetcher (`db_writer.py:115`), so a genuine downward correction reads as a 0 delta there. Velocity must difference raw observed totals stored in the snapshot table:

```
days_between = max(1, snapshot_date_today − snapshot_date_(N days ago))   // absorb missed cron days
star_delta   = max(0, stars_today − stars_(N days ago))
dl_delta     = max(0, download_count_today − download_count_(N days ago))
star_per_day = star_delta / days_between
dl_per_day   = dl_delta   / days_between
```

Floor deltas at 0: GitHub totals can drop (un-stars, deleted/re-tagged assets); a deletion is not negative momentum — same defensive posture as commit `adaa2c3`. Dividing by `days_between` stops a 2-day gap reading as doubled velocity.

### EWMA smoothing (recency-weighted, spike-resistant)

```
α = 1 − exp(−ln2 / halflife_days)
y_t = α·x_t + (1−α)·y_{t-1}
```

**Recommended half-life = 7 days → α ≈ 0.094.** Reacts within a week (feels "hot") but ignores single-day noise. This is the sharpest knob: too short (~2d) thrashes; too long (~30d) degenerates into a totals score. **Config-overridable, never inlined** (mirror the "weights live in the object only" convention). Persist `star_velocity_ewma` / `dl_velocity_ewma` so the read path never recomputes.

### Noise-shrinkage (small-denominator guard)

A velocity off a tiny base is noise (a 3★ repo gaining 2 is not hot). Two layers:

1. **Hard baseline gate:** a delta counts as momentum only when `stars ≥ 50 OR cumulative_downloads ≥ 500`. Below that, the repo scores on totals alone.
2. **Bounded relative-growth estimate** (only if a relative-growth term is actually wired into momentum — see below). To keep it a true fraction in `[0,1)`, the observed base goes in the denominator too:

```
g_shrunk = (star_delta + α0) / (star_delta + stars_(N days ago) + α0 + β0)
```

with `α0 = 5`, `β0 = 500`. At `delta=0, prev=0` → `5/505 ≈ 0.99%` baseline prior; as the repo surges, `g_shrunk → 1` rather than blowing past it. (The earlier form `(star_delta+α0)/(stars_prev+α0+β0)` was **not** bounded — a 10k delta on a 100-star base returned 16.5, defeating the shrinkage. Fixed here.)

**Wiring decision:** momentum (§4b) is driven by `norm(log1p(star_vel_ewma))` / `norm(log1p(dl_vel_ewma))` — absolute velocity, which is the GitHub-Trending primary signal. `g_shrunk` is **optional** and, if used, enters only as a small secondary "surge-ratio" multiplier; it is not a third additive term. Pick one path explicitly in Phase 1; do not compute `g_shrunk` and then never read it.

### Acceleration (optional secondary bonus)

`accel = max(0, velocity_recent_7d − velocity_prior_7d)`, floored at 0, weighted small (~0.15 of the momentum sub-term). Rewards repos speeding up without overriding absolute momentum.

### Normalization

Normalize **each of stars/downloads separately, over the eligible pool only** (whole-catalog junk would pollute the distribution):

1. `log1p(per_day)` — compress heavy tails.
2. Robust **median/MAD z-score** (mean/stddev is fragile for heavy tails; guard `MAD==0` with epsilon), clamp `z` to ±3.
3. Logistic squash `1/(1+exp(−z))`.

**Range caveat (load-bearing for §4b weights):** logistic of a ±3-clamped z spans only **[0.047, 0.953]**, never 0 or 1. State the real range and rescale to [0,1] (`(s − 0.047)/(0.953 − 0.047)`) so the declared engagement weights are the *effective* weights, not silently compressed ones.

Optional GitHub-Trending booster: divide absolute velocity by the repo's own slow baseline EWMA (long ~30d half-life) for a "surge ratio", applied as a **secondary** multiplier; `log1p(absolute velocity)` stays primary so steady momentum stays in the driver's seat.

---

## 4. The full feed-v2 scoring model

Structure is **multiplicative**, not the additive weighted-sum of today's `SearchScore`. A multiplicative quality *gate* makes quality structurally #1: no velocity spike can carry an ineligible repo in. (An additive blend lets one strong term mask a failed one — exactly the 1M-star *archived* repo with a stale release scoring high.)

```
final_rank_key = quality_gate(repo) × engagement_score(repo) × cooldown_factor(repo)
```

### 4a. Eligibility quality-gate (binary, from TOTALS)

Totals belong in the gate (quality is slow-moving; lagging is correct here). Added to each pool's `WHERE`:

| Predicate | Column(s) | Note |
|---|---|---|
| Has installer for platform | `has_installers_{android,windows,macos,linux}` | existing; `platform=null` ⇒ any one true |
| Real signal | `stars > 0 OR download_count > 0` | existing |
| Actively released | `latest_release_date IS NOT NULL` | existing |
| Not stale | `latest_release_date > NOW() - INTERVAL '<gate>'` | gate window owner-decided (proposed 365d) |
| **Not archived** | `archived = FALSE` | **NEW COLUMN; fetcher must populate it (Phase-0, §6/§10) — until then every repo reads not-archived and archived megaprojects leak in** |

`quality_gate ∈ {0,1}`. A repo failing any predicate is never `SELECT`ed; its `final_rank_key` is undefined. This is what makes "junk never enters" structural rather than a tuning artifact.

### 4b. engagement_score = momentum + popularity + recency

Computed only for eligible repos. **Each sub-term must actually span [0,1]** (the §3 rescale handles momentum; popularity and recency are defined natively in [0,1] below — note the deliberate avoidance of `SearchScore.compute`):

```
engagement_score =
    0.45 · momentum     // rescaled EWMA velocity — the LEAD signal
  + 0.30 · popularity   // log-normalized stars+downloads totals, NOT SearchScore.compute
  + 0.25 · recency      // exp(−days_since_release/90)
```

where

```
momentum   = 0.55·rescale(norm(log1p(star_vel_ewma))) + 0.45·rescale(norm(log1p(dl_vel_ewma)))   // (+ optional surge/accel booster)
popularity = 0.5·(log1p(stars)/log1p(STAR_NORM)) + 0.5·(log1p(downloads)/log1p(DL_NORM))          // clamp each to [0,1]
recency    = exp(−days_since_release / 90)                                                          // floor days at 0 (clock-skew)
```

**Why popularity is computed here, not via `SearchScore.compute`:** the live `compute(stars, daysSinceRelease)` (a) caps at 0.50 (ctr/install=0), so a 0.30 weight would only ever contribute ≤0.15; (b) re-injects a 0.10·recency term that **double-counts** the 0.25 recency sub-term; (c) carries no download term at all. Defining popularity natively from `log1p(stars)`/`log1p(downloads)` fixes all three. `STAR_NORM`/`DL_NORM` are calibration constants (the pool's high percentile) living in `VelocityScore.kt`. **Recency is counted exactly once** — only the 0.25 term.

**Weight rationale:**
- **momentum 0.45 (lead):** the whole point of v2 — leading, non-lagging, telemetry-free. Must dominate so the feed answers "what's hot now."
- **popularity 0.30:** a quality floor *inside* the ranking (not just the gate) so a velocity blip on a tiny repo can't out-rank a steadily-excellent project. Keeps ordering defensible ("why is this 60-star repo above that 5000-star one").
- **recency 0.25:** rewards active release cadence; `exp(−days/90)` is already grounded and clamped in `SearchScore.daysSinceRelease()` (reuse that helper for the day computation only, not the scoring).

**Effective-weight check (do before tuning):** after each term spans a true [0,1], confirm the realized contributions match the intended 0.45/0.30/0.25. The original draft's terms ranged [0.047,0.953]/[0,0.50]/[0,1], which would have made recency quietly out-weigh popularity — the rescale + native popularity above is what prevents that. Add a unit test asserting each sub-term's min/max.

**Momentum split (0.55 star / 0.45 dl):** stars are the cleaner, universally-present signal; downloads are powerful but **cold for the first ~7–14 days** after the snapshot table ships (§3, §6) and noisier (asset churn). Lean on stars until download history accrues. Keep `dl_vel` modest also because download velocity correlates with release recency (already weighted in recency + the releasesPool) — avoid triple-counting one release event.

### 4c. cooldown_factor (global rotation memory)

A **separate** multiplier on the sort key — orthogonal, composable, gentle. **It reads `shown_count`, not only `last_shown`** (a recency-only multiplier cannot round-robin a tail — §2, §5):

```
recency_term   = 1 − exp(−days_since_last_shown / TAU)                  // ∈ [0,1)
frequency_term = 1 / (1 + shown_count_in_window)                        // least-frequently-shown
cooldown_factor = FLOOR + (1 − FLOOR) · (0.5·recency_term + 0.5·frequency_term)
```

with `FLOOR = 0.5`, `TAU = 4.5` (≈14-day recency recovery), `shown_count_in_window` = exposures in the trailing coverage window. The frequency term is what guarantees an unshown tail repo eventually out-ranks a repeatedly-shown elite; recency alone does not.

| state | recency_term | frequency_term | factor (FLOOR=0.5) |
|---|---|---|---|
| shown today, shown 10× this window | ~0 | ~0.09 | ~0.52 |
| shown 4.5d ago, 3× | ~0.63 | 0.25 | ~0.72 |
| never shown | 1.0 | 1.0 | 1.00 |

`cooldown_factor` only ever scales an already-eligible repo within `[FLOOR, 1]`; it can never lift an ineligible repo in. Centralize in a new `ranking/CooldownFactor.kt` (mirror `SearchScore`'s single-source pattern — never inline `exp()` in SQL/Kotlin copies). `FLOOR`/`TAU`/window env-overridable (mirror `TOKEN_QUIET_START_UTC` style).

**What FLOOR=0.5 actually guarantees (corrected):** a demoted repo is never scaled by less than 0.5×, so it stays ahead of any peer within **2× of its base**. It does **not** guarantee a demoted-excellent repo outranks every mediocre peer — `base_excellent·0.5 ≥ base_mediocre·1.0` only when `base_excellent ≥ 2·base_mediocre`. That is intended rotation behavior, not a violated invariant; do not sell FLOOR as absolute quality preservation.

**Critical implementation requirement — apply cooldown to base, not to momentum (most likely subtle bug):** multiplying the *whole* engagement_score (which contains the decaying momentum term) by cooldown means a genuine ongoing surge fights its own exposure penalty, and a repo can oscillate (spike→shown→demoted-while-decaying→drops out→never returns once momentum is stale). Apply cooldown to **`base_score = 0.30·popularity + 0.25·recency` only**, leaving momentum un-penalized, so an ongoing surge stays visible while steady-quality repos rotate. State this steady-state explicitly: the long tail is surfaced by the **popularity+recency base × frequency-cooldown**, and momentum is the short-lived "what's hot" overlay on top.

**And widen the candidate fetch** — cooldown is useless if the SQL still `SELECT`s only the static top-100 (demoted repos at rank 101+ are never selected). See §4d.

### 4d. Candidate selection must cover the eligible set, not four capped pools

Coverage is bounded by `union(pool top-N)`, not by E. Four pools × `LIMIT 400` = ≤1600 rows, still short of E up to 3000 — any eligible tail repo outside every pool's pre-sort is never selected and cooldown can't reach it. **Replace the four capped pre-sorts with a single eligible-set query** (the 4a `WHERE` gate, no `LIMIT` below E, or `LIMIT` ≥ E), then re-rank the whole eligible set by `final_rank_key` in Kotlin and take the assembler's `targetSize`. Pool *labels* (trending/releases/gems/popular) can be retained as assembler diversity hints derived from the single result set, but selection eligibility is the gate, not four separate top-N slices.

---

## 5. Coverage: best-effort, not a closed-form guarantee

Definitions: E = eligible pool, F = distinct daily slots (~400–500), r = fraction recaptured by recently-shown high-score repos.

- **Idealized round-robin floor** = `E/F` days → E=2000 ⇒ ~5 days; this assumes a rotation that *actually cycles the whole pool*.
- **The `E/(F·(1−r))` arithmetic is correct** (E=2000, F=400, r=0.5 ⇒ 10.0; r=0.3 ⇒ 7.1) **but only holds with the §4c frequency term and §4d full-eligible-set selection.** A FLOOR=0.5 *recency-only* multiplier on a frozen base does **not** cycle the tail: simulation (E=2000, F=400, TAU=4.5) reached full coverage in **0 of 60–120 days**, with ~750/2000 repos mathematically un-surfaceable; FLOOR=0 still only reached 1512/2000. **This is why §4c uses least-frequently-shown and §4d selects the whole eligible set.** With those, treat coverage as **best-effort, measured empirically** — not a closed-form guarantee.
- **Elite recurrence (empirical, not `ceil(TAU·ln2)`):** true elites (base > ~2× the cutoff) recur **daily** even at the floor — desired, the best stay visible. Repos near the cutoff recur with a gap that **grows as their margin to the cutoff shrinks**; there is no clean closed form. (The earlier `ceil(TAU·ln2) ≈ 3–4 days` was the time for the recency factor to reach the midpoint of [FLOOR,1], not a recurrence interval — dropped.)

**Velocity self-rotation:** momentum decays by construction (release spikes fade `exp(−days/90)`, star bursts are transient), so tomorrow's top-by-velocity is naturally a different set. But velocity self-rotation and cooldown are **not independent and compound multiplicatively** — a just-spiked repo is both high-momentum and likely recently-shown, so cooldown would demote exactly what momentum promoted. Applying cooldown to **base only** (§4c) resolves this: velocity drives the short-term "hot" churn; the frequency-cooldown on the base drives long-tail coverage. Add a Phase-1 metric for **"repos surfaced exactly once in the last 30d"** to catch one-shot-then-vanish, plus a coverage metric (distinct repos surfaced in last 14d / eligible pool size).

---

## 6. Data capture plan

### Phase-0 prerequisite (fetcher) — irreplaceable, do FIRST

The time-series is irreversible; columns not captured from day one cannot be backfilled. **Add `open_issues` and `archived` to `db_writer.py:77-140` `_upsert_repo`** (the GitHub repo JSON the fetcher already fetches carries `open_issues_count` and `archived`):
- Add to the INSERT column list, the `ON CONFLICT DO UPDATE SET` list, and the params dict — alongside the existing `forks`/`download_count`.
- Surface `openIssuesCount` and `archived` on the fetcher's repo object (`fetch_all_categories.py`) up to `db_writer`.

Until this lands, the snapshot's `open_issues` captures the V14 default (stale/zero) for most repos, and the §4a `archived` gate treats every repo as not-archived. Do **not** defer this as a "post-hoc backfill" — there is no backfill for a time-series gap.

### DDL — V19 + V20 (no-Flyway idempotent convention)

Migrations run via `DatabaseFactory.runMigrations()`, **one transaction per migration**, with `IGNORABLE_MIGRATION_SQLSTATES` (42P07/42701/…) swallowing re-runs. **Every statement carries `IF NOT EXISTS`** — table-level skip does not cover indexes if a first apply partially failed (this is stronger than V18's bare `ADD COLUMN`, and correct). Active migrations list ends at `V18__pushed_at.sql` (`DatabaseFactory.kt:81-100`); next free numbers are **V19/V20** (V7/V8/V10 intentionally delisted). Both files **must be appended to that list** or they never run.

```sql
-- V19__feed_exposure.sql
-- Global (NOT per-user) rotation ledger for /v1/feed. Keyed on repo_id+platform
-- only — no device/user/IP — so it is fully compatible with the anonymous,
-- CDN-shared, byte-identical-per-(platform,day) invariant. Read at snapshot-build
-- time for least-recently AND least-frequently-shown cooldown.
CREATE TABLE IF NOT EXISTS feed_exposure (
    repo_id             BIGINT      NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    platform            TEXT        NOT NULL DEFAULT 'all',
    last_shown_epochday INTEGER,
    shown_count         INTEGER     NOT NULL DEFAULT 0,
    last_position       INTEGER,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repo_id, platform)
);
CREATE INDEX IF NOT EXISTS idx_feed_exposure_lookup
    ON feed_exposure (platform, last_shown_epochday);
```

```sql
-- V20__repo_daily_snapshot_and_archived.sql
-- Daily time-series of cumulative public GitHub counters, one row per repo per
-- UTC day. Velocity (stars/day, downloads/day) is derived by differencing
-- consecutive rows — GitHub has no download-history endpoint. RAW cumulative
-- values only (NOT pre-computed deltas) so the table dual-serves the future paid
-- maintainer-analytics dashboard without re-capture. Append-only per (repo_id, date).
CREATE TABLE IF NOT EXISTS repo_daily_snapshot (
    repo_id              BIGINT      NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    snapshot_date        DATE        NOT NULL,
    stars                INTEGER     NOT NULL,
    download_count       BIGINT      NOT NULL,
    forks                INTEGER,
    open_issues          INTEGER,
    latest_release_date  TIMESTAMPTZ,
    star_velocity_ewma   REAL,
    dl_velocity_ewma     REAL,
    captured_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repo_id, snapshot_date)
);
CREATE INDEX IF NOT EXISTS idx_repo_daily_snapshot_repo_date
    ON repo_daily_snapshot (repo_id, snapshot_date DESC);   -- trailing-window velocity reads
CREATE INDEX IF NOT EXISTS idx_repo_daily_snapshot_date
    ON repo_daily_snapshot (snapshot_date);                 -- dashboard "all repos on day X"

ALTER TABLE repos ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_repos_archived ON repos (archived) WHERE archived = FALSE;
```

`forks`/`open_issues` are stored even though the feed ignores them — they unlock dashboard charts with zero re-capture (provided the §6 Phase-0 fetcher change populates `open_issues`). The partial index `WHERE archived = FALSE` is intentional, matching the gate's `archived = FALSE` filter.

### Where the snapshot is written

A new function in `db_writer.py`, called from `fetch_all_categories.py main()` **after all `save_to_postgres` calls complete**, runs ONE catalog-wide statement:

```sql
INSERT INTO repo_daily_snapshot
    (repo_id, snapshot_date, stars, download_count, forks, open_issues, latest_release_date)
SELECT id, (CURRENT_DATE AT TIME ZONE 'UTC'), stars, download_count, forks, open_issues, latest_release_date
FROM repos
ON CONFLICT (repo_id, snapshot_date) DO UPDATE SET ...;
```

Zero extra GitHub calls (reads persisted values), covers all ~12k repos (load-bearing for "walk the whole quality pool"), idempotent per UTC day.

**Snapshot-of-record policy (decide explicitly):** the 02:00 full run refreshes all ~12k; the every-3h run only refreshes ~400 hot repos. With `ON CONFLICT DO UPDATE`, the every-3h run would overwrite the day's row for those hot repos with a later same-day value. **Recommended: capture ONLY from the 02:00 full run** (skip the snapshot step in the 3h cadence) so every row reflects a full-catalog refresh and day-over-day deltas compare like-for-like. If `DO UPDATE` is kept across cadences instead, document that hot repos get a last-run-of-day value while the rest keep the 02:00 value — acceptable for velocity, but state it.

**Concurrency note (corrected attribution):** this `INSERT…SELECT` runs in the **Python fetcher (psycopg2)**, not the Ktor app — so Hikari's `TRANSACTION_REPEATABLE_READ` (`DatabaseFactory.kt:36`) does **not** govern it. The active Kotlin isolation level (REPEATABLE_READ, not SERIALIZABLE) matters only for the Kotlin workers in §8. Sequence the snapshot strictly after the fetcher commits its write phase; if any Kotlin path ever performs a catalog-wide INSERT…SELECT, wrap it in a short transaction with a bounded retry on `40001` (serialization_failure — not in `IGNORABLE_MIGRATION_SQLSTATES`, surfaces as a normal runtime error).

### Retention — commit a dashboard-safe default NOW

12k × 1 row/day ≈ **4.4M rows/year** — trivially indexable but unbounded. A sweep (clone `RetentionWorker.kt:26-93`, but `WHERE snapshot_date < …`) prunes. The feed needs only ~90d; the paid dashboard wants long history, and **download velocity has no backfill — pruned history is gone forever.** **Commit the dashboard-safe default in Phase 0: keep daily rows ≥ 730d** (optionally roll up to weekly beyond). A Phase-0 deploy must NOT ship the retention worker with the feed's 90d default or it silently destroys dashboard history before the dashboard exists.

### Stargazers backfill (cold repos, optional, one-shot operator job only)

For cold/new small repos where star history is wanted before N days accrue: `GET /repos/{o}/{r}/stargazers` with `Accept: application/vnd.github.star+json`, `per_page=100`, returns `{starred_at}` per star. **Cost = `ceil(stars/100)` requests/repo** — the primary, independently-sound reason not to backfill big repos. GitHub paginates stargazers and historically caps deep pagination around ~40k results; treat any per-repo backfill as **best-effort** and never rely on it for the catalog's largest repos. A full-catalog star backfill is hundreds-of-thousands-to-millions of requests — **never a recurring job.** Run it as a **standalone operator script that rotates the GH_TOKEN pool, modeled on the existing `backfill_downloads.py`** — NOT through `coldQueryGate` (that is a request-path `Semaphore(8)` in `GitHubSearchClient`; borrowing it would starve live search). **Downloads have no backfill at all** — download velocity is genuinely cold for the first N days after the table ships; this is the load-bearing constraint on rollout order (§10).

---

## 7. `feed_exposure` + the daily exposure-write

Keyed on `(repo_id, platform)` because the four platform feeds rotate independently (`FeedService` seeds per-platform). `shown_count` feeds the §4c frequency term and the future dashboard. Global by construction — **never add `device_id` or any per-caller key** (would shatter the shared cache and leak identity).

### Where the write fires — and the request-path problem

Today `FeedService.feedFor` (`FeedService.kt:56-97`) builds lazily on first request, caches in a `ConcurrentHashMap`, and rebuilds every 3h (`REFRESH_INTERVAL_MS`, `:110-113`) under `buildLock` (`:60`). There is **no background worker driving it.** Two consequences the write must handle:

1. **Never-requested platforms stall.** A low-traffic platform not requested on a given UTC day never builds a snapshot, so no exposure is written and its cooldown never ages — coverage silently freezes for that platform.
2. **Record what's PLACED, not what's SELECTed.** `FeedAssembler.assemble` (`FeedAssembler.kt:32-71`) drops candidates that fail owner≥8 / topic≥4 diversity windows, so a selected repo may never be placed. **Exposure = placed in the assembled `items` list**, not the raw eligible set — recording un-placed candidates would mark repos "shown" that nobody saw.

**Recommended fix:** move snapshot build + exposure write into a small scheduled worker (cron-like, **once per UTC day per platform**, started from `Application.kt` like the other workers), keeping `FeedService` a pure read cache. This decouples exposure from request traffic and fixes the never-requested-platform stall. If request-triggered build is kept instead, add a daily sweep that ages cooldown for un-requested platforms, and document exposure = "placed."

### The write

`FeedRepository.recordExposure(platform, placedIds)` — raw-JDBC batch upsert over the assembler's **result list**:

```sql
INSERT INTO feed_exposure (repo_id, platform, last_shown_epochday, shown_count)
VALUES (?, ?, :today, 1)
ON CONFLICT (repo_id, platform) DO UPDATE
SET last_shown_epochday = EXCLUDED.last_shown_epochday,
    shown_count         = feed_exposure.shown_count + 1
WHERE feed_exposure.last_shown_epochday < EXCLUDED.last_shown_epochday;
```

**Idempotency across the 3h rebuild is mandatory.** The rebuild keeps the daily seed; the `WHERE last_shown_epochday < today` guard fires the write only on the **first build of a UTC day** — otherwise `shown_count` inflates ~8×/day and over-penalizes. (A scheduled once-per-day worker makes this trivial.)

**Intraday set-stability:** the daily seed is held across 3h rebuilds so order stays stable while fresh releases fold in. Cooldown may change the *selected set* **only on the first build of the day**; intraday rebuilds must stay set-stable, or pagination tears mid-day.

---

## 8. Backend integration map (verified against `zed.rainxch.githubstore`)

| Concern | Location | Change |
|---|---|---|
| Migration list | `DatabaseFactory.kt:81-100` | Append `"V19__feed_exposure.sql"`, `"V20__repo_daily_snapshot_and_archived.sql"` after `"V18__pushed_at.sql"` |
| Pool → eligible-set selection | `FeedRepository.kt:21-57` + `poolQuery` (`:59-105`) | Replace four capped pre-sorts with a single eligible-set query (4a gate); JOIN `repo_daily_snapshot` for velocity; LEFT JOIN `feed_exposure` for cooldown; re-rank by `final_rank_key`, take `targetSize` (§4d) |
| Velocity math | new `ranking/VelocityScore.kt` (object, mirrors `SearchScore`) | EWMA + shrinkage + rescaled-normalization + native popularity params centralized; **do NOT touch `SearchScore.kt:16-37`** (it feeds `/search` + `/categories`) |
| Cooldown math | new `ranking/CooldownFactor.kt` (object) | `factor(daysSinceShown, shownCount, FLOOR=0.5, TAU=4.5)`; env-overridable; applied to base, not momentum (§4c) |
| Snapshot capture (catalog-wide) | Python `db_writer.py` new fn, called from `fetch_all_categories.py main()` after save loop | `INSERT…SELECT … ON CONFLICT (repo_id, snapshot_date) DO UPDATE`; capture from 02:00 run only (§6) |
| **Fetcher column add (Phase-0)** | `db_writer.py:77-140` `_upsert_repo` + `fetch_all_categories.py` repo object | Add `open_issues`, `archived` to INSERT + ON CONFLICT + params; surface `openIssuesCount`/`archived` up the call chain |
| Velocity EWMA worker | new `ingest/VelocityAggregationWorker.kt` (clone `SignalAggregationWorker.kt:206-267`: chunkSize 1000, batched UPDATE) | **advisory lock `911_007L`** (911_001–911_006 are all taken; comment "Distinct from … so the workers run concurrently" like `ForgejoFdroidSeedWorker.kt:65-67`); reads trailing snapshots, writes `star/dl_velocity_ewma` |
| Snapshot retention worker | new `ingest/SnapshotRetentionWorker.kt` (clone `RetentionWorker.kt:26-93`) | **advisory lock `911_008L`**; sweep `WHERE snapshot_date < …`; default keep ≥730d (§6) |
| Daily feed-build/exposure worker | new worker started from `Application.kt` (§7 recommended option) | Once-per-UTC-day-per-platform build + `recordExposure(placedIds)`; own advisory lock (next free, e.g. `911_009L`) |
| Worker DI | `AppModule.kt` (`single { … }` block) | Register the new workers |
| Worker start | `Application.kt` (existing `inject` + `.start()` pattern) | Start each new worker |
| Exposure write | `FeedService.kt:74-95` (or the new daily worker) | After `assemble`, day-rollover-gated, call `recordExposure` over the **placed** items |
| Assembler tuning | `FeedAssembler.kt:32-71` | `targetSize` (500) + diversity windows may need retuning once selection covers the full eligible set |
| Feed response mapper | `FeedRepository.kt:107+` (`toRepoResponse`) | If velocity is surfaced to clients, carry it in the feed's own mapper only — the five-place rule does NOT apply (feed is Postgres-only, not Meili) |
| Route | `FeedRoutes.kt`, `Routing.kt` | **No change.** Exposure-write must NOT happen per-request (CDN-cached) |

`Repos` Exposed object (`Tables.kt`) gets `val archived = bool("archived").default(false)`. `repo_daily_snapshot` and `feed_exposure` get **no** Exposed object — both raw-JDBC (bulk upsert / targeted update), following the `oauth_ephemeral` precedent and the feed's existing raw-JDBC style. (Advisory-lock IDs verified: SignalAggregationWorker 911_001, RepoRefreshWorker 911_002, RetentionWorker 911_003, FdroidSeedWorker 911_004, MirrorStatusWorker 911_005, ForgejoFdroidSeedWorker 911_006 — grep `911_0` before assigning any new one.)

---

## 9. DUAL-USE: paid maintainer analytics dashboard

The same `repo_daily_snapshot` series powers a future **paid maintainer analytics product** — provided the capture schema is right **now**, so no later migration is needed.

**Metrics unlocked** (all public-data, privacy-clean):
- Per-repo star & download velocity charts over time (the raw series is exactly this).
- Release-impact curves (`download_count` delta around `latest_release_date`).
- Category/cohort benchmarks & percentiles across the 12k catalog.
- Multi-signal trends (forks, open_issues) — **conditional on the §6 Phase-0 fetcher change** that actually populates `open_issues` catalog-wide; without it the open_issues series is near-useless and unrecoverable.

**API surface sketch** (separate from the anonymous feed, **distinct cache** to protect feed anonymity):
- `GET /v1/analytics/repo/{owner}/{name}/history?metric=stars|downloads&window=` — keyed auth.
- `GET /v1/analytics/repo/{owner}/{name}/release-impact`
- `GET /v1/analytics/benchmark/{category}?metric=` — cohort percentiles.

**Decisions to make NOW (irreversible):**
1. **Raw cumulative totals, never deltas** — deltas are a query/view concern. (In the DDL.)
2. **Keep `forks` + `open_issues`** in the snapshot. (In the DDL; `open_issues` requires the Phase-0 fetcher change.)
3. **Per-version download granularity — the one irreversible cardinality decision.** `fetch_all_categories.py:434` sums `asset.download_count` across all releases into one repo total and discards per-asset detail (numeric `release_id`/`asset_id` never reach `db_writer`). Capturing per-version is **zero extra API cost** (the fetcher already iterates every asset) but **irreversible if skipped**. If adopted, build a **separate** `release_asset_snapshot (repo_id, release_id, asset_id, snapshot_date, download_count, …)` keyed on numeric IDs (tags are mutable), `INSERT … ON CONFLICT DO NOTHING`, with **its own retention/rollup budget** — it is high-cardinality (a 100-release × ~5-asset repo = ~500 rows/repo/day; across 1–3k release-heavy repos that is **millions of rows/day**, dwarfing the 4.4M/yr `repo_daily_snapshot` estimate). To bound it: snapshot assets only for releases within a recent window (e.g. last N releases / the analytics window), not every release ever. Requires a fetcher data-plumbing change to surface `release_id`+`asset_id`, not just a table. **Owner decision (§10).**
4. **Retention long enough for the product** — committed in §6 (≥730d), set once.

Do **not** reuse the legacy `repo_stats_daily` table (`V1__initial_schema.sql`) — dead telemetry.

---

## 10. Rollout phases & open product decisions

### Phases (capture-first — history must accrue before scoring can use it)

**Phase 0 — Capture + fetcher prerequisites (ship first, let it bake).**
- **Fetcher:** add `open_issues` + `archived` to `db_writer.py` `_upsert_repo` and surface them in `fetch_all_categories.py` (irreplaceable — §6).
- Ship V19 + V20. Wire the catalog-wide snapshot `INSERT…SELECT` (02:00-only). Backfill `archived` via the fetcher run + the three backend ingest paths (search passthrough, `POST /refresh`, `RepoRefreshWorker` — V14 multi-site pattern).
- Ship `SnapshotRetentionWorker` with the **≥730d** default (NOT 90d).
- **Feed scoring unchanged** — still totals-based pools. Let the snapshot accrue **~7–14 days** before anything reads it. (Download velocity is genuinely cold until then; no backfill.)

**Phase 1 — Feed-v2 scoring.**
- Add `VelocityScore`/`CooldownFactor` + the velocity worker. Replace pool selection with the eligible-set query + `final_rank_key` re-rank (§4d). Add `feed_exposure` + the day-gated, placed-only exposure write (§7 — prefer the scheduled worker).
- Fallback: `final_rank_key` must `COALESCE` to the static score when a repo has <2 snapshots, or the feed empties for the first week / buries newly-ingested repos.
- Metrics on `/internal/metrics`: coverage (distinct surfaced in 14d / eligible), **"surfaced exactly once in 30d"** (one-shot-then-vanish), top-decile elite-dominance.
- Unit tests (`SearchScoreTest.kt` template): `CooldownFactor` (FLOOR at d=0 + high shown_count, monotonic recovery, never-shown→1.0); each engagement sub-term spans true [0,1]; recency counted once. **Coverage simulation test:** assert the ACTUAL number against an N-synthetic-day run over a 2000-repo pool — and note it will **fail** with a recency-only cooldown on a frozen base (that's the regression guard for the §4c/§4d fixes).

**Phase 2 — Paid dashboard.**
- Build the analytics API on the matured series. If per-version curves are in scope, the §9.3 `release_asset_snapshot` table + fetcher plumbing must have shipped in Phase 0.

### Open product decisions for owner sign-off

1. **Quality-gate thresholds.** Stale window (proposed 365d); min-signal (`stars>0 OR download_count>0`); baseline momentum gate (`stars≥50 OR downloads≥500`).
2. **engagement_score weights.** momentum 0.45 / popularity 0.30 / recency 0.25; momentum split 0.55 star / 0.45 dl — confirm after the effective-weight check (§4b).
3. **EWMA half-life.** 7 days (config, not inlined).
4. **Cooldown FLOOR / TAU / window** and the **base-only application** (§4c). 0.5 / 4.5 / 14d proposed.
5. **Snapshot retention.** ≥730d committed in §6 — confirm.
6. **`release_asset_snapshot` on day one — yes/no** (§9.3; the one irreversible per-version decision, with its own row budget).
7. **Selection sizing** (§4d): single eligible-set query vs capped pools.

---

## 11. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| **Coverage doesn't hold** — recency-only cooldown on a frozen base buries the tail (~750/2000 un-surfaceable in sim) | CRITICAL | §4c frequency term (least-frequently-shown reading `shown_count`) + §4d full-eligible-set selection; §5 downgraded to best-effort + coverage unit test |
| **Cooldown does nothing because SQL `SELECT`s only top-100/400** — rank-tail never climbs in | FATAL (most likely mistake) | §4d single eligible-set query (≥E), re-rank by `final_rank_key`, take `targetSize` |
| **popularity sub-term broken** — `SearchScore.compute` caps at 0.50, double-counts recency, ignores downloads | HIGH | §4b computes popularity natively from `log1p(stars)`/`log1p(downloads)`; do NOT call `SearchScore.compute`; recency counted once |
| **Declared engagement weights ≠ effective weights** — logistic spans [0.047,0.953], not [0,1] | HIGH | §3 rescale each normalized term to [0,1]; §4b effective-weight check + unit test |
| **Fetcher writes neither `archived` nor `open_issues`** → gate leaks archived repos; dashboard open_issues series useless & unrecoverable | HIGH | Phase-0 fetcher change to `db_writer.py` _upsert_repo (§6) — prerequisite, not backfill |
| **Per-user exposure breaks CDN-shared cache + anonymity** | CRITICAL | `feed_exposure` global, keyed `(repo_id, platform)`; never add device/IP/token |
| **Exposure write tied to request path** → never-requested platforms stall; un-placed candidates marked shown | HIGH | §7 scheduled daily worker (or daily sweep); record PLACED items only |
| **Reading velocity off `repos.download_count`** (GREATEST-clamped) masks downward corrections as 0 delta | HIGH | Velocity reads raw values from `repo_daily_snapshot` only |
| **Advisory-lock ID collision** — 911_004/911_005 already used by FdroidSeedWorker/MirrorStatusWorker | HIGH | Use 911_007 (velocity) / 911_008 (retention) / next-free for the feed worker; grep `911_0` before assigning |
| **No history at launch** — velocity undefined until ≥2 snapshots | HIGH | Capture-first; `COALESCE` to static score when <2 snapshots; recency carries brand-new releases |
| **Double-count across 3h rebuild** inflates `shown_count` ~8× | HIGH | Day-rollover-gated write (`WHERE last_shown_epochday < today`); a once-daily worker removes the hazard |
| **Retention default prunes dashboard history** before the dashboard exists | HIGH | Ship `SnapshotRetentionWorker` at ≥730d in Phase 0, never 90d |
| **release_asset_snapshot cardinality** — millions of rows/day | MEDIUM | If adopted: bound to recent-release window + own retention/rollup budget (§9.3) |
| **Cooldown vs velocity compound** — cooldown demotes what momentum just promoted; one-shot-then-vanish | MEDIUM | Apply cooldown to base (popularity+recency) only, not momentum (§4c); Phase-1 "surfaced once in 30d" metric |
| **Missed cron days** double apparent velocity | MEDIUM | Divide deltas by actual `days_between`; EWMA damps |
| **Total drops** (un-star, deleted assets) → negative velocity | MEDIUM | Floor deltas at 0, mirroring `adaa2c3` |
| **EWMA half-life mistuned** | MEDIUM | Config-overridable; start 7d; measure feed churn |
| **Small-denominator spikes** | MEDIUM | Hard baseline gate + bounded `g_shrunk` (base in denominator, §3) |
| **Read-path recomputes anything time-dependent** → response drifts intraday, breaks byte-identical CDN | MEDIUM | All velocity/EWMA frozen into columns by the worker; cooldown changes the SET only on first build of the day |
| **Snapshot unbounded growth** (~4.4M rows/yr) | LOW-MED | Retention sweep (`snapshot_date <`); weekly rollup beyond the daily window |
| **Snapshot capture races the fetcher** | LOW | Runs in psycopg2 after the fetcher's write phase — **not** governed by Ktor's REPEATABLE_READ; any Kotlin catalog-wide INSERT…SELECT must use a short txn + bounded 40001 retry |
| **Migration created but not listed** → never runs | LOW | Append V19/V20 to `DatabaseFactory.kt:81-100` |
| **Stargazers backfill borrows request-path `coldQueryGate`** → starves live search | LOW | One-shot operator script rotating the GH_TOKEN pool, modeled on `backfill_downloads.py`; best-effort, never for >~40k-star repos, never recurring |

---

### Note on `SearchScore.compute()`

The live `SearchScore.compute()` (`SearchScore.kt:17-26`) is the **old additive signature** (`ctr`, `installSuccessRate` defaulting to 0.0; `0.40·star + 0.30·ctr + 0.20·install + 0.10·recency`); its KDoc ("Weights sum to 1.0") is stale versus the download-aware re-weight in PR #34 (not yet merged to main). Feed-v2 therefore **does not** depend on it for the popularity term (§4b computes popularity natively) and must **add no velocity term to `SearchScore`** — that object feeds `/search` + `/categories`, and changing it would silently re-rank both. The only reuse is `SearchScore.daysSinceRelease()` for the clock-skew-safe day computation.
