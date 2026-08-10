# Iteration — the painter catalog, extended (V96–V99)

Backend migrations V96–V99. No PWA changes — data-only, and no frontend surface needed touching.

## Why

Painter is the second trade the tetris treatment reached, after tiling (V82–V84). But the shape of
the problem is not the same, and the fix had to be different.

Tiling's old catalog was thin — 73 accumulated works, several of them duplicates, that described
tiling the way someone guesses at it. PAINTER's live catalog was the opposite: **152 real,
mostly non-zero-priced positions across 16 categories**, several of them (Фасад, Шпалери,
Мікроцемент, Звукоізоляція, Підвіконня, Вагонка, Стелі, Декор, Оздоблення, Декоративна штукатурка)
not mentioned anywhere in the new 4-price-list source. A literal "rebuild like tiling" would mean
`DELETE FROM catalog_templates WHERE trade = 'PAINTER'` before inserting the new spec — wiping
~85–90 real positions no master asked to lose, to make room for a narrower list. That is the
opposite of what the tiling rebuild was for.

Raised to the user before writing anything (per the standing "ask if something looks off" rule for
this prompt); decision, 2026-08-10: **expand, don't replace** — the new positions get added, and
only genuine duplicates get removed.

## What shipped

### V96 — the catalog, extended by 79 positions, not replaced

Sourced from 4 real price lists (the master's own + a colleague's — the master signed off on the
defaults becoming the shared catalog). Price = median where sources agree, the single source
otherwise. Three open ⚠-variances got a resolved default rather than blocking the migration:
Фарбування (білий) 160 / (у кольорі) 180 [sources 130/160/220]; Поклейка дифузорів 200 [range
100/200/300]; Фарбування 3D панелей 400 [range 300-500] — all three logged in open-questions for
the master to confirm later. Roofing work was dropped outright — not a painter's trade.

Positions the source spec marked «†split» (quoted per m² AND per running metre at the same price)
became two catalog rows, «(м²)» / «(м.п.)».

**Deduplication, two levels**, same rule tiling used:
- *Within the new spec* — no two canonical positions share (normalized name, type, unit).
  «Грунтування» stayed singular; «Улаштування скловолокна» merged into «Армування стін
  скловолокном» rather than shipping as two near-identical rows.
