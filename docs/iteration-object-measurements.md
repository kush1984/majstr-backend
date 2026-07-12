# Iteration: object measurements (Заміри) + substitution into estimates

The headline feature. A master measures an object **once** (by room), then pulls
ready metrics into estimate line quantities with checkboxes — no re-measuring.
A superset over the existing single-line `MeasureCalculator` (which stays as-is).

- **Status:** 🔨 Stage 1 + Stage 2 code complete — backend build on the user; PWA green
  (tsc / 88 tests / build).
- **App version:** PWA `0.6.0 → 0.7.0` (headline feature → minor).
- **Migration:** `V46__measurements.sql` — full model at once (rooms + items +
  `estimate_items.measurement_refs` / `quantity_manual`) so we never migrate twice.
- **PRO-gated:** new `Feature.MEASUREMENTS` (PRO+TEAM), upgrade trigger `MEASUREMENTS`
  (`upgrade_event.trigger_source` is a free VARCHAR — no CHECK, no migration for it).

## Recon (confirmed)

- Latest Flyway = V45 → next free **V46**.
- Object = `Project` (`project_id`). `EstimateItem.quantity` NUMERIC(15,3),
  `unit` enum incl. **M2 / LINEAR_METER** (no new units), sum in
  `EstimateService.toResponse` (server; client never sums money).
- Single-line calc = `MeasureCalculator.tsx` — pure `computeMeasure(mode, segs, openings)`
  (area = Σl·w − Σopenings; length = Σl), payload `{segments, openings}`. **Reused** for
  the SURFACE type; not modified.
- Object screen tabs: Кошторис / Фото / Зміни / Акт (last three placeholders); economy is a
  section below. **Decision: «Заміри» is a 5th tab** (tab bar becomes horizontally scrollable
  on mobile).
- PRO gate = `FeatureGuard.requireFeature` + `PlanConfig`; mirror `OBJECT_ECONOMY`
  (`ObjectExpenseController`/`Service`: requireFeature → loadOwned, owner-scoped, FREE→403
  UPGRADE_REQUIRED). FREE teaser like `ObjectEconomySection` + `UpgradeBanner trigger="MEASUREMENTS"`.

## Data model

```
Project (object)
 └─ measurement_room {id, project_id FK cascade, name, sort_order, created_at}
     └─ measurement_item {id, room_id FK cascade, name, type, unit, result NUMERIC(15,3),
                          payload (text = raw JSON), sort_order}
EstimateItem += measurement_refs (text = JSON array of item ids), quantity_manual (bool)
```

- `MeasurementType`: SURFACE (M2), PARTITION (M2), LINEAR (LINEAR_METER). Unit is derived
  from the type (stored explicitly for the substitution filter).
- `result` = computed **server-side** (source of truth); `payload` = raw entered data (text
  JSON) for re-editing. Payload parsed to compute, stored canonical, returned to the client.

### Result formulas (server)
- **SURFACE** = Σ(l·w) − Σ(w·h·n)  (same as `computeMeasure` area)
- **PARTITION** = HW·left + HW·right + HD·end + WD·top  (faces; defaults left/right/end)
- **LINEAR** = (H·left + H·right + W·top + W·bottom) · qty  (sides; defaults left/right/top)

## Stages

**Stage 1 (this step):** V46 model + backend CRUD (rooms/items, PRO-gated, owner-scoped,
server result calc, tree + per-room/object totals) + PWA «Заміри» tab (rooms, 3 element
types, reuse the calculator for SURFACE, totals) + FREE teaser.

**Stage 2 (done):** «Вибрати з замірів» in the estimate item dialog.
- Backend: `EstimateItemRequest`/`Response` += `measurementRefs` (List<UUID>) + `quantityManual`.
  `EstimateService.addItem`/`updateItem` → `resolveQuantity`: when refs present and not
  hand-edited, quantity is **recomputed server-side** (`MeasurementService.sumForRefs`,
  unit-checked, deleted refs ignored) — the client number is never trusted; otherwise the
  sent quantity stands and the selection is kept as memory. `MeasurementRefs` codec stores
  the ids comma-separated in `estimate_items.measurement_refs`. SIGNED guard = the existing
  `requireNotSigned` (409). Never added to `PublicEstimateView` → no portal/PDF leak.
- PWA: `MeasurementPicker` (inline panel in `ItemForm`) — checkbox tree **filtered to the
  line's unit**, sums picked results → applies to the quantity field, pre-checks previous
  refs (memory), warns if the quantity was hand-edited. A hand-typed quantity or the
  single-line calc sets `quantityManual=true`; the picker's Apply sets it false + stores refs.
  Button shown only for PRO + M2/LINEAR lines; `objectId` passed as undefined for SIGNED
  estimates (both edit + add flows).
- Tests: backend `EstimateServiceTest` (recompute vs manual-keep), `MeasurementServiceTest`
  (sumForRefs sum / unit-mismatch / empty); PWA `MeasurementPicker.test` (unit filter + apply
  + manual warning). Fan-out fixed: 4 `new EstimateItemRequest(` + `EstimateItemResponse` factory.

## Not changed / confirmed
- Single-line `MeasureCalculator` untouched (надбудова).
- Estimate quantity×price / statuses / reopen / portal / PDF unchanged; measurements owner-only.
- No new units (M2 / LINEAR_METER already exist).

## Gotchas
- Payload stored as **text** (raw JSON) — avoids jsonb-cast pain; (de)serialize with the
  injected Jackson 3 `ObjectMapper` (`tools.jackson.*`).
- Cascade: delete object → rooms → items (FK ON DELETE CASCADE).
- Substitution mixes units? Forbidden — a line's refs must all match the line's unit.
