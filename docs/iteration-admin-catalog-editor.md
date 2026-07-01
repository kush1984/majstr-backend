# Admin editor for the default catalog + default estimate templates

- **Status:** 🔨 Code complete; backend `./gradlew build` pending at the gate (this
  iteration adds real Java + unit tests, so the build IS the check — no migration).
- **Migration:** none. Both entities (`catalog_templates`, `estimate_templates` +
  `estimate_template_items`) already carry every column needed — this is pure
  application code + admin HTML.
- **Goal (user, 2026-07-01):** Let the admin edit the shared default catalog
  positions and the default estimate templates from the admin panel (left menu),
  instead of touching the DB by hand — and have changes reach masters without a
  backend restart.

## Why "no restart" already holds

Nothing caches these rows (`grep` for `@Cacheable`/`@EnableCaching` → none), so
every read is live. The two default sets reach masters by different mechanics,
which shapes what "picked up" means:

- **Default estimate templates** are applied **live** — a master never copies
  them; `EstimateTemplateService.applyToProject` reads the template's items from
  the DB every time. So an admin edit here reaches **all** masters immediately, no
  versioning, no restart.
- **Catalog templates** are **copied** into a master's own `catalog_items` at
  register / sync. So:
  - **Create** stamps the new position at `currentVersion()+1` → every existing
    master picks it up under "Add new from library" (the version cutoff in
    `CatalogTemplateService.addNewFromCatalog`), fresh registrations seed it.
  - **Update / delete** affect only NEW registrations. A master who already
    copied the item keeps their own copy — `catalog_items` are never touched
    (their pricing is theirs). Decided with the user: **don't touch their copy**
    (sacred-data model). Pushing edits into already-owned items is the separate,
    opt-in "Market-price updates" open question.

## Backend (all new unless noted)

- **DTOs:** `AdminCatalogTemplateRequest` (trade/category/name/type/unit/price;
  server owns `addedInVersion`), `AdminCatalogTemplateResponse`,
  `AdminCatalogTemplatePage` (page + `currentVersion`), `AdminEstimateTemplateRequest`
  (name + nullable trade). Reuses `EstimateTemplateSummary`/`EstimateTemplateDetail`/
  `TemplateItemRequest` for the estimate-template side.
- **Repositories:** `CatalogTemplateRepository.adminSearch(trade,type,q,pageable)`
  — optional filters, paginated; the LIKE pattern is built in Java (mirrors
  `UserRepository.searchAdmin`, avoiding the `lower(bytea)` Fix-K trap).
  `EstimateTemplateRepository.findAllDefaults()` — explicit JPQL (not a derived
  name — dodges the boolean `isDefault` property-parsing gotcha).
- **Services:** `AdminCatalogTemplateService` (list/create/update/delete; create
  bumps the version, update keeps it), `AdminEstimateTemplateService` (list/get/
  create/update/delete + item add/update/remove). Both `log.info` every mutation
  with the actor's email (lightweight audit; a structured audit table stays the
  open question). `AdminEstimateTemplateService` only ever touches **defaults** —
  `loadDefault` rejects a master's own template (404).
- **Controllers:** `AdminCatalogTemplateController` (`/api/admin/catalog-templates`)
  and `AdminEstimateTemplateController` (`/api/admin/estimate-templates` + `/items`).
  Both sit under `/api/admin/**` → `hasRole("ADMIN")` from the existing security
  chain; actor taken from `@AuthenticationPrincipal UserPrincipal`.

## Admin panel (`static/admin/index.html`)

Restructured from a single stacked page into a **left nav** with three views:
**Дашборд** (the existing metrics/funnel/upgrade/growth/users, unchanged),
**Каталог**, **Шаблони кошторисів**. Catalog/templates load lazily on first open.

- **Каталог:** trade/type/name filters + pagination over the 983 rows; a create/
  edit form (same form does both — id present ⇒ PUT, else POST); per-row edit +
  delete; the page footer shows the current catalog version. Copy tells the admin
  the propagation rule ("new position → everyone via Add-new; edits → new
  registrations only").
- **Шаблони кошторисів:** master list of the 102 defaults (name/trade/item count)
  → detail panel with editable name + trade, inline per-item edit (name/type/unit),
  add/remove item, delete template, create new. Every save re-renders from the
  server response; counts refresh.

Enum labels (trades/types/units) are a small hardcoded map in the admin JS
(internal tool). All user-supplied text is `escapeHtml`-ed.

## Not changed / confirmed

- No schema change, no migration, no `added_in_version` bump in a migration (the
  drill's version assertions still hold at 4). Runtime admin creates bump the live
  version but never touch migration-applied data / the drill.
- No entity/record signature changed → no record-constructor fan-out risk.
- Existing user-facing `EstimateTemplateService` (owner-scoped) untouched; the
  admin service is separate so the two auth models don't mix.

## Tests

- `AdminCatalogTemplateServiceTest` — create stamps `currentVersion+1`; update
  mutates fields but leaves the version (and never calls save — dirty checking);
  missing → 404; list maps the page + reports the version.
- `AdminEstimateTemplateServiceTest` — create makes a default owner-less template;
  get/update reject a non-default (master-owned) template; addItem appends at the
  next sort order; removeItem rejects an item from another template; delete.
- Admin HTML is verified by eye (layout in the Launch preview) + manual admin
  login against a running backend.

## Verify (after build green)

1. Admin → Каталог: filter by trade, add a position → it gets the next version;
   an existing master's "Add new from library" then offers it.
2. Edit a position's price → a brand-new registration sees the new price; a master
   who already had it keeps theirs.
3. Admin → Шаблони кошторисів: edit a default's items → a master applying that
   template immediately gets the new composition (no restart).
