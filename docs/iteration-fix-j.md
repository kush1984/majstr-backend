# Fix J — LazyInitializationException on catalog reset-from-template

- **Status:** ✅ Backend code complete — `./gradlew build` pending a local run
  (Gradle isn't reachable from the agent's sandbox).
- **Commit:** _(uncommitted at time of writing)_
- **Migrations / deps:** none.
- **Source:** production 500 on `POST /api/catalog/reset-from-template`
  (the "Стартовий набір" button).

## Symptom

```
LazyInitializationException: Cannot lazily initialize collection of role
'com.majstr.backend.entity.User.trades' (no session)
  at CatalogTemplateService.resetForUser(CatalogTemplateService.java:43)
  at CatalogController.resetFromTemplate(CatalogController.java:86)
```

## Root cause

`open-in-view: false` (deliberate — see CLAUDE.md), so the Hibernate session is
confined to the service `@Transactional` boundary. `CatalogController.resetFromTemplate`
loaded the user with a plain `userRepository.findById(...)` — its own short
repository transaction closes immediately, returning a **detached** `User`.
`CatalogTemplateService.resetForUser` is itself `@Transactional`, but the
detached entity is **not re-attached** when passed in, so
`user.getTrades()` (a `@ElementCollection(fetch = LAZY)`) cannot initialize →
`LazyInitializationException`. The exception fired *before* any catalog write,
hence a clean 500 with no partial state.

## Fix

`UserRepository.findWithTradesById(UUID)` annotated `@EntityGraph(attributePaths
= "trades")` — fetches the user **and** its trades in one query, so the returned
entity is safe to read or map regardless of session state. Chosen over
controller-level `@Transactional` because it is explicit about the data needed
and doesn't rely on session-open semantics holding for future callers.

- `CatalogController.resetFromTemplate` → `findWithTradesById` (the reported bug).
- `ProfileController.uploadLogo` → `findWithTradesById` (**same latent bug**: it
  reloaded via `findById` then mapped `UserResponse.from`, which reads
  `user.getTrades()` outside a session — would have 500'd identically).

## Audit — every `user.getTrades()` reachable from a controller

| Site | In a session at access? | Verdict |
|------|--------------------------|---------|
| `AuthService.register` / `login` / `refresh` → `UserResponse.from` | service `@Transactional`, user attached | OK |
| `AuthController.me` → `UserResponse.from` | controller `@Transactional(readOnly)` | OK |
| `AdminUserController.list` / `changePlan` → `AdminUserSummary.from` | `@Transactional(readOnly)` / `@Transactional` | OK |
| `ProfileService.updateProfile` → `UserResponse.from` | `@Transactional` | OK |
| **`CatalogController.resetFromTemplate`** → `resetForUser` | `findById`, detached | **FIXED** |
| **`ProfileController.uploadLogo`** → reload + `UserResponse.from` | `findById`, detached | **FIXED** |

No other controller-reachable site touches `trades` on a detached entity.
`CatalogTemplateService` is unchanged — `resetForUser`/`seedForUser` stay
`@Transactional`; the fix is purely at the load site.

## Tests

- `CatalogControllerTest` (new, standalone MockMvc + Mockito):
  - `resetFromTemplate_loadsUserWithTradesAndReturnsCount` — asserts the
    controller loads via `findWithTradesById` (and **never** `findById`),
    returns 200 with `itemsAdded`. This pins the eager-fetch path: a regression
    that reverts to `findById` (re-introducing the lazy bug) fails the test.
  - `resetFromTemplate_returns404WhenUserMissing` — empty optional → 404.

The true `LazyInitializationException` only fires against a real Hibernate
session, which needs a Testcontainers integration slice the project doesn't
have yet (see open-questions). The unit test guards the fix at the seam that
caused it (load method choice); the integration-level guarantee is deferred
with the integration-test gap.

## Verify (after local build)

`./gradlew build` green, then smoke:
- `POST /api/catalog/reset-from-template` for a user with trades → 200, items
  copied (no 500).
- `POST /api/profile/logo` → 200 with the profile body (trades present).

---

## Catalog tenant-isolation audit (reported "one master sees another's catalog")

A follow-up report claimed a **data-isolation leak**: master B sees master A's
catalog after A runs reset-from-template. Diagnosed end-to-end — **the backend
catalog is correctly tenant-isolated; it is not the leak.** Evidence:

1. **Ownership column.** `CatalogItem.owner` is a non-null FK
   (`owner_id`, `optional = false`, `updatable = false`) — every item belongs
   to exactly one user.
2. **Every read is owner-scoped.**
   - list → `CatalogItemRepository.findByOwnerIdOrderByNameAsc(ownerId)` /
     `findByOwnerIdAndTypeOrderByNameAsc(ownerId, type)`.
   - categories → `findDistinctCategoriesByOwner(ownerId)`.
   - `update` / `delete` → `CatalogService.loadOwned(id, ownerId)` throws
     `AccessDeniedException` if `item.getOwner().getId() != ownerId`.
   There is no unfiltered `findAll()` on the catalog repo.
3. **Reset writes only into the current master's catalog.** `copyMissing`
   stamps `.owner(owner)` on each new `CatalogItem` and dedups against
   `findByOwnerIdOrderByNameAsc(owner.getId())` — never a shared space.
4. **Global templates are a separate entity.** `CatalogTemplate` (read-only,
   shared) ≠ `CatalogItem` (per-user). Reset reads templates, writes items.
5. **The principal is the authenticated user.** `JwtAuthenticationFilter`
   resolves `parseUserId(token)` → `findById` → `UserPrincipal`; every catalog
   call uses `principal.id()`. A request with B's token returns B's data.

**Actual cause — frontend (PWA) React Query cache, not the backend.** Catalog
query keys are `['catalog','list',type]` — **not scoped to the user** — and
`useLogin` does **not** clear the cache (it only sets tokens and primes
`ME_QUERY_KEY`). `useLogout` *does* `qc.clear()`, so the leak surfaces on an
**account switch that doesn't go through logout**: B logs in while A's cached
list is still warm (staleTime 30s) and sees it until a refetch. The bytes were
A's, fetched with A's token; React Query simply served the stale cache to B.

**PWA fix — APPLIED** (`majstr-pwa`): `useLogin.onSuccess` now calls `qc.clear()`
before priming `ME_QUERY_KEY` (mirrors `useLogout`), so a login always starts
from an empty cache — no previous account's data can survive an account switch.
Test `useLogin.test.tsx` (renderHook + real QueryClient): a pre-seeded
`['catalog','list','all']` entry is gone after login, and the new user's `/me`
is primed. Full PWA vitest suite green (24 tests). Scoping per-user query keys by
user id remains a possible future hardening (tracked in open-questions), but
clear-on-login is the robust minimal fix.

### Backend hardening (this iteration)

No backend code change was needed for isolation — but regression tests now lock
the ownership guarantee so it can't silently regress:
- `CatalogServiceTest`: cross-user `update` denied (and the foreign item is left
  untouched); cross-user `delete` denied (no `delete` call); `listForOwner`
  queries only the owner-scoped method.
- `CatalogTemplateServiceTest`: `resetForUser` stamps the current user's id as
  `owner` on **every** created item.
