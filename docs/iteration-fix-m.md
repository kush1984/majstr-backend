# Fix M — the money-correctness batch from the review (B-12, B-13, B-14, B-21, B-31a, B-31c)

- **Status:** ✅ Code + tests complete, `./gradlew build` **green** (1486 tests).
- **Commit:** _(uncommitted at time of writing — awaiting the user's approval to push)_
- **Migrations:** **V136** `normalize_blank_fiscal_identity.sql` (data only, no DDL).
- **Source:** `C:\Work\prompts\FIXES.md` §2. Six items, taken as one batch because each either gets
  the master the wrong amount of money or the wrong amount of material, and every one of them fails
  **silently** — nothing throws, nothing logs, the figure is simply wrong.

## What each item was

### B-12 — the «ДОВІДКОВО» total on the client's emailed copy

`ActSignedCopyService` summed **gross `amount()` over every receipt row**, while every other receipt
total in the codebase (`WorkActReceiptRepository.sumByWorkActId`, the PDF, the ADDENDUM, `payable`)
sums `billedAmount()` (V115: paid less returned) and **skips the legacy `itemized` rows** whose money
is already in the act lines. So the figure double-counted an itemized receipt and billed back a
partial return.

The arithmetic moved into a package-private `receiptsTotal(List<ReceiptRow>)` so it can be
unit-tested on its own. **The value is dead today** — `ActCumulativeCalculator.forDownload` reads it
only when the act is not SIGNED, and both callers stamp SIGNED first — which is exactly why the
divergence went unnoticed, and exactly why it is now pinned by a test.

### B-13 — the offline replay lookup reached outside the owner boundary

`addManual` resolved the `X-Entity-Uuid` with an **unscoped `findById`**. For an id belonging to
another list that either leaked the row or threw 403 at a master whose own create had simply never
landed. Now `findByIdAndShoppingListId`: a miss means «not mine, not here», which is the create path.

**The test found a sharper second half.** `shopping_list_item.id` **is** the client's uuid (no
`@GeneratedValue`; `@PrePersist` fills a missing one), so `save()` with the id set is a **`merge()`**
— writing a foreign id back would have overwritten that row and **moved it onto this list**, a
cross-object write driven by a request header. So the create now checks `existsById` and, when the id
is taken elsewhere, **drops the offered id and authors its own**. Only a colliding or forged uuid
reaches that branch; the PWA mints a fresh one per queued create.

### B-14 — a note on a calculated row was lost to a recalculation

A hand-typed quantity (`edited`) kept a row alive when the position left the estimate; a **note** did
not, so «взяти в Епіцентрі, спитати Сергія» vanished with the line. `ShoppingListItem` gained
`authoredByMaster()` = `edited || note is present`, and the «gone from the calculation» branch asks
that instead of `edited`.

Deliberately **not** the same predicate as `edited`: a note says something about the material, not
about the number, so the calculator still owns the quantity on such a row.

**One asymmetry is deliberate and documented in place**: the *covered* branch still deletes an open
row even when it carries a note. His own settled purchases already cover the demand, so what is left
open is a top-up he no longer needs — and `shopping_list_item_quantity_check` forbids an open row at
0, so the alternative is a stale figure standing in a shop list. A stale figure costs money; a lost
note does not.

### B-21 — a blank fiscal identity was an identity

`fiscal_fn`/`fiscal_id` arrive on a PATCH and the DTOs stored whatever was sent. `""` is not null, so
a blank-identity receipt was an **identified** receipt: the `IS NOT NULL` lookups returned it and both
readers keyed it as the single string `"|"`.

- read path → a false «цей чек уже є» on every blank receipt of the object, across both tables;
- **money path** → `ActReceiptReconciler` matches on that key alone and, when it matches, stamps
  `billed_on_act_id` and (with `receipts_to_expenses` on) **deletes the object receipt's own-cost
  `object_expenses` row**. Two unrelated blank papers were reconciled into each other: one left the
  reimbursable axis it belonged on and a real cost disappeared from `Прибуток`.

Fixed at every layer, because one layer is not enough when the same rule has four readers:

| where | what |
| --- | --- |
| `dto/FiscalIdentity.java` (new, public) | the one `normalize` / `complete` / `key` |
| `ProjectReceiptRequest`, `WorkActReceiptRequest` | `normalizedFiscalFn/Id()` + `@AssertTrue` refusing **half** an identity |
| `ProjectReceiptService.update`, `WorkActReceiptService.update` | write the normalized values |
| `ReceiptIdentityIndex`, `ActReceiptReconciler` | both `keyOf` delegate to `FiscalIdentity.key` |
| both `findIdentifiedByProjectId` queries | `AND TRIM(...) <> ''` beside the `IS NOT NULL` |
| **V136** | nulls the blanks already stored, both tables, both columns together |

