# Review round 3 — the money audit, §0 «fix these first» (2026-09-25)

**Status:** built, green on both repos, **uncommitted**. Migration **V141**. Source:
`C:\Work\prompts\FIXES-3.md` — a full external money audit of `majstr-backend` at `170a419` and
`majstr-pwa` at `cd43bec`, continuing `FIXES.md` and `FIXES-2.md`.

The owner's scope for the day was **the whole §0 list, both repos**: the ten items where money is
wrong or lost *today* — the master is underpaid, the client is over-billed, or a signed document
drifts after the signature. Items in §1-§4 are recorded in
[open-questions.md](open-questions.md) and not touched here.

Four items were **DECISION** items; three were answered by the owner before any code was written
(B-55, B-65+B-33, B-70), and B-72 is still unanswered — see the end of this file.

---

## The three things this round is really about

**1. A signed document must be the one that was read.** Two separate holes: the client's tap signed
whatever the row held at that instant (B-61), and receipts or lines could land on an act *after* the
signature, outside the ADDENDUM and outside `doc_hash` (B-60). Both are now version-checked.

**2. Two axes must count one set of facts.** «Прийнято актами» ⊆ «За договором» was enforceable only
while nothing could remove the contract from underneath accepted money — but an ADDENDUM could be
reopened, an estimate with signed acts could be uncounted or duplicated, and an estimate's
discounts never reached the acts at all. B-55 and B-58/59/63/64 close that from both sides.

**3. A number the master cannot read is not zero.** The PWA turned «1 200» and every typo into
`0 ₴`, and the act editor showed its own float arithmetic instead of the server's figures
(P-34/P-35).

---

## Backend

### B-56 / B-57 — an act line is a frozen copy, and the freeze is the point
`ActLineBinder` is now the single answer to «what is this line allowed to be», used at the write and
at every door the act leaves through. A line carrying an `estimateItemId` takes its name, unit and
price **from the estimate**, never from the request; the quantity is capped by what that position
still has open (`cumulative_before` + this act ≤ estimate quantity, 400 `WORK_ACT_OVER_ESTIMATE`);
a «%» estimate line can never be closed by an act (400 `WORK_ACT_PERCENT_LINE`); and an ADDENDUM
position — a line that *is* a previous act's own record — cannot be closed again
(400 `WORK_ACT_ADDENDUM_LINE`).

### B-55 — estimate discounts reach the act as an ADJUSTMENT line (owner: option **a**)
**V141** adds `work_act_item.line_kind` (`ESTIMATE` / `ADDITIONAL` / `ADJUSTMENT`, CHECK-enforced,
backfilled by the rule the code used until now: no estimate item = additional work).

The distinction had to be **recorded rather than inferred**: an adjustment has no
`estimate_item_id` but does belong to an estimate, and that exact combination used to mean «an
off-estimate work», which `ActAddendumCreator` rolls into a SIGNED ADDENDUM — rolling a discount up
that way would post it into «За договором» a second time.

`ActAdjustmentCalculator` authors one line per act, per estimate and per type, carrying that
estimate's percentages **prorated by what this act closes**. It is re-derived at save and at sign,
never editable, and never seeded into «Додаткові роботи» on the PWA side.

`WorkActLineKindMigrationOnLiveDataIntegrationTest` applies V141 over legacy rows and asserts the
backfill, the CHECK, and that an existing act's totals do not move.

### B-60 / B-61 — nothing lands on a document after the signature
`work_act` and `estimates` carry their `@Version` into the portal render, and the sign request
carries it back; a mismatch is 409 `WORK_ACT_CHANGED` / `ESTIMATE_CHANGED`
(`DocumentChangedException`, shared by both documents — same situation, same remedy, same HTTP
answer). The wording is deliberately not an accusation: look again, then sign.

`WorkActConcurrencyIntegrationTest` drives the real race — a receipt write committing against an
act being signed — and asserts the loser is refused rather than silently absorbed.

### B-58 / B-59 / B-63 / B-64 — the contract cannot vanish from under accepted money
Reopen, delete, duplicate and «виключити з економіки» all go through one guard, `requireNoActs` —
409 `ESTIMATE_HAS_SIGNED_ACTS` with a message per door, since the refusal is one rule and the way
out differs. It asks two questions, not one: does a live act line close this estimate, **and** is
this estimate some signed act’s own ADDENDUM. An ADDENDUM is additionally read-only through every
one of those doors (409 `ESTIMATE_ADDENDUM_LOCKED`, `requireNotAddendum`), and `duplicate()` no
longer uncounts a SIGNED source the moment the copy is made — only a signature on the copy supersedes the parent.

### B-65 + B-33 — a refund is subtracted from the axis it belongs to (owner: the B-33 formula)
One shared reader, `MaterialRefundCalculator` → `MaterialRefundSplit`, feeds all three surfaces that
were each doing their own arithmetic (the master's payments summary, the FREE-visible materials
axis, the client portal card). The formula the owner chose:

