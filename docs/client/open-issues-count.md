# Client Integration — `openIssuesCount`

**Audience:** client coding agent (KMP / Compose Multiplatform).
**Goal:** surface a repo's open-issues count on the details screen using the new `openIssuesCount` field on `RepoResponse`. No new endpoint, no pagination — the value rides on the existing GET response.

---

## 1. What changed

`RepoResponse` (the DTO returned by `GET /v1/repo/{owner}/{name}`, every category/topic list endpoint, and the new `POST /v1/repo/{owner}/{name}/refresh`) now carries a new field:

```kotlin
val openIssuesCount: Int = 0
```

Default is `0` so older clients that don't know the field still parse cleanly. Same back-compat story as every other additive field on `RepoResponse`.

---

## 2. What the value means

GitHub's `open_issues_count`. Identical to the number you see on the GitHub website's **Issues** tab badge. Two important properties:

1. **Includes open pull requests.** GitHub treats PRs as a kind of issue, so this count is `open_issues + open_prs` combined. There is no first-class field for "issues only — no PRs" on the GitHub API; getting that requires a separate paginated call. We do not paginate, so we accept GitHub's combined number.
2. **Is a snapshot, not realtime.** Updated when the row is upserted (passthrough ingest, refresh button, daily fetcher run, hourly RepoRefreshWorker). Worst case stale: 24h on a repo nobody has refreshed manually since the last fetcher run.

If the client UI labels it as "open issues" specifically, that's an acceptable simplification — the GitHub website itself uses the same number under "Issues."

---

## 3. Where the field appears in responses

Every `RepoResponse`-shaped payload, including:

| Endpoint | Behaviour |
|----------|-----------|
| `GET /v1/repo/{owner}/{name}` | DB-hit and lazy-fetch paths both fill it. DB-hit value may be up to 24h stale; lazy-fetch is live. |
| `POST /v1/repo/{owner}/{name}/refresh` | Fresh from GitHub, up-to-the-second. |
| `GET /v1/categories/.../...` | DB value (same staleness as the rest of the curated row). |
| `GET /v1/topics/.../...` | DB value. |
| `GET /v1/search?q=...` | Meilisearch index value (synced from DB by `meili_sync.py`). |

If you don't see the field, check that the row was last upserted before this change rolled out. Hit the refresh endpoint and the value will populate.

---

## 4. Display recommendations

- **Where:** details screen, near star count + fork count. Same row, same icon-with-number style.
- **Icon:** GitHub's open-circle "issue" glyph. Whatever your existing iconography uses.
- **Label:** "Issues" or "Open issues." Don't say "Bug count" — PRs are mixed in.
- **Tap behaviour:** open `https://github.com/{owner}/{name}/issues` in a browser tab. We do not currently expose a list endpoint; the user goes to GitHub for details.
- **Zero handling:** show "0" rather than hiding. Zero open issues is a meaningful signal (well-maintained or unused).
- **Large numbers:** format with `1.2k` / `12k` style above 1000. Current biggest repos (e.g. `facebook/react`, `microsoft/vscode`) show >5000. Don't overflow the layout.

---

## 5. Pseudo-code (Compose Multiplatform)

```kotlin
@Composable
fun RepoStatsRow(repo: RepoResponse) {
    Row(...) {
        StatChip(icon = Icons.Star, value = repo.stargazersCount.compactFormat(), label = "Stars")
        StatChip(icon = Icons.Fork, value = repo.forksCount.compactFormat(), label = "Forks")
        StatChip(
            icon = Icons.Issue,
            value = repo.openIssuesCount.compactFormat(),
            label = "Issues",
            onTap = { openInBrowser("https://github.com/${repo.fullName}/issues") },
        )
    }
}

private fun Int.compactFormat(): String = when {
    this >= 1_000_000 -> "%.1fM".format(this / 1_000_000.0)
    this >= 1_000 -> "%.1fk".format(this / 1_000.0)
    else -> toString()
}
```

---

## 6. Backwards compatibility notes

- **Older app builds against the new server:** field is unknown, ignored by `kotlinx.serialization`'s `ignoreUnknownKeys = true`. Nothing breaks.
- **Newer app builds against the old server (during rollout):** field is missing, `Int = 0` default kicks in. UI shows "0 issues" briefly until backend deploy completes. Acceptable — rollout window is ~5 minutes.
- **Client deserializer:** must have `ignoreUnknownKeys = true` (you almost certainly already do; every other passthrough field has been added the same way).

---

## 7. What you do NOT need to do

- **No separate fetch.** Don't call `/v1/repo/{o}/{n}/issues` or anything like it — there is no such endpoint, and you don't need one for the count.
- **No pagination.** Count comes free with the repo lookup.
- **No client-side caching of the count.** The repo response is already cached client-side per your existing `/repo` cache strategy. The count rides along.
- **No special refresh UX.** If the user wants the count fresher than the cached repo response, they tap the refresh button (`POST /v1/repo/{o}/{n}/refresh`) — same as every other repo field.

---

## 8. Acceptance criteria

- [ ] `RepoResponse` deserializes with the new `openIssuesCount` field on every existing call site (details screen, search results, category/topic lists, repo refresh response).
- [ ] Details screen displays the count next to stars/forks.
- [ ] Tapping the issue chip opens `https://github.com/{owner}/{name}/issues` externally.
- [ ] No crash, no parse error, when an old server response (without the field) is consumed during rollout — `Int = 0` fallback works.
- [ ] Compact-format helper handles 0, small (`42`), thousands (`1.2k`), millions (`1.5M`).

---

## 9. Authoritative reference

Backend definitions:
- `model/RepoResponse.kt` — the DTO shape
- `db/migration/V14__open_issues_count.sql` — the column
- `routes/RepoRoutes.kt`, `routes/SearchRoutes.kt`, `db/RepoRepository.kt`, `ingest/GitHubSearchClient.kt` — all the mappers wired together

If the client and server disagree, the backend wins; file an issue on the backend repo.
