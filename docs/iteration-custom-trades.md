# Iteration — custom (master-invented) trades

PWA `1.8.0 → 1.9.0`. Backend: **V91**. Follow-up (registration): PWA `1.9.2 → 1.10.0`, no schema
change.

## The workflow it serves

A system trade (`Trade` enum) is a key into a reference catalog — every value ships an admin-curated
starter set of works and templates. A master doing "натяжні стелі" or another trade we never seeded
has no such catalog and never will. Before this, his only options were the wrong system trade or
"Інше" with no label.

Now: the master can uncheck every system trade in his profile and add his own — «+ Свій напрям».
There is no reference catalog for it, and the profile says so before he adds one ("Для власного
напряму готового каталогу немає — позиції додасте самі"). He fills it himself, position by
position, the same way he always could under "Інше" — just with his own label on it now.

## Why a nullable FK, not a wider enum

Every system trade lives as a literal in **four CHECK constraints** (`user_trades`,
`catalog_templates`, `catalog_items`, `estimate_templates`) — each new one (BUILDER, METAL, …) meant
a migration touching all four. A master-typed trade cannot work that way: no migration ships every
time someone types a new word into a text box.

So a custom trade is a real row, `user_trade (id, user_id, name, sort_order)`, with a
per-master unique index on the lowercased/trimmed name. `catalog_items` and `estimate_templates`
(own templates only) get a nullable `custom_trade_id UUID REFERENCES user_trade(id) ON DELETE SET
NULL`. The existing `trade` enum column is untouched, stays `NOT NULL`, and a position under a
custom trade simply reads `trade = OTHER` — the same legitimate "Інше" catch-all V33 already
established. A position/template is **either** a system trade (`custom_trade_id` NULL) **or** a
custom one (`trade = OTHER AND custom_trade_id` set) — a CHECK on both tables pins the pairing, and
on `estimate_templates` also pins `is_default = false` alongside it (a system default can never
carry one).

**Why this makes deletion and renaming free:**
- **Delete** — `ON DELETE SET NULL` alone drops every referencing row back to plain OTHER. No
  application-level `UPDATE` is needed: those rows already read `trade = OTHER` (the invariant), so
  clearing the FK is the entire operation. Nothing is lost — the position just stops being special.
- **Rename** — a live FK, so every position/template filed under it picks up the new name on the
  next read. No snapshot to keep in sync.

## `CatalogTemplate` / `TemplateTradeOverride` — deliberately untouched

Both are about the **system** reference catalog: `CatalogTemplate` is the admin-curated starter
library (no `owner`, never personal), and `TemplateTradeOverride` is a master's personal re-filing
of a **system default** template. Neither has anywhere for a custom trade to go, by design — a
custom trade never has a reference catalog, and re-filing a default still only offers system trades.

## Backend surface

- `UserTrade` entity + repository; `ProfileService.addCustomTrade/renameCustomTrade/deleteCustomTrade`
  (409 `CUSTOM_TRADE_DUPLICATE` on a repeat name) under `/api/profile/custom-trades`.
- `CatalogItemRequest`/`Response` and `SaveAsTemplateRequest`/`TemplateTradeRequest`/
  `EstimateTemplateSummary`/`Detail` all gained `customTradeId` (+ `customTradeName` on responses,
  denormalized from the live FK so the client needs no separate lookup).
- `CatalogService`/`EstimateTemplateService` resolve-and-own-check the id (404 if it isn't the
  caller's), then force `trade = OTHER` whenever it's set — the request's own `trade` field is
  ignored in that case, mirroring the DB invariant.
- `ProfileUpdateRequest.trades` relaxed from `@NotEmpty` to `@NotNull` — a master can drop every
  system trade and rely entirely on custom ones. `RegisterRequest.trades` mirrors this as of the
  follow-up below (1.10.0) — see that section for the registration path.
- `UserResponse.from(User, List<UserTrade>)` — the `customTrades` list is a **separate explicit
  query**, not a lazy collection on `User`. Riding a lazy relation would repeat the exact
  "must eager-fetch or `LazyInitializationException` outside the session" trap `trades` already
  carries, and `UserResponse.from` is called from many places (auth, profile, billing) — a plain
  query each caller already has to make anyway has no such failure mode. Every call site
  (`AuthService`, `ProfileService`, `AuthController./me`, `BillingController.startTrial`,
  `ProfileController.uploadLogo`) was updated to pass it.

## PWA surface

- `TradeFilterChips`/`tradeMatches` reworked around `TradeKey = Trade | \`custom:${id}\``. The chip
  list is now built from trades **actually present in the loaded items** — not the master's profile
  — so unchecking a system trade never makes its positions unfilterable, and a custom trade only
  shows once it has a position. The "Інше" chip predicate is `trade === 'OTHER' AND customTradeId ==
  null` — without the second half it would sweep up every custom-trade position too, since they also
  read `trade = OTHER` underneath.
- `CatalogItemForm`'s trade `<select>` offers system trades + `me.customTrades` + OTHER; the item's
  current trade (even if since removed from the profile, or its custom trade since deleted) is
  always in the option list so editing never silently wipes it.
- `TradeSelect` (own-template save/re-file) gained an optional `customTrades` prop; `TemplatesPage`'s
  `TradeMove` only passes it for the master's own template (`!template.isDefault`), matching the
  backend's default/own split.
- `ProfileEditModal` — system-trade checkboxes can now all be unchecked (no client-side "choose at
  least one" error either); a new "Власні напрями" section lists/renames/deletes custom trades with
  the honest empty-catalog note shown **before** the master confirms adding one, not after.
- Fixed 🏷️ placeholder icon for every custom trade (v1) — deliberately not 🔧 (already PLUMBING).

## Not changed

- `CatalogTemplate`, `TemplateTradeOverride`, the admin default-catalog/template editors.
- The starter-set merge flow (`/catalog/add-from-template`) — custom trades have nothing to merge.

## Tests

Backend: `CatalogServiceTest`/`ProfileServiceTest`/`EstimateTemplateServiceTest` cover create/update/
rename/delete + the trade-forced-to-OTHER + ownership-denied paths; `CustomTradeIntegrationTest`
(Testcontainers) proves the CHECK invariants, the `is_default=false` pin, `ON DELETE SET NULL`, and
the per-master unique index against a real Postgres.

PWA: `TradeFilterChips.test.tsx` (real presence, "Інше" never sweeps custom), `CatalogItemForm.test.tsx`
(custom trade in the picker, survives a deleted custom trade), `TradeSelect.test.tsx`,
`ProfileEditModal.test.tsx` (unchecking everything still saves, add/rename/delete flows),
`TemplatesPage.test.tsx` (own-template re-file into a custom trade).

## Follow-up: custom trade at registration (1.10.0, no schema change) — сесія 2026-08-07

A master doing a trade with no system entry had to finish registering under a wrong/OTHER trade,
then go fix it in the profile before he could do anything useful — an extra trip for the exact
scenario this feature exists for.

**`RegisterRequest.trades` relaxed from `@NotEmpty` to `@NotNull`** (empty now allowed), and a new
optional `customTrades: List<String>` field added alongside it. A new `@AssertTrue isTradeChosen()`
cross-field check replaces the old per-field `@NotEmpty` — it passes if `trades` is non-empty **OR**
`customTrades` is non-empty, so a master can register on a self-invented trade alone.

**`AuthService.register` reuses `ProfileService`'s create logic**, not a copy of it — `createCustomTrade`
was extracted from `addCustomTrade` into a package-visible method both call. `AuthService` dedupes
`customTrades` case-insensitively **within the request** before calling it (`TreeSet` with
`CASE_INSENSITIVE_ORDER`): typing (or pasting) the same name twice must merge into one row, not
surface `ProfileService`'s 409 `CUSTOM_TRADE_DUPLICATE` — that check exists for a master editing an
*existing* list, and a brand-new account owns zero trades yet, so a within-request duplicate is
purely a UI accident to swallow, not a real conflict.

PWA: `RegisterPage` gained the same "+ Свій напрям" flow `ProfileEditModal` offers, but simpler —
there's no account yet to save each one against individually, so names are collected into local
state and sent as `customTrades: string[]` on submit rather than round-tripping the
add/rename/delete endpoints. `registerSchema`'s `trades.min(1)` became a `superRefine` on the whole
object (mirrors `isTradeChosen()`): the error still anchors to the `trades` field so the existing
error-display spot under the system-trade checkboxes keeps working unchanged.

Tests: backend — `AuthServiceTest` (custom-trade-only registration, both-kinds, in-request duplicate
merge), `AuthControllerTest` (400 when both are empty, 201 with a custom trade alone). PWA —
`RegisterPage.test.tsx` (blocks on double-empty, submits `customTrades` with no system trade, local
add/remove before submit).

## Open questions logged

1. Emoji picker for custom trades (v1 ships one fixed 🏷️ for all).
2. Whether a popular custom trade (many masters typing the same thing) should be a signal to add it
   as a real system trade with a reference catalog.
3. Moving positions between trades in bulk (system ↔ custom, or custom ↔ custom).
