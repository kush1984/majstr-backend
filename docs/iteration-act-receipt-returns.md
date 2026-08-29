# Iteration: partial returns on an act receipt

**Status:** complete on both sides — backend green on `./gradlew build`, PWA green on the full CI
mirror (lint · tsc · typecheck:tests · vitest · vite build). NOT pushed (awaiting the user's
approval).
**Source:** the master, from a live job — he bought nails for 2000 ₴, ~500 ₴ worth were left over,
the owner did not want them, so he took them back to the shop. It can happen on several receipts of
the same act at once: «ми не весь чек мінусуємо». His workaround was to add the refund to the
client's advance, which is actively wrong (§1).
**Shape, decided by the master and deliberately minimal:** «не буде ніякого видаткового чеку — треба
просто рядок типу, повернення по чеку, де можна вписати суму яку повернули і вона не може бути
більшою від суми чеку і все.» No return document, no `kind`, no photo, no date, no label — ONE extra
number on the purchase receipt, capped by it.
**Migration:** V115 (`work_act_receipt.returned_amount`).

---

## 1. Why the advance workaround had to be replaced

`payment_receipt` means **money the client paid**. Putting a shop refund there moves both axes of
the economy in the wrong direction at once:

- `received` grows by 500 ₴ that the client never paid, so «Заборгованість замовника» /
  «Невідпрацьований аванс» on the works axis both read wrong;
- the returned material stays a full MATERIALS expense, so `Прибуток = contracted − Σ expenses` is
  understated by the same 500 ₴.

The return is not income. It is the purchase being smaller than the paper says.

## 2. The model: one number, not a second row

Every alternative shape was a second billing path for the same money, which this codebase has
already paid for once — `itemized` created exactly that, and the cost is `AND r.itemized = false` in
every money query, forever, because rows created that way are frozen into SIGNED acts. A return is
**one** path with a smaller number, so no money query needs a discriminator.

`work_act_receipt.returned_amount numeric(15,2) NOT NULL DEFAULT 0`, with the cap in the schema:

```sql
CHECK (returned_amount >= 0 AND returned_amount <= amount)
```

`amount` still holds what the paper says — the client can hold the photo against it — and everything
downstream reads `billedAmount() = amount − returnedAmount`. Both `WorkActReceipt` (entity) and
`WorkActReceiptResponse` (DTO) expose that method, so no caller subtracts by hand.

**The cap is load-bearing beyond the obvious.** Because a return can never exceed its receipt, every
figure it feeds stays non-negative: `object_expense.amount >= 0` (V42) and
`estimate_items.unit_price >= 0` (V29) hold unchanged, and no negative ADDENDUM line is needed.

## 3. Where `billedAmount` replaced `amount`

The rule is that the three consumers of receipt money may never disagree, so all three moved
together:

| consumer | change |
| --- | --- |
| the client's bill | `WorkActResponseFactory.receiptsTotal` maps `billedAmount`; `payable` follows |
| the ⊆ invariant | `sumSignedActReceipts` (native) sums `(r.amount - r.returned_amount)`; `sumByWorkActId` sums `(r.amount - r.returnedAmount)` — both keep `AND r.itemized = false` |
| the master's expenses | `ActAddendumCreator` posts `billedAmount()` as the MATERIALS/RECEIPT `ObjectExpense` |

`ActAddendumCreator` also filters `billedAmount().signum() > 0` when building the ADDENDUM lines: a
fully returned receipt bills nothing, and a 0 ₴ line in a SIGNED estimate is noise. The **expense**
list is kept separate and unfiltered by that predicate — the master did spend the money and did get
it back, and posting the net is what keeps `Прибуток` honest.

`existsUnpricedByWorkActId` is deliberately untouched: it tests `amount <= 0`, and a fully returned
receipt is *priced* — it just bills 0. Blocking a signature on it would be wrong.

## 4. Documents

- **PDF** — the money column prints `billedAmount()`, and a receipt with a return grows a small
  second line under its label: «за чеком X ₴, повернуто Y ₴». The receipts block is inside the
  canonical hashed render, so the return is frozen into `doc_hash` with everything else.
- **Portal** (`?a=`) — `PublicActView.Receipt` carries `returnedAmount` beside `amount`;
  `static/portal/index.html` nets the row and shows the same muted «за чеком … · повернуто …» line.
  The client sees both numbers, which is the point: the photo shows 2000 ₴ and the bill says 1500 ₴.

## 5. Writing a return

`WorkActReceiptService.update()` takes it; `add(...)` deliberately does **not** — a receipt is
photographed at the shop and returned later, so there is no moment at upload when the number exists.
Over the cap is 400 `WORK_ACT_RECEIPT_RETURN_TOO_BIG` (`error.work-act.receipt-return-too-big`), checked in
`requireValidFields` before the DB CHECK so the master gets a sentence, not a constraint violation.

