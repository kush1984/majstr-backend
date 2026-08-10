# Iteration — community prices: what masters actually charge, read back into the catalog

PWA `1.11.0 → 1.12.0`. Backend: **V94**.

A weekly job turns masters' own ESTIMATE lines into two admin candidate queues — a default
price that has drifted from what the market actually charges, and a position no default covers
at all — with a human in the loop before anything ships, and a master's own price never touched
without their say-so.

---

## 1. Source of truth is the estimate, not the catalog

The catalog is what a master occasionally tidies; the estimate is what he prices for real, every
job. A master edits a number directly on an estimate far more often than he goes back to update
his library — so aggregating catalog prices would miss most of the actual market movement. This
is also why SIGNED status is deliberately **not** required: masters sign rarely (the feature is
still new to most of them), so a signed-only sample would starve the aggregate of data.

The filter: every non-REJECTED estimate line, `type = WORK` only (materials price from a
receipt's real shop price, not this), `unit <> PERCENT` (a percentage is not a price — and this
filter alone already excludes every frozen consolidated-percent line, since only PERCENT lines
ever get frozen), `unit_price > 0` (an unpriced draft line is not a quote).

## 2. Two-level median, against noise from two different directions

**Level 1 — per master.** One master can carry many draft/duplicate estimates repeating the same
line. Without folding his own rows to one number first, his habits alone would drag the median
and inflate the apparent "how many masters agree" count. `EstimateItemRepository.
aggregatePerMasterWorkPrices()` does this in **SQL** (`percentile_cont(0.5)` grouped by
`(owner, lower(trim(name)), type, unit)`) — the heavy pass over what is the biggest table in the
schema stays in Postgres; only the resulting (master × exact spelling) summary rows, orders of
magnitude fewer, reach the JVM. This mirrors the exact SQL/Java split `CatalogItemRepository.
aggregateMasterPositions` already established for the admin catalog-insight screens, for the same
reason: `CatalogNameKey`'s fuzzy normalisation is Java-only, deliberately never duplicated into
SQL (this codebase already carries one mirrored-formula tax; it doesn't need a second).

**Level 2 — across masters.** `PriceInsightMath.trimmedMedian` (pure, no Spring, so it's pinned by
tests without a database) IQR-trims outliers from the per-master values, then takes the median of
what survives, and requires at least **3 masters** after trimming — a position two people
independently priced is easy to write off as coincidence; three is the minimum that isn't.
Trimming runs even at exactly 3 values, but at that sample size the interquartile range's own
interpolation is hostage to the very outlier it would need to exclude (Q3 gets interpolated
*from* the outlier) — nothing gets trimmed. That turns out fine: the **plain median of exactly
three values already ignores either extreme**, since it just picks the middle-ranked one. The
outlier still shows up in `maxPrice` (an honest, visible spread), never in the reported price.

## 3. Two kinds of candidate, one snapshot table

`price_insight_candidate` (V94) is refreshed wholesale every run — the weekly job deletes and
re-saves both kinds, so a position that stops clearing the bar (N dropped, an outlier moved the
trim) disappears rather than lingering from a stale row nothing ever deletes.

- **PRICE_DRIFT** — the fuzzy key matches an existing `catalog_templates` row. Candidate carries
  the current default alongside the proposed median, the master count, and the (trimmed) spread.
- **NEW_POSITION** — the fuzzy key matches nothing we ship. This is a **second source** for the
  existing admin "від майстрів" gap list, not a new screen: `AdminCatalogInsightsService.
  newPositions()` now merges the catalog-sourced candidates (unchanged) with this
  estimate-sourced set (new), catalog-sourced winning any key collision — a master's own catalog
  price is the stronger signal of the two. `PRICE_DRIFT` also joins the existing
  `catalog_insight_dismissals` mechanism (one more `CatalogInsightKind` value) rather than a
  parallel dismiss system.

## 4. Apply is a two-step handoff, on purpose

**Admin apply** (`PriceInsightService.applyPriceDrift`) only ever touches the **shared**
`catalog_templates.suggestedPrice` — `addedInVersion` is left untouched, mirroring
`AdminCatalogTemplateService.update`'s existing rule that an edit must never re-propagate to
masters who already copied the position. It then queues one `CatalogUpdateNotice` per **eligible**
master: `source = LIBRARY` and `defaultPrice` still exactly equal to the **old** default. A master
who set his own price is invisible to this query from the start — his number was never the old
default to begin with, so he gets no notice and nothing about his catalog changes.

**Master accept** (`CatalogController` `POST /update-notice/{id}/accept`) is the only place a
master's own `catalog_item` price actually changes, and it re-checks the same guard at accept
time (`CatalogTemplateService.acceptUpdateNotice`) — if the master edited the price himself in
the gap between the admin's apply and the master opening the app, the notice is declined
automatically (silently, from the master's point of view: it just dismisses) rather than
overwriting a number he already changed. "Закрити" (decline) always just dismisses; it never
touches a price either way.

## 5. The notice model became a real queue mid-build

The first pass reused `catalog_update_notices`' existing shape — one row per master
(`UNIQUE(user_id)`), extended with nullable price fields alongside the migration-only
`positions_added`/`positions_removed`. Code review caught the real problem: repricing several of
one master's positions in the same week would let each new notice silently clobber the last, and
a rare migration count-notice could collide with a price-drift notice in the same slot. V94 was
rewritten before it ever shipped (still local, never applied) to drop the `UNIQUE(user_id)`
constraint and add an explicit `kind` (`COUNT` / `PRICE_DRIFT`) discriminator column, with a CHECK
enforcing each kind carries exactly its own fields and none of the other's
(`catalog_update_notices_kind_shape_check`). The endpoint became a real list
(`GET /api/catalog/update-notice` → `CatalogUpdateNoticeResponse[]`, empty array = nothing
pending) instead of a single optional object; dismiss/accept became id-scoped
(`POST …/{id}/dismiss`, `POST …/{id}/accept`) instead of implicit "the" notice. The PWA banner
shows the oldest pending notice one at a time — resolving it optimistically drops it from the
cached list and reveals the next, never a stack of modals.

## 6. The weekly job

`PriceInsightRefreshJob` — a separate `@Scheduled` bean (same isolation habit as
`TrialReminderService` staying out of `AutoRenewService`: a bug in a reporting job must never
share a class with anything that writes masters' data), Saturday 02:00 Europe/Kyiv, gated by a
real Postgres advisory lock (`pg_try_advisory_xact_lock` — transaction-scoped, released
automatically at commit/rollback, no matching unlock call to forget). The project is single-node
today, so this is currently unreachable in practice, but it costs little to make it a real guard
rather than a comment promising one — the established pattern elsewhere in this codebase (`
TokenCleanupService` et al.) is the comment; this is a deliberate upgrade on it for a job whose
failure mode (double-running the aggregate) is cheap to actually prevent.

## Tests

- **`PriceInsightMathTest`** — pure unit tests pinning the trim/median math: clean 3-value median,
  N=2 below the minimum, an outlier among exactly 3 (median unaffected, nothing trimmed — see §2),
  an outlier among 5 (correctly trimmed, count drops to 4), all-identical values (IQR=0, nothing
  wrongly excluded), the `median()` helper for even/odd/single counts.
- **`PriceInsightServiceTest`** — `weeklyRefresh()`: PRICE_DRIFT vs NEW_POSITION branching, N<3
  rejected, a dismissed key skipped, one master's two spelling variants counting as exactly one
  vote, both queues always replaced wholesale. `applyPriceDrift()`: eligible masters notified,
  `addedInVersion` untouched, a same-price-different-work coincidence correctly NOT notified (the
  Java-side fuzzy match catching what the SQL price-only pre-filter can't), a NEW_POSITION
  candidate rejected (nothing to apply to), multiple eligible masters each getting their own row.
- **`PriceInsightRefreshJobTest`** — lock acquired → refresh runs; lock held elsewhere → skipped.
- **`PriceInsightAggregationIntegrationTest`** (Testcontainers) — the native `percentile_cont`
  query run against real Postgres: one row per (master, exact spelling), REJECTED excluded,
  MATERIAL/PERCENT/zero-price excluded, a real WORK line included.
- **`PriceInsightSchemaInvariantsIntegrationTest`** (Testcontainers) — the V94 CHECK constraints
  actually reject a malformed row (price fields on a COUNT notice, missing price fields on a
  PRICE_DRIFT notice), accept a well-formed one, allow two pending notices for the same master
  (the queue, not the old slot), and confirm PRICE_DRIFT joins the dismissal mechanism.
- **`AdminCatalogInsightsServiceTest`** — new merge test: an estimate-sourced NEW_POSITION
  candidate is folded in, catalog-sourced wins on a key collision.
- **`CatalogTemplateServiceTest`** / **`CatalogControllerTest`** — rewritten for the queue-shaped
  API; new tests for `acceptUpdateNotice`'s price guard (updates when still at the old price,
  never touches a self-edited or MANUAL/IMPORT-sourced item, a COUNT notice just dismisses).
- PWA: `CatalogUpdateNotice.test.tsx` (new) — empty queue renders nothing, a COUNT notice's single
  OK button, a PRICE_DRIFT notice's two real choices (accept calls accept-not-dismiss and vice
  versa), and the queue behavior (resolving the oldest reveals the next).

PWA gate green: lint, both `tsc -b` passes, full vitest suite, `vite build`. Backend build — on
the user (Gradle can't run in this sandbox).

## What this left open

Four items, logged in `docs/open-questions.md`:

1. **Auto-applying high-confidence drifts** (large N, small spread) — Phase 2; every apply today
   is a human decision.
2. **Regional price variation** — one national median is a blunt instrument; the median already
   softens it, but a Kyiv price and a rural price are not the same market.
3. **Custom (master-invented) trades' positions as a signal** — they should point toward adding a
   real system trade (existing open item), never feed directly into a shared default.
4. **A master-invented material's price** — explicitly out of scope for the WORK-only aggregate;
   ties into the existing "how materials come back after V81" item.
