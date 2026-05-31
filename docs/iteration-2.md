# Iteration 2 — Projects, clients, catalog, estimates

- **Status:** ✅ Done
- **Commit:** `5a1353a` (Add clients, projects, catalog and estimates domain)
- **Migrations:** `V3__create_clients_table`, `V4__create_projects_table`,
  `V5__create_catalog_items_table`, `V6__create_estimates_table`,
  `V7__create_estimate_items_table`
- **Goal:** the core domain a contractor works in — clients, projects
  (the unit of value), a personal catalog, and estimates with line items and
  money math.

## Chunks

### Entities
- `Client` — name, phone, address; `owner` (User) 1:N projects.
- `Project` — name, address, description, `status`
  (`ProjectStatus`: DRAFT / ESTIMATING / IN_PROGRESS / COMPLETED /
  CANCELLED); belongs to a client and an owner.
- `CatalogItem` — the contractor's personal library: name, `type`
  (`ItemType`: WORK / MATERIAL), `unit` (`Unit`), `defaultPrice`.
- `Estimate` — belongs to a project; `status` (`EstimateStatus`:
  DRAFT / SENT / SIGNED / REJECTED), `validUntil`, `notes`.
- `EstimateItem` — line item: type, name, unit, `quantity`, `unitPrice`,
  `sortOrder`; `lineTotal = quantity × unitPrice`.

### CRUD endpoints
- `ClientController` → `/api/clients`
- `ProjectController` → `/api/projects` (+ status update endpoint)
- `CatalogController` → `/api/catalog`
- `EstimateController` → `/api/estimates` (+ items, add-from-catalog)
- All thin; delegate to `ClientService`, `ProjectService`, `CatalogService`,
  `EstimateService`.

### Money math (BigDecimal, do not break)
- All money is `BigDecimal`, HALF_UP rounding to kopecks.
- `EstimateService` computes `worksSubtotal`, `materialsSubtotal`, `total`.
- Price rule: `CatalogItem.defaultPrice` is the library price;
  `EstimateItem.unitPrice` is copied from catalog into the estimate and may be
  edited per-project — editing it does **not** mutate the catalog.
- `UnitLabel` maps `Unit` enum values to display labels.

### Ownership checks (cross-tenant access → 403)
- Every service verifies the authenticated user owns the resource before
  read/write; foreign access returns 403.
- Repositories provide owner-scoped lookups (`ClientRepository`,
  `ProjectRepository`, `CatalogItemRepository`, `EstimateRepository`,
  `EstimateItemRepository`).

### Validation, DTOs, paging
- Request/response DTOs are records with `jakarta.validation`:
  `ClientRequest/Response`, `ProjectRequest/Response/StatusUpdateRequest`,
  `CatalogItemRequest/Response`, `EstimateCreateRequest/UpdateRequest/
  Response/Summary`, `EstimateItemRequest/Response/FromCatalogRequest`.
- `PageResponse` — shared pagination envelope.

### Tests
- `ProjectControllerTest` (MockMvc standalone), `ClientServiceTest`,
  `EstimateServiceTest` — Mockito unit tests covering ownership and money
  math.

## Notes / gotchas
- The "limit on number of projects" is **not** enforced here — limits arrive
  in iteration 4. This iteration leaves creation unrestricted.
- Lazy associations must be fetched in the service (`open-in-view: false`),
  never relied on from controllers.