```
refundApplied        = min(Σ refunds, reimbursable)
workPaid             = received − refundApplied
remaining            = max(0, contracted − workPaid)
materialsOutstanding = reimbursable − refundApplied
```

«Усе сплачено» appears only when **both** are 0; an overpayment is shown as an overpayment rather
than clamped to «сплачено»; and «Заробив» stays `income − outlays`, with refunds demoted to an info
line. The two queries behind the split live in one place for the same reason the split does: a
receivable filtered differently on two screens is the bug, not the formula.

### B-32 (+ a/b/c) — a receipt a signed act already billed is frozen
`ProjectReceiptBilledException` (409 `PROJECT_RECEIPT_BILLED_ON_ACT`) refuses the amount edit, the
delete and the «чия це витрата» flip **in both directions** once `billed_on_act_id` (V134) is
stamped. What the paper *says* — label, date, photo — is still correctable, because that is not
money. The PWA disables the same three controls and says «Врахований в акті №N» instead of failing
after the tap.

### B-47 — markup and duplicate round to the kopeck, HALF_UP
`markedUp` rounded to **whole hryvnia**, and it is shared by `duplicate()` and `markItemsUp()`:
0.40 ₴ +20 % stored as **0.00** (2 000 pcs billed nothing), 1.20 ₴ +15 % stored as 1.00 (−190 ₴ over
500 pcs). Now scale 2 HALF_UP, and a price above zero can never round down to zero.

---

## PWA

### P-33 — queued payments were never sent
`useObjectPayments` and the receipt hooks queued against the outbox entities `project-payment` and
`payment-receipt` — **and neither handler existed**. A queued op with no handler is skipped by every
flush and never retried, so a payment authored on a weak link showed as received, sat in IndexedDB
forever, and disappeared at the next global invalidate. The money was never sent and nothing said
so.

Both handlers now exist, replaying under the op's own `X-Entity-Uuid` so a retry cannot bill the
same money twice. The entity names keep their hyphens on purpose — a queue outlives an app update,
so renaming them would strand every op a master already has stored.

`handlerCoverage.test.ts` is the guard that makes this class of bug impossible to repeat: it walks
every entity name the app *enqueues* and fails if `init.ts` registers no handler for it.

### P-34 — the act editor shows the SERVER's figures when it has nothing of its own to say
Act lines are frozen from `act.data.items` only when the act is **signed**; a DRAFT or SENT act
reads the live progress feed (B-56 makes the server re-read the estimate at save time anyway), with
orphan lines re-added.

The totals gate turned out to need a second, narrower question. The leave-guard's `dirty` flag
counts the auto-title, so an untitled draft is «dirty» from its first render and the server's own
figures would never be the ones shown. A **retitled act is not a re-priced one**: `moneySnapshot`
tracks only advance, the materials toggle, quantities and additional lines, and the server's
`total`/`receiptsTotal`/`payable` are shown while that half is clean, the query is not refetching,
and nothing is queued. The editor's «Разом» renders `serverTotal − adjustmentsTotal`, because
`WorkActResponse.total` includes the B-55 ADJUSTMENT line.

### P-35 — one reader for every money field
`src/lib/decimal.ts` gained `parseMoney` and `parseQuantity`, returning **`null`** for anything they
cannot read (a phone keypad's `1 200` with a non-breaking space *is* readable; `1e3`, a stray
letter, a fourth decimal and eleven digits are not), plus `roundMoney`/`sumMoney` (the HALF_UP
string detour that agrees with the server — P-39's reader, applied here to act money).

Every money field named in the review now goes through it, with a red field, an inline message and a
blocked Save: the act editor, both receipt sheets, `PaymentsBlock` (five sites plus the custom split
percentages), the estimate item schema, `ItemForm`'s frozen «%» base sum — the one money field the
schema never looked at — and the import review's rows and deposit.

**Blank stays 0 where blank is a deliberate product state**: a V129 batch receipt saved before
anyone prices it, an unpriced act receipt, an import row with a price and no count yet. Only genuinely
unreadable input is refused. Nothing maps to `0 ₴` any more.

`decimal.test.ts` pins both readers against the table in the review.

---

## Still open out of §0's neighbourhood

- **B-72** (crew margin, «%» lines added to a copy afterwards) — **DECISION, unanswered.** The
  recommendation is: freeze unpriced lines at their client amount in the crew view (a negative
  unpriced line → 0), and prorate «%» lines into «з прийнятого актами» the way B-55 prorates them
  into the act. The parity fixture changes on both sides.
- **B-70** (deleting an object deletes signed money) — owner answered **409 + «Архівувати»**;
  the code is not written.
- **B-62** (REJECTED→DRAFT ignores a SIGNED FINAL act) — §1, not built.
- **P-39** (`Math.round(n*100)/100` still in `useEstimate.ts` and `crewMargin.ts`) — the reader
  exists now; the two estimate-side call sites still use the old one.
