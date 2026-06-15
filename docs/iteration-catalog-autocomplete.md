# Catalog autocomplete — backend search endpoint

- **Status:** ✅ Backend complete — `./gradlew build` pending a local run. Frontend
  (PWA dropdown) is a separate task in `majstr-pwa`.
- **Commit:** _(uncommitted at time of writing)_
- **Migrations / deps:** none.
- **Goal:** Back the "type → suggestions from my own catalog" autocomplete when
  adding an estimate line item (`majstr-pwa/autocomplete-prompt.md`). Backend
  only this iteration, per request.

## Recon (what already existed)

- **Adding a line item:** `POST /api/estimates/{id}/items` (manual,
  `EstimateItemRequest`) and `POST /api/estimates/{id}/items/from-catalog/{catalogItemId}`
  (copy a catalog entry by **id**, price/qty overridable). The "add from catalog
  by id" path already exists — the gap was *finding* the item by name.
- **Catalog search by partial name:** none. `CatalogController` had create / list
  (`?type=`, returns all) / categories / update / delete / reset-from-template.
- **Autocomplete:** none on the backend.
- **Self-filling the catalog** (manual add → save to catalog) needs **no new
  backend**: the existing `POST /api/catalog` create endpoint already persists a
  new item for the owner; the "save to catalog" checkbox is a frontend call to it.

## What shipped (backend)

A single owner-scoped search endpoint; reuses the existing `CatalogItemResponse`
(`{id, name, category, type, unit, defaultPrice}`) — exactly the fields the
dropdown needs, so no new DTO.

- **`GET /api/catalog/search?q=<text>&type=<WORK|MATERIAL>&limit=<n>`**
  (`CatalogController.search`). `q` required; `type` optional; `limit` default
  10, clamped 1..20. Owner = `principal.id()`.
- **`CatalogService.search(ownerId, query, type, limit)`** — builds the LIKE
  pattern in Java, returns `[]` for a blank query (don't dump the whole catalog),
  clamps the limit, maps to `CatalogItemResponse`.
- **`CatalogItemRepository.searchByOwner(ownerId, type, pattern, prefix, Pageable)`**
  — `WHERE owner.id = :ownerId AND (:type IS NULL OR type = :type) AND
  LOWER(name) LIKE :pattern`, ordered **exact-prefix-first then alphabetical**
  (`ORDER BY CASE WHEN LOWER(name) LIKE :prefix THEN 0 ELSE 1 END, LOWER(name)`),
  `Pageable` caps the count.

### SQL typing — the `lower(bytea)` trap is avoided (Fix K lesson)

`pattern` (`%term%`) and `prefix` (`term%`) are **lowercased and built in Java**
and bound as plain text LIKE operands (`LOWER(name) LIKE :pattern`). No parameter
is wrapped in `LOWER(CONCAT('%', :q, '%'))`, so PostgreSQL never infers `bytea`.
The `type` filter is a typed enum in a direct `=` (same proven pattern as the
admin-search fix). Blank/`null` query is handled before any query runs.

## Tenant isolation

Search is owner-scoped at the query (`owner.id = :ownerId`), like every other
catalog read — a master can only ever autocomplete against their **own** catalog.

## Tests

`CatalogServiceTest` (+4): blank query → empty, repo untouched; `"  Плит  "` +
`WORK` → builds `%плит%` / prefix `плит%`, clamps `limit 999 → 20`, page 0, maps
results; no-type → `null` type passed; `likePattern`/`prefixPattern` lowercase +
trim + wrap (incl. Cyrillic). Full SQL execution (the actual `LOWER`/`LIKE`/CASE
order against Postgres) still belongs to the Testcontainers slice — tracked in
open-questions, same as Fix K/J.

## Not in scope / follow-ups

- **Ranking by usage frequency/recency** — `CatalogItem` has no usage stats, so
  v1 ranks prefix-first then alphabetical. Logged as a new OPEN item.
- LIKE wildcards (`%`, `_`) in the query aren't escaped (matches existing catalog
  behaviour); harmless for an owner-scoped autocomplete.
- Frontend dropdown (debounce, keyboard nav, "add manually" + save-to-catalog) —
  separate `majstr-pwa` task.

## Verify (after local build)

`GET /api/catalog/search?q=плит` (authed) → up to 10 of the owner's items whose
name contains "плит", case-insensitive, prefix matches first; `&type=WORK`
filters; empty `q` → `[]`. No `lower(bytea)` in logs/Sentry.
