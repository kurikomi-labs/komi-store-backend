# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product context

Backend for **GitHub Store** (a KMP + Compose Multiplatform app store — Android + Desktop, ~90k users). The client app lives at `/Users/rainxchzed/Documents/development/kmp/GitHub-Store`. The Python fetcher that feeds data into this backend lives at `/Users/rainxchzed/Documents/development/backend/api`.

**The backend is optional by design.** The client falls back to cached JSON files in `OpenHub-Store/api` on GitHub when the backend is unreachable. Never design features that require the backend to be up.

**Live deployment:** single Hetzner VPS (4 vCPU / 8 GB) at `<VPS_IP>`, served through two hostnames:
- `https://api.github-store.org/v1/` — CDN-fronted (Cloudflare, free plan). Normal path.
- `https://api-direct.github-store.org/v1/` — direct-to-origin, bypasses the CDN. Used as client-side fallback when the CDN path is throttled (mainly Chinese ISPs flagging shared CDN IPs) and as origin-pull target for Gcore itself.

Both hostnames serve identical responses. Caddy on the VPS terminates TLS (Let's Encrypt) for both. See `GITHUB_STORE_BACKEND_PLAN.md` for the full strategic doc and `CLIENT_MIGRATION_X_GITHUB_TOKEN.md` for the client contract.

## Common commands

```bash
# Local development (exposes Postgres/Meilisearch ports to host via docker-compose.override.yml)
docker compose up postgres meilisearch -d
./gradlew buildFatJar
DATABASE_URL="jdbc:postgresql://localhost:5432/githubstore" \
  DATABASE_PASSWORD="githubstore" \
  MEILI_URL="http://localhost:7700" \
  MEILI_MASTER_KEY="devkey" \
  java -jar build/libs/github-store-backend.jar

# Full stack via Docker (dev compose, uses override file for exposed ports)
docker compose up -d --build

# Compile only (fast feedback loop)
./gradlew compileKotlin

# Deploy to production VPS (rsync + docker compose)
./deploy.sh <VPS_IP>

# Check production
curl https://api.github-store.org/v1/health
```

## API surface

All under `/v1/`:

| Endpoint | Purpose |
|----------|---------|
| `GET /` | Greeting JSON `{name, docs, api}` so bare-hostname hits don't 404. Cached `max-age=3600, s-maxage=86400`. |
| `GET /health` | Health check (Postgres + Meilisearch status) |
| `GET /search?q=&platform=&sort=&limit=&offset=` | Meilisearch-powered search. Auto-triggers GitHub passthrough if <5 results. `sort` ∈ {`relevance` (default), `stars`, `recent` / `releases` (alias, by latest stable release date), `updated` (by repo `updated_at_gh`)}. `relevance` requires `q`; the others allow empty `q` for browse-mode listings. `sort=updated` is routed directly to Postgres FTS until the fetcher repo's `meili_sync.py` adds `updated_at_gh` to Meili's sortable-attributes. Reads optional `X-GitHub-Token` header to run passthrough on the user's 5000/hr quota instead of the backend's fallback quota. Response carries `passthroughAttempted: Boolean` so clients can distinguish "index was warm but returned nothing" from "GitHub also has nothing". |
| `GET /search/explore?q=&platform=&page=` | User-triggered deep GitHub search, paginated, ingests into index. Also reads `X-GitHub-Token`. Cold-path latency is 10–30s — clients must use a 30s timeout. |
| `GET /categories/{trending\|new-releases\|most-popular}/{android\|windows\|macos\|linux}` | Pre-ranked repo lists. Sort order is `search_score DESC NULLS LAST, rank ASC` — static `rank` is only the tie-breaker once behavioral signals exist. |
| `GET /topics/{privacy\|media\|productivity\|networking\|dev-tools}/{platform}` | Topic-bucketed repos. Same dynamic ordering as categories. |
| `GET /repo/{owner}/{name}` | Single repo detail. Curated DB hit on the fast path; on miss, lazy-fetches metadata from GitHub via `GitHubResourceClient` and reads optional `X-GitHub-Token`. Response includes `openIssuesCount` (mirrors GitHub's `open_issues_count`, which counts open issues + open PRs together — same value the GitHub website's Issues tab shows) and `licenseSpdxId` / `licenseName` (GitHub-detected license; null when no LICENSE file or unrecognised). |
| `POST /repo/{owner}/{name}/refresh` | User-triggered refetch of a repo's metadata + latest release. Re-fetches from GitHub via `RepoRefreshCoordinator`, upserts Postgres + pushes Meili, returns the same shape as the GET. Per-repo cooldown 30s + global hourly budget 1000 prevent pool-token torch from spam clicks. Reads `X-GitHub-Token`. Response is `Cache-Control: no-store`; the GET path's CDN cache catches up via its own TTL (~5 min on `s-maxage=300`). |
| `GET /releases/{owner}/{name}?page=&per_page=` | Proxied list of GitHub releases. Reads optional `X-GitHub-Token`. Cached server-side for 1h. |
| `GET /readme/{owner}/{name}` | Proxied README JSON (base64-encoded content + metadata, GitHub's shape). Reads optional `X-GitHub-Token`. Cached 24h. |
| `GET /user/{username}` | Proxied GitHub user/org profile. Reads optional `X-GitHub-Token`. Cached 7d. |
| `GET /users/{username}/repos?type=&sort=&direction=&page=&per_page=` | Proxied list of a user/org's repos. `type` ∈ {all, owner, member}, `sort` ∈ {created, updated, pushed, full_name}, `direction` ∈ {asc, desc}. Whitelisted to block SSRF via query injection. Cached 1h server-side, edge `s-maxage=1800`. Reads `X-GitHub-Token`. |
| `GET /users/{username}/starred?sort=&direction=&page=&per_page=` | Proxied list of a user's starred repos (the public form -- the OAuth viewer-self form is intentionally NOT proxied). `sort` ∈ {created, updated}. Cached 30min server-side, edge `s-maxage=900`. Reads `X-GitHub-Token`. |
| `POST /events` | **Deprecated 2026-04-26 — telemetry was killed in the E6 audit.** Returns `204 No Content` and silently discards the batch — pre-1.8.3 clients (`TelemetryRepositoryImpl`) treat any non-2xx as failure and retry, so a 410 here triggers an error-log + retry storm. 204 lets old clients see success and back off. The `Events` table and `SignalAggregationWorker` remain wired for historical rows but no new data is ingested. Once 1.8.3 ships a sticky-disable-on-non-2xx flag on the client (telemetry cleanup task), flip this back to `410 Gone` with the proper JSON deprecation notice so laggard clients get a real signal. |
| `GET /announcements` | Public, anonymous announcements feed. Same byte-identical envelope for every caller. Backed by JSON files in `src/main/resources/announcements/<id>.json` (or `ANNOUNCEMENTS_DIR` env override). Validator enforces every rule from `docs/backend/announcements-endpoint.md` §2 at load time; expired items are filtered at serve time. `Cache-Control: public, max-age=600` + ETag revalidation. No auth, no per-user logic, no logging beyond standard access. |
| `POST /auth/device/start` | Stateless proxy for `github.com/login/device/code`. Client used to call GitHub directly; some user networks (documented in OpenHub-Store/GitHub-Store#433, #395) can't reach GitHub reliably. Backend adds `client_id`, forwards GitHub's body verbatim. 10 req/hr/IP. |
| `POST /auth/device/poll` | Stateless proxy for `github.com/login/oauth/access_token`. Reads `device_code` from form body, adds `client_id` + `grant_type`, forwards GitHub's body verbatim (including tokens on success). The backend never logs, caches, or persists the token. 200 req/hr/IP. Per-request diagnostic line `[auth-poll rid=… ] dch=<sha256-prefix(device_code)> ghs=<upstream-status> gh_err=<github-error-code-or-"-"> lat_ms=<ms> ua=<truncated-UA>` is logged for auth-stuck triage (GitHub-Store#433, #395) — the raw `device_code`, response body, and every token field are explicitly excluded; only the `error` key is parsed off the upstream body, via a DTO that doesn't even declare `access_token`/`refresh_token`. |
| `GET /internal/metrics` | Operator-only. Gated by `X-Admin-Token` matching the `ADMIN_TOKEN` env var (open if unset, for local dev). Returns per-source search counters, P-latency, worker queue depth, and top 20 misses (8-char `query_hash` prefix only) in last 7 days. |
| `POST /internal/backfill-stale?limit=N` | Operator-only. Spawns a paced background job that refreshes every curated row whose new metadata columns are still at their migration defaults (currently keyed on `license_spdx_id IS NULL`). One concurrent run; returns 409 on re-trigger. Uses `searchClient.refreshRepo` + persist; respects the quiet window so the daily fetcher's pool stays free. Run after a column-add deploy; no-ops afterwards once the filter no longer matches. |
| `GET /badge/...` | M3-styled SVG badges. Per-repo: `/badge/{owner}/{name}/{kind}/{style}/{variant}` for kind ∈ {release, stars, downloads}. Global: `/badge/{kind}/{style}/{variant}` for kind ∈ {users, fdroid}. Static: `/badge/static/{style}/{variant}?label=&icon=`. Style 1-12 hue, variant 1-3 shade. Vectorized glyph rendering — no font dependency at SVG embed time. |
| `GET /mirrors/list` | Curated catalog of GitHub mirrors with hourly-probed health. Each entry carries `traffic_kinds: ["release_asset", "raw_file"]` for whole-URL proxies (template ends `/{url}`) and `["raw_file"]` for jsDelivr's `/gh/` path-based mirror (template `https://fastly.jsdelivr.net/gh/{owner}/{repo}@{ref}/{path}`). Clients MUST consult `traffic_kinds` before routing a download — sending a release-asset URL through a `raw_file`-only mirror 404s. Cached `max-age=300, s-maxage=3600`. |
| `{GET,POST} /repo/login/{device,oauth}` | **Deprecated 2024-09-01** — tombstone for pre-1.6 builds that wired the device-flow under `/repo/`. Returns `410 Gone` with `use_instead` pointing at `/v1/auth/device/start` + `/v1/auth/device/poll`. Cached `max-age=86400`. Declared **before** `repoRoutes` so the static segments win over `/repo/{owner}/{name}`. |

Client-facing API contract and migration history live in `internal/` (gitignored, operator-only). The client repo at `OpenHub-Store/GitHub-Store` is the public source of truth for client behavior.

## Architecture

**Data flow:**

```
Python fetcher (daily cron on VPS: /opt/fetcher/)
    │ upserts via db_writer.py
    ▼
Postgres (source of truth) ◀── SignalAggregationWorker (hourly)
    │                              ▲
    │ synced by meili_sync.py      │ reads Events, writes RepoSignals + search_score
    ▼                              │
Meilisearch (search index) ◀───────┘ (pushes search_score via PUT /documents)
    ▲
    │ on-demand ingest writes to both (async, off the request path)
Ktor app → GitHubSearchClient → GitHub API
    │
    │ periodic warming
    ▼
SearchMissWorker (disabled) — used to re-run missed queries, but raw queries
                              are no longer persisted (V6 privacy migration).
                              Class is kept as a stub; start() logs once and exits.
RepoRefreshWorker (hourly)  — re-fetches passthrough repos by oldest indexed_at
```

**Key architectural decisions:**

- **No Flyway.** Flyway 11 community edition rejected standard `V1__` naming. Migrations run via `DatabaseFactory.runMigrations()` — reads SQL from `src/main/resources/db/migration/` and executes directly via Exposed. V1 is idempotent (table-exists check), V2+ are additive and wrapped in try/catch. New migrations must be added to the `migrations` list in `DatabaseFactory.kt`.
- **Koin DI but no `Route.inject()`.** Koin's `Route.inject()` extension breaks in Ktor 3.x (references a moved class). Dependencies are injected at the `Application` level in `routes/Routing.kt` and passed as parameters to route functions. The three workers (`SearchMissWorker`, `SignalAggregationWorker`, `RepoRefreshWorker`) are `single { ... }` in `AppModule.kt` and started from `Application.module()`.
- **Rate limiting** keys off `CF-Connecting-IP` first (set by Cloudflare on the CDN-fronted vhost; never client-forgeable because Cloudflare always overwrites it), then `X-Forwarded-For` first IP, then "unknown". The api-direct vhost still strips `CF-Connecting-IP` and overwrites XFF with the TCP source as defence-in-depth, so the same `forwardedFor()` helper works on both paths. Global limit is **360/min/IP**; **search bucket** (covers `/search`, `/search/explore`, `/releases`, `/readme`, `/user`, `/users/{u}/repos`, `/users/{u}/starred`) is **240/min** keyed by `tok:<HmacSHA256(pepper,token)[:16]>` when `X-GitHub-Token` is present, else `ip:<XFF-or-socket>`. The CDN-vhost change to keep CF-Connecting-IP gives each anonymous user behind a Cloudflare POP its own bucket — without it, every anon user behind a POP shared one slot and the search-bucket bump had no effect. Tokens never appear in logs.
- **Two search paths (background warming is currently disabled):**
  1. **Automatic passthrough**: when Meilisearch returns <5 results, `GitHubSearchClient.searchAndIngest()` runs in the same request. Merges results before responding. Misses (any result count) are recorded as a `query_hash` only — raw text is never stored.
  2. **Explicit explore**: `/search/explore` is a user-triggered paginated fetch. Each page adds up to 10 new repos to the index.
  3. ~~Background warming~~: `SearchMissWorker` is now a no-op stub. Re-enabling it requires a privacy-safe representation of the original query (the V6 migration dropped `query_sample`).
- **Cold-query gate.** A class-level `Semaphore(8)` (`coldQueryGate`) wraps both `searchAndIngest` and `explore`. Concurrent cold queries past the limit queue rather than dogpile the GitHub rotation pool. Hikari pool default bumped to 20 in tandem.
- **Kill switches.** `DISABLE_LIVE_GITHUB_PASSTHROUGH=true` cuts every outbound GitHub-API call (search/explore/runPass/refreshRepo/fetchCached/badge release fallback); cached values still serve. `DISABLE_BADGE_FETCH=true` is the narrower variant, only blocking the badge release fallback. Both read fresh per-call from `util/FeatureFlags.kt`.
- **Privacy hashing.** `device_id` is hashed via `util/PrivacyHash.kt` using `DEVICE_ID_PEPPER` env (required in prod, default dev pepper otherwise). Search misses store `sha256(canonicalize(query))` only; the V6 migration drops the legacy `query_sample` column and rehashes existing device_id rows.
- **Persistence is off the request path.** `GitHubSearchClient` owns a `persistenceScope` (`SupervisorJob + Dispatchers.IO`). The live search returns the enriched results synchronously; the Postgres upsert + Meilisearch push are fire-and-forget coroutines so the user never waits on them. A failure in persistence is logged, not surfaced.
- **GitHub token strategy: bring-your-own + rotation pool fallback + rate-limit retry.** `/search`, `/search/explore`, `/repo` (lazy-fetch path), `/releases`, `/readme`, and `/user` all read an optional `X-GitHub-Token` header from the client and use that token for the upstream call (scales quota linearly with users). When the client sends none, `GitHubSearchClient.pickFallbackToken()` round-robins across a pool of four backend PATs (`GH_TOKEN_TRENDING`, `GH_TOKEN_NEW_RELEASES`, `GH_TOKEN_MOST_POPULAR`, `GH_TOKEN_TOPICS`). `GitHubResourceClient` (powering `/repo`/`/releases`/`/readme`/`/user`) shares the same pool via the provider injected in `AppModule.kt`. **Rate-limit retry**: when an upstream call returns 429 — or 403 with `x-ratelimit-remaining: 0` or a `retry-after` header — the call is retried once with a different pool token. A bare 403 (auth scope, blocked repo) is NOT retried. **Quiet-window guarantee**: during UTC hours `TOKEN_QUIET_START_UTC`–`TOKEN_QUIET_END_UTC` (default 1–4) the rotation pool is bypassed (only `GITHUB_TOKEN` is used) and rate-limit retry is skipped — so the Python fetcher's daily run gets the full pool. Inside that window a rate-limited user token surfaces verbatim. Any change here must preserve the quiet-window guarantee.
- **Resource cache key segmentation.** `GitHubResourceClient.fetchCached` appends `|authed` or `|anon` to every cache key based on whether the request carried `X-GitHub-Token`. Without this, one user's bad-PAT 403 (or the anon path's IP-rate-limit 403) would poison the slot for every other caller until the negative TTL elapsed. Separate slots are filled and read independently.
- **Upstream 401 → backend 502.** `GitHubResourceClient` remaps a 401 from GitHub to `Result.UpstreamError` (handler returns 502), instead of a `NegativeHit` that would forward 401 verbatim. 401 in our own response surface is reserved for backend-auth (no auth-required routes today, but the convention prevents the client from misreading "your PAT was rejected upstream" as "your session with us expired").
- **Auth is a stateless proxy, not a session.** `/v1/auth/device/*` forwards to `github.com/login/*` with the backend's `GITHUB_OAUTH_CLIENT_ID` injected. The backend must **never** log, cache, or persist the access token returned by a successful poll — it passes through the suspending handler and out to the HTTP response, nothing else. No database table, no in-memory map, no breadcrumb. The client is the only place the token lives. Client is backend-first on these two calls and falls back to direct-to-github.com on 5xx / network errors (only — not on valid-but-negative responses like `authorization_pending` or `access_denied`, which are GitHub's real answer and `github.com` direct would say the same thing).
- **Unified ranking via `SearchScore.compute()`** (`ranking/SearchScore.kt`). Formula: `0.40·log₁₀(stars+1)/6 + 0.30·ctr + 0.20·install_success_rate + 0.10·exp(-days_since_release/90)`. Two callers: `SignalAggregationWorker` (hourly, with real signals) and `GitHubSearchClient` at ingest time (cold-start, signals = 0 — still gives passthrough repos a non-null score so they sort). Weights live in the object only; never inline the formula elsewhere.
- **Meilisearch partial-update gotcha — PUT, never POST.** `MeilisearchClient.addDocuments()` is POST, which on Meili *replaces* the document with whatever fields you send (everything else becomes null). `MeilisearchClient.updateScores()` is PUT, which merges. Pushing just `{id, search_score}` with POST will wipe every other field on 3000+ docs. If you add a new "partial update" path, verify the HTTP verb before deploying.
- **Dynamic category/topic ordering.** `RepoRepository.findByCategory()` picks a category-specific primary sort column (`trending_score` for trending, `popularity_score` for most-popular, `latest_release_date` for new-releases), falls back to global `searchScore`, then static `rank` as final tie-breaker. Without category-specific primary, both trending and most-popular collapse onto the same global score — the bug fix in PR #12. `findByTopicBucket()` keeps the simpler `searchScore DESC NULLS LAST, rank ASC` order because topics are flat lists, not flavour-segmented like the categories.
- **Exposed `Repos` table uses `array<String>("topics", TextColumnType())`** for the Postgres `TEXT[]` column. The Python fetcher writes these via psycopg2's automatic list-to-array conversion.
- **Cache headers are set per endpoint**, not globally. Announcements: 600s/3600s. Categories/topics: 60s/600s. Repo detail: 30s/300s. Search: 15s/30s. Readme proxy: 3600s/21600s. User proxy: 86400s/604800s. Signing-seeds: 86400s/604800s with `stale-while-revalidate=86400` and a strong ETag for 304 revalidation — content only changes on the daily F-Droid sync cron, so the long edge TTL is paired with an operator-side Cloudflare purge when the seeds rotate. Badges (fresh): 3600s/3600s with `stale-while-revalidate=86400`; (degraded) 300s/300s. Unmatched-route 404s: 300s/300s — `Plugins.kt:respondNotFound` returns `ApiError("not_found")` and logs `[404 rid=… ] METHOD /path` (no query string) so scanner traffic and old-client paths are classifiable from the application log without hammering origin. Edge respects `s-maxage`; the larger `s-maxage` lets Gcore's shield/tiered cache topology absorb origin load while browsers stay fresher via the smaller `max-age`. `/internal/metrics` is uncached.
- **HEAD routes to GET** via the `AutoHeadResponse` plugin (`Plugins.kt`). Without it, Ktor 3 returns 404 for HEAD on every GET handler — confusing for `curl -I`, monitoring, and CDN origin probes.
- **Owner / repo-name path-param validation.** Every GitHub-proxy route (`/readme/`, `/user/`, `/release/`, `/repo/`, `/badge/{owner}/{name}/...`) calls `util/GitHubIdentifiers.validOwner` / `validName` at the top of the handler. Owner regex matches GitHub's actual username rule (`^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$`), name allows a slightly broader set up to 100 chars. Reject early with 400 — keeps SSRF-by-path-trickery off the upstream URL.
- **Badge service** lives under `badge/` (`BadgeRenderer`, `BadgeColors`, `BadgeIcons`, `BadgeService`, `FdroidVersionClient`, `TtlCache`, `BadgeGlyphs`). Text is rendered as vectorized `<path>` elements composed from glyph data extracted at startup from `src/main/resources/fonts/Inter-Bold.ttf` (SIL OFL 1.1). The renderer is deliberately font-independent at SVG embed time — every browser, markdown viewer, and feed reader sees byte-identical glyphs. Color palette mirrors `ziadOUA/m3-Markdown-Badges` hex-for-hex (12 hues × 3 shade variants).

## Conventions

- All API routes are under `/v1/` and additive only. Never break client apps on field changes.
- One file per route resource in `routes/`. Routes receive repositories/clients as parameters, not via `inject()`.
- Migrations are numbered: `V1__`, `V2__`, etc. Each new migration needs to be added to the `migrations` list in `DatabaseFactory.kt`.
- Never log raw search queries, device IDs, or `X-GitHub-Token` values. Hash queries via `util/queryHash` (8-char prefix is the convention) and device IDs via `util/PrivacyHash.hash`; treat tokens as secrets — no logging, no Sentry breadcrumbs, no metrics labels. Sentry's `beforeSend` already strips `Authorization`, `X-GitHub-Token`, `X-Admin-Token`, `Cookie`, and IP-bearing headers.
- Use `rainxch.githubstore.ranking.SearchScore.compute(...)` for any ranking score anywhere. The formula is centralized on purpose — never re-derive weights inline.
- Commit style: **one logical change per commit.** Batched commits are discouraged (stored as user preference). Code + tests + docs for the same change go together, but separate changes stay separate.
- When adding a new field to `RepoResponse`: update the Exposed `Tables.kt`, the `RepoRepository.toRepoResponse()` mapper, the `MeiliRepoHit` DTO, the `SearchRoutes` mapper, the on-demand ingest in `GitHubSearchClient`, AND the Python `meili_sync.py` query — all five need to agree or data is silently dropped.

## Deployment notes

- `.env` lives only on the VPS at `/opt/github-store-backend/.env`, never in git. Under `APP_ENV=production` the app refuses to start without these: `DATABASE_URL`, `DATABASE_PASSWORD`, `MEILI_URL`, `MEILI_MASTER_KEY`, `GITHUB_OAUTH_CLIENT_ID`, `DEVICE_ID_PEPPER`. Also relied on: `GITHUB_TOKEN` (quiet-window fallback), `GH_TOKEN_TRENDING` / `GH_TOKEN_NEW_RELEASES` / `GH_TOKEN_MOST_POPULAR` / `GH_TOKEN_TOPICS` (rotation pool), `ADMIN_TOKEN`, `SENTRY_DSN`, `TOKEN_QUIET_START_UTC` / `TOKEN_QUIET_END_UTC` (default 1 / 4), `BADGE_USER_COUNT`, `DATABASE_POOL_SIZE` (default 20), `DISABLE_LIVE_GITHUB_PASSTHROUGH` and `DISABLE_BADGE_FETCH` kill switches. `.env.example` is the canonical list.
- `docker-compose.override.yml` is local-dev only (exposes DB/search ports to host). Never deploy it — `.dockerignore` excludes it.
- Production uses `docker-compose.prod.yml` which does NOT expose Postgres/Meilisearch ports (only Caddy on 80/443). Postgres binds `127.0.0.1:5432` for the SSH tunnel from laptop.
- `Caddyfile.prod` defines two site blocks: `api.github-store.org` (Cloudflare-fronted primary) and `api-direct.github-store.org` (direct origin fallback). Both reverse-proxy to `app:8080` and both issue Let's Encrypt certs. The CDN vhost passes `CF-Connecting-IP` and `X-Forwarded-For` through unchanged (Cloudflare overwrites the former; the latter still has the real client IP appended by Cloudflare). The api-direct vhost overrides XFF with the TCP source and strips `CF-Connecting-IP` to defeat client forgery on the un-fronted path. Cloudflare's origin pull protocol must be **HTTPS**, not HTTP — HTTP triggers Caddy's auto-redirect and leaks the direct hostname.
- `deploy.sh` runs `caddy reload` after sync and falls back to `docker compose restart caddy` if reload fails. The Caddyfile is bind-mounted, so file changes alone don't reconfigure the running process.
- Python fetcher runs on the VPS at `/opt/fetcher/` via cron: full fetch daily at 02:00 UTC, new-releases every 3h. The fetcher consumes the same 4-token pool as the backend's rotation pool — the backend's `TOKEN_QUIET_START_UTC`/`END_UTC` window exists precisely to keep the pool free for it during its run.
- Sentry DSN is pulled from `SENTRY_DSN` env var at startup; init is a no-op if unset (good for local dev).
