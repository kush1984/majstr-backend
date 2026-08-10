# Iteration: Unified object status (ObjectStage) + InfoPopover

- **Status:** DONE
- **Prompt:** `object-status-unification-prompt.md` — two related steps in one file: (A) unify the
  object's status into one derived vocabulary (was: two languages on screen — "Очікує 1" on the
  dashboard vs "Очікує · 0" in the list filter, because they counted different things); (B) a
  reusable (i)-InfoPopover, curated placement.
- **Version:** majstr-pwa 1.13.3 → 1.14.0 (minor — a new headline capability: the derived stage
  model and the InfoPopover component, not a fix on already-shipped work).

## A) ObjectStage — one derived status

### Root cause (confirmed by recon)

Three places disagreed because they read three different things:
- The dashboard's "Очікує" (`DashboardService.metrics`) counted **SENT ESTIMATES**
  (`estimateRepository.countByProjectOwnerIdAndStatus(SENT)`).
- The object-list filter chip counted **OBJECTS** whose *latest* estimate was SENT
  (`p.estimateStatus === 'SENT'`, PWA-side).
- The object card's badge showed the **latest estimate's own status** (DRAFT/SENT/SIGNED/REJECTED)
  when one existed, falling back to the object's raw `ProjectStatus` otherwise — two vocabularies
  in one badge slot, switching depending on whether an estimate existed yet.

### Model — `ObjectStage` (backend `entity/ObjectStage.java`)

```
CANCELLED > COMPLETED > IN_PROGRESS > PENDING_SIGNATURE > ASSESSMENT   (priority top-down)
```
`ObjectStage.derive(status, completedAt, hasSigned, hasSent)` — a pure static method, unit-tested
directly (`ObjectStageTest`).

**Storage decision (chosen and explained per the prompt's own ask):** no migration. Two manual
states are stored — `CANCELLED` reuses the existing, previously **unused** `ProjectStatus.CANCELLED`
enum value (confirmed via grep: nothing set or read it anywhere before this), and `COMPLETED` reuses
the existing `completedAt` timestamp. The three active sub-stages are never persisted — computed from
whether the object has ≥1 SIGNED or ≥1 SENT estimate. `PublicEstimateService.sign()`'s old
`project.setStatus(IN_PROGRESS)` write is left in place but is no longer load-bearing for display —
documented inline rather than removed, per the prompt's explicit "leave harmless or remove, don't
break anything else" allowance.

**Manual transitions (Завершити / Повернути в роботу / Скасувати / Відновити) reuse the EXISTING
`PATCH /api/projects/{id}/status` endpoint** rather than four new endpoints. This was a deliberate
choice: the PWA already has offline-outbox infrastructure built around this exact endpoint
(`useSetProjectStatus`, `entity: 'projectStatus'`, `X-Entity-Uuid`-idempotent replay) — a queue
already sitting in a master's IndexedDB from before this iteration keeps replaying correctly with
zero new endpoint surface. `applyCompletedAt`'s existing behavior (stamp `completedAt` entering
COMPLETED, clear it leaving) already gives the exact invariant needed, for free.

### Backend wiring

- `ProjectRepository`: `findStageFlags` (batched has-signed/has-sent per project, mirrors
  `EstimateRepository.findLatestEstimateSummaries`'s own batching pattern — no N+1),
  `countInProgressStage`/`countPendingSignatureStage` (dashboard aggregates). Removed
  `findByOwnerIdAndStatusOrderByCreatedAtDesc` (superseded, no longer called anywhere).
- `EstimateRepository.countByProjectOwnerIdAndStatus` removed — the old (buggy) source of the
  dashboard's "Очікує", genuinely dead once `DashboardService` switched to the new count queries.
- `ProjectResponse` gained `stage: ObjectStage`. `listForOwner(ownerId, ObjectStage stage)` fetches
  the owner's whole list, computes stage per project (batched), filters in memory — simpler than
  translating the priority chain into SQL, and cheap enough at a solo master's object count (same
  trade-off the admin `MetricsService` full-table-scan open item already accepts at larger scale).
- `ProjectController`: `GET /api/projects?status=` → `?stage=` (typed `ObjectStage`). The `PATCH
  .../status` endpoint is untouched.
- `DashboardMetricsResponse.pendingEstimates` renamed to **`pendingObjects`** — the whole point of
  this iteration is killing the "estimates vs objects" confusion, so a field that now counts
  objects needed an honest name, not just a fixed value behind a stale one. PWA `DashboardMetrics`
  mirrored.

## B) InfoPopover