Like every other receipt write it runs behind `requireNotSigned`. **Known limitation:** a return
against a receipt already frozen in a SIGNED act cannot be entered — the act is immutable by design.
If the material comes back after signing, it belongs to the next act.

## 6. PWA

`ActReceiptsSection` — a full-width «Повернення, ₴» field in the edit dialog (`placeholder="0"`,
never pre-filled), the cap enforced live (the error blocks Save, it does not clamp the typed value),
a «До сплати за чеком» row under the fields so the netted number is visible before saving, and a
badge on the card for a receipt that carries one. The subtotal goes through `billedOf(r)`.

`payable` floors at 0, which is unchanged behaviour — a return large enough to cancel the whole
receipts block just makes the receipts contribute nothing.

## 7. Tests

- Backend — three integration tests: `partialReturn_isNettedOffEveryConsumerOfTheReceipt` (the
  subtotal, `payable`, the ADDENDUM line and the posted expense all move together),
  `fullyReturnedReceipt_billsNothingAndCostsNothing_butKeepsItsPaper` (no ADDENDUM line, no
  expense, `acceptedByActs` unchanged — and the receipt row and its photo survive), and
  `returnOverTheReceiptAmount_isRefusedWithItsOwnCode` (also reachable by LOWERING the amount under
  an existing return, which is why it has a code and not a DB 500). Plus
  `view_receiptWithAPartialReturn_billsTheNet_butStillShowsWhatThePaperSays` on the portal, and the
  `WorkActPdfServiceTest` / `WorkActIntegrationTest` literals updated for the widened `ReceiptRow` /
  request record.
- PWA — three `ActReceiptsSection` cases: the row and the subtotal are netted while the card still
  shows the paper's own sum; the dialog refuses an over-cap return and shows what is left to pay;
  and a re-read from the card keeps a return the master already typed (recognition fills label/sum/
  date off the paper, which knows nothing about a return made afterwards).

## 8. Round 2 — the editor reads as panels, and the advance explains itself

Same screen, same review round. «Додаткові роботи, чеки і аванс воно якось все на купі» — the act editor had
grown from one form into five different things (the act's own fields, the estimate groups, the
additional works, the receipts, the bill) drawn as one continuous stream of white cards on a 375px
screen, with the advance input sitting bare between the receipts and the totals.

**`components/Section.tsx`** is the whole change: a titled panel — a bordered header carrying the
block's name, an optional `(i)`, and an optional right-aligned `aside` figure, over a
`bg-surface-sunken` body so the white item cards inside read as *contents of* the panel rather than
as siblings of it. `flush` turns the sunken body off for a block that draws its own full-width rows.
The `aside` lives in the HEADER, not the body, because on a phone the header is what stays legible
while the body scrolls past — it is the one figure that says whether the block is worth opening.

The editor is now five panels:

| Panel | `aside` | Why |
| --- | --- | --- |
| «Про акт» | — | kind / title / period / date / contract, `flush` (it holds fields, not cards) |
| one per estimate | that group's sum | the number the master is building; it moves as lines are ticked |
| «Додаткові роботи» | its total | same, and the block is often empty — the header says so at a glance |
| «Чеки та рахунки» | **none, deliberately** | it ends with its own labelled «Разом за чеками»; the same figure twice on one phone screen is noise |
| «Розрахунок» | — | the bill: the advance input, Разом / чеки / аванс / До сплати, and the ДОВІДКОВО tick |

The `isNew` receipts placeholder is a titled panel too: before the first save the block still says
where receipts will live, instead of being a stray line of grey text.

**A bug the panel work surfaced.** `ActEditorPage` keeps its OWN `receiptsTotal` — the receipts panel
writes straight to the server, so the act row's `receiptsTotal` is stale between saves — and it was
summing gross `amount`. So §5's netting reached the panel's subtotal and the server's `payable`, but
not the «Разом за чеками» / «До сплати» the master reads while editing: one screen, two different bills.
`billedOf` is now exported from `ActReceiptsSection` and used by both, pinned by
«*a partial return reaches «До сплати», not just the receipts panel*».

### What «Зараховано авансу» actually is («то для мене поки загадка»)

`work_act.advance_offset` came with V104, the original work-acts migration. It is **hand-typed and
document-only**. Every reader in the codebase:

- `WorkActResponseFactory` — `payable = total + receiptsTotal − advance`, floored at 0
- `PublicActPortalService` — the same computation for the client's `?a=` view
- `WorkActPdfService` — the same, inside the canonical hashed render
- `static/portal/index.html` — prints an «Зараховано авансу − X» row only when > 0

