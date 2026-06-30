# Estimate templates — ready-made bundles of works for a typical job

- **Status:** ✅ Backend + PWA complete; V28 validated via the migration drill. PWA
  `tsc -b` clean, vitest **36 passed** (+2 picker), `vite build` green. Backend
  `./gradlew build` + the drill pending at the gate (Claude can't run Gradle).
- **Migrations:** `V28__estimate_templates.sql` (2 tables + 88 system defaults, 333
  items); `V29__relax_estimate_item_quantity_for_templates.sql` (relax estimate_items
  quantity + unit_price CHECKs to `>= 0` — see Follow-up).
- **Goal:** Let a master start an estimate from a ready set of positions instead of
  adding them one by one. Two kinds: 88 **system defaults** (from us) + the master's
  **own** ("save current estimate as template"). Apply = a normal editable estimate.

## Data model

- `estimate_templates` (id, owner_id nullable, name, trade nullable, is_default,
  timestamps). A DB CHECK enforces the pairing: **default ⇒ owner null**, **owned ⇒
  owner set** (`estimate_templates_owner_check`). `trade` null = general.
- `estimate_template_items` (id, template_id FK ON DELETE CASCADE, name, type, unit,
  sort_order). **No quantity, no price** — a template is object-agnostic: quantities
  are filled per object, prices come from the applying master's own catalog.
- Items live in their own table loaded via a separate repo (mirrors Estimate /
  EstimateItem — no cascade collection, open-in-view off).

## The 88 defaults (V28 seed)

- Parsed from `C:\Work\prompts\default-templates.md` (`N. Name :: pos1; pos2; …`,
  grouped by trade header) by `C:\Work\prompts\gen-templates-migration.cjs` (not
  committed). Trade map TILER→TILING, PLUMBER→PLUMBING, ELECTRICIAN→ELECTRICAL; the
  rest pass through. Counts: TILING/PLUMBING/ELECTRICAL/PAINTER/BUILDER 12 each,
  DRYWALL/FLOORING 10, DEMOLITION 8 = **88**, **333 items**.
- The md has position **names only, no units** — the generator resolves each unit by
  matching the name against the default catalog (`majstr-default-catalog.csv`). 6
  near-miss names were aligned to the catalog so **all 333 match** → correct units
  AND working price-substitution at apply-time. Verified in the drill:
  `matched=333`, `items_with_unknown_unit=0`.

## API (all owner-scoped, JWT)

- `GET /api/estimate-templates` → `EstimateTemplateSummary[]` — system defaults
  relevant to my trades (+ general) **plus** my own. Item counts from one grouped
  query (`countByTemplateIds`, no N+1). Loads the user via `findWithTradesById`.
- `GET /api/estimate-templates/{id}` → `EstimateTemplateDetail` — preview composition.
- `PATCH /api/estimate-templates/{id}` — rename my own (defaults read-only → 403).
- `DELETE /api/estimate-templates/{id}` — delete my own (items cascade).
- `POST /api/estimates/{id}/save-as-template` (`{name}`) → 201 summary — names+units+
  type+order kept, quantities/prices dropped, trade null.
- `POST /api/projects/{projectId}/estimates/from-template/{templateId}`
  (`EstimateCreateRequest`) → 201 `EstimateResponse` — new editable estimate: names+
  units in, **quantity 0** (master fills), **unitPrice from my catalog by name match**
  (type/unit/category too), else 0. Reuses `EstimateService.get` for the response.

## Access rules

- Defaults are shared (any authenticated master can read/apply). Own templates are
  private (`loadAccessible` = default OR mine; `loadOwnTemplate` = mine only, for
  rename/delete). `applyToProject` also runs `LimitService.requireCanAddEstimate`
  (FREE per-project estimate cap) — applying a template is creating an estimate.

## Deliberately NOT done

- Quantities are empty (0 placeholder on apply), not pre-filled — see open-questions
  *"typical pre-filled quantities"*. Own templates are single-trade-less (always
  shown to the owner); cross-trade defaults — see open-questions *"templates spanning
  multiple trades"*.

## Tests

- Backend `EstimateTemplateServiceTest` (Mockito): apply substitutes catalog prices
  by name + leaves qty 0 + falls back when no match; apply rejects another master's
  template; save drops qty/price and keeps names/units/type/order (owner set, trade
  null); list folds in item counts; default is read-only for delete.
- Backend `EstimateTemplateControllerTest` (standalone MockMvc): list via
  `findWithTradesById`; save-as-template 201 + blank-name 400; from-template 201.
- PWA `TemplatePickerSheet.test.tsx`: lists own + defaults, preview fetches positions,
  confirm calls `onPick`; empty-own hint.

## PWA

- `api/estimateTemplates.ts` + `useEstimateTemplates.ts` (list / preview / save /
  rename / delete / apply); types in `api/types.ts`; `templates.*` i18n (uk + en).
- `TemplatePickerSheet` — reusable picker: my templates (rename/delete) + defaults
  grouped by trade → preview composition → `onPick`. Doubles as the "my templates"
  management surface (task 1B).
- `NewEstimatePage` — "Тип кошторису: Порожній | З шаблону" segmented control; choosing
  a template applies it on submit (`from-template`) instead of creating an empty one.
- `EstimateEditorPage` — "Зберегти як шаблон" in the ⋮ actions menu (shown only when
  the estimate has items) → name modal → `save-as-template`.

## Follow-up fix (post-run)

Applying a template 500'd: `estimate_items` carried `CHECK (quantity > 0)` **and**
`CHECK (unit_price > 0)` from V7, but an applied template item starts at quantity 0
(the empty placeholder) and a price taken from the master's catalog that may itself
be 0 (V27 allows 0-price catalog items) or absent. **V29** relaxes both to `>= 0`
(mirrors V27's price relaxation). This also fixes a latent bug: adding a 0-price
catalog item to an estimate via `addItemFromCatalog` would have hit the same wall.
Validated on the drill: 0-qty/0-price inserts; negatives still rejected.

## Follow-up: edit own templates' positions (UX requests)

After first use the master asked for a discoverable home + full editing:
- **Dedicated "Шаблони" nav entry** (`/templates`, `TemplatesPage`) under Каталог —
  lists own templates (rename/delete/preview) + defaults by trade (read-only).
- **Template choice surfaced at the entry**: NewEstimatePage leads with
  "Тип кошторису: Порожній | З шаблону"; ProjectDetailPage's "Новий кошторис" opens
  the same choice (applies into the existing project via `applyToProject`).
- **Edit own template items** — new endpoints (own-only, defaults read-only,
  both return the updated `EstimateTemplateDetail`):
  - `POST /api/estimate-templates/{id}/items` (`TemplateItemRequest{name,type,unit}`)
    → append a position.
  - `DELETE /api/estimate-templates/{id}/items/{itemId}` → remove a position.
  - `EstimateTemplateDetail.Item` now carries `id` (needed for remove).
  - PWA: the `TemplatesPage` preview is an editor for own templates (🗑 per row +
    add-position form); tests `EstimateTemplateServiceTest` (+4: add appends with
    next sort, add/remove reject defaults, remove deletes).
- **Dev SW disabled** (`vite.config.ts devOptions.enabled:false`) — the dev service
  worker was serving a stale bundle and hiding every change; prod build keeps the SW.
- Also in this batch: PRO price 399→299 (i18n only).

## Gate

- `./gradlew build` green + `scripts/migrate-drill.sh` (now also exercises V28/V29).

## Verify (after build green)

1. New estimate → "Create from template" → pick "Санвузол повний" → positions in,
   quantities empty, prices from my catalog (empty where I don't have the item).
2. Defaults relevant to trade (tiler sees санвузол, not квартирна електрика).
3. Estimate → "Save as template" → name → appears in "My templates" (no quantities).
4. Applied template = a normal editable estimate.
5. My templates are private; rename/delete work; defaults are read-only.
