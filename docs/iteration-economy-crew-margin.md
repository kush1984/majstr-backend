# Economy: «Прибуток» removed, «Твоя націнка» added (2026-09-24)

**Status:** built, green, **uncommitted**. **No migration** — everything needed was already in
`estimate_items.source_unit_price` (V85). PWA **1.48.0**.

Closes `docs/open-questions.md` → «Object economy: Прибуток/Витрати parked again».

## 1. Why «Прибуток» is gone, not un-parked

It was hidden in August behind `INTERNALS_ENABLED = false` on the argument that the formula
(`contracted − Σ expenses`) reads as «заробіток» without being one for a бригадир who forgets to log
crew pay. Reading the code seven months later, that argument turns out to understate the problem
twice over — and to point at the wrong population.

1. **The formula has no inputs at all.** `ExpenseSheet` lived only inside the hidden block, and
   «Мої гроші» deliberately asks nothing about an object (`AddCashSheet`: *«Adding asks nothing about
   an object»*). So **no screen in the app adds an expense against an object**. `object_expenses`
   fills only automatically: act receipts (V110) and a till receipt flipped to «моя витрата» (V129).
   Switched on today, «Заробіток» would read ≈ «За договором» for **every** master.
2. **The бригадир who records everything gets a WRONG number.** He enters crew pay in «Мої гроші»
   under `CREW`, which is a `cash_entry` with no object. It never reaches the object's formula, so
   the object screen and «Мої гроші» would disagree precisely for the most diligent user.
3. **«Скільки я заробив» already has an answer** — «Мої гроші» → «Заробив», which counts everything
   and whose shape the master specified himself.

And the population the parking note was written about is the minority: most masters work alone, and
for them the formula was never wrong. That is not an argument for switching it back on — it is an
argument that the figure was answering a question the object screen is the wrong place for.

**The rule this leaves behind, now in `CLAUDE.md`:**

> The object shows MONEY (contract / acts / received) and facts the master TYPED HIMSELF (the margin
> over the crew). Earnings live in «Мої гроші». A figure that depends on what the master did not
> forget to record is not shown on the object.

## 2. What the бригадир gets instead, and why it needs no journal

A duplicate-with-markup already stores the crew's price **on every line** (`source_unit_price`,
V85). So «скільки лишається мені понад бригаду» is computable from what he typed:

```
Бригаді      = the same sheet, recomputed at the crew's prices
Твоя націнка = client total − Бригаді
```

**The crew view runs through the SAME `EstimateMath.recalculate`**, not through an arithmetic of its
own. One pass covers «% від позиції», «% від кошторису», discounts and surcharges — and a second
implementation would drift from the first eventually, as every mirrored formula in this codebase has
had to learn.

| line in the copy | crew view |
|---|---|
| ordinary, `source_unit_price` present | `unitPrice := source_unit_price`, quantity from the copy |
| PERCENT, `source_unit_price` present | `quantity := source_unit_price` (the ORIGINAL percent), base re-measured in the crew view |
| `source_unit_price` NULL (added later) | **unchanged** — client price, contribution to the margin 0 |

### The trap that would have corrupted a client's estimate

`recalculate` writes `lineTotal` **into every entity it is handed**. Had the crew view been built
over the managed rows, a flush would have persisted the CREW's prices into the sheet the client
signed. Everything is built from **detached copies**, and they keep their ids because
`percentBaseItemId` resolves against exactly those.
`CrewMarginIntegrationTest.readingTheMarginDoesNotTouchTheStoredLines` reads the rows before and
after two margin reads and compares them; it is the single most important test in this round.

### A line with no crew price is not margin

V85's javadoc said the opposite — «nobody is paid for it downstream, so the whole line is margin».
**Reversed here.** Work added to the copy may well be work the crew does and is paid for; the data
does not say, and crediting it to the master is exactly the flattering arithmetic that got
«Прибуток» hidden. Contribution: zero. Reported separately as `unpricedCount`/`unpricedTotal` so the
screen can name it.

Subtlety worth keeping straight: such a line still sits inside a «% від кошторису» base **in both
views**, so its own amount and the surcharge on top of it appear on both sides and cancel. «Zero
contribution» is about the DIFFERENCE, not about the crew total. The parity fixture asserts exactly
this on both sides.

### A discount duplicate reports nothing

`markup_percent < 0` is a cheaper offer to the client, not a crew sheet.

### One deviation from the prompt, and the test that forced it

The prompt specified the rule as `duplicated_from_id IS NOT NULL AND markup_percent > 0`, and also
asked for a test proving the margin survives **deleting the parent**. Those contradict:
`estimates.duplicated_from_id` is `ON DELETE SET NULL` (`confdeltype = 'n'`). Gated on it, a master
who tidied away the crew's original sheet would silently lose the figure — the exact scenario V85
stores a per-line price for. The gate is `markup_percent > 0` alone: it is written only by
`duplicate()` and never cleared.

