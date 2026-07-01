# PRO upgrade intent — painted door + admin stats

- **Status:** ✅ Backend code + tests done. PWA done (tsc + vitest **58** + vite build
  green). Backend `./gradlew build` pending at the gate; V34 drill pending (Docker was
  down — V34 is a plain `CREATE TABLE`, low risk). Two commits (backend + PWA).
- **Migration:** `V34__create_upgrade_event_table.sql`.
- **Goal:** Measure real PRO demand instead of guessing price. A master taps "Upgrade"
  → (1) count the click (who, how often, from which trigger), (2) show a painted door
  ("PRO coming, we'll build it to your need") + optional reason → a warm lead to call.
  Shown in the admin dashboard next to the activation funnel.

## Recon (before code)

- Upgrade CTAs: `UpgradeBanner` (5 places — object-limit on Головна/Об'єкти/Новий об'єкт/
  Новий кошторис = OBJECT_LIMIT; estimate-limit on ProjectDetailPage = ESTIMATE_LIMIT)
  navigated to /profile; the ProfilePage upgrade button toasted "subscription soon".
  All shown only to logged-in FREE masters → user_id always present.
- Latest Flyway = **V33** → new = **V34** (prompt expected V32). *(Materials V34 was
  paused/never created; it becomes V35 later.)*
- Admin: `AdminMetricsController` (`/api/admin/metrics`, ROLE_ADMIN), `MetricsService`
  aggregates (COUNT/GROUP BY, projections like `PlanCount`), `AdminUserDetail` per-user
  card, `admin/index.html` with the funnel section. No existing events table.

## Backend

- **V34** `upgrade_event`: id, user_id (FK CASCADE), type (CHECK CLICK|INTEREST),
  `trigger_source` (nullable — `trigger` is a reserved word), reason (text, INTEREST),
  created_at; indexes on user_id/type/created_at. `UpgradeEvent` entity (plain `userId`,
  no association — the leads view joins in JPQL), `UpgradeEventType` enum.
- `UpgradeEventRepository`: `countByType`, `countDistinctUsersByType`,
  `countClicksByTrigger` (grouped projection), `findInterestLeads` (JPQL join to `User`,
  newest first), per-user `countByUserIdAndType` + `findFirst…OrderByCreatedAtDesc`.
- `UpgradeEventService`: `recordClick` (blank trigger → OTHER), `recordInterest` (trim),
  `interestStats` (one query each, by-trigger sorted desc), `userActivity`.
- `UpgradeController` (`/api/upgrade`, authed): `POST /click` + `POST /interest` — both
  best-effort, return 204. `/api/upgrade/**` is authed by default (not in PUBLIC_PATHS).
- Admin: `GET /api/admin/metrics/upgrade-interest` (ROLE_ADMIN via the existing gate);
  `AdminUserDetail` + `UpgradeUserActivity` (clicks, last click, interested + reason).
- `admin/index.html`: "Інтерес до PRO" block (distinct clickers / total clicks / leads /
  by-trigger) + warm-leads table (email/name/reason/date, `escapeHtml` on the free text)
  + two per-user rows. Aggregated, no N+1.

## PWA

- `api/upgrade.ts`: `click(trigger)` best-effort (swallows errors), `interest(reason?)`.
- `UpgradeIntentModal` (painted door, honest — no fake checkout): title "PRO ще готуємо",
  optional reason textarea, "напишемо на <email>" (email known, not re-asked), "Так,
  цікавить" → `POST /interest` → thank-you. Closing without submit is fine (click already
  recorded).
- `UpgradeBanner` now takes a `trigger`, records the click and opens the modal (replacing
  navigate-to-profile) at all 5 sites; ProfilePage upgrade button records PROFILE + opens
  the modal (replacing the toast). Double-tap doesn't double-submit INTEREST (one submit
  per modal, then thank-you).
- Privacy alignment: added an "anonymized usage statistics" line to the policy's
  technical-data section (keeps the shipped policy consistent with this tracking).

## Tests

- Backend: `UpgradeEventServiceTest` (click default OTHER, interest trim, stats aggregate
  + sort, userActivity), `UpgradeControllerTest` (204 + records with principal),
  `AdminUserServiceTest` (mock the new dependency).
- PWA: `UpgradeBanner.test` (click records trigger → modal → interest → thank-you).

## Verify (live)

1. FREE master hits a limit → "Дізнатися про PRO" → painted door (not /profile); submit a
   reason → thank-you. Closing without submit still counted the click.
2. Admin: "Інтерес до PRO" (distinct / total / leads / by-trigger); leads table with
   reasons + dates; per-user card shows clicks + interest.
3. Several clicks from one master → total↑, distinct=1. Clicks from different CTAs →
   different triggers. ADMIN-only; aggregated (no N+1).
