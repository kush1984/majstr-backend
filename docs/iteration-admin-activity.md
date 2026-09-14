# Admin panel — user activity & activation funnel

- **Status:** ✅ Backend + static admin page complete. `./gradlew build` (compile +
  Mockito tests) pending a local run; JPQL is validated at app **startup** (a
  smoke-start confirms it) — queries mirror the existing working
  `Estimate.project.owner.id` paths.
- **Migration:** none.
- **Goal:** See in the admin who actually works vs. who's stuck (activation
  funnel), without manual SQL.

## What shipped (all in majstr-backend — admin is `static/admin/index.html` + endpoints)

### 1. User list — activity columns
`GET /api/admin/users` now returns, per user: `emailVerified`, `clientsCount`,
`projectsCount`, `estimatesCount`, `signedEstimatesCount`. **No N+1:**
`AdminUserService.search` loads the page of users, then runs **one grouped query
per entity** over the page's ids (`countByOwnerIdIn`, `countByProjectOwnerIdIn`,
`...AndStatus(SIGNED)`) and folds them in (same pattern as the project-list
unread-question count). Columns added to the table: ✓ (email), Об., Кошт., Підп.

### 2. User detail (click a user → modal)
`GET /api/admin/users/{id}` → `AdminUserDetail`: email + verified, trades, plan,
role, registration, last activity, and the **per-master funnel** — clients,
projects, estimates with a status breakdown (draft/sent/signed/rejected),
hasShareLink, hasSigned, catalog size, hasLogo, last estimate date. Built from a
single grouped status query + a few per-owner counts.

### 3. Aggregate activation funnel (top of the admin)
`GET /api/admin/metrics/funnel` → `ActivationFunnelResponse`: registered →
verified email → ≥1 project → ≥1 estimate → shared → has signed. Each step is
**one aggregate COUNT** (`countByRole`, `countDistinctOwners`,
`countDistinctProjectOwners[ByStatus]`, ...) — no per-user loop. Masters only
(`ROLE_USER`; distinct-owner steps are naturally master-only). The page renders
each step's % of registrations.

### 4. PDF-bypass signal
A ⚠️ marker in the list for **active + unverified** masters
(`estimatesCount > 0 && !emailVerified`). This is exact, not heuristic: an
unverified master **can't share** (the portal share endpoint 403s them), so
"active + unverified" ⇒ "active without sharing" ⇒ likely PDF-only use that
never touches the client portal. A real per-user **PDF-download counter** doesn't
exist — logged in open-questions.

## Security & performance
- All endpoints are under `/api/admin/**` → `SecurityConfig` requires
  `ROLE_ADMIN` (existing gate; `AdminAccessTest` covers it). New endpoints inherit it.
- Aggregated/grouped queries throughout — no N+1. Lazy `trades` mapped within the
  read-only service tx (`@BatchSize`), `findWithTradesById` for the detail.
- Admin search reuses the Fix-K-safe `searchAdmin` (no `lower(bytea)`).

## Tests
- `MetricsServiceTest`: `activationFunnel` counts each step.
- `AdminUserServiceTest` (new): list folds per-user counts (missing rows → 0);
  detail builds the funnel + status breakdown + flags.

## Not changed / follow-up (open-questions)
- **PDF-download metric** — there's no counter for "generated/downloaded a PDF",
  so true PDF-bypass can only be inferred (active+unverified). A real counter
  (increment on `GET /api/estimates/{id}/pdf`) would make it precise.
- Existing `MetricsService` churn still uses `findAll()` (flagged separately);
  not touched here — the new funnel/detail code is all aggregate.

## Verify
1. Login as ADMIN → list shows email✓ + Об./Кошт./Підп. counts.
2. Click a user → modal with the full funnel + estimate status breakdown.
3. Funnel section at the top (registered → … → signed, with %).
4. Cross-check against a real user.
5. Non-admin gets 403; Sentry clean; no N+1.

---

# Round 2 — «хто був онлайн за останню добу» (2026-09-14)

- **Status:** ✅ complete. `./gradlew build` green — **1401 tests, 0 failures, 0 errors,
  0 skipped** (1400 + one new integration test); `:test` executed, not `FROM-CACHE`.