**V136 deliberately does not unwind money.** An object receipt already carrying `billed_on_act_id` was
settled against a **SIGNED** act, whose `doc_hash` must keep verifying — the same rule V134 followed
when it refused to rescan signed acts. So a mis-reconciled row is **reported** (`RAISE WARNING` with
the count), not reverted; the expected count is zero, since the reconciler shipped one release ago
with V134 and the fiscal-QR path is the only thing that ever writes a code.

### B-31a — un-buying a cleared row left it settled

`applyBought(false)` cleared `bought`/`boughtAt` but not `cleared_at`, and a cleared row is settled
just as much as a bought one. So the row stayed **invisible** while its quantity still counted as
covered: the master un-ticked a material and the next recalculation refused to ask for it. It also
dodged `ux_shopping_list_item_open`, which is why `mergeOpenSibling` has to run for a cleared row too.

### B-31c — two lines for one material lost one of them

`MaterialCalculatorService.resolve()` returned `Map<MaterialLineRequest, Material>`, keyed by the
**request record**. Two lines naming one material are ordinary (the same плита is consumed by several
positions), and `MaterialLineRequest` is a record — so `(material, 12)` sent twice is **one map key**
and the second was dropped: he was asked to buy 12 where he needs 24. Now `Map<Material, BigDecimal>`
with `merge(..., BigDecimal::add)`.

`ShoppingListService.mergeInput` sums by dedup key and would have caught a pair that reached it, which
is precisely why this was invisible — the pair never got there.

## Tests

| test | what it pins |
| --- | --- |
| `ActSignedCopyServiceTest` (new) | `receiptsTotal`: a partial return is not billed back, an `itemized` row is not counted, the rest is summed |
| `dto/FiscalIdentityTest` (new) | blank / whitespace / half identities key to `null`; the key normalises on read; both request records answer alike |
| `ReceiptIdentityIndexTest` | a blank code is not an identity — two blank rows are not twins, across tables either |
| `ActReceiptReconcilerTest` | a blank act receipt looks for nothing at all; a blank object paper is not the act's paper and keeps its expense |
| `ProjectReceiptIntegrationTest` | the query excludes a blank identity stored by an older client; the write path stores `""` as no identity |
| `ShoppingListIntegrationTest` | a replayed add on another object is a fresh row **and the foreign row is untouched**; a note survives the material leaving the estimate; a note does **not** keep a covered top-up; un-ticking a cleared row brings it back and it is asked for again |
| `MaterialCalculatorServiceTest` | two lines for one material are summed to 24 |

## Not changed / confirmed

- **No B-09 `Creator`-bean retry was added to the shopping list.** The PK-collision path is
  pre-existing; B-13 closed the part that was a boundary violation (the read scope) and the part that
  was a cross-object write (the merge), and «bug fix ≠ refactor». Left as a remainder for the owner.
- `itemized` stays load-bearing everywhere — B-12 restored the filter, it did not remove one.
- `work_act.advance_offset` is still document-only; nothing here reads it.
- V136 touches **data only** — no column, index or constraint changed, so nothing downstream had to
  relax a `>= 0` CHECK.
- No PWA change: every fix is server-side arithmetic or scoping, and the one new failure mode
  (`@AssertTrue` on half an identity) is a 400 the PWA already renders. Nothing new surfaces on a
  phone screen.
- **No PWA code changed, so no mobile verification was possible or needed** — but the PWA **patch
  version was bumped to 1.46.2**, by the standing rule that the PWA version tracks the PRODUCT, not
  just frontend work. Note the precedent both ways: V134's backend-only round shipped as «без зміни
  версії PWA». Flag for the owner, not a decision made silently.

## Gotchas

- `FiscalIdentity` had to be **public**: the rule has readers in `service/` as well as `dto/`, and the
  whole point of B-21 is that there is exactly one definition of «identified».
- `@AssertTrue` in this repo carries a **literal English message**, not a bundle key — there is no
  `validation.*` namespace in `messages*.properties`, so a key would have resolved to nothing.
- Working-copy line endings: `.java` files are **CRLF**, `db/migration/*.sql` are **LF**. A
  `perl -0777 -i -pe` in-place edit silently rewrites the whole file's endings; every edit here went
  through a patch helper that preserves them.

## Still open from FIXES.md §2

- **DECISION needed from the owner:** B-31b (refuse deleting a bought row with 409, or accept it as
  «his own action») and B-31d (derive «Чек №N» from a sequence, or accept possible duplicates).
- Untouched: B-18, B-19, B-20, B-22, B-23, B-25, B-26, B-28, B-29, B-30, B-31f, B-31g, B-31h.
