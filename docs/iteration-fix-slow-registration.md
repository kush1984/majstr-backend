# Fix: registration is slow (minutes) when many trades are selected

- **Status:** 🔨 Config change applied (`application.yml`); `./gradlew build` pending at
  the gate. Backend-only — no PWA change, no migration, no API/behavior change.
- **Symptom:** after pressing "Create account" the user waits **several minutes**,
  *especially when many/all trades are selected*. Reported from real use.

## Root cause

`AuthService.register` → `CatalogTemplateService.seedForUser` copies the starter catalog
for every chosen trade with `catalogRepository.saveAll(toCreate)`. The live
`catalog_templates` table holds **~1,900 rows** across all trades (V27 reseed 611 +
V31 1016 + V35 295 + V36 15; V13/V15 were wiped by V27's `DELETE`). A user who ticks
every trade therefore inserts ~1,900 `CatalogItem`s in one go.

**Hibernate JDBC batching was never enabled** (`application.yml` set only
`hibernate.jdbc.time_zone` and `format_sql`). Without `batch_size`, `saveAll` issues
**one INSERT round-trip per row** — ~1,900 sequential round-trips. Against the managed
Postgres in prod (tens of ms RTT each) that is minutes of wall-clock. The number of
round-trips scales with the number of trades, which is exactly why "many trades" is
worse.

Ruled out: the verification email is already `@Async` (fail-soft), so it does not block
`register`. BCrypt(12) is ~300 ms — negligible.

## Fix

Enable Hibernate JDBC batching in the base `application.yml` (inherited by dev + prod;
prod only overrides `jpa.show-sql`):

```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
          batch_size: 100
        order_inserts: true
        order_updates: true
        format_sql: false
```

~1,900 inserts collapse into ~19 multi-row JDBC batches — sub-second instead of minutes.
Batching engages because `CatalogItem`/`CatalogTemplate` IDs are **application-assigned
UUIDs** (`@PrePersist`), not `IDENTITY` (which would silently disable batching).
`order_inserts` keeps same-table inserts contiguous so a batch isn't broken up.

Config-only, correctly scoped. Benefits every bulk `saveAll` path for free: register
seed, `reset-from-template`, add-trades merge, "add new from catalog", and the catalog
price-list import.

## Not changed (deliberate)

- `missingItems` loads the owner's existing catalog into a dedup `seen` set. On
  **register** the new user's catalog is empty, so this costs nothing — not part of this
  bug. (It's bounded ~1,900 on the user-initiated reset/merge paths; leave as-is.)
- No move of seeding off the request thread. Batching makes the synchronous seed
  sub-second while preserving the "never an empty library on first login" guarantee
  (CLAUDE.md) and the single `@Transactional` boundary. Async seeding would risk a
  first catalog fetch racing an empty table — unnecessary complexity given the fix.

## Tests / verification

- No unit test meaningfully asserts a batching YAML property — the project has no
  Testcontainers integration slice, and the standalone Mockito tests never touch
  Hibernate/JDBC. The change is a performance config with **unchanged behavior**.
- Gate: `./gradlew build` must stay green (the app context still loads with the new
  properties; all existing tests pass).
- Manual check: register with **all trades** selected → completes in ~1 s, and the
  full starter catalog is present on first login.

## Incidental (fixed to get the suite green — unrelated to the perf change)

Running the full suite surfaced two pre-existing **test-mock** bugs (not touched by the
YAML change, not production bugs): `EstimateTemplateServiceTest.addItem_...` and
`AdminEstimateTemplateServiceTest.addItem_...` stubbed the item repository with an
**immutable** `List.of(...)`, but `addItem` appends to the returned list — and real
Spring Data derived queries return a **mutable** `ArrayList`, so it works in production.
Fixed the two stubs to return `new ArrayList<>(List.of(...))`, matching real repository
behavior; assertions unchanged.
