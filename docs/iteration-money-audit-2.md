# Review round 2 — §0-§1 of `FIXES-2.md` (2026-10-01)

**Status:** §1 built and green on both repos. Migration **V143**. Source: `C:\Work\prompts\FIXES-2.md`
— the external review of `majstr-backend` at `8726a82` / `majstr-pwa` at `bd9bc09`, continuing
`FIXES.md` and continued by `FIXES-3.md`.

FIXES-3 was worked first (§0 in `35e314c`, then §1 and §2 — see
[iteration-money-audit-3.md](iteration-money-audit-3.md)), which is why three of round 2's items were
already closed when this pass started: **B-32** (+ a/b/c), **B-33** and **B-47** all rode along with
round 3's §0, and **B-39** with it. The migration number is **V143**, not the V141 the review asked
for: V141 and V142 have shipped.

---

## §0 — what round 1 left, re-checked

The review's own list is the record. What this pass acted on is named below; the rest of §0 points at
items in §2 (B-48, B-25/B-26 leftovers) and at the PWA sections, which are not in this pass.

## §1 — backend, must fix

### B-34 — the fork's id translation now outlives the request that forked
V113 forks a system default on first write and hands THAT request a default-id → copy-id map. The
PWA's outbox replays every queued template op addressing the **default's** ids, so op 1 forked and
landed while ops 2..n found the fork already there, were handed an **empty** map, matched nothing —
and were answered as `SUCCESS`. An offline batch of «rename, drop A, retype B, reorder» kept only the
rename, and the editor, which re-seeds its baseline from the answer, showed a bundle that looked saved.

**V143 adds `estimate_template_items.forked_from_item_id`** (nullable self-reference,
`ON DELETE SET NULL` — a shipped position can be deleted by a later catalog rebuild, and losing the
pointer must not take the master's copy with it). `forkDefault` stamps it; `loadWritable` rebuilds the
map from it via `translationFor`, so the translation is reconstructible from the DATA rather than only
from the moment of the copy. The backfill matches `(sort_order, lower(trim(name)), type, unit)` against
the default the override row names and fills **only unambiguous** pairs — a master who has since renamed
or reordered his copy is ambiguous by definition, and his device is no longer holding the default's id
for that row either. A NULL pointer simply leaves that one id untranslated, which is today's behaviour.

Trap found while writing it: **`min(uuid)` does not exist in Postgres.** The `HAVING count(*) = 1` is
what makes the pick safe, so the aggregate is only there to satisfy the GROUP BY.

### B-35 — V137's re-filing stranded the masters' own forks
V137 moved ten shipped DRYWALL norms to `trade = NULL`, scoped `owner_id IS NULL`. But V126 put
`owner_id` **inside** `ux_material_norm` precisely so a fork sits beside the default it hides, so a
fork of one of those ten kept `DRYWALL` while its default moved — and the pair stopped being a pair:
on a DRYWALL line **both** rows answered (the primer was bought twice), on a PAINTER line only the
shipped one did (his correction ignored), and `MaterialNormService.own()` could no longer find the
fork from the default, so «restore default» was a silent no-op and the next edit tried to insert a
second fork onto the unique index.

V143 runs the same UPDATE over the owned rows, with a `DO $$` self-check. No conflict is possible: a
trade-less fork of these names could not already exist. Deliberately **not** a general repair of
«every fork whose default moved» — nothing records what a default's trade used to be, so a broader
rule would be guesswork. **The lesson is the rule: a migration that re-files a shipped norm re-files
the forks in the SAME statement.**

`StrandedNormForkMigrationOnLiveDataIntegrationTest` seeds the fork against V142 and upgrades.

### B-36 — the contract snapshot test could never pass on a fresh checkout
`render()` builds `\n`; `core.autocrlf` hands a fresh Windows checkout `\r\n`. The assertion was green
only on the machine whose own run had rewritten the file — which is every machine that ever ran it,
which is why nobody noticed. **Mirrored pair, fixed on both sides:** `.gitattributes` in each repo
pins the JSON to `eol=lf`, and both tests normalise CR/LF before comparing, because a working copy
predating those lines is still out there.

### B-37 — a sub-kopeck cash amount was a 500 or a 0,00 expense
`@DecimalMin(value = "0.0", inclusive = false)` let `0.004` through; `setScale(2)` made it `0.00`;
then a personal row and an object payment hit their `CHECK (amount > 0)` as a 500 with a Sentry event,
while an object **expense** saved happily as 0,00 (V42 allows `>= 0`) — a cost row worth nothing for
the master to find and delete. Now `@DecimalMin("0.01") @Digits(integer = 13, fraction = 2)`, which
**refuses** the sub-kopeck figure rather than rounding it. This is also the only door for the
object-row edits: `CashFlowService.update` builds `PaymentReceiptEditRequest`/`ExpenseRequest` by hand,
so their own constraints never run.

### B-38 — an omitted `discount` silently meant «raise prices»
`fail-on-null-for-primitives: false` (V135, needed for an unrelated reason) turned a missing primitive
from a 400 into a silent `false`. `discount` reverses the SIGN of money, so it is now
`@NotNull Boolean` on both `EstimateDuplicateRequest` and `EstimateItemsMarkupRequest`, and
`api/types.ts` declares it required. Both PWA call sites always passed it, so nothing breaks; the
contract snapshot moved and was refreshed in both repos.

### B-39 — already fixed
The «receipts to expenses off» IT now flips the flag through `updateHeader`, not a detached setter.
Verified rather than re-fixed.

### B-40 — deleting an estimate deleted the master's own note
B-14 taught the RECALCULATION to keep a shopping row carrying a note (`authoredByMaster` = «edited OR
noted»); `deleteUntouchedByEstimate` still asked only about `edited`, so «взяти в Епіцентрі, спитати
Сергія» survived every recalculation and then vanished with the estimate. The query now spells out the
same two clauses the method names.

### B-19 (reopened) — a habit that was saved, confirmed, and ignored
A Ukrainian keyboard types «2,5». It was stored verbatim, the screen said «збережено», and the READ
path's `new BigDecimal("2,5")` threw — a miss that is swallowed and treated as «no answer». The
correction changed nothing and nothing on the screen was wrong. Separately, nothing was bounded:
`PAINT_COVERAGE` is m²/l and **divides**, so `0.5` multiplies every paint figure by eighteen.

`MaterialPrefs` is now the one definition for both directions: the write CANONICALISES (`2,5` → `2.5`)
and validates per key — waste 0–100, coverage 3–20 m²/l, coats 1–4 **whole**, joint 1–20 mm, sheet
`^\d+[x×*]\d+$` — refusing anything else with 400 `error.material-pref.invalid`; the read stays comma
tolerant, because rows stored before this class exists still carry the comma. **Zero waste is an
answer**, not an error, and a blank value still forgets the habit.

---

## Also this round

**Both message bundles are at parity, and a test says so.** `messages_en.properties` was missing six
keys — three act ones from round 3's §0 (`addendum-line`, `over-estimate`, `percent-line`) and three
material ones — so an English-locale master silently fell back to Ukrainian. Nothing anywhere read
both files, which is why it went unnoticed twice; `LocalizationBundleParityTest` is a set comparison
in both directions and nothing more (wording is a human judgement, a missing key is a mechanical fact).

**One stale comment removed:** `useMarkUpItems` still claimed the optimistic patch rounds «whole
hryvnia on the UNIT price», which round 3's B-47 fix made false on both sides.

## Still open in FIXES-2

§2 (B-41…B-54, should-fix), §3 (norm data corrections) and §4-§5 (PWA) are untouched by this pass.