- *Against the live catalog*, fuzzy and punctuation-insensitive — exactly one new-spec position
  already existed verbatim («Грунтовка поверхонь бетоноконтактом», shipped since V27) and was
  **not** re-inserted. Everything else that looked similar but wasn't an exact match — «Армування
  сіткою» vs the new spec's «Армування стін сіткою», several differently-scoped «Штукатурка
  стін ...» rows vs the new spec's generic «Штукатурні роботи (від)», and a dozen more — went into
  a **documentation-only near-duplicate report** (V96's own header comment) instead of being merged
  automatically. Auto-merging near-matches is exactly how the tiling rebuild lost 110 positions the
  first time; this one keeps them visible for a human to review instead.

**Four pre-existing duplicate pairs were found incidentally** while doing this comparison — not
part of the new spec at all, just two historical import waves (V27/V31 vs the V50 tetris import)
describing "work done by someone else, by agreement" under different wording, at different prices.
Surfaced to the user twice (three pairs first, a fourth of the identical pattern found on a closer
sweep); decision both times: **keep the higher-priced row, drop the lower one**. Neither half of
any pair is referenced by `price_insight_candidate` — zero cascade risk.

Net: **152 → 227** (+79 new, −4 duplicates). 8 new categories: Підготовка та захист, Демонтаж,
Штукатурка та армування, Шпаклювання та шліфування, Фарбування, Молдинги та декор, Приховані
двері·тіньові шви·треки·люки, Інше. `added_in_version = 10`.

**Gotcha caught before this shipped**: the first draft matched the 4 duplicate rows to delete by
literal `id`. `catalog_templates` rows are seeded with `gen_random_uuid()` (confirmed in V27), so
those ids exist only on this production database — the DELETE would silently match zero rows on
any fresh install or CI test. Rewritten to match by content (`trade`/`name`/`type`/`unit`/`price`)
instead, the same way `CatalogCleanupOnLegacyDataIntegrationTest` already does it.

### V97 — the extension reaches the one master who already registered

Same mechanics as V83, scoped down to what V96 actually changed (not the whole trade, since V96
wasn't a full rebuild):

1. A master's own copy of one of the 4 removed rows is deleted **only if** it is still
   `source = 'LIBRARY'` at exactly our old price (60.00) — captured into
   `painter_v10_removed_baseline` before the DELETE, dropped after. A repriced copy or a MANUAL
   retype survives untouched, whatever its price.
2. The 79 new positions are pushed (not merely offered via "Додати нові позиції") to every user
   with PAINTER among their trades.
3. `catalog_update_notices` gets one more `kind = 'COUNT'` row — the table has been a queue since
   V94, not a single per-user slot, so this doesn't overwrite or need to merge with any notice a
   master already has pending from another trade's changes.
4. `last_synced_catalog_version` advances to 10 for every current PAINTER-trade user.

One real edge case this surfaced: the leftover-cleanup step (step 1) isn't trade-scoped — it
matches any `LIBRARY` row with the right name/price/type/unit, the same way V83's does — so it also
cleaned 3 rows off two OTHER users whose `catalog_items` still carried `trade = 'PAINTER'` copies
even though neither currently lists PAINTER among their active trades (one dropped the trade for
METAL, one has no trades at all). That's the same design V83 already ships, not a new bug — a
master's catalog holds a position regardless of whether the trade that copied it is still active.

### V98 — 19 templates replaced by 6, ordered by phase (**wrong — see V99**)

V98 shipped on the theory that templates are curated bundles, not broad reference data, and the
source prompt asked for them "перегруповані по фазах" the same way V84 replaced tiling's. That
theory does not survive contact with what the old 19 actually were: real, detailed bundles — one
alone (`ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ`) carried 61 lines — not tetris leftovers. Deleting them lost
real curated work. The user caught it immediately after building locally: "для чого ми прибрали всі
шаблони... треба ревертнути". See V99 below for the correction; this section is left in place as a
record of what shipped first and why it was wrong, not as current behaviour.

### V99 — the fix: restore, fold, collapse, and one more real addition

Three unrelated corrections landed together, because V96–V98 were already applied on the database
this had to run against and an applied migration is never edited:

**1. V98's DELETE reverted, the right way.** The 19 old bundles are restored verbatim — copied from
what V1-V95 actually produce on a fresh throwaway database, not hand-transcribed, same discipline as
"planted, not picked" test data. But a straight revert alone would have re-buried the new
phase-3/4/5 positions with nothing built around them. So the corrected shape follows the *same*
"additive, not destructive" rule V96 already used for the catalog, applied to templates too
("краще трохи більші шаблони ніж купа маленьких" — user, 2026-08-10): new phases fold into whichever
old bundle already covers that scope, and only get their own new bundle where nothing does.

| Old bundle | Gains |
|---|---|
| `ШТУКАТУРКА` (13→22 w/ always-billed 3) | the plaster/reinforcement phase |
| `Стіни під фарбування` (9→27) | the full wall-prep cycle **and** finish-coat phase — both are a more detailed version of what this bundle already did, in the same order |
| `Багети молдінги` (3→11) | the molding/decor phase |
| *(new)* `Захист і підготовка приміщення` | no old bundle covered room-prep/protection alone |
| *(new)* `Приховані двері та тіньові шви` | no old bundle covered hidden-door work at all |

Net: **21 default bundles** (19 restored + 2 new), none of V98's 6 survive as standalone templates.

**2. The †split naming was a real bug, not a style question.** V96 gave each split position two
rows, same name, disambiguated only by a `(м²)`/`(м.п.)` suffix *inside the name text*. Flagged
before touching it: `EstimateTemplateService` joins bundle lines to catalog prices, and dedupes
lines within a multi-select estimate, **purely on `lower(trim(name))` — no unit in the key**. Two
catalog rows sharing a name collide there: `Collectors.toMap(..., (a,b) -> a)` silently keeps only
one in the price-lookup map, and the multi-template-apply `seen` dedup silently drops the second
bundle line. That is exactly why the suffix existed in the first place. Resolved with the user
(2026-08-10, after a `AskUserQuestion` on the mechanism): **collapse each of the 22 pairs to a
single M2 row, unsuffixed** — "якщо є однакові позиції тоді залишаємо одну з них, м², але щоб воно
йшло як одиниця, а не в тайтлі". The LINEAR_METER half of each pair is gone; a master who genuinely
needs to bill one of these per running metre adds a one-off line by hand. Catalog: 79 → 57 new rows
at `added_in_version = 10`. The rename is mirrored onto the one registered painter's already-synced
`catalog_items` copies, baseline-protected the same way as everything else in V97.

**3. Organizational services — PAINTER had almost none.** Prompted directly, with a screenshot of
tiling's own `ОРГАНІЗАЦІЙНІ ПОСЛУГИ` category: PAINTER shipped essentially nothing in this space —
checked, only one loosely related row (`Збирання сміття в мішки після демонтажу`, a different
scope). 11 positions added at `added_in_version = 11`, mirroring tiling's category, adapted wording
(e.g. "Виїзд майстра в магазин для підбору матеріалів" instead of "...для підбору плитки"). **All
11 ship at price 0** — unlike the rest of V96, none came from the 4 real painter price lists that
were this rework's actual source data, so inventing a number here would be exactly the mistake V82
already rejected for its own zero-priced positions. The three a painter bills on nearly every job —
cleanup, waste removal, the warranty callout — are appended to **every** default PAINTER bundle that
doesn't already carry them (63 = 3 × 21 new lines), the same "always-billed" pattern V84 used for
tiling (there it was four items including film covering; PAINTER already has its own more granular
protection items per-bundle, so film covering isn't repeated here).

Master-facing result: two `catalog_update_notices` rows now sit in the queue for the one registered
painter (V97's `+79 / −2`, V99's `+11 / −22`), `last_synced_catalog_version` at 11.

Every template line — restored, folded, or newly appended — resolves against the post-V99 catalog
by exact name; 0 unresolved, checked directly (see Testing).

## The rule that shaped every migration

Same five constraints as the tiling rebuild (`docs/iteration-tiling-catalog-rebuild.md`), plus two
this iteration needed explicitly:

6. **Reference data that already describes real, priced work is not a blank slate.** V82 could
   delete-then-rebuild tiling because the old set was thin and mostly guesswork. A trade whose
   catalog already carries real market prices across categories the new source doesn't cover gets
   *extended*, never wiped — the "additive not destructive" decision this iteration turned on, and
   V99 learned applies to curated templates too, not only reference-data catalogs (V98's mistake).
7. **A join key that omits a real differentiator is a latent bug, not a naming preference.**
   `EstimateTemplateService.nameKey` matches purely on name — anything that needs a second row to
   mean something different (same job, different billing unit) must earn that distinction some
   other way than two rows sharing one name.

## Testing

`PainterCatalogRebuildOnLiveDataIntegrationTest` — same pattern as
`TilingCatalogRebuildOnLiveDataIntegrationTest`: a second database migrated to V95 (the version
before this change), given one PAINTER master and one ELECTRICAL-only master, then migrated to
head (now through V99). Unlike the tiling test, the 4 duplicate-pair positions are **not planted**
by the test — they are left for the real V27/V31/V50 migrations to produce, and the test asserts
against whatever those actually ship (queried at V95, not hardcoded), because content-matching only
proves itself against migration-produced data, not synthetic rows shaped to fit.

Covers: net catalog growth (before-count + 64, not a hardcoded absolute — +79/−4 from V96, −22/+11
from V99); the 4 duplicate leftovers gone and their higher-priced counterparts untouched; no split
suffix survives anywhere (catalog or the master's own copies) and the collapsed position keeps only
its M2 row; the 11 organizational-service positions all ship at 0; no new row collides with an
existing one on the dedup key; other trades untouched; every new/renamed position reaches the one
registered painter and none reach the electrician; a repriced leftover copy survives at the
master's price; a MANUAL retype survives regardless of price; a leftover still quoted on an open
estimate has its catalog copy removed while the estimate's own snapshot is untouched; both notices
carry the right added/removed counts; `last_synced_catalog_version` advances to 11; 21 default
bundles exist (V98's 4 folded-away standalone templates are gone, the 2 genuinely-new ones exist,
the 3 extended old bundles grew by exactly the folded phase + the always-billed 3, the single
biggest old bundle survived at its original size + 3); every default bundle carries the 3
always-billed positions; 0 unresolved lines anywhere.

Before writing (and rewriting, for V99) the test, every migration was dry-run directly against the
live dev database inside `BEGIN; ... ROLLBACK;` (docker exec psql), and separately against a
from-scratch V1→V99 throwaway database built the same way Flyway would (each file in its own
transaction, since a migration using `CREATE TEMP TABLE ... ON COMMIT DROP` needs that to behave
correctly) — every assertion in the final test file was hand-verified against that throwaway
database's actual numbers before being written down. This caught the `gen_random_uuid()`
id-matching bug in V96 and confirmed the exact per-template item counts V99 was expected to produce
before the test ever ran.

### Follow-up (code, not a migration) — a shared position was invisible under its non-owning trade

After V99 built locally, the user reported (with a screenshot) that several new organizational-
service positions showed up in the PAINTER templates but not in the PAINTER catalog view. Root
cause: `catalog_items` has one row per `(owner, name, type, unit)` — the unique index has no room
for a second row under the same name — so a master who already ran TILING had 9 of the 11 org-
service names copied under `trade = 'TILING'` back when V83 pushed them; V99's push correctly saw
those rows already existed by name and skipped duplicating them, but left them tagged TILING. The
PWA's trade filter (`TradeFilterChips.tradeMatches`) matches strictly on that single column, so
selecting the "Малярка" chip hid all 9 — even though they price correctly at apply-time (the
estimate-apply join has no trade filter at all).

Querying `catalog_templates` directly for every `(name, type, unit)` more than one trade
recognizes turned up **42 such keys**, only 9 of which came from V99 — the other **33 predate this
iteration entirely** (BUILDER/DRYWALL/FLOORING pairs like «Кладка перегородки з піноблоку...» that
have had this exact invisible-under-one-filter bug all along, just never triggered by a large
enough overlap to be noticed). That confirmed the general fix was correct, not over-engineering for
one trade pair.

**Fix**: `CatalogTemplateRepository.findNameKeysSharedAcrossTrades()` — one grouped query over
`catalog_templates` returning every `(name, type, unit)` key more than one trade ships, with the
CSV of trades. `CatalogService.listForOwner` builds this into a map once per request and attaches
each item's *other* trades (its own excluded) to a new `CatalogItemResponse.sharedTrades` field
(added via a second `from(item, sharedTrades)` factory overload — the original single-arg `from`
still exists, delegating to `List.of()`, so the other 4 call sites in `CatalogService` needed no
change). `TradeFilterChips.tradeMatches` now checks `sharedTrades` after the item's own trade, and
the chip-visibility scan also treats a `sharedTrades` entry as "present" — so a trade shows up even
on the (currently theoretical) catalog where nothing is directly tagged with it, only shared into
it.

`CatalogItemResponse.sharedTrades` is optional in the TypeScript type even though the server always
sends it — 7 test files construct `CatalogItemResponse` fixtures by hand (the same
record-constructor-fan-out shape as the Java side, just caught by `typecheck:tests` instead of a
grep), and making the field optional avoided touching all of them for data none of those tests
actually exercise.

## Open

- The three ⚠-variances (painting price spread 130/160/220, дифузори 100/200/300, 3D панелі
  300-500) shipped with a resolved default, not the master's own confirmation — logged in
  open-questions for a follow-up conversation.
- Whether "Приховані двері, тіньові шви, треки, люки" deserves to become its own sub-trade rather
  than a PAINTER category — logged, not decided.
- The near-duplicate report in V96 is documentation only; nothing merges or dismisses those
  candidates automatically. A future pass could turn it into an admin-reviewable queue the way
  `price_insight_candidate` (V94) already does for community pricing — not attempted here, scope
  was catalog + templates only.
- The 11 organizational-service positions (V99) all ship at 0 — real for tiling's own positions
  (real data), a placeholder here (no source price list covered this category for painting). The
  master could price these for real in a follow-up.
- The LINEAR_METER billing option for the 22 †split positions is gone (V99 collapsed each pair to
  M2 only). Accepted trade-off for lookup-key correctness, not an oversight — see the join-key rule
  above. If a master's real usage shows this is missed often, the fix is a proper unit-aware key in
  `EstimateTemplateService`, not reviving the suffix-in-name workaround.
