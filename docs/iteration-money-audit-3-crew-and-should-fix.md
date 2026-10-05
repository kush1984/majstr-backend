# Review round 3 — §3 (crew margin) and §4 (money should-fix), 2026-10-01

**Status:** built and green on both repos. Migration **V144**. Source:
`C:\Work\prompts\FIXES-3.md` §3 (B-71…B-75) and §4 (B-76…B-85), continuing
[iteration-money-audit-3.md](iteration-money-audit-3.md) (§0-§2) and
[iteration-money-audit-2.md](iteration-money-audit-2.md).

Four **DECISION** items were put to the owner before any code was written, and all four were
answered with the review's own recommendation: **B-51** primer → option (a), **B-42** V140's ties →
leave them, **B-72** a «%» line added to a crew copy → freeze at the client amount, **B-74** the
copy's default name → no percent, plus a strip on the public side.

---

## §3 — crew margin

### B-74 — the markup was in the NAME, and the name is printed for the client
«Санвузол +20%» was the default name of a markup copy — composed by the PWA, with
`EstimateService.duplicateName` as the fallback — and that name is the heading of the client's
portal section and of the act PDF's per-estimate group. One division and he has the crew's prices,
which is the one number `PublicEstimateIsolationTest` exists to keep from him; the leak simply
travelled in a string instead of a field.

New copies are «… (копія)» on both sides. The ones already created keep their stored name and are
stripped on the way out by **`ClientSafeName`** — a deliberately narrow trailing `[+−]N%`, so
«Фарбування 2 % розчином» survives and a name that is *nothing but* a rate is left alone (an empty
heading is worse than a hint). Applied at the two client surfaces: `sectionOf` on the portal, and
`WorkActPdfService.PdfModel`'s own constructor, which covers all three callers at once because an act
is always a document for the client.

The PDF guard became a real one in the same round. `PublicEstimateIsolationTest` walks public DTO
trees by component NAME, and both PDF models are records whose components are ENTITIES — an
`EstimateItem` legitimately carries `source_unit_price`, so the walk stops there and can say nothing
about whether the renderer prints it. `EstimatePdfServiceTest#render_neverPrintsTheCrewsOwnPrice`
renders the page and asserts the crew figure is absent from the extracted text; the act's PDF model
joined the reflection roots, and `baseprice`/`sourceprice`/`parentprice` joined the forbidden list.

### B-71 — «%» is not a unit, it is a different arithmetic
`source_unit_price` holds a crew PRICE on an ordinary line and the crew's own PERCENT on a «%» one.
`updateItem` changed the unit without touching it, so a 500 ₴/м² line switched to «%» was read as
«500 %» and the crew total became an invented number. Crossing that boundary now nulls the figure:
the line becomes unpriced, which contributes zero margin and is named by `unpricedCount`.

### B-72 — a discount the master gives comes out of HIS margin (owner's rule)
A «Знижка −10 % від кошторису» typed on the copy *after* it was made has no crew price, and both
sides re-measured it against the crew's SMALLER base: −1 000 ₴ for the crew against −1 200 ₴ for the
client, so a discount he had just given away read as 1 800 ₴ of margin. The owner's rule: an unpriced
line is **frozen at its client amount**, and a negative one at **zero**. On that sheet the figure is
800 ₴.

The freeze rides `baseDetached`, which the PWA's `recomputeLines` already honoured on both percent
passes and `EstimateMath` honoured on only one — a divergence no stored row could reach (the flag is
set only on a POSITION line or a frozen consolidated MANUAL one), but the two files disagreed on
paper. They now say the same thing, and the crew view uses it. Keeping the line's KIND untouched is
what keeps its place in the subtotals identical to the client view.

**The accepted half needed the ADJUSTMENT line.** «З прийнятого актами» summed gross prices, so a
full act on crew 100 × 200 at +20 % −10 % reported 4 000 ₴ accepted against a margin of 1 600 ₴ —
more accepted than exists. A «%» line can never BE an act line (B-57), so an estimate's percentages
reach the act as one prorated ADJUSTMENT row per type (B-55); `sumSignedActAdjustments` is the new
aggregate, and the margin they carry is the client's amount minus the crew's own, scaled by how much
of them travelled. A fully closed estimate now meets its margin to the kopeck — which is the test.

### B-73 — a copy of a copy, and sibling copies
`duplicate()` stored the SOURCE's CLIENT price as the new copy's crew price, so B (+20 %) → C (−5 %)
showed no margin at all and B → C (+5 %) reported «Бригаді 12 000» for a crew paid 10 000. The figure
is **inherited** when the source already has one, because that is what it already is.

Sibling copies were the same double one level sideways: `doSign` only ever looked at the PARENT, so
two variants off one sheet could both be SIGNED ∧ counted and «За договором» read the sum of two
quotes for one job. `supersededSiblings` uncounts the earlier one and stamps
`superseded_by_estimate_id`, exactly as a superseded parent is treated — the signature is a
historical fact, and refusing the client's tap over a decision the master made when he sent two links
would be the B-28 mistake. A sibling that signed acts cannot be uncounted (B-64) and then the refusal
is unavoidable.