### «З прийнятого актами»

`Σ (act price − crew price) × act quantity` over SIGNED acts, joined to THIS copy's lines. The act's
own price is used, not the estimate's: the master may bill a different figure, and the margin follows
the money actually accepted.

**The convention is NOT the same as «Прийнято актами», and cannot be.** `sumSignedActLineTotals`
deliberately includes off-estimate ADDITIONAL lines (`estimate_id IS NULL`) because their ADDENDUM is
part of «За договором». Here such a line cannot count: it was never a copy line, so no crew price for
it exists anywhere. PERCENT needs no exclusion clause — the progress picker skips percent lines
outright, so one can never reach a `work_act_item` row.

### Gating

Panels stay FREE-visible; the margin rides the **same soft check as `payments`/`internals`**
(`Feature.OBJECT_ECONOMY`), not a 403 of its own. Because that feature is temporarily granted to
FREE, both are visible to everyone today — so the test asserts the **coupling** (`crewMargin == null`
⇔ `payments == null`) rather than a plan, and will keep holding when the grant ends.

## 3. The client must never see a crew price

Strengthened rather than assumed:

- **No public DTO shares a line record with the owner's.** `PublicEstimateView`,
  `PublicPortalView` and `PublicActView` each define their own; nothing public references
  `EstimateItemResponse`. That is what made it safe to ship `sourceUnitPrice` to the editor at all.
- `PublicEstimateIsolationTest` walks all three trees by reflection **and now the estimate PDF's
  `PdfModel`**, which is the fourth thing a client receives and which nothing else would have
  noticed a crew price appearing on.
- Its forbidden-substring list gained **`crew`** and **`sourceunitprice`**. Neither
  `crewMargin` nor `sourceUnitPrice` contains any of the original five (`expense`, `profit`,
  `economy`, `cost`, `margin`) — so the guard that existed for exactly this would have let both
  through silently.

## 4. The texts that promised what no screen showed

| where | was | now |
|---|---|---|
| `estimate.duplicateMarkupHint` | «в економіку піде лише націнка» — false, «За договором» takes the copy's WHOLE total | «Копія з націнкою: в економіці рахується вона, а твою націнку видно на її панелі» |
| `estimate.markupHint` | «У прибуток піде тільки різниця» | names the copy, the excluded original, and «Твоя націнка» |
| `acts.receiptsToExpensesInfo` | «щоб прибуток не був завищений» — there is no profit figure | explains it through «Мої гроші»: the receipt lowers «Заробив», the client's reimbursement raises it, the two cancel |
| `landing.good4`, `feature4Title/Text`, `featuresSubtitle`, `llms.txt`, `index.html` (×2, incl. JSON-LD) | «чистий заробіток по обʼєкту», «витрати мінус доходи» | contract / acts / paid, the crew margin, and «Мої гроші» for the period |

`landing.feature4` carries `pro: measurementsAndEconomyPro`, which is `!TEMP_FREE_GETS_...` — false
today, so the badge is absent and lies in neither direction. **One thing to re-check when that temp
unlock is removed:** the new feature4 text also mentions «Мої гроші», which is FREE regardless, so
the badge would then over-claim by one sentence.

## 5. The admin metric that replaces an assumption

`GET /api/admin/metrics/crew-usage` → masters with a markup copy (all time / 30 days / with one
SIGNED), against the funnel's own denominator. It is a **FLOOR and is labelled as one**: a бригадир
who prices the client's sheet by hand, or uses the in-place markup (which deliberately records no
provenance at all), leaves no duplicate and is invisible here.

## 6. Tests

**Backend** — `./gradlew build` green, **1558 tests in 179 classes**, 0 failures (1542 → +16).

- `CrewMarginIntegrationTest` (11): ordinary lines; «% від кошторису» re-measured against the crew
  subtotal; a line added afterwards (zero contribution, named); a price edited after duplicating; a
  discount copy and an ordinary estimate reporting nothing; the parent deleted; **the stored lines
  untouched after two margin reads**; the signed panel; only SIGNED acts contributing; the gating
  coupling; and the parity fixture.
- `CrewUsageMetricIntegrationTest` (4): one master counted once for two copies, a discount copy
  excluded, the 30-day window and the signed step, an admin's demo data excluded.
- `PublicEstimateIsolationTest`: two more forbidden names, one more public root.

**PWA** — lint ✓, `tsc -b` ✓, `typecheck:tests` ✓, **1184 tests in 133 files** ✓, `vite build` ✓.

- `crewMargin.test.ts` (4) — the mirror, including **the parity fixture asserted to the same two
  figures as the backend's**.
