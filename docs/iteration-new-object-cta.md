# "Новий об'єкт" CTA — honest name + consistent FREE-cap gating (PWA)

- **Status:** ✅ PWA done. tsc clean, vitest (39) + vite build green. PWA-only (no
  backend). Separate PWA commit.
- **Trigger (user feedback):** Two things on the global "+ Новий кошторис" button.

## Issue 1 — inconsistent FREE-limit gating (bug)

The global CTA always creates a **new object** (об'єкт), which the FREE plan caps at 2.
- Об'єкти page: button `disabled` at cap + upgrade banner. ✅
- `/new` submit: `disabled` at cap + banner + toast. ✅ (backstop)
- **Головна: the CTAs never checked the cap** → navigated to `/new` and dead-ended
  (form fills, "Створити" disabled). ❌

**Fix:** `DashboardPage` now computes `atProjectLimit = isAtLimit(projects, maxProjects)`
(same `usePlanLimits` + `isAtLimit` as the other surfaces) and disables the desktop CTA,
the mobile CTA, and the "Новий об'єкт" quick action, plus shows the `UpgradeBanner` — so
all three entry points (Головна / Об'єкти / `/new`) behave identically at the cap. The
first-run empty-state CTA is left enabled (at zero objects you're never at the cap).
`QuickAction` gained an optional `disabled` prop. The predicate `isAtLimit` is already
unit-tested (`usePlanLimits.test.tsx`); the wiring mirrors `ProjectsPage`/`NewEstimatePage`
one-for-one, so no new page-level test was added.

## Issue 2 — the button name was misleading

`/new` always creates a **new object** (client + object + first estimate); the client is
pick-or-create but the object is create-only. So "Новий кошторис" actually meant "новий
об'єкт". Decision (user): keep `/new` object-creation-only and **rename** the global CTA
to be honest, rather than adding an existing-object picker.

- `common.addEstimate` "+ Новий кошторис" → **"+ Новий об'єкт"** (Головна ×2, Об'єкти ×2).
- New key `common.newObject` "Новий об'єкт" → Об'єкти empty-state action (creates an object).
- `estimate.newTitle` and `dashboard.quickNewEstimate` → "Новий об'єкт".
- **Unchanged:** `common.newEstimate` "Новий кошторис" stays for the **in-object** "+ Новий"
  (ProjectDetailPage) — there it genuinely adds an estimate to an existing object. Clean
  split: **"Новий об'єкт"** globally, **"Новий кошторис"** inside an object.

## Files

`features/dashboard/DashboardPage.tsx` (gating + banner + QuickAction disabled),
`features/projects/ProjectsPage.tsx` (empty-state → `common.newObject`),
`locales/{uk,en}.json` (label changes + `common.newObject`).

## Verify (live)

1. FREE master at 2 objects: Головна CTAs (desktop/mobile/quick action) disabled + upgrade
   banner; Об'єкти identical; no more dead-end `/new`.
2. Under the cap: CTA opens `/new` titled "Новий об'єкт"; create works.
3. Inside an object: "+ Новий" still says "Новий кошторис" and adds an estimate.
