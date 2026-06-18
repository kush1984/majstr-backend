# Catalog merge on trade-add + estimate-list action menu

- **Status:** ✅ Backend + PWA complete. Backend `./gradlew build` pending a local
  run; PWA `tsc -b` clean, vitest green.
- **Migration:** none.
- **Goal:** Two test-found UX fixes — (1) offer to add a trade's starter set when
  a trade is added to the profile; (2) quick delete/reopen from the estimate list.

## 1. Catalog merge by trade (consent-based, never auto-delete)

**Principle:** the catalog is the master's data. The system *offers* to top it up
(with consent), never auto-deletes on trade removal.

- **Backend:** `POST /api/catalog/add-from-template` (`AddTemplatesRequest{trades}`)
  → `CatalogTemplateService.addTemplatesForTrades(user, trades)`. It filters the
  requested trades to the user's **own** trades, then reuses the existing
  `copyMissing` — which already adds only items the user lacks (by name+type+unit)
  and **never overwrites or deletes**. So it's a true merge. `reset-from-template`
  is unchanged (full top-up across all trades). User loaded via `findWithTradesById`
  (Fix-J `@EntityGraph`) → no lazy-init.
- **PWA:** on profile save, diff trades. If a trade was **added**, the modal shows
  a consent prompt — *"You added «X». Add its starter set to your catalog?"*
  [Add] [Not now] → `POST add-from-template`. If a trade was **removed**, just a
  quiet toast ("items stayed in your catalog — remove manually if needed"); the
  catalog is never touched.

## 2. Estimate-list action menu (⋮)

- `ProjectDetailPage` estimate rows get a ⋮ button → an action sheet:
  - **DRAFT/SENT:** "Видалити кошторис" → the same `ConfirmDialog` as in the editor.
  - **SIGNED:** "Переоткрити для змін" (reopen) — **no delete** option shown.
- Reuses the existing backend logic (delete rejects SIGNED; reopen owner-only →
  re-sign) — just a second access point. Row mutations pass the estimate id at
  mutate time. Deleting frees a FREE-limit slot (live count). The row stays a
  normal-flow `<div>` with the navigate button + a separate ⋮ button (no nested
  buttons).

## Bonus fix

`CatalogResetResponse` was typed `{ added }` in the PWA but the backend sends
`{ itemsAdded }` — so the reset-from-template toast silently always said "starter
already present" even when items were added. Aligned the type + both consumers
(`CatalogPage`, the new profile prompt) to `itemsAdded`.

## Tests

- Backend: `CatalogTemplateServiceTest` — `addTemplatesForTrades` merges the
  requested trade without duplicating an existing item; ignores trades the user
  doesn't have (returns 0, no save).
- PWA: `tsc -b` clean; full vitest suite green (the row-menu/profile-prompt are
  wiring over already-tested hooks/dialogs).

## Verify

1. Profile → add a trade → save → "add starter set?" prompt → Add → its items
   appear in the catalog (no duplicates, existing items untouched).
2. Remove a trade → catalog unchanged (items remain).
3. Estimate list: ⋮ on DRAFT → Delete (with confirm) works.
4. ⋮ on SIGNED → "Переоткрити" present, "Видалити" absent.
5. Menu actions == the in-estimate actions (same dialogs/logic).
6. Sentry clean (no lazy-init / lower(bytea)).