One reusable component (`components/InfoPopover.tsx`) — (i) → tap → small panel, closes on a tap
outside (scrim), Esc, or ✕. No existing tooltip/popover component in the PWA (checked per recon
point 5), so this reuses `ActionMenu`'s proven shape instead of inventing a new pattern: portalled
to `document.body` (a clipping ancestor can't slice it), position measured from the trigger and
clamped to the viewport so it can't run off a 375px screen.

**Curated placements** (not on every label):
- Status filter chips legend (`ProjectsPage`) — replaces a separate legend entirely.
- Economy: За договором / Отримано / Залишок (`PaymentsBlock`) — one each, three separate short texts.
- Payment form: Сума vs Отримано (one combined explanation), Дата-умова (the "not a debt, a
  condition" framing).
- Act card: "Не враховувати цей акт" note (`ObjectEconomySection`) — required pulling the note OUT
  of the card's own navigate-`<button>` into a sibling `<p>`, since InfoPopover is itself a button
  and a button inside a button swallows taps (the same class of bug this codebase has fixed before —
  see `ActionMenu`'s own doc comment on why its panel is a sibling, not a nested child).
- Estimate editor: the "✓ Підписано клієнтом — лише перегляд" read-only banner.
- Portal (`static/portal/index.html`) — **one**, near the payment graph. No shared component with
  the React app (the portal has no build step), so this is a hand-rolled equivalent in plain JS/CSS
  with the same three close paths, delegated via a single `document`-level click listener (triggers
  are re-rendered on every `load()`, so binding once up front is simpler than re-wiring per render).

## PWA — unified display

- `ProjectCard` badge: was `estimateStatus ? {…} : {…status…}` (two vocabularies) → always
  `t('status.stage.' + project.stage)`. `PROJECT_STATUS_VARIANT` kept (deprecated) since
  `ProjectResponse.status` itself is kept; nothing renders off it anymore.
- `ProjectDetailPage` hero badge: same fix. Added a ⋮ menu (sibling of the hero content, same
  nested-button-avoidance as above) with the four manual actions, each behind its own
  `ConfirmDialog` — a SEPARATE confirm-state union from the existing estimate-row one, since they
  patch different kinds of things.
- `ProjectsPage`: `Filter` type is now `'ALL' | Exclude<ObjectStage, 'CANCELLED'>` — CANCELLED gets
  no dedicated chip (a master rarely filters TO it), but **"Усі" still counts and shows cancelled
  objects** — a judgment call (the prompt left "окремо/сховано" — separate-or-hidden — genuinely
  undecided); hiding them from the list entirely would make a mis-click unrecoverable from the UI,
  so this session chose the less destructive reading. Filed as an open question in case the master
  wants it hidden after all. URL param renamed `?status=` → `?stage=`.
- `DashboardPage`: deep-links updated to `?stage=IN_PROGRESS` / `?stage=PENDING_SIGNATURE` /
  `?stage=COMPLETED`; `m?.pendingObjects` (renamed field).
- `useSetProjectStatus`'s optimistic patch now also updates `completedAt` and recomputes `stage`
  (`resolveOptimisticStage`, exported/tested standalone) — CANCELLED/COMPLETED are always exactly
  right (top priority regardless of estimates); the "back to active" fallback (Повернути в
  роботу / Відновити, both send `IN_PROGRESS`) is a best-effort guess off the cached
  `estimateStatus` field (the PWA cache doesn't carry "has ANY estimate ever been SIGNED", only the
  latest one's status), corrected a moment later by the real refetch (`onOnlineSuccess: invalidate`)
  — documented as never the value anything rests on.

## Tests

- Backend: `ObjectStageTest` (pure derive() unit tests, all 5 stages + priority ordering),
  `ProjectStageQueriesIntegrationTest` (real Postgres — `nativeQuery = true`, so Mockito can't
  reach these: `countInProgressStage`/`countPendingSignatureStage`/`findStageFlags`, including the
  COUNT DISTINCT-per-object and priority-exclusion cases), `ProjectServiceTest` (3 new: stage from
  the signed flag, no-estimates → ASSESSMENT, filter-by-derived-stage-not-raw-status),
  `DashboardServiceTest`/`ProjectControllerTest` fan-out fixed.
- PWA: `InfoPopover.test.tsx` (7 — open/close via all three paths, portal-escapes-clipping,
  children-vs-text), `ProjectCard.test.tsx` (9 — one badge per stage, proves the estimate-status
  label is gone), `ProjectsPage.matches.test.ts` (3 — the exact bug: derived stage wins over raw
  status), `useProjects.resolveOptimisticStage.test.ts` (5). Full PWA gate green (lint, `tsc -b`,
  `typecheck:tests`, vitest) — 566/566. `portal-check.mjs` extended (source-presence check for the
  portal's hand-rolled popover — no jsdom in that script, so real click behavior isn't exercised
  there, only that the trigger and all three close paths exist in source) — 10/10.
- No live browser/mobile click-through this session (no test account available) — flagged
  explicitly rather than assumed fine, consistent with every other UI change this session.

## Not changed (confirmed)

- Estimate statuses (DRAFT/SENT/SIGNED/REJECTED) — untouched; they still live on the estimate/in the
  economy tab, this iteration only touches the OBJECT's status.
- `completed_at` / "completed this month" dashboard metric — untouched query, still correct under
  the new model (an object cancelled after being completed already had its `completedAt` cleared by
  the existing `applyCompletedAt`, so it naturally drops out — no special-casing needed).
- Economy/portal/PDF isolation, offline-first outbox shape — untouched.
