# Iteration: offline-first PWA (work in a no-signal basement, sync when network returns)

> **Status (2026-07-22):** ✅ **Phase 0 (read-offline)** and **Phase 1** shipped — offline authoring for
> **clients, objects, estimates and line items** (idempotent client-UUID replay via the outbox), plus
> **Slice 3** sync UX (status indicator, over-limit "PRO or delete" gate, warn-before-logout). The
> backend idempotency for each entity is behind the user's Gradle build. **Remaining follow-ups**
> (measurements offline, re-sync-on-relogin, conflict UI, photos offline) are logged in
> `open-questions.md` → "Offline follow-ups". PWA build/tests are Claude's; the backend build is the user's.

## Why

~95% of masters use the PWA on a phone, and electricians (and others) routinely work in buildings
with **no signal** — basements, new-builds, lift shafts. Today the app shell boots offline (SW
precache) but every screen is empty offline: API responses aren't cached and the query cache is
in-memory only. The master needs to **open the app, see their data, and keep working** with no
network, then have it **sync when signal returns**.

## The boundary — what can be offline, what can't

- **Offline-core (authorable on-site):** view + create/edit **estimates, clients, objects,
  measurements**. Calculators (площі, and the parked штроба/кабель) already compute client-side.
- **Inherently online (gate with a clear "потрібен інтернет"):** portal share/sign, PDF generation
  (backend), LLM import (кошторис/чек/план), payments/checkout, push subscribe, email verify.

## Decisions (locked with the user)

| # | Decision | Consequence |
|---|----------|-------------|
| Conflict model | **LWW + conflict surfacing** | One master, usually one phone → last write wins; but if the server changed under us, show "залишити моє / серверне". Needs `updated_at`/version on clients & objects (estimate already has `@Version`); the signed-estimate case is caught by the existing `ESTIMATE_SIGNED` 409. |
| First authoring cut | **estimates + clients + objects + measurements** | Measurements need the `result` computed **locally** (the PWA already mirrors the formulas) and reconciled on sync. Photos = a later phase. |
| Engine | **hand-rolled Dexie + outbox + TanStack Query persistence** | Fits the existing TanStack Query + axios + REST stack, no new infra. RxDB was rejected — it would mean rewriting the whole data layer for a single-writer app. |

## FREE limits & anti-abuse (the sharp part)

- **Gated LOCALLY at creation time** (PRO/TEAM never gated offline). This closes the obvious abuse
  ("turn wifi off → hoard unlimited objects") **by design**: the client blocks the 3rd object
  offline exactly as online. Turning off the network changes nothing.
- **Over-limit only reaches sync in edges** (e.g. the trial expired on the server while the master
  was offline, so the locally-cached plan was stale). Then, at sync: a **forced, immediate, binary**
  choice — **[Оформити PRO]** (reuse the monobank checkout; the outbox then flushes) or
  **[Скасувати → the over-limit items are deleted]**. **No "decide later" / indefinite local hold** —
  that limbo *is* the abuse vector. Show *which* items are over the limit (by name); the first 2
  objects / 3 estimates (within FREE) always sync.
- **Server `LimitService` stays the source of truth** — a tampered client still hits the wall on
  sync, so client-side gating is UX, not the last line.

## Local-data lifetime & security (temporary working copy)

- **Wipe local store + outbox on:** (a) **explicit logout** — but WARN first if there is
  within-limit unsynced work ("N незбережених змін — вийти й втратити?"); (b) a **genuinely-dead
  session** (server returns 4xx on `/refresh`, i.e. online and refused).
- **NEVER wipe on offline / an expired access token.** The JWT access token expires every few
  minutes; treating that as "session over" would self-destruct the feature the moment the master
  goes offline. We reuse the axios interceptor's existing line: **network/5xx = transient (keep
  everything); 4xx on `/refresh` = dead (wipe + redirect)**. `forceLogin()` does a hard redirect
  that drops in-memory state — so it must ALSO clear the *persisted* IndexedDB cache, or the next
  load would rehydrate the dead session's data.