**Not done, deliberately:** the review's `crew_priced boolean`. It would be set only by
duplicate-with-markup, which is *precisely* the condition `markup_percent > 0` already expresses —
a new column with identical semantics. Distinguishing «a crew sheet» from «a solo master's premium
variant» needs the master to say which it is, and no screen asks.

### B-75 — the should-fixes
- **The editor's margin is plan-gated** like the panel it mirrors (one soft `isEnabled`, asked only
  when there IS a figure, so an ordinary estimate neither loads the owner nor pays for the lookup).
- **A superseded copy reports no margin** — `findSignedMarkupDuplicates` now requires
  `count_in_economy`; a renegotiated copy is SIGNED forever and kept reporting a margin on a deal
  that counts nowhere else on the tab.
- **One rate rule for both surfaces.** The panel query accepted several «% від кошторису» lines as
  long as they happened to share a rate, and read a rate off a FROZEN consolidated line whose stored
  percent was measured against a sum that is not on this sheet. It is now the portal's rule verbatim:
  exactly one line of that direction, no frozen one.
- **A parent's line delete no longer leaves the copy stale.** The copy's own «%» lines pointing at the
  deleted twins are detached and the copy is recalculated — `line_total` is stored, so nothing
  recomputed it until the next edit of THAT sheet, and a SENT copy went on showing «5 % від позиції»
  measured against a line the client no longer had.
- **`markup_percent` is bounded by its column**: `@DecimalMax("999.99") @Digits(3,2)`, where
  `@DecimalMax("1000")` let 1000 % through the validator and overflowed `NUMERIC(5,2)` on the INSERT.
- `CrewMarginIntegrationTest`'s «most important test» ran in read-only transactions that never flush,
  so it could not have caught the corruption it is named for. It now reads the margin through an
  ordinary edit — a rename, which moves no money — and checks the rows via JDBC after that
  transaction commits.

**Still open from §3:** the parity fixture is still two assertions in two files rather than one JSON
read by both suites (the review's last test bullet). Both fixtures now cover the B-72 cases and name
each other.

---

## §4 — money should-fix

### B-77 — «ДОВІДКОВО» answers two different questions, and got both wrong
While the act is OPEN the block should say «what will this object stand at once this is accepted», and
the act's own off-estimate work was counted in «виконано з початку» but not in «за кошторисами» —
because the ADDENDUM that carries it does not exist until the signature. The client read
«Залишок −5 000 ₴» on the page he was about to sign. Signing's future ADDENDUM (ADDITIONAL lines +
the act's receipts) is now added to the contract side as well. ADJUSTMENT lines are deliberately not:
they carry a share of an estimate's own discount, which `sumIncomeCounted` already measured in full.

Once SIGNED the block is history, and it was still live: re-rendering act 3 after act 4 was signed
printed act 4's work inside act 3's «виконано з початку», so a document the client already holds said
something different every time it was downloaded. Three `…AsOf` aggregates answer as of the act's own
`signed_at`.

### B-78 — the advance may not exceed what the act bills
The PDF prints `max(0, total + receipts − advance)`, so a 30 000 ₴ offset on a 20 000 ₴ act told the
client he owed nothing and recorded the remaining 10 000 ₴ against nothing at all. `ActAdvanceGuard`
refuses at the master's doors — the header save (immediate, he is looking at the field), the publish
to SENT and `signOffline`. The client's portal sign is NOT guarded, the same reason `ActFinalGuard`
leaves it alone. And an omitted `advanceOffset` now LEAVES the stored one alone: it used to clear it,
so any client or replay that did not resend it silently un-offset a prepayment.

**Not done, deliberately:** the review's second half, «Σ advances of signed acts ≤ work-only
received». V115 settled that `advance_offset` is a DOCUMENT-ONLY figure nothing in the economy reads
or reconciles, and the editor only SUGGESTS it from «Мої гроші», non-blocking. Reconciling it across
acts would make a save fail over a payment recorded somewhere else entirely.

### B-79 — one slip, one act
The same fiscal identity on act 3 and act 4 was a warning on both and billed on both: the client paid
4 800 ₴ for a 2 400 ₴ purchase, and with `receipts_to_expenses` on the cost was posted twice too.
`ActReceiptDuplicateGuard` refuses at all three doors. **No override flag** — V134 already settled
that one `fn` + one `id` is one piece of paper, so there is nothing to ask; the way out is to correct
the receipt. This is the one guard worth showing the CLIENT: being billed twice for one purchase is
worse than an error message.

### B-80 + B-43 — one column fixed two bugs
Neither receipt table had an optimistic lock, so a PATCH built on a stale read wrote the whole entity
back and reset `billed_on_act_id` — V134's stamp, the only thing keeping one paper from being billed
twice. V144 adds `version` to both.

Making it a **`Long`, not a primitive**, closes B-43 at the same time. These rows carry a
client-assigned id, so `save()` could not tell a create from an update and went through `em.merge`: a
concurrent replay of the same queued receipt UPDATED the winner's row (resetting `expense_id`, the
fiscal identity and `billed_on_act_id` to builder defaults) and the duplicate-key recovery never ran,
because no key was ever violated. Spring Data reads a nullable version attribute as «is this new?» —
null means insert, the insert collides, and the recovery that re-reads the winner's row finally
fires. B-43's second half too: a replay loser no longer copies its photo into the gallery a second
time.

