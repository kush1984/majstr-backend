# Iteration: offline step 3 — building an estimate from the catalog, offline

Picking positions from the catalog is how estimates actually get built. Typing every line by
hand on a phone is not a real alternative, so "make estimates offline" was only half true
while this needed a signal.

- **Status:** 🔨 backend unverified by me (cannot run Gradle); PWA verified green
- **Migration:** none
- **PWA:** 0.37.0

## Why it could not just reuse the plain item add

The obvious shortcut — resolve the catalog line client-side and queue an ordinary
`estimateItem` create, which is already offline-proven — is wrong, and quietly so.

`addItemFromCatalog` copies the catalog position's price verbatim, and a catalog position may
legally cost **0**: V27 relaxed the catalog CHECK to `>= 0`, and V29 relaxed the estimate-item
CHECK for the same reason, its comment naming "and, latently, adding a 0-price catalog item to
an estimate". But `EstimateItemRequest.unitPrice` still validates `>= 0.01`, deliberately, for
the hand-filled form.

So every 0-price position would have queued happily offline and been **rejected on replay** —
surfacing to the master as "не збережено в хмару" for a line they watched appear. The fix
replays through the real from-catalog endpoint instead, which meant giving that endpoint the
same idempotency the other offline writes have.

## Backend

- `addItemFromCatalog` gains a `requestedId` overload + `X-Entity-Uuid` on the controller,
  mirroring `addItem` exactly (idempotency checked **before** the signed check, so a replay of
  something that already landed returns quietly rather than 409-ing because the estimate was
  signed in between).
- `AddCatalogItemsBatchRequest.Entry` gains an optional `id` — the batch equivalent of the
  header, which cannot carry N ids. A 3-arg compact constructor keeps every existing call site
  compiling (checked with the usual `new …Entry(` sweep before editing).
- The batch add filters entries whose id already exists **per line**, so a partially-applied
  batch resumes instead of duplicating everything that landed. An id belonging to a different
  estimate is still rejected.

No migration: the ids are client-generated primary keys, which the entity's `@PrePersist`
already honours.

## PWA

Both hooks now go through `offlineMutate`. The multi-select stays **one** op carrying the whole
selection — online it is still a single round trip, and offline "add these six" replays as one
unit, with per-line ids so a partial application resumes.

The optimistic preview resolves name/unit/type/price from the **cached catalog** — the same
copy the server performs — so the master sees real lines and correct totals offline, not
placeholders. If the catalog isn't cached, the preview is skipped rather than invented; the
server still adds the line correctly on replay. That case has its own test.

No UI change was needed: `AddItemSheet` never gated itself offline — it simply failed. The
template picker in `NewEstimatePage` stays online-only, correctly: applying a template is a
genuinely server-side flow, not a field copy.

## Testing

Backend: a replayed single add returns the existing line without saving; a partially-applied
batch adds only the missing entry and keeps its client id.

PWA: the line is resolved from the cached catalog with totals re-derived by the shared
`recompute`; a multi-select produces one op with distinct per-line ids; an uncached catalog
item still queues but shows no invented line.

`AddItemSheet.test` needed updating — it pinned the exact batch payload, which now carries an
`id`. Updated to assert the ids are present **and distinct** rather than loosened.

`npm run build`, 302 tests, `eslint`, `typecheck:tests` green.

## Gotchas
- Don't "simplify" the offline catalog add into `estimateItem` — the 0-price case is the trap,
  and it only shows up on replay, long after the master saw the line appear.
- `useAddItemFromCatalog` (singular) is currently used by no component — only the batch is
  wired into `AddItemSheet`. It is kept offline-capable and tested because it is a public hook
  and the two must not drift.
- `npm run test:e2e:offline` still not run here (needs a live backend for `journey.spec.ts`).
