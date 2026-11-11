# Admin — how long a master is active, and PRO for a term

- **Status:** ✅ Backend + static admin page. `./gradlew build` green before push.
- **Migration:** V199 (`user_activity_session`, `admin_plan_grant`), V200 (`users.plan_ending_reminded_at`).
- **PWA (1.76.0):** `src/api/contract/request-dtos.json` (mirrored pair; `PlanUpdateRequest.months`) and `PlanEndingBanner`.
- **Goal (master, 2026-11-11):** see not just when a master last came in, but how long he used the
  app and when he stopped; grant PRO from the admin for 1, 2 or 3 months, not only forever.

## 1. Sessions — «скільки був активний»

- `LastActiveTracker.touch` already ran once per 5 minutes per master (the throttle). The same
  touch now calls `ActivitySessionService.record`: stretch the master's open session (seen within
  the last **30 minutes**, `GAP`) to now, or open a new one. One UPDATE per touch, an INSERT only
  after a silence.
- **Precision is the throttle**: a session's end is its last let-through touch, so it is known to
  within 5 minutes; a one-touch session shows «< 5 хв».
- The insert is a **native `@Modifying` query, not `save()`** — `save()` flushes at the commit,
  outside the tracker's `try`, and a DB hiccup there would have failed the master's own request.
- Kept **90 days** (`purgeOld`, daily 03:40, `app.activity-sessions.retention-cron`); `ON DELETE
  CASCADE` with the account. No IP, no user agent — start and last-seen only.
- `GET /api/admin/users/{id}/activity` → `AdminUserActivity`: minutes over 7 and 30 days (a session
  straddling the window counts only its part inside), the last 20 sessions (`open` = seen within
  30 minutes), and the last 10 admin plan grants. Kept out of `AdminUserDetail` on purpose.

## 2. PRO for a term

- `PATCH /api/admin/users/{id}/plan` takes `months` (1-3, optional) beside `plan`; the logic moved
  out of the controller into `AdminPlanService`.
- A paid plan with a term ends N **calendar months (Kyiv)** after what is left of the **same plan's
  running term**, or after today — «+1 міс» never shortens a term. No `months` = no end
  (`plan_expires_at = NULL`, as admin grants always were). FREE ignores the term and leaves
  `plan_expires_at` alone (a billing date is billing's business).
- Expiry needs nothing new: `BillingExpiryService` already downgrades every non-null
  `plan_expires_at`; an admin grant has no card, so no grace applies.
- Every change is a row in `admin_plan_grant` (actor email, plan, months, resulting expiry) and a
  log line — the plan-change half of the «Audit log for sensitive actions» open question.
- Admin page: the users list gets a term select beside the plan select (1/2/3 міс, ∞); the user
  card gets «+1/+2/+3 міс», «Без терміну», «Зняти → FREE» (each confirmed), the plan's end date,
  the «Активність» block and «Видачі плану з адмінки».

## 3. «PRO закінчується» for every term that ends (V200)

- Before: only the self-serve TRIAL was warned (`TrialReminderService`, daily over its last days);
  a subscription with auto-renew got the «we will charge» letter; a subscription WITHOUT auto-renew
  and the new termed admin grant got nothing — PRO just vanished.
- `PlanEndingReminderService` (daily 09:35): push + email three days out and again one day out, for
  every dated paid plan that is NOT a renewable subscription (auto-renew ∧ card) and NOT a running
  trial. `isDue` compares `plan_ending_reminded_at` with the latest stage begun, so a rerun never
  repeats a stage and a renewed/re-granted term is warned afresh.
- **«Is the current term a trial»** = `trial_started_at` set ∧ no SUCCESS payment ∧ no admin grant
  after the trial started. The trial query gained the third clause: without it a former trial user
  granted PRO by an admin would be told «пробний період закінчується». Among dated plans that END (not renewing from a card) the two queries are complements —
  `PlanEndingReminderQueryIntegrationTest` pins that none of them gets both or neither.
- PWA: `PlanEndingBanner` over the last three days (same condition: dated, not renewing from a
  card — trials included), «Продовжити PRO» → profile; «✕» hides it until the last-day stage.

## Not changed / confirmed

- `last_active_at` and its device columns are untouched; the list's ordering still reads them.
- The referral reward for a dateless-PRO referrer is unchanged; a TERMED admin grant now has a
  date, so the +30 days stack onto it like any dated PRO.
- `PlanConfig`'s stale FREE comment re-worded (open question closed).

## Gotchas

- Sessions start counting from this deploy; older history does not exist.
- A second instance would keep its own throttle map; sessions stay correct (the open-session lookup
  is in the database), only the touch rate doubles.