- **Migration:** none.
- **Goal (master's words):** «мені зараз показує хто є онлайн і список показується вгорі, а ще
  хочу щоб також під цим було хто за останню добу був онлайн… і беграунд має бути такий
  оранжевий як ми всюди по додатку використовуємо».

## The one thing worth knowing: "online" was never a list

There is no "who is online" endpoint, list or DOM section to copy. **"Online" is a SORT BUCKET** —
`UserRepository#searchAdminByPattern`'s ORDER BY floats active-right-now users to the top of the
ordinary user page, and `admin/index.html` tints those rows green client-side. So "за останню добу"
is not a second list either: it is a **second bucket in the same ORDER BY**, which is exactly why it
lands physically under the green group with no layout work at all.

```sql
ORDER BY
    CASE WHEN u.lastActiveAt > :activeSince THEN 0
         WHEN u.lastActiveAt > :recentSince THEN 1
         ELSE 2 END,
    CASE WHEN u.lastActiveAt > :recentSince THEN u.lastActiveAt END DESC,
    u.createdAt DESC          -- ASC in the registrationAscending twin
```

**Mutual exclusion is free, and it is not written in Java anywhere**: a SQL `CASE` stops at its
first matching `WHEN`, so a user inside `activeSince` is bucket 0 and can never also be counted as
"today". No dedup, no second round trip.

**The second sort key is scoped on purpose.** The Round-1 bug (see the class javadoc on
`AdminUserSearchOrderingIntegrationTest`) was a bare `u.lastActiveAt DESC`, which silently outranked
`createdAt` for everyone, because `lastActiveAt` is non-null for nearly every user who ever logged
in. The key is therefore `CASE WHEN <recent> THEN u.lastActiveAt END` — NULL for the "everyone else"
bucket, so `createdAt` still fully controls it. `:recentSince` (not `:activeSince`) is the right
condition because it covers **both** active buckets; inside each bucket the key is uniformly
non-null, so Postgres' `NULLS FIRST`-on-DESC default never mixes a NULL in with real values.

## Radius

- **`UserRepository`** — `:recentSince` added to both queries (DESC + the separately-written
  ascending twin) and to both `searchAdmin` default delegates. Arities **5→6** and **6→7**; no
  overload ambiguity. Per the fan-out rule, every call site was grepped across main + test.
- **`AdminUserService`** — `RECENT_WINDOW = Duration.ofHours(24)` beside `ACTIVE_WINDOW`. **One
  `Instant.now()` read feeds both cutoffs** — two separate calls could straddle a tick and drop a
  user out of both buckets.
- **`static/admin/index.html`** — `--amber: #f5821f` in `:root` (the brand orange the client portal,
  the message page and the PWA all use), `tr.recent-row` + `:hover` mirroring the existing
  `tr.active-row` rules, `RECENT_WINDOW_MS`, and a row render that derives `activeToday` as strictly
  `> ACTIVE_WINDOW_MS && <= RECENT_WINDOW_MS` so a row is one colour or the other, never both.
  🟠 marker + «Був онлайн за останню добу» tooltip.

### Two deliberate non-changes

- **`AdminUserSummary` is untouched.** The client already derives `activeNow` from the
  `lastActiveAt` field it receives, so `activeToday` derives identically — no record signature
  changed, so the record/constructor fan-out never had to happen on the DTO side.
- **A rolling 24h, not a calendar day.** The admin opens this at any hour; "since midnight" would
  empty the bucket every morning, which is precisely when it is most worth reading.

## Tests

- **New:** `AdminUserSearchOrderingIntegrationTest#yesterdaysUsersFormATheirOwnBucket_betweenActiveNowAndEveryoneElse`
  — five users (2 min / 3 h / 20 h / 48 h / never active) whose **registration order is the reverse
  of their activity order**, so the assertion can only pass on the bucket sort; `createdAt DESC`
  alone would produce exactly the opposite list. Pins both the DESC and the «Реєстрація»-ascending
  query, and the boundary case (3 h = today, not now). It has to be an integration test: the
  exclusion lives entirely inside the SQL `CASE`, which a mocked repository cannot see.
- **Updated for the new arity:** `UserRepositorySearchTest` (9), `AdminUserServiceTest` (4),
  `AdminUserSearchOrderingIntegrationTest` (3). No existing assertion was weakened — only the extra
  `recentSince` matcher was added.

## Honest gaps

- **Not verified live in a browser.** The colour, the 🟠 marker and the group boundary are asserted
  by reasoning over the ORDER BY plus a CSS rule copied from the working green one, not by opening
  the page.
- **Mobile was not checked** — the admin panel is a desktop-only internal tool; the mobile-first
  rule targets the PWA, which this round does not touch at all.
- **The PWA version was NOT bumped.** The per-iteration rule bumps it even for backend-only work
  because it is the product's single visible version — but nothing here reaches a master's screen.
  Owner's call.