That is the complete list. **Nothing in the object economy reads it**: «Прийнято актами» is gross
(`sumSignedActLineTotals + sumSignedActReceipts`) and «Отримано» is `PaymentReceiptRepository`
`.sumByProjectId` over `payment_receipt`. So the field is genuinely invisible in Економіка — which is
the master's whole complaint, and it is correct behaviour, not a missing wire: the advance was
already counted as «Отримано» when the client paid it, and counting it again on the act would
double it.

Its legitimate job is one line in ONE document: a client who paid 10 000 ₴ up front and is now
accepting 15 000 ₴ of work should read «До сплати: 5 000 ₴», not 15 000 ₴. What it is NOT: it is
never suggested from what the client actually paid, nothing reconciles it against `payment_receipt`,
and nothing stops the same 10 000 ₴ being credited on two different acts — the master carries all of
that in their head.

### The field explains itself («щоб було всім зрозуміло що і для чого воно є»)

The master's answer to the investigation above was to make the field self-explanatory rather than
remove it, so it now says all of the above **in the object's own numbers** instead of leaving them in
his head. Three pieces, all in `ActEditorPage`'s «Розрахунок» panel:

1. **The `(i)`** (`acts.advanceInfo`) leads with a worked example — «клієнт дав 10 000 ₴ авансу, акт
   на 15 000 ₴ — зараховуєте 10 000 ₴, і клієнт бачить «До сплати: 5 000 ₴»» — then states the
   boundary: it is a DOCUMENT line, it moves no figure in Економіка, and that money is already
   counted there as «Отримано», so counting it twice is the mistake to avoid.
2. **The object's unearned advance, named under the input.** `useEconomy(projectId)` →
   `max(0, acts.received − acts.acceptedByActs)` — the FREE-visible figure `ObjectEconomySection`
   already labels «Невідпрацьований аванс», so the act and the economy tab can never quote two
   different numbers. With it comes a one-tap «Зарахувати X у цей акт» filling
   `min(unearned, total + receiptsTotal)`: never more than this act bills, because `payable` floors
   at 0 anyway and offering more would read as «the rest carries over», which nothing implements.
   When the figure is 0 the block says so in one line («клієнт ще нічого не платив наперед — лишай
   порожнім»); while the economy is unknown (loading/offline) it says **nothing** rather than guessing.
3. **An amber warning when the typed value exceeds the unearned advance** — the one failure this
   screen can catch: the same advance credited on two acts. It is deliberately a *warning*, not a
   block. The unearned figure already nets itself across acts (an advance credited on an earlier act
   is accepted work there, so the remainder shrinks on its own), and a master may legitimately credit
   money he never logged as a `payment_receipt`. Blocking would make the field unusable for exactly
   the case that has no paper trail yet.

`advance_offset` stays **document-only**: this reads the economy to suggest a number, and writes
nothing back to it. Three tests pin the behaviour — the one-tap credit capped at the act's value and
its effect on «До сплати», the «nothing paid ahead» line, and the over-credit warning leaving the
typed value standing.

### An ADDENDUM panel offers no actions («з нього ми не можемо робити акт, правильно?»)

Same review round, spotted on the Економіка screenshot: the ⋮ menu on the «Додатково до акта № 3»
panel still listed both of its actions, and **neither could work on an ADDENDUM**:

- **«Згенерувати акт»** → `/acts/new?…&scope=<addendumId>`, and `WorkActService.progress` skips
  ADDENDUM estimates, so the editor would open with an empty position list. Rightly so: that money
  IS act № 3 — the rollup exists so «За договором» absorbs it. Billing it again would double it.
- **«Не враховувати цей кошторис»** → 409 `ESTIMATE_ADDENDUM_LOCKED` from
  `EstimateService.setCountInEconomy`. The act's off-estimate lines and re-billed receipts count in
  «Прийнято актами» regardless of the flag, so unticking the rollup pushes the ratio past 100 %.

The backend was already right on both; only the menu lied. `EstimatePanel` now renders no
`ActionMenu` at all when `panel.kind === 'ADDENDUM'` — the panel keeps its badge and its tap-through
to the read-only estimate view. Pinned by «*an ADDENDUM rollup has no ⋮ at all*», which renders a
regular panel beside it and asserts exactly ONE menu on screen (so the fix can't quietly take both).

## Follow-ups

- **The master's live data still holds the workaround** — the 500 ₴ he entered as an advance must be
  deleted from `payment_receipt` and re-entered as a return on the receipt it belongs to, or both
  axes stay wrong for that object.
- Not exercised on a phone; the field is measured only in jsdom.
