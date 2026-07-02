# Referral-source attribution (partner rev-share prep)

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate.
  V38 migration **drill PASSED** (prod backup → V38, column + partner registry +
  LIGA seed + backfill all hold). PWA verified: **tsc + vitest(62) + vite build green**.
- **Migration:** `V38__add_referral_source.sql` — `users.referral_source` + `partners`.
- **Goal (user, 2026-07-02):** Distinguish where a master came from (direct / a
  partner like Ліга Майстрів) for a fair rev-share and a transparent partner report.
  Built **generically** (any partner as data), **no money math** (billing rev-share
  layers on later) — counts only.

## Backend

- **V38:** `users.referral_source VARCHAR(40) NOT NULL DEFAULT 'DIRECT'` (existing
  rows backfilled to DIRECT by the default) + index; `partners(code UNIQUE, source,
  name, active)` as **data, not hardcode** — seeded with `LIGA`.
- **First-touch resolution** (`ReferralService`): a `ref` (partner link value) wins,
  then a typed `promoCode`, else `DIRECT`; both looked up in the partner registry
  (case-insensitive, active only). `AuthService.register` stamps it **once** —
  never auto-overwritten (integrity for a future rev-share). A rare conflict
  (partner A's link + partner B's code) records as it arrived (ref wins); the admin
  can correct by hand. `RegisterRequest` gained optional `ref` + `promoCode`.
- **Admin by-source report** (`GET /api/admin/metrics/by-source`): per source —
  registered, activated (has an object), PRO clicks, PRO interest. **Four grouped
  queries, no N+1** (`countUsersBySource`, `countActivatedOwnersBySource`,
  `countDistinctUsersBySourceAndType` for CLICK/INTEREST). upgrade_event (V34) makes
  the PRO columns possible; without it they'd just be zero.
- **Admin per-user:** `referralSource` on `AdminUserSummary` + `AdminUserDetail`; a
  `source` filter on the user list (exact match — not LIKE, so no `lower(bytea)`
  repeat of Fix K); manual override `PATCH /api/admin/users/{id}/referral-source`
  (conflicts / survey leads), logged as a sensitive action.

## PWA

- **First-touch capture** (`lib/referral.ts`): `?ref=<code>` on any entry is stored
  once in localStorage (`captureRefFromUrl` on boot in `main.tsx`); a later ref never
  overwrites. `/liga` alias route stores `ref=liga` then → landing (clean URL for the
  partner; the mechanism is generic for any `?ref=`).
- **Registration:** optional collapsible "Є промокод спільноти?" field (zod optional,
  max 40); the payload sends the stored `ref` + typed `promoCode`. A plain
  registration (no ref/code) is unchanged → DIRECT.

## Not changed / confirmed

- `UserResponse` (the master's own /me) does **not** expose `referralSource` — it's
  admin-only. No PWA-user-facing change beyond the promo field.
- Record/constructor fan-out done: `RegisterRequest` (+2), `AdminUserSummary` /
  `AdminUserDetail` (internal factories), `UserRepository.searchAdmin` (+source arg)
  — all call sites + tests updated (`AuthServiceTest`, `AuthControllerTest`,
  `AdminUserServiceTest`, `UserRepositorySearchTest`).
- No money / percentage anywhere — the report is counts only.

## Tests

- `ReferralServiceTest` — ref wins, promo fallback, trim, unknown → DIRECT, blank →
  DIRECT with no lookup. `UserRepositorySearchTest` — source filter trimmed +
  uppercased. `AuthServiceTest`/`AuthControllerTest`/`AdminUserServiceTest` updated
  for the new signatures. PWA `referral.test.ts` — first-touch capture. Drill asserts
  the V38 column/table/seed/backfill.

## Verify (after backend build green)

1. `majstr.pro/liga` (or `?ref=liga`) → register → user's `referralSource = LIGA`.
2. Register with promo `LIGA` (no link) → `LIGA`. Plain register → `DIRECT`.
3. First-touch: a second `?ref=` doesn't change an already-stored ref.
4. Admin: source column + filter; "За джерелом" report (counts); manual source edit.