- `ObjectEconomySection.test.tsx` (+3, 1 rewritten) — the margin block, the unpriced line, an
  ordinary estimate showing nothing, and «Заробіток» gone rather than parked.
- Deleted: `useEconomy.test.tsx` (it tested only the offline expense journal, which no longer exists
  in the PWA).

**Mobile**: the two new surfaces are fluid by construction — `flex justify-between` rows and wrapping
`text-[11px]` captions, no fixed widths, nothing that can overflow 375 px. **Not verified in a live
375 × 812 viewport**, because that needs the local stack running; worth one look when it is up.

## 7. Not changed, deliberately

- **The expense journal's backend CRUD** stays exactly as it was: «Мої гроші» edits an object's rows
  through it (`PaymentService.editReceipt` / `ObjectExpenseService.update|delete`), which is the
  whole point of V135's «a second DOOR to one record, never a second copy».
- `ObjectEconomyInternalsResponse` is still computed and served. Nothing reads it; removing a field
  from a response the PWA caches for a week buys nothing. Its javadoc says so.
- `upgradeApi.click('OBJECT_PROFIT')` keeps its historical id — renaming it would break the
  analytics series that has recorded PRO interest from this screen since the card existed.

## 8. What his live testing turned up, same round

Three things, all reported while checking the work above.

### «Економіка не оновлюється, треба рефрешити пейджу»

Signing happens in the CLIENT's portal — a backend-rendered page — so the master's open tab learns
nothing by itself. Both paths that should have told it were shut:

- **Returning to the tab refetched nothing.** `refetchOnWindowFocus` is off globally, and no mutation
  of his fires, so react-query had no trigger at all. `src/lib/clientDrivenQuery.ts` now turns it on
  for exactly the four queries whose answer a CLIENT can change behind his back — the estimate, the
  object's estimate list, the object economy, and the acts. Not globally: everything else changes
  because he did something, and there a mutation already invalidates what it touched. Not polling:
  the moment he looks at the screen is the moment the answer must be current, and `staleTime` keeps
  a tab-switch from becoming a request.
- **The push arrived and refreshed the wrong things.** The backend DOES push on a signature
  (`PublicEstimateService`), but `usePushRefresh` invalidated `projects`, `dashboard` and
  `project-messages` — it had been written for a client's QUESTION and never widened. A signature or
  a payment lands on none of those. It now invalidates the four above as well; the message payload
  does not say which kind of push it is, so all of them go.

### «Знижка 14,776%» for a discount typed as 15

A real defect, and it had been on screen since the panel existed. `AdjustLine` derived the percent
as `amount / (works + materials)`, but a «% від кошторису» line is measured against **its own
TYPE's** subtotal — so the figure was wrong for every estimate that carries materials
(4 774 / 31 829 = 15 %, 4 774 / 32 312 = 14,776 %).

The client now derives nothing: `SignedEstimatePanelResponse.markupRate`/`discountRate` carry the
percent the master actually typed, and are **null when several percent lines disagree** — one
blended figure would be a number nobody's estimate carries, which is the rule the multi-estimate
summary panel already followed by showing amounts only.

**A trap paid for on the way:** an apostrophe inside a `--` comment in a native `@Query` is read as
the start of a string literal, and the whole application fails to start
(`QueryCreationException` on an unrelated-looking repository method). The explanation moved into the
javadoc, with a note.

### «Мої гроші» — a period he picks himself

The backend already took arbitrary `from`/`to`, so this is PWA-only: a fourth button beside
Тиждень / Місяць / Рік, with two date fields under it. Four decisions worth keeping:

- the fields render **only** while «Період» is chosen — two empty inputs above every other view are
  furniture on a screen opened to read three numbers;
- the chosen dates **survive** switching to «Місяць» and back, so returning to the same window is one
  tap rather than four;
- always a flat list, **never** the year view's month totals: he chose the window, so he wants what
  is inside it;
- a reversed range is **swapped, not refused** — two date fields on a phone are tapped in whatever
  order, and the server swaps them too.

**Not a bug, and worth writing down because it will be asked again:** a signed estimate is not money.
«Мої гроші» shows movement, so an object whose payments all landed in August shows nothing under
«Місяць» in September — which is exactly what happened on «Будинок в Куликові» (11 700 ₴ of payments
on 11–14.08, and +20 ₴ / −10 ₴ on 17.09).

### Tests

Backend **1560 in 179 classes** (+2: the rate the master typed, and two disagreeing percent lines
reporting none). PWA **1190 in 134 files** (+6: the percent from the server and the amount-only
fallback, the custom range and its reversed bounds, and `crewMarginLine.test.tsx` — which also
proves the editor line does not re-render forever, since it recomputes on every render).
