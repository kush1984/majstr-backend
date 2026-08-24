# Iteration: the funnel's «поділився з клієнтом» step counted the wrong table

**Status:** code complete, backend build green (1103 tests), NOT pushed (awaiting the user's approval).
**Source:** `C:\Work\prompts\analytics-prompts-final.md`, prompt 1 of 3 (backend only).
**Migrations:** none.
**PWA:** 1.24.1 → **1.24.2** (patch — a backend fix, but the PWA version is the product's one visible
version number).

Prompts 2 (funnel by source + UTM) and 3 (PostHog) are deliberately NOT in this iteration — the
prompt file's own instruction is to stop here, look at the corrected funnel in the admin panel, and
only then decide what the worst step actually is.

---

## 1. The hole

`MetricsService.activationFunnel()` took its `shared` step from
`EstimateShareLinkRepository.countDistinctOwners()` — a `COUNT(DISTINCT …)` over
**`estimate_share_links` alone**, i.e. only the per-estimate `?t=` link.

Sharing from the object goes through a **different table**, `project_share_links`
(`?p=` PORTAL, `?e=` ECONOMY, `?a=` ACT), and that repository had no counting query at all — three
`Optional` lookups and nothing else.

Object-level sharing is the main flow since the portal iteration. So the funnel drew a **cliff at
«кошторис → поділився» that does not exist**, and any product decision of the shape "this step is
where masters drop off" taken on that number would have been a decision about an invented problem.

The same lie existed per-master: `AdminUserService` built `hasShareLink` from the same
estimate-only `countByOwner`, so the admin card said «Поділився хоч раз: ні» about a master who had
published an object portal. Fixing only the aggregate would have been **worse than fixing nothing** —
two numbers on one admin page contradicting each other.

## 2. What `shared` means now — the exact definition

> A master counts in `shared` if they have **ever** minted a link of any of these kinds:
>
> - `project_share_links` with `kind` ∈ {**PORTAL**, **ECONOMY**, **ACT**} — the object-level portals;
> - `estimate_share_links` — any row (the per-estimate `?t=` link).
>
> **`MESSAGE` does not count.** It opens a contact form (`MessageLinkService`) and is routinely minted
> for a supplier or a colleague; counting it would award the step to a master who sent the client
> nothing.
>
> **`revoked` and `expires_at` are NOT filtered.** The step means "ever shared". A filtered step
> would *shrink* over time as masters revoke old links, which a state-reaching funnel step cannot do.
>
> **Union of owner-id sets, never a sum of two counts.** Most masters hold both kinds of link; a sum
> would count them twice and could push the step above the previous one.
>
> Every step of the funnel now also filters `role = USER`.

### The two halves are not equally honest

- **Object half — honest.** A row appears only on a deliberate publish: `ProjectPortalService.state()`,
  `economyState()` and `actState()` only read; the three `update*` methods mint. (The prompt listed
  `sendEmail` among the minting methods — it is not: all three e-mail methods require an already
  published link and 404 without one.)
- **Estimate half — inflated.** The PWA's `SharePortalSheet` mints the per-estimate link in a
  `useEffect` on `open`, i.e. before the master has copied or sent anything.

So the honest reading of the step is: **«опублікував на порталі обʼєкта АБО відкрив шторку шерінгу
кошторису»** — not «надіслав клієнту». Fixing that is a PWA change (mint lazily, on copy/send) and
was deliberately left out of scope: the link has to be shown in order to be copied, so lazy minting
changes the sheet's UX. Worth doing once, consciously — and it will make historical numbers
incomparable a second time.

### `ACT` in the kind list

A master can now enter `shared` by a route that already presupposes a signed estimate. The funnel is
six independent `COUNT`s, not strict transitions, so nothing breaks. If it is ever rebuilt as real
transitions, this is the place to remember.

## 3. ⚠️ Historical figures are not comparable

Before this iteration `shared` counted the per-estimate half only. Every recorded value is therefore
**understated** and **must not be compared** month-over-month across this change.

## 4. What changed

| File | Change |
|---|---|
| `entity/ShareLinkKind` | `SHARED_WITH_CLIENT = {PORTAL, ECONOMY, ACT}` — one place classifies a kind, so the aggregate and the per-master card can never drift |
| `repository/EstimateShareLinkRepository` | `countDistinctOwners()` → `findSharedOwnerIds()` (ids, `role = USER`) |
| `repository/ProjectShareLinkRepository` | new `findSharedOwnerIds(kinds)` + `existsByProjectOwnerIdAndKindIn` — the first counting queries this repository has ever had |
| `repository/ProjectRepository` | `countDistinctOwners()` gained `role = USER` |
| `repository/EstimateRepository` | both `countDistinctProjectOwners*` gained `role = USER` |
| `service/MetricsService` | `sharedWithClientCount()` unions the two id sets; funnel javadoc rewritten (it used to *explain* the assumption this iteration cancels) |
| `service/AdminUserService` | `hasSharedWithClient(userId)` — same rules, per master |
| `dto/ActivationFunnelResponse` | javadoc now states the definition and the incomparability |
| `static/admin/index.html` | `loadFunnel` renders a **step-to-step** percentage under the existing «% від реєстрацій» |

### The step-to-step percentage

`loadFunnel` only ever rendered `n / registered`. That figure falls monotonically and makes every
late step look equally bad, so it never points at *which* step is the worst — which is the only
question the funnel is read for. Each step now also shows «N% з попереднього кроку».

Side note found while verifying: `ActivationFunnelResponse`'s javadoc **already claimed** the admin
page rendered "vs registered and vs the previous step". It did not. Same disease as `shared` — a
documented number that did not exist.

## 5. Tests

`MetricsServiceTest` (Mockito) — the union arithmetic: a master holding both kinds of link counts
once (2, not 3); a master who *only* ever shared from the object counts (the regression on the
actual bug).

`AdminUserServiceTest` — `hasShareLink` is true for an object-only sharer.

`ActivationFunnelSharedStepIntegrationTest` (**new**, Testcontainers) — the SQL itself, which no
Mockito test can reach:

- object-only + estimate-only + both → `shared` delta is **3** (not 1, not 4);
- an `ACT` link counts, a `MESSAGE`-only master does not;
- a master whose only link is **revoked** still counts;
- an admin with an object, an estimate and both link kinds appears in **no** step.

The Testcontainers database is shared across the run, so the tests assert **deltas** and set
membership, never absolute funnel figures.

## 6. Not changed / not verified

- **No migration.** Nothing about the schema was wrong; the query was.
- **`admin/index.html` was not opened in a browser.** It is vanilla JS with no test harness (and the
  prompt says not to build one). The change is six lines inside one template string; the step-to-step
  math was read, not observed rendering.
- The by-source report (`bySource()`) still shows only `registered/activated/clicks/interested` — the
  six-step-by-source split is prompt 2, on purpose.
- `SharePortalSheet`'s eager minting is untouched (see §2).

## 7. Follow-up worth logging

The estimate half of `shared` means "opened the share sheet", not "sent". Proposed as a new
open-questions item — **not added yet**, awaiting the user's word (status changes are explicit).
