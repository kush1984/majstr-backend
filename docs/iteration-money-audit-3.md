# Review round 3 — the money audit, §0 «fix these first» (2026-09-25)

**Status:** §0 built, green and **committed** (`35e314c`). §1 closed out 2026-10-01 — its only
remaining item was **B-62** (below); everything else in §1 shipped with §0. Migration **V141**. Source:
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

## §1 closed out — B-62, «підсумковий» means the last one (2026-10-01)

§1 («backend — acts and signing») was almost entirely answered by §0's work: `ActLineBinder` covers
B-56 and B-57 (the act unit picker lost «%» in the PWA the same round), `requireNoActs` covers
B-58/B-59, `line_kind` covers B-59's «record it, don't infer it», the two `@Version` checks cover
B-60/B-61, and B-32's siblings are frozen by `PROJECT_RECEIPT_BILLED_ON_ACT`. **B-62 was the one
item left**, and it is the hole a REJECTED act opens.

A REJECTED act is deliberately **not** an OPEN act — that is what unwedged an object whose client
declined an act (round 2). But the object then carries on without it: a FINAL act can be created,
signed and paid while the rejected one sits there, and `changeStatus` only ever asked about open
acts. Move the rejected act back to DRAFT and the object has an act dated after its own closing act.

Two halves, and they are different kinds of answer:

- **`ActFinalGuard`** refuses when a SIGNED FINAL act *other than this one* closed the object —
  409 `WORK_ACT_FINAL_SIGNED`. Only SIGNED counts: a FINAL act still in DRAFT or REJECTED closes
  nothing, and a REJECTED one has to stay reopenable, since it is the very act being reopened. It
  sits on all three doors that can still turn a non-signed act into a signed one — the move to
  DRAFT, the publish to SENT, and `signOffline`, **which never asked about the status at all** and
  would have signed the rejected act directly. With the DRAFT move refused the other two are
  unreachable today; they ask anyway, for the same reason `requireStillValid` asks at every door.
  The portal's own sign is deliberately NOT guarded — with publish refusing, a SENT act cannot
  coexist with a signed FINAL, and an error the CLIENT cannot act on is worse than none (B-28).
- **`ActLineBinder.refreshCumulativeBefore`** re-freezes «виконано раніше» on the move to DRAFT.
  The save-time freeze is stable only *because* the one-open-act rule means nothing else can be
  signed beside an open act; a rejected act breaks exactly that premise, so every figure on it
  predates the signatures that happened without it — and the editor, the PDF and «ДОВІДКОВО» all
  quote it. This half is **not** a refusal: DRAFT is where the master fixes the quantity,
  `exceedsEstimate` now names the line, and the B-56 cap still refuses at publish and at both
  signatures. Refusing the reopen instead would hand him a rejected act he can neither fix nor
  delete.

`rejectedAct_cannotComeBackOnceTheFinalActIsSigned` and
`rejectedToDraft_refreezesWhatEarlierActsAlreadyClosed` pin both halves (both verified red without
the fix). No migration, no DTO change, no PWA change — the PWA renders the server's localised
message, so a new code needs nothing there.

---

## §2 closed out — economy and payments (2026-10-01)

§0 had already answered B-63, B-64, B-65+B-33 and B-47. Five items were left, and three of them are
the same shape: a figure the master had already READ got rewritten by a later action.

**B-67 — the card and the dashboard read «the newest estimate, whatever it is».** «Whatever it is»
became a problem the day acts started WRITING estimates: signing an act with extras creates a SIGNED
ADDENDUM, now the newest row, so an object with a 10 800 ₴ contract and 1 000 ₴ of extras showed
«SIGNED · 1 000 ₴» on its card while the economy tab said 11 800. Both queries now take **Σ SIGNED ∧
counted when the object has any** — deliberately the SAME definition as `sumIncomeCounted`, ADDENDUMs
included, because an addendum IS part of the contract — and fall back to the latest **non-ADDENDUM**
estimate only for an object with nothing signed yet. Same round, the dashboard's month moved to
`LocalizationConfig.ZONE`: on the 1st until 02:00/03:00 Kyiv it opened on the PREVIOUS month, so the
object he finished an hour ago was missing from «завершено цього місяця» at the moment he looked.

