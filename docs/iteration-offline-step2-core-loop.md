# Iteration: offline step 2 — the core-loop gaps

Four holes in what a master does every day, found by reading the hooks rather than the docs.
Three of them were not recorded anywhere as open items.

- **Status:** 🔨 backend unverified by me (cannot run Gradle); PWA verified green
- **Migration:** none
- **PWA:** 0.36.1

## What was broken

| | Before |
|---|---|
| Estimate status / name / valid-until / notes / deposit | straight to the network |
| Object status | straight to the network |
| Delete an estimate | straight to the network |
| Delete an object | straight to the network |

"Straight to the network" understates the last three: they also lacked `networkMode: 'always'`,
so TanStack Query's default **paused** them offline. The master tapped delete, nothing visibly
happened, and the pending mutation lived only in memory — closing the app lost it silently.

And the four compound into a dead end: a master over the FREE cap is told "оформіть PRO або
видаліть зайве", and deleting was exactly what they could not do without a signal.

## The fix

All four now go through `offlineMutate` with an optimistic cache patch, matching the pattern
already used by create/update elsewhere.

Two structural choices worth keeping:

- **`projectStatus` is a separate outbox entity, not another branch of `project`.** A queue can
  outlive an app update, so reshaping the existing project-update payload would break ops
  already sitting in a master's IndexedDB — silently, on the one code path whose entire job is
  not losing their work. A new entity name costs nothing and cannot.
- **No `deps` were added.** The engine already orders by `entityId` regardless of entity name
  (`o.entityId === op.entityId && o.seq < seq`), so a status change or delete automatically
  waits for that object's own create. The cross-entity `deps` check explicitly skips self, so
  even a self-referential dep would not deadlock — but relying on the implicit ordering keeps
  the ops honest about what they actually depend on.

## The backend half nobody had noticed

`consolidate`-style replays exposed something else: **deletes were not idempotent.**
`ClientService`, `ProjectService` and `EstimateService` all went through `loadOwned`, which
throws 404 when the row is gone — while `MeasurementService.deleteRoom` had explicitly been
made a no-op, and CLAUDE.md already claimed the whole convention.

That gap was **already live for clients**, which have been offline-deletable for a while. The
failure mode is the same lost-response case that step 1 was about: the delete lands, the reply
does not, the outbox retries, gets a 404 — and the classifier treats a 4xx as a permanent
rejection, so the master is shown "не збережено в хмару" for something that saved perfectly.

All three now return quietly when the row is already gone, and **still enforce ownership when
it exists** — idempotency must not slide into "anyone may delete anything". There is a test for
that specific slide. The SIGNED-estimate refusal (409) is likewise unchanged.

## Testing

PWA: status change and delete queue correctly for both objects and estimates, with the
optimistic patch asserted on both the list and the detail cache; the `projectStatus` op's
entity and payload are pinned so the separate-entity decision cannot be quietly undone.

Backend: each of the three deletes is a no-op when the row is missing (and does not fire the
churn counter), plus a client delete aimed at somebody else's row still throws 403.

`npm run build`, 299 tests, `eslint`, `typecheck:tests` all green. The eslint autofix stripped
two now-redundant type assertions from the new tests; the full gate was re-run afterwards
rather than trusted, since an autofix has broken the build here before.

## Not done here
- Adding catalog positions into an estimate offline (`addItemFromCatalog` / batch) — the next
  chunk, and the one that most affects "can I actually build an estimate offline".
- Notes, economy expenses, photos.
- `npm run test:e2e:offline` was not run: `journey.spec.ts` needs a live backend. Nothing here
  touches the service worker or routing, but that is a reason it is low-risk, not a substitute.
