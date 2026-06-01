# Fix C — Linear-metre unit, FREE plan opens up, share → SENT

- **Status:** ✅ Code complete — `./gradlew test` + app restart (V18) pending a
  local run (Gradle wasn't reachable from the agent's sandbox)
- **Commit:** _(uncommitted at time of writing)_
- **Migrations:** `V18__add_linear_meter_unit`
- **Goal:** four small estimate/status/plan fixes so the FREE plan can actually
  demonstrate value and the estimate lifecycle reflects reality.

## 1. New unit "м.п." (linear / running metre)

- `Unit` enum gains `LINEAR_METER`; `UnitLabel` maps it to **"м.п."** (the API
  still returns the raw enum code — clients translate).
- `V18` extends the `unit` CHECK on `catalog_items`, `estimate_items` and
  `catalog_templates` to allow `LINEAR_METER` (additive; existing rows
  untouched). `LINEAR_METER` (12 chars) fits the existing `VARCHAR(20)`.
- Used by contractors for skirting, window slopes, etc. The PWA unit selects
  must add it (frontend task — the API already accepts/returns it).

## 2. FREE plan: limit only on projects, not on features

- `PlanConfig` FREE features were empty; now FREE grants **CLIENT_PORTAL,
  ONLINE_SIGNATURE, PHOTO_REPORTS** — so a FREE contractor can share a portal
  and have the client sign within their project cap. **BRANDED_PDF stays
  PRO+** (logo on the PDF), and **AI_ASSISTANT stays TEAM**.
- No gating code changed: `DefaultFeatureGuard` reads `PlanConfig`, so the
  matrix edit is enough. The hard gates (CLIENT_PORTAL in `ShareLinkService`,
  ONLINE_SIGNATURE in `PublicEstimateService`) now pass for FREE; the soft
  BRANDED_PDF gate in `EstimatePdfService` still yields a logo-less PDF on FREE
  (basic PDF works).
- PHOTO_REPORTS is granted in the matrix but still has no implementation
  (tracked open question) — granting it changes nothing functional yet.

## 3. FREE = 2 projects

- **Already 2** in `PlanConfig` (`MAX_PROJECTS = 2`) and the limit message is
  dynamic, so nothing to change. `LimitServiceTest` already asserts the 2-cap
  and the "2 об'єкти" wording. Confirmed, no code change.

## 4. Creating a share link flips DRAFT → SENT

- Bug: sharing left the estimate `DRAFT`, so it never reached the "pending
  signature" metric (counts SENT) and showed the "Чернетка" badge.
- `ShareLinkService.create` now sets `SENT` when the estimate is `DRAFT`
  (within the existing transaction; `SIGNED`/`REJECTED` left untouched). This
  feeds `DashboardService.pendingEstimates` and the project-card
  `estimateStatus`. Lifecycle: DRAFT → SENT (share) → SIGNED (client signs).

## Not changed (confirmed)

- Pricing/money logic untouched; ownership checks unchanged (share/sign still
  go through `loadOwned`, foreign → 403).
- `MAX_PROJECTS` value and the limit-exceeded behaviour unchanged (still 2,
  still enforced on create only).

## Tests

- `DefaultFeatureGuardTest` updated: FREE now *can* use portal/signature/photos
  and *cannot* use BRANDED_PDF; `minimumPlanFor(CLIENT_PORTAL) == FREE`.
- New `ShareLinkServiceTest`: create flips DRAFT→SENT, leaves SIGNED untouched.
- New `UnitLabelTest`: LINEAR_METER → "м.п."; every Unit has an explicit label.
- The DB-level unit CHECK and the metric wiring are best verified live (the
  CHECK needs a real DB — Testcontainers is a tracked open question); verify
  after restart.

## Note

`C:\Work\SPEC.md` already documents all four fixes (plans table, unit list,
estimate lifecycle) — the user rewrote it in parallel, so this iteration's
code matches the spec as written.
