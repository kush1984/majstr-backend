# Iteration — the tiling catalog, rebuilt (V81–V84)

PWA 0.47.0. Backend migrations V81–V84.

## Why

Two things were wrong at once, and they were the same problem seen from different sides.

**The catalog did not describe the work.** The tiling default set was 73 works accumulated across
V13, V27, V31, V36 and the V50 "tetris" import, then repaired by V70–V73. It described tiling the
way someone guesses at it: a handful of broad positions, several of them duplicates under
different wording. A real tiler prices a job in far more detail.

**Masters did not know the list was theirs to change.** Several told us they had not realised
positions could be edited or added at all. Those two facts compound: a master who cannot find
their work in the list, and does not know they can add it, concludes the product is not for them.
A better empty state does not fix that — a catalog that already says what they do does.

Separately, the shipped **materials** carried invented prices («Клей для плитки 25 кг — 380 ₴»)
that nobody maintains, while the app already has a better answer: receipt-photo import, which
adds a material at the price on the receipt. A stale guess competing with a real number is worse
than no guess.

## What shipped

### V81 — materials leave the default catalog, in every trade

`ItemType.MATERIAL` stays, the works/materials split in totals stays, the object economy stays.
Only the ability to *pick a material from our list* goes away. How materials come back is a
separate decision, deliberately deferred.

### V82 — the tiling catalog rebuilt to 167 works in 11 categories

Sources: the public price list at plytochnyk.ua (149 positions, read 2026-07), 8 of our own with
no equivalent there, and 10 found by research into what tilers routinely bill and no published
list carries — carrying, rubbish removal, covering with film, the warranty callout.

**18 positions ship at price 0**, deliberately: they are quoted per object («договірна»), and a
made-up number is worse than a blank the master fills once. V27 relaxed both price CHECKs to
`>= 0` for exactly this. Two units had to be added first (V80): `DAY` and `FLOOR`.

**Categories are sentence case**, like every other trade's. The first cut kept the source price
list's own CAPS and `SeedCatalogInvariantsIntegrationTest` caught it — the rule "no CAPS
categories" existed because CAPS had always been a tetris-import leftover nobody chose. The
tempting fix was an exception for tiling; the right one was to convert, because a master working
in two trades would have seen two conventions in one list, and an invariant with an exception list
is not an invariant. `ЗІЗ` stays capitalised — it is an acronym, and that carve-out predates this.

### V83 — the rebuild reaches masters who already registered

V82 alone reaches nobody: defaults are copied BY VALUE at registration. The product answer is the
"Додати нові позиції" button — but it is a button, and the whole reason for the rebuild is that
masters were not finding things they never knew to look for. So the positions are **pushed**, to
every user with TILING among their trades, and a one-time notice tells them what changed.

### V84 — 12 bundles replacing 13

The old bundles named positions by NAME and V82 replaced every position, so each would have
priced its lines at 0. They also overlapped badly: «Ванна кімната», «Санвузол повний» and
«Душова з піддоном» were three names for largely the same list. Now: 9 bundles for the jobs a
tiler actually takes, 2 for the niches that are a different trade in practice (pools/mosaic,
natural stone), and «УСІ ПЛИТОЧНІ РОБОТИ» — the renamed catch-all from V50, now holding all 167.

Four positions repeat in every bundle: covering with film, rubbish removal, cleaning up, the
warranty callout. A tiler bills these on nearly every job, and a bundle that omits them teaches
the master to omit them — money they earn and forget to charge for.

### Several bundles into one estimate

`EstimateTemplateService.applyToProject` takes a list. Positions are concatenated in pick order
and **de-duplicated by lowercased name** — the same key the catalog price lookup already uses —
so overlapping bundles cannot bill the same work twice. One estimate, so one limit check.
The offline path (`useApplyTemplate`) replays the same rule locally; one uncached bundle out of
several is a refusal, because applying the rest would produce an estimate that is short a section
and looks complete.

### Finding a bundle in the picker

Turning the picker into a multi-select made a pre-existing problem visible: a master with one busy
trade (BUILDER ships ~20 bundles) scrolls a long way to reach «Паркан профнастил». So the sheet
grew a **search box** — the part that actually helps inside a single trade — plus **trade chips**,
rendered only when the defaults span more than one trade, and the whole bar only when there are
more than 8 default bundles.

Both filter the **view only**: a bundle ticked before searching stays ticked and still counts in
the footer. And the two empty-state texts had to be split — «Ви ще не зберегли жодного шаблону»
under an active search is not just unhelpful, it reads as data loss.

## The rule that shaped every migration

> «якщо щось додано вже майстром і збережено в його позиціях, шаблонах чи кошторисах, то цього не
> чіпаємо»

Stated as constraints in each migration's header, and pinned by tests:

1. **`estimate_items` are never referenced.** Snapshots with no FK — every estimate keeps every
   figure it was written with.
2. **Only `source = 'LIBRARY'` rows are deleted.** MANUAL (typed) and IMPORT (price-list/receipt)
   are the master's. This is what V79 exists for.
3. **A LIBRARY row whose price the master changed stays.** Repricing is work: the row came from
   us, that number did not. V82 captures the shipped prices into `tiling_v9_baseline` *before*
   deleting the old templates, because after the delete there is nothing left to compare against;
   V83 drops the table when done.
4. **Master-built templates are untouched.** Only `is_default` bundles are rewritten.
5. **A position still shipped by ANY trade is kept.** Some names live in several trades, and a
   tiler who is also a builder must not lose their builder copy.

## Testing

Two new integration tests, both on the pattern from `CatalogCleanupOnLegacyDataIntegrationTest`:
a second database in the same container, migrated to the version *before* the change, given
realistic master state, then migrated to head. A clean-database test cannot express any of this —
it has no masters, so there is nothing for the migration to spare.

- `MaterialRemovalOnLiveDataIntegrationTest` — the four V81 guarantees.
- `TilingCatalogRebuildOnLiveDataIntegrationTest` — V82–V84, including that no bundle line is
  orphaned (an unresolvable line prices at 0 forever, and this class of mismatch stopped startup
  in V71).

Old positions used in tests are **planted, not picked** from the shipped seed: a test that names
a real position asserts against data that is free to change in the next catalog edit.

## Open

- The other trades still carry the pre-rebuild catalogs. The 10 research-found positions
  generalise (every trade carries rubbish away) and should travel.
- Whether the "always-billed four" belong in every trade's bundles, or only tiling's.
- How materials come back — receipt import covers the real-price case, but a master pricing a job
  before buying has nothing to pick from.