- **Re-sync after re-login:** if the session died with unsynced work, on the next login **as the
  same master** we offer to sync the still-queued outbox. It flows through the **same** sync + FREE
  over-limit gate as a normal sync — one path, no special-casing. Over-limit there → the same
  "PRO or delete".
- **Framing:** the offline banner calls local data a **"тимчасова робоча копія на пристрої"**, not a
  safe store — sets the master's expectations and nudges them to sync (the honest incentive: sync =
  save for good). Aligns with the existing cross-account cache-bleed fix (a borrowed phone / account
  switch starts clean).

## UX messaging — three calm states (mobile-first, no modal walls)

1. **Offline (passive):** a thin banner — "Офлайн. Це тимчасова копія на цьому пристрої —
   синхронізуйте, коли буде мережа." Online-only actions shown **disabled** with a "потрібен
   інтернет" hint (not hidden — explained).
2. **Unsynced changes pending:** a small "очікує синхронізації" clock badge on affected cards + a
   pending count in the banner.
3. **Reconnect → sync:** an unobtrusive "Синхронізація… (N)" → "Готово". Failures (limit / validation)
   → "M потребують уваги → переглянути" opens the decision list. Never a silent data drop.

## Phasing

- **Phase 0 — read-offline (✅ DELIVERED, NO backend change):** the query cache is persisted to
  IndexedDB (`idb-keyval` + TanStack `PersistQueryClientProvider` in `main.tsx`; `gcTime` bumped to a
  week so queries aren't evicted before the snapshot; `buster = __APP_VERSION__`, `maxAge` a week).
  `useOnline` (TanStack `onlineManager`) drives the existing `OfflineBanner`, re-messaged to frame the
  data as a **saved copy** (`offline.banner`). `clearPersistedCache()` is wired into **useLogin**
  (drop the prior account before priming), **useLogout**, and **`forceLogin`** (dead session — the
  hard redirect drops memory but not IndexedDB, so we del the key first). Reads now survive reload +
  work offline; the whole FREE dataset is tiny and fully cached, so the FREE count comes for free.
  Tests: `useOnline.test`, `OfflineBanner.test`. tsc + full vitest + build green. v0.16.0.
  **Note:** the message stays honest for Phase 0 — "показано збережену копію, деякі дії недоступні"
  (no offline authoring yet); it becomes the "тимчасова копія, синхронізуйте" framing in Phase 1.
- **Phase 1 — authoring outbox (the big one), built in slices:**
  - **Slice 1 — outbox ENGINE (✅ done, PWA-only):** `src/lib/outbox/` — a Dexie queue of
    `OutboxOp`s replayed in insertion order, an op waiting until every entityId in its `deps` has
    left the queue (child never precedes parent; a failed parent blocks its children). One attempt
    per op per flush; failures stay queued and retry on the next reconnect (`startOutboxSync` +
    `onlineManager`). `clearOutbox()` for the wipe paths. Idempotency is the backend's job (replay
    safety). Unit-tested with `fake-indexeddb` (order, failure-blocks-dependents-then-retry,
    unknown-handler, clear). Entity-agnostic — not wired to any entity yet.
  - **Slice 2 — CLIENTS wired end-to-end (✅ done; backend part needs the Gradle build):**
    - *Backend:* an offline-authored create carries its client-generated UUID in the **`X-Entity-Uuid`
      header** (chosen over a body field to avoid the record-fanout that's bitten us twice across 5
      DTOs). `ClientService.create(req, ownerId, requestedId)` is **idempotent** — if a client with
      that id exists and the caller owns it, return it (no duplicate on replay); if it belongs to
      someone else, `AccessDenied` (never leak). The `Client` entity already accepts a settable id
      (`@PrePersist` only fills a null). Overload keeps the 2-arg `create` for existing callers. Tests
      in `ClientServiceTest` (persists requested id / idempotent no-insert / foreign id rejected).
    - *Frontend engine upgrade:* per-entity ordering (an update/delete waits for its own create even
      with no explicit dep) + `MAX_ATTEMPTS` cap (a stuck op stops retrying, surfaced later). New tests.
    - *Frontend wiring:* `newUuid()`; `clientsApi.create(req, id)` sends the header; `useCreateClient`/
      `useUpdateClient` write the cache **optimistically** and `enqueue` an op (returning the client with
      its id immediately, so it attaches to an estimate/object before syncing); `init.ts` registers the
      `client` handler + `startOutboxSync` (flush on reconnect, then reconcile); `initOutbox(queryClient)`
      in `main.tsx`; `clearOutbox()` wired into login / logout / dead-session (cross-account safety —
      re-sync-on-relogin is slice 3). Test: `useClients.test` (optimistic + queued op).
    - *Known slice-2 limitations (→ slice 3):* the warn-before-logout-if-pending dialog, owner-tagged
      re-sync on re-login, and rollback/toast on a permanent (4xx) rejection are deferred. Objects &
      estimates still create online-only until their slices.
  - **Write model — queue ONLY when offline (`offlineMutate`):** a mutation goes DIRECT to the API
    when online (ordered, returns the server entity), and only writes optimistically + enqueues when
    offline — with a **network-blip fallback** (a dropped connection mid-request also queues). This
    is deliberate while not every entity is on the outbox yet: it keeps online combined flows
    race-free (e.g. "create an object, then an estimate on it" — the estimate's online create must
    see the object already on the server, which an always-async queue would break). The client-UUID
    still rides the header even online, so a retry stays idempotent. Once every write is queued, this
    can flip to always-queue. Lives in `src/lib/outbox/offlineMutation.ts`. **Critical:** these
    mutations set `networkMode: 'always'` — the TanStack default (`'online'`) PAUSES a mutation while
    offline, so `offlineMutate` would never run and the create would hang until reconnect.
  - **Slice 2b — OBJECTS (projects) wired (✅ done; backend part needs the Gradle build):** same
    pattern. `ProjectService.create(req, ownerId, requestedId)` is idempotent — and the idempotency
    check runs **before `requireWithinLimit`**, so a replay of an already-counted project can't
    spuriously trip the FREE cap. `ProjectController` reads `X-Entity-Uuid`. Frontend: `projectsApi.create`
    id header; `useCreateProject`/`useUpdateProject` optimistic + enqueue; the `project` handler in
    `init.ts`. **Client dependency:** a project referencing an offline-created client enqueues with
    `deps: [clientId]`, so the client syncs first (the server's `loadOwned(clientId)` would 404 otherwise).
    The **FREE object cap is already gated client-side** (`isAtLimit` off the cached count) — and that
    works offline for free, which is the primary anti-abuse defense (over-limit rarely reaches sync).
    Tests: `ProjectServiceTest` (idempotent + skips-limit-on-replay + foreign rejected), `useProjects.test`
    (optimistic + queued op with/without the client dep); `NewObjectPage.test` rewritten to the outbox flow.
  - **Slice 2c — estimate LINE ITEMS wired (✅ done; backend part needs the Gradle build):** the
    frequent on-site action — add/edit/remove line items on an EXISTING estimate, offline. Backend:
    `EstimateService.addItem(..., requestedId)` is idempotent (id in `X-Entity-Uuid`; a foreign id
    rejected); `deleteItem` is now an idempotent no-op if the item is already gone. Frontend:
    `useAddItem`/`useUpdateItem`/`useRemoveItem` route through `offlineMutate` (`networkMode: 'always'`,
    `deps: [estimateId]`) and **optimistically edit the cached estimate + re-derive totals** client-side
    (`recompute` mirrors the server: lineTotal = qty·price, works/materials subtotals, total, balance);
    the `estimateItem` handler in `init.ts` carries the estimateId in the op payload (entityId = the
    item id). Per-entity ordering means an item's add → update → delete replay in order. Tests:
    `EstimateServiceTest` (idempotent add / foreign rejected / idempotent delete), `useEstimate.test`
    (optimistic add+remove recompute + queued op). Catalog-add / batch stay online-only for now.
  - **Slice 2c-2 — estimate CREATE offline (✅ done; backend part needs the Gradle build):**
    `EstimateService.createForProject(..., requestedId)` idempotent (id before the FREE-cap + churn
    counter; foreign project rejected) + controller header. Frontend `useCreateEstimate` (optimistic
    empty DRAFT estimate in the detail cache + a summary prepended to the project's list,
    `deps: [projectId]`, `networkMode: 'always'`) wired into BOTH entry points (ProjectDetailPage
    "new empty estimate" + NewEstimatePage object+estimate flow); `estimate` handler in `init.ts`.
    The whole **client → object → estimate → items** chain is now offline-authorable. Tests:
    `EstimateServiceTest` (idempotent / skips-limit-on-replay / foreign rejected), `useEstimate.test`
    (optimistic seed + queued op), `NewEstimatePage.test` arg fixed.
  - **Slice 3a — sync-status indicator (✅ done, PWA-only):** the outbox engine now publishes a
    reactive `SyncStatus` ({pending, syncing}), updated on enqueue/flush/clear and re-counted from
    Dexie (`subscribeSyncStatus`/`getSyncStatus`; `useSyncStatus` via `useSyncExternalStore`). The
    top banner (`OfflineBanner`) now shows all states: **offline** (saved copy + "очікують: N"),
    **syncing…**, **N waiting** (online but queued/failed), or hidden. `initSyncStatus()` primes the
    count at app start (leftovers from a prior offline session). Test in `outbox.test`.
  - **Slice 3c — warn before logout (✅ done, PWA-only):** the ProfilePage logout confirm now shows a
    stronger, count-aware message (`profile.logoutUnsyncedConfirm`) when `useSyncStatus().pending > 0`
    — logout wipes the device's local copy, so a master must knowingly confirm losing unsynced work.
  - **Slice 3b — over-limit "PRO or delete" gate (✅ done; PWA-only):** the engine now classifies a
    replay failure via a configurable `setOutboxErrorClassifier` — a NETWORK blip / 5xx / 429 retries;
    a permanent 4xx **blocks** the op (new `status: 'blocked'` + `blockReason: 'limit' | 'other'`),
    which is never auto-retried and keeps blocking its dependents. `init.ts` maps a **403 with a
    `*_LIMIT_REACHED` code** → `'limit'`. `SyncStatus` gained `blocked`; `listBlockedOps` /
    `retryBlockedOps` (un-block + flush, e.g. after upgrading) / `dropBlockedOps` (discard + return ids).
    UI: the banner turns into a red **"Не збережено в хмару: N — вирішити"** button → `SyncReviewSheet`
    (lists the held changes; **[Оформити PRO]** via the existing `UpgradeBanner`, **[Спробувати
    синхронізувати]**, **[Видалити ці зміни]** → `dropBlockedOps` + `invalidateQueries` so the never-synced
    optimistic entities drop). Nothing is discarded silently. Tests: `outbox.test` (blocked-not-retried
    → retry-after-upgrade lands; drop returns ids). This closes the anti-abuse design's "PRO or delete".
- **Still remaining (moved to open-questions.md, "Offline follow-ups"):** measurements offline (needs a
  client-side `MeasurementCalc` mirror + tree recompute); owner-tagged **re-sync on re-login** (today the
  outbox is wiped on any auth transition — safe, but no cross-login resume); the **LWW conflict UI**
  (`updated_at`/version on clients & objects — estimate already has `@Version`); catalog-add / batch item
  adds offline; per-item (vs bulk) blocked-op resolution; **photos offline** (a blob outbox — heaviest).
  Under the "tests + green build before push" gate.

## Backend implications (why this isn't purely frontend)

- **Client-provided UUIDs + idempotency:** a create must be safe to replay (upsert-by-id / "create if
  absent"), so a re-run queue never duplicates and offline IDs are stable across sync.
- **Conflict detection:** `updated_at`/version on clients & objects (estimate has `@Version`).
- **FREE-limit behaviour on sync:** the server rejects over-limit; the client turns that into the
  "PRO or delete" decision. Define the response shape (which ids were rejected + why).
- **Delta pull (optional):** a "changes since <ts>" endpoint for efficient re-sync vs full refetch.