**B-68 — a signed consolidated rollup had no contract.** `consolidate()` creates the rollup
uncounted so it cannot double its sources; that is right until the client signs the ROLLUP, at which
point nothing on the object is SIGNED ∧ counted. A 50 000 ₴ signed deal read «За договором 0 ₴» and
no act could be made against it. `countSignedConsolidation` counts the rollup and uncounts its
sources — and is deliberately narrow: if any source is ALREADY signed ∧ counted, nothing moves,
because that source is the contract and counting the rollup beside it would double exactly what the
original `false` protected. The MIXED case stays under-counted and is an open question, not a guess.

**B-69 — a TRANSFER rewrote history two ways.** The surplus row carried no id, so an offline replay
recorded the overflow again; worse, when the stage was already fully received there was no closing
row either, so nothing at all was recognisable on replay. The surplus now rides a **derived** id
(`surplusIdOf`) and the replay check asks for both. And `transferSurplus` wrote ONE aggregated row
dated `today()` with no refund flag: 3 000 ₴ received 28 Aug, moved in September, left August 3 000
lighter — `received_at` is the authoritative day in «Мої гроші» — and turned a «повернення за
матеріал» into ordinary earnings. It now writes **one row per source row**, each keeping its own date
and flag. Moving a surplus onto its own stage is refused (it would delete the receipts and re-post
the money, losing every split).

**B-70 — an object holding signed money could be deleted** (owner: 409 + «Архівувати»).
`ProjectDeleteGuard` refuses when the object carries a SIGNED estimate, a SIGNED act, any
`payment_receipt` or any `object_expenses` row — every neighbouring door already refuses this one row
at a time, and the cascade walked past all of them at once. Nothing new had to be built to offer the
alternative: a terminal object is already hidden behind the archived reveal, and the permanent delete
is offered ONLY on a terminal object, so by the time a master reaches this refusal the object is
already out of his way. A reimbursable `project_receipt` is deliberately NOT in the set — it writes no
expense by design (V129), so it moves no month; an own-cost one is in it through the `ObjectExpense`
it posts.

**B-66 — the client's portal disagreed with the master's economy, in both directions.** The ECONOMY
portal filtered `economyVisible ∧ SIGNED` and not `count_in_economy`, and a SUPERSEDED parent is
SIGNED forever: a renegotiated job with both halves ticked showed the client 50 000 + 47 500 for ONE
job, with his «Залишок» measured against the pair. The read now requires counted as well, and
`updateEconomy` refuses to share an uncounted estimate outright (400 `error.estimate.not-counted-economy`).
The other direction was the ADDENDUM: SIGNED, counted, and never shared, so extras the client accepted
ON AN ACT were in «Отримано» but not in «За договором» and a client still owing for them read «Залишок 0».
`economySections` adds them — nothing is disclosed, since an ADDENDUM records the lines printed on the
act he signed and it already names itself for him («Додаткові роботи до акта № 3»). It rides along only
when the master shared something: an ECONOMY portal with no sections of its own is not a portal about
this deal, and extras alone would be a bill out of nowhere. **The portal HTML needed no change** — it
renders sections generically.

Tests: `ObjectEconomyQueriesIntegrationTest` (four B-67 cases, one asserting the card equals
`sumIncomeCounted`), `SupersedeOnSignIntegrationTest` (both B-68 branches),
`PaymentTransferIntegrationTest` (four B-69 cases), `ProjectDeleteGuardIntegrationTest` (three B-70
cases), `ProjectPortalServiceTest` + `WorkActIntegrationTest` (B-66 write guard and read path). No
migration in this round.

---

## Still open out of §0's neighbourhood

- **B-72** (crew margin, «%» lines added to a copy afterwards) — **DECISION, unanswered.** The
  recommendation is: freeze unpriced lines at their client amount in the crew view (a negative
  unpriced line → 0), and prorate «%» lines into «з прийнятого актами» the way B-55 prorates them
  into the act. The parity fixture changes on both sides.
- **B-70** (deleting an object deletes signed money) — owner answered **409 + «Архівувати»**;
  the code is not written.
- **P-39** (`Math.round(n*100)/100` still in `useEstimate.ts` and `crewMargin.ts`) — the reader
  exists now; the two estimate-side call sites still use the old one.
