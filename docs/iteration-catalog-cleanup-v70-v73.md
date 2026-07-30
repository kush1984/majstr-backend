# Iteration: cleaning up after the tetris catalog import (V70–V73)

> **Retrospective doc.** Written during the 2026-07-27 catch-up from the diffs (`3098cac`,
> `4789926`, `aa7cfb4`). The migrations themselves are unusually well commented — they are the
> primary source, this is the summary.

- **Status:** ✅ shipped
- **Migrations:** V70, V71, V72, V73 (~1900 lines of SQL)
- **PWA:** none (category is display-only)

## The bug behind all four

V50 (the "tetris" import) claimed it deduped **punctuation-insensitively** and introduced no
duplicates. That was true of the names it compared — but the pre-existing V27-era rows had been
written with punctuation **and connecting words** stripped
(`"Монтаж котельної котел бойлер насоси крани фільтра"`), so a punctuation-insensitive comparison
still saw two different strings and could not tell they were the same work.

Consequences, in the order a master notices them:

1. **One work sold under two names.** Both land in the master's catalog and they cannot tell
   which one an estimate will price.
2. **Default templates reference positions BY NAME**, so the big tetris bundles and the older
   small bundles ended up pointing at *different rows for the same job*.
3. **Four categories that only repeated the trade name** — «ЕЛЕКТРИКА» inside the electrical
   trade is not a grouping, it is 34 positions with nowhere to be, sitting next to the real
   buckets (Розетки, Щит, Освітлення, Свердління) that already existed.

## Two invariants that made this safe to fix at all

Both are now written into `CLAUDE.md`, because every one of these migrations leans on them:

1. **Default catalog data is reference data, copied BY VALUE.** `catalog_templates` is what a new
   master's `catalog_items` are seeded from; editing a template only changes what *future* copies
   receive.
2. **`estimate_items` are snapshots** — own name, unit and price, copied when the line was added,
   **no foreign key** to `catalog_items`. An estimate written last month keeps every figure it was
   written with, whatever happens to the catalog. **Created estimates were not touched and could
   not be.** Never "fix" this with a FK: the client signed those numbers.

## What each migration does

- **V70** — only the fixes with a single correct answer. Duplicate positions and placeholder
  prices were **deliberately left alone** because they need a pricing decision from the owner.
  Added positions carry version 8, so existing masters are offered them via "Додати нові позиції".
- **V71** — collapses the duplicate groups, in **two passes**: first only what a string test can
  *prove* (identical apart from punctuation, a dimension separator, word order, or one word's
  spelling), then the groups where the older row is the same work with its connecting words
  stripped. Within a group **the highest price wins** — the same rule V49 used — so a price a
  master raised themselves survives. `4789926` extended this after a prod deployment issue.
- **V72** — breaks up the four trade-name categories: 107 positions moved into real buckets of
  their own trade, plus a new «Штроблення» because channel-cutting is a distinct job (7
  electrical + 2 plumbing) that no existing bucket fit. **Statements are per position, not per
  keyword**, so the mapping is reviewable line by line. Category is display-only grouping —
  nothing matches on it, which is exactly what makes it redoable.
- **V73** — carries V71/V72 into the catalogs masters **already hold**. V71/V72 only changed what
  a new copy receives; anyone seeded after the tetris import already had the duplicates and the
  trade-name categories in their own `catalog_items`, which is what they actually see.

## Testing

Two integration tests, and they have to be integration tests — this is SQL, and Mockito cannot
see SQL:

- `SeedCatalogInvariantsIntegrationTest` — the seed data's own invariants hold after the rewrite.
- `CatalogCleanupOnLegacyDataIntegrationTest` — runs the cleanup against *legacy-shaped* data,
  i.e. the state a real master's catalog is actually in, not a fresh one.

## Gotchas
- Per-position statements are verbose on purpose. If you regenerate them by keyword to "tidy up",
  the mapping stops being reviewable and the next cleanup has to trust it blindly.
- The market-price half of the old open question is **still open**: whether pre-existing positions
  ever get the master's own prices is an opt-in diff, never a silent overwrite.