### B-81 — an email cannot be rolled back
The stamped client copy and the master's push both fired from inside the signing transaction, and a
transaction can still lose: the B-60/B-61 optimistic lock, a constraint, the connection dropping on
commit. `@Async` did not help — it only made the race non-deterministic. **`AfterCommit`** runs an
outward side effect once the transaction that justifies it has committed; the PDF is still RENDERED
inside (it needs the entities and the live figures) and only the send is deferred, with everything it
needs copied out to locals first.

And both sign paths are now `@Transactional(rollbackFor = Exception.class)`: Spring rolls back on a
RuntimeException and **commits** on a checked one, and the `doc_hash` render throws checked
exceptions — the default rules would commit a SIGNED, immutable, undeletable act with no tamper stamp.

### B-82 — the database says it too
Two open acts on one object, two SIGNED FINAL acts, and two acts claiming one ADDENDUM estimate were
all accepted by the database even though the service refuses each — a concurrent pair could land both.
V144 adds three partial unique indexes. Each is preceded by a `DO $$` block that **RAISEs on
pre-existing duplicates** rather than dropping rows: deciding which of two signed acts is the real one
is not a migration's call, and the explicit message is better diagnostics than the index's own.

### B-83 — a money row's date is a Kyiv date
`LocalDate.now()` in six places (`CashEntry`, `ObjectExpense`, `ActAddendumCreator`,
`ObjectExpenseService`, `ProjectReceiptService`, `ActReceiptExtractor`) read the server's UTC clock, so
a receipt flipped at 00:30 Kyiv on 1 October was dated 30 September — and «Мої гроші» is a screen
about months. All six take `LocalizationConfig.ZONE`.

### B-84 — the other money requests
`@Digits(integer = 13, fraction = 2)` on the six requests B-37 did not cover, so a sub-kopeck figure
is refused rather than rounded into something the CHECK constraint then rejects as a 500; and
`ProjectPaymentRequest`'s stage amount is `@DecimalMin("0.01")`, where 0 made a stage instantly
RECEIVED.

**Not done, deliberately:** making `ExpenseRequest.source` server-side only. There IS a legitimate
client caller — the estimate-side receipt import offers to save the receipt total as an expense, and
RECEIPT is the truth there. A lying client can only mislabel its own row (`requireNotOwnedByAReceipt`
keys on the back-link, never on `source`), so the fix would cost a second endpoint to remove a
cosmetic lie.

### B-85 — the nits
- **The push says «До сплати», not the works subtotal.** On an act carrying 8 000 ₴ of material the
  two differ by that much, and the smaller number was the one the master was told.
- **The act's own `place` is printed**, with the contractor's `docCity` as the fallback — the field was
  on the act, the editor offered it, and the PDF ignored it.
- **`internals` is never sent.** «Прибуток» left the object for good with the crew-margin iteration;
  the field went on being computed (an aggregate per request) for nobody. The DTO field stays, because
  the PWA's hand-written types declare it nullable and a null needs no change there.
- `lineTotal` is bounded at 9 999 999 999 999.99 — both factors were bounded and their product was
  not, which is a 500 on `NUMERIC(15,2)` and, below that, a «сума словами» that silently says less
  than the figure above it (`HryvniaInWords` stops at milliards).
- `@Size(max = 500)` on the act's `items`; `sumSignedActMargin` rounds to the kopeck;
  `CrewUsageResponse.activeMasters` → `allMasters` (nothing about it was ever about activity);
  `MetricsService`'s orphaned javadoc moved onto the method it describes; `WorkAct`'s
  `receipts_to_expenses` javadoc no longer points at a screen that does not exist.

**Not done:** **B-76** (`doc_hash` cannot be reproduced) is recorded in
[open-questions.md](open-questions.md) rather than half-built. The honest fix is a canonical JSON
snapshot of everything printed, hashed instead of the PDF bytes, with SIGNED documents rendered from
the snapshot — a new column, a second render path for acts and estimates, and a decision about the
hashes already stored. That is an iteration, not a should-fix, and a partial version would leave two
notions of what the hash certifies.

---

## Also this round

**One file was double-encoded, and part of it was a runtime string.** `ActSignedCopyService` carried
23 mojibake sequences from some earlier tooling pass — the class javadoc, and `"Кошторис"`, the
fallback name the ACT PDF prints as a group header for an unnamed estimate. An offline-signed act
whose estimate had no name printed `ÐÐ¾ÑÑÐ¾ÑÐ¸Ñ` on the client's document. Repaired by
re-decoding (the mojibake was lossless), and a scan of every `.java`/`.sql`/`.properties`/`.json`
file in `src` says it was the only one.
