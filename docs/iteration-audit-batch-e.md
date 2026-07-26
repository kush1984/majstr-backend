# Iteration: audit batch E — the real test gate, concurrency, and the last L items

The tail of the audit sweep. Batch A put CI in place but left the PWA test sources
non-blocking; this batch makes that gate real, then closes the two concurrency findings and
the remaining small ones.

- **Status:** 🔨 backend unverified by me (cannot run Gradle); PWA verified green
- **Migration:** V68
- **PWA:** unchanged behaviour, test-infrastructure only

## 1. The PWA gate was decorative — now it bites

`typecheck:tests` ran with `continue-on-error: true`, so **35 type errors** sat in the test
sources and CI stayed green. That is worse than no check: it reports coverage that does not
exist. A mock missing a field the component under test reads doesn't fail — it silently
renders the "not set" branch forever, and the test passes while asserting nothing real.

All 35 are fixed and the step is now blocking. What they actually were:

- **Drifted mocks (the majority).** `UserResponse` had been hand-rolled in four files and had
  since grown seven fields (privacy consent, plan expiry, auto-renew, card mask, trial,
  referral code). Same story for `MeasurementRoom.floor`, `ProjectImportFloor.roomsOnThisSheet`,
  the editor-v2 gabarit fields, and `PlanLimits`' two photo caps.
- **Values that never existed in the type at all** — `trade: 'FINISHING'` and `unit: 'PCS'`
  were invented; the real enums have neither. Those mocks had been lying since the day they
  were written.
- **`HTMLElement` has no `.value` / `.checked` / `.disabled`** — 15 assertions read properties
  off Testing Library's generic return type.

**The root fix is `src/test/factories.ts`,** not 35 patches. `aUser()`, `aPlanLimits()`,
`anEstimate()`, `aMeasurementRoom()`, `anImportRoom()`, `anImportFloor()` each declare a
return type and use **no `as` casts**, so the next field added to an interface breaks *that
one file* loudly instead of drifting silently across the suite. Tests override only what they
assert on, which also makes each test's intent legible.

For the DOM reads, `src/test/dom.ts` has `asInput` / `asButton`, which **assert at runtime**
rather than casting. A blind `as HTMLInputElement` would silence the error but leave a wrong
query returning `undefined` — which compares unequal to both `true` and `false` and reads as
a baffling assertion failure. These say "expected an `<input>`, matched `<div>`".

Two smaller ones: `tsconfig.test.json` now includes `src/vite-env.d.ts` (the test program
alone could not resolve the `__APP_VERSION__` define that app sources referenced), and
`vitest.config.ts` carries one **named, explained** cast — vitest 2.x bundles its own vite 5
while the app builds on vite 6, so the two `Plugin` types are nominally different. Removing
that cast means upgrading vitest to 3.x, which is a deliberate dependency decision with 400+
tests behind it, not a drive-by fix. **Flagged, not silently taken.**

Verified locally: `npm run build`, `npx vitest run` (290 passed), `npx eslint .`,
`npm run typecheck:tests` — all clean.

## 2. M4 — plan limits lost a race with themselves

`LimitService` counted rows and then the caller inserted. Two concurrent creates could each
read "2 of 3 used", each conclude there was room, and both insert — leaving a FREE account
permanently over its cap that the app has no way to notice or repair.

Not theoretical here: **the offline outbox replays a queue of creates back-to-back on
reconnect**, which is exactly the burst that loses this race.

Fixed with `findByIdForUpdate` — a `SELECT … FOR UPDATE` on the user row taken at the top of
each `require*` check. The second create blocks until the first commits, then counts the row
it just inserted and refuses correctly. Contention is a user against themselves; different
users never touch the same row.

Two consequences worth stating:

- The `require*` methods **dropped `readOnly = true`** — they take a write lock, they are not
  reads any more. `limitsFor` (a genuine read) keeps it and the plain finder.
- Every caller **must** already be inside the transaction that inserts, or the lock releases
  before the insert and guards nothing. All five were checked and are plain `@Transactional`:
  `ProjectService.create`, `EstimateService` ×3, `EstimateTemplateService`,
  `ProjectPhotoService`.

`LimitServiceTest` now stubs `findByIdForUpdate` for the guards — deliberately, so that if the
lock is ever dropped the tests go red instead of passing against the unlocked finder.

## 3. M5 — the canonical email had no lock and no constraint

`email_canonical` exists to stop one person farming a free plan per gmail alias. But
registration read `existsByEmailCanonical` and then inserted, and **V55 made the index
non-unique on purpose** — its comment grandfathers legacy duplicates that predate the column.

So the naive fix is wrong: adding `UNIQUE` now would fail on that existing data and block a
production deploy. Instead `register` takes a **transaction-scoped Postgres advisory lock**
keyed by the canonical address before the check. Firing `j.o.hn+1@`, `jo.hn+2@`, … in parallel
now serialises: the first commits, the rest see it and get a 409. Released automatically at
COMMIT/ROLLBACK, so a crashed request cannot strand it, and V55's decision is untouched.

The test asserts the **ordering** (`InOrder`: lock, *then* check) — a lock taken after the
check is decoration, and a plain "was it called" assertion would not notice.

## 4. The small ones

- **`project_photo.estimate_id` had no index.** Postgres does not index foreign keys
  automatically, so every estimate DELETE scanned `project_photo` whole to apply
  `ON DELETE SET NULL`, holding the estimate's lock while it did. V68 adds it **partial**
  (`WHERE estimate_id IS NOT NULL`) — only RECEIPT photos carry one, so indexing the NULLs
  would double the size for nothing.
- **The PDF dated estimates in UTC.** `createdAt.atZone(ZoneOffset.UTC)` meant anything
  created after ~22:00 UTC — i.e. after midnight in Kyiv — printed **yesterday's date** on the
  document the client keeps and signs. The zone now lives once in
  `LocalizationConfig.ZONE`, and `ResendEmailService` (which already had it right, separately)
  points at the same constant so the two cannot drift. The new test extracts the PDF text and
  pins a 23:30Z instant to `11.03.2026`, not `10.03.2026`.
- **`consolidate` double-counted a repeated source.** The loop ran over the raw id list, so
  the same estimate listed twice — a double tap in the picker, a retried request — had its
  items copied twice and silently inflated the rollup. Now iterates a `LinkedHashSet`
  (distinct, original order). This is **not** item-level dedup: merging equal positions from
  *different* estimates stays the documented plain concat.
- **`ConstraintViolationException` leaked internals.** It returned `ex.getMessage()` raw —
  `"listPhotos.projectId: must not be null"`, Java identifiers in English shown to a Ukrainian
  master. Detail moved to the log; the response uses the existing `error.validation-failed`.
  Per-field body validation stays un-localized by design (the PWA owns those texts); this is
  the parameter-level path, which has no such counterpart.

## Gotchas
- The `require*` lock only works because its callers are already transactional. If you add a
  new caller, it must be `@Transactional` and read-write — a lock in its own short transaction
  is released before your insert and protects nothing.
- Don't "tidy" `email_canonical` into a UNIQUE index without first checking production for
  pre-existing duplicates; V55 chose non-unique deliberately.
