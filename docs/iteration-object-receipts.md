# Iteration — «Чеки обʼєкта» (V129)

**Dates:** 2026-09-09 … 2026-09-10
**Migration:** `V129__project_receipts.sql`
**Related:** [iteration-shopping-list.md](iteration-shopping-list.md) (V126/V128),
[iteration-material-calculator.md](iteration-material-calculator.md) (V127),
[iteration-receipts-batch.md](iteration-receipts-batch.md) (the act-side shape this reuses),
[open-questions.md](open-questions.md) → *Object economy: photo of a receipt attached to an expense*.

---

## 1. The question this round answers

The master described the whole material flow in one message, and the framing matters more than any
of the individual asks:

> «треба так, щоб майстер по мінімуму задумувався про оці всі економічні речі, він деколи не має
> просто на це часу і задумка додатку в цьому власне допомогти, а не ще більше взламати мозок.»

Then the scenario: he builds an estimate → materials are calculated → some of it is already on the
object, or the client buys it himself (so the master either ticks «куплено» or does nothing at all
and just sends the list on) → he drives to the merchant and buys what is left. And then the two
questions the previous rounds had left unanswered:

> «1) Для чого нам те меню додати матеріали зі списку у кошторис?»
> «2) Майстер має чеки на руках … куда ті витрати лягають зараз, куда воно пишеться? — теоретично
> це діло має потрапити до клієнта, щоб той віддав суму потрачену на ці матеріали — бо вбільшості
> це витрати клієнта а не майстра? отут я вже сам не розумію і майстер також не захоче роздумувати
> над цим — все має бути просто, один чи два кліки і поїхали.»

Both were answered by the master himself on 2026-09-10: **(1) прибираємо**, **(2) робимо так** — the
design below. Two more instructions came with them: **at least a real PDF** for sharing the list
(«просто текст це дуже примітивно»), and the receipt flow must let him **pick several photos from the
gallery**, not only open the camera — «так як ми робимо в актах».

---

## 2. The ruling: a receipt is a RECEIVABLE by default

This is the decision the whole table encodes. In this trade the material money is mostly the
**client's**, so:

* `project_receipt.reimbursable` defaults to **TRUE** — «клієнт відшкодовує».
* A reimbursable receipt writes **nothing** to `object_expenses`. It is not a cost.
* Only an explicit tap («це моя витрата») flips it to `false`, and only *then* is a MATERIALS/RECEIPT
  `ObjectExpense` created, its id stored in `project_receipt.expense_id`. Flipping back deletes it.

Why this way round and not the obvious «every receipt is an expense»: booking material the client
paid for as the master's cost makes `Прибуток` quietly wrong for the majority of masters, and the
mistake is invisible — the number still looks like a number. Defaulting to a receivable makes the
common case free of decisions (camera → сума → зберегти, two taps, nothing decided) and puts the
one economic judgement behind a single tap in the minority case.

Consequences worth keeping in view:

* **The V126 rule survives.** «The list never writes an `ObjectExpense`» — money still enters the
  economy only through a receipt, and now only through a *deliberate* one.
* `expense_id` is `ON DELETE SET NULL`, so deleting the expense by hand from the journal leaves the
  receipt intact and simply unlinks it. A CHECK
  (`project_receipt_expense_only_when_own`) stops a later edit leaving a dangling bill:
  `expense_id IS NULL OR reimbursable = false`.

---

## 3. Why a SECOND receipt table

`work_act_receipt` (V110) is a receipt **on a document**: it exists to be re-billed on one act and is
frozen into that act's `doc_hash`. The paper in the master's hand at the merchant belongs to no act
yet — he buys before he signs, and usually before an act exists at all. Filing it against the
**object** is exactly what lets the till involve no document choice and no economics.

The two tables stay apart deliberately. Merging them would mean either a nullable `work_act_id` on a
table whose rows are hashed into signed documents, or an object receipt inheriting the act's
not-signed guard, which an object has no equivalent of.

Everything else is the shape [receipts-batch](iteration-receipts-batch.md) already proved on live
paper:

* the **photo is mandatory** (400 `PROJECT_RECEIPT_PHOTO_REQUIRED`) — a receipt row with no paper
  behind it is a number anyone could type;
* the photo is **saved first and priced afterwards**, so `amount = 0` is a legal intermediate state.
  Nothing gates on it here: unlike an act, an object receipt is not part of a document anyone signs,
  so a priceless one costs nobody anything — the screen just says how many still need a number
  (`unpricedCount`);
* the create is **idempotent** on a client-supplied `X-Entity-Uuid`, or a retried upload over a weak
  connection bills the same material twice;
* recognition is **QR first, vision second**, persists nothing, and never fails hard;
* **positions are never read into anything** — the money on a receipt is its total (decided
  2026-08-28 for act receipts; nothing here revisits it).

Limits: `MAX_RECEIPTS = 100` per object (higher than an act's 50 — an act covers one stage, an object
covers the whole renovation), `MAX_PHOTO_BYTES = 8 MB`, blank label named «Чек №N» by the **server**.

---

## 4. Fiscal identity is a WARNING, never a lock

`fiscal_fn` + `fiscal_id` come off the printed fiscal QR and identify the physical paper exactly, so
the same slip photographed twice can be **spotted**. It is deliberately **not** a unique index: the
photo is saved first and read afterwards, so the identity is only known on a later PATCH, and a
unique index there would turn a duplicate into a failed save of a photo already taken. Duplicates are
computed on the read path (`render()` walks the receipts oldest-first and flags every later row that
repeats an `fn|id` pair) and the master decides. A hand-written товарний чек carries no identity at
all — that gap is real and accepted.

---

## 5. The materials axis is its OWN axis

`ObjectEconomyResponse` gains `materials` — `ObjectEconomyMaterialsResponse(reimbursable,
receiptCount, unpricedCount)` — computed **unconditionally** in `ObjectExpenseService.materialsAxis`,
so it sits on the **FREE-visible** side beside the estimates and the works axis.

**It is deliberately NOT folded into `contracted` / `acceptedByActs`.** Those two count one estimate
set and the invariant «Прийнято актами» ⊆ «За договором» must hold; a receipt at the till belongs to
no signed document at all. Money for material enters the contract only when an act picks the receipt
up, and then it arrives through `ActAddendumCreator` like every other act receipt. Fold the
receivable into either figure and the ⊆ invariant breaks silently.

Receipts flipped to «це моя витрата» are **absent** from this axis on purpose: those are
`object_expenses` rows, counted in the (PRO) `internals` — never twice.

`receiptCount` rides along because a sum with no count reads as a mystery; `unpricedCount` says the
figure is not yet the whole story.

---

## 6. The endpoints

All under `/api/projects/{id}/receipts`, FREE on every plan (filing a receipt photo already was, and
so is reading its footer):

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/` | receipts + `reimbursable` / `own` totals + `unpricedCount` |
| `POST` | `/` | multipart; photo mandatory, label/amount/date optional; `X-Entity-Uuid` |
| `POST` | `/qr` | fiscal QR, no model call; own `QrScanRateLimiter` bucket |
| `POST` | `/{receiptId}/recognize` | reads the **already-stored** photo; `ReceiptScanRateLimiter`; not `@Transactional` |
| `PATCH` | `/{receiptId}` | label/amount/date + the **three-valued** `reimbursable` |
| `DELETE` | `/{receiptId}` | drops the posted expense too, if any |
| `GET` | `/{receiptId}/file` | authenticated owner stream, never `/api/files/**` |

`reimbursable` being three-valued is what keeps the ordinary «I read the sum off the paper» save free
of opinion: `null` leaves it alone. While it stays `false`, an edited amount/label/date is mirrored
onto the expense — the two are the same fact and must never drift.

The QR path counts against its **own** limiter, not the recognition one: a QR read spends no model
call and fires automatically on every photo of a batch, so sharing the bucket would let one shopping
trip eat the budget for the pass that actually costs money.

---

## 7. «Прибираємо» — materials no longer go into the estimate

`POST /api/estimates/{id}/materials/estimate-items` is **gone**. The master's answer to his own
question was «прибираємо», and the reasoning is sound: a material line at 0 ₴ (the V81 rule) inside a
document the client signs is worse than no line, and the buying answer already has a home — the
shopping list. `MaterialCalculatorController` now exposes only `GET`, `GET /availability` and
`POST /shopping-list`.

**PWA consequence, not yet done:** `materialsApi.toEstimateItems` and the 📄 button in
`MaterialCalculatorPage` still call the removed endpoint and will 404. They go with the PWA half of
this round.

---

## 8. Hiding «Матеріали» when we can calculate nothing

> «якщо в нас зараз є трейди по яких ми не можемо взагалі нічого порахувати, то Матеріали по
> закупівлі ховаємо взагалі — воно збиває з толку»

`GET /api/estimates/{estimateId}/materials/availability` →
`MaterialAvailabilityResponse(available, workLines, coveredLines)`. V127 ships norms for **DRYWALL
only**, so for every other trade the screen would open with each position listed as a gap and an
empty buying list. An absent feature is quieter than a broken-looking one. `available` is true when at
least one work line resolves to a norm; `workLines`/`coveredLines` let the caller hint at a partial
answer instead of pretending completeness.

The PWA must gate all three entry points on it (`EstimateEditorPage` ×2, `EstimateNextStep`) — also
still open.

---

## 9. «Поділитись списком з клієнтом» — the list as a PDF

> «можна додати таку річ як поділитись списком з клієнтом» … «ну хоча б якусь пдф робити, бо просто
> текст це дуже примітивно»

`GET /api/projects/{id}/shopping-list/pdf` → `ShoppingListPdfService`. A deliberately plain A4 sheet:
title «СПИСОК МАТЕРІАЛІВ», object, address, master + phone, date, then two sections — **ТРЕБА
КУПИТИ** and **УЖЕ КУПЛЕНО** — with columns №/Найменування/Од./К-сть/✓.

Three decisions inside it:

* **No prices at all**, by the same V126/V81 rule the list itself follows. In the shop the price comes
  off the tag; a figure printed on a sheet carrying the master's name would be read as a quote. The
  footer says so in one line.
* **Bought rows are kept**, in their own section. The client's usual question is not «що купити» but
  «що вже куплено», and a sheet that silently drops the settled half looks shorter than the job is.
  The ✓ column also makes the same sheet printable and usable in the shop.
* **The unsigned-source caveat rides along** — when `sourceEstimateUnsigned`, one quiet line says the
  quantities can still move. A hint, never a gate (V126's master ruling).

The model is assembled inside `ShoppingListService.renderPdf`'s transaction, **not** in the
controller: `Project.owner` is LAZY and `open-in-view` is off — the same precedent as
`WorkActService.renderPdf`. An empty list renders «Список порожній.» rather than bare headings.

Tests: `ShoppingListPdfServiceTest` (PDFBox `Loader.loadPDF` + `PDFTextStripper`, five cases incl. the
no-price and empty-list assertions) and a `pdf_…` case in `ShoppingListControllerTest`.

---

## 10. The PWA half — landed

All five items of the original list are in. What each turned into:

1. **«Прибираємо»** — the «додати матеріали у кошторис» button and `materialsApi.toEstimateItems` are
   gone, so nothing calls the deleted endpoint.
2. **Object-receipts UI** — `/receipts/:projectId` (`ProjectReceiptsPage`), a full-screen surface for
   the same reason the shopping list is one: it is a single job done straight after the shop. It
   leads with «Клієнт відшкодовує» because that is the master's real question, shows «Мої витрати»
   only once a receipt is actually filed that way (a permanent 0 ₴ row is the noise this feature
   exists to remove), and puts the one economic decision behind a single tap. The toggle PATCHes the
   row's WHOLE text state — the server requires `label` + `amount`, so sending only the flag would
   erase what the master typed — and deliberately omits `fiscalFn`/`fiscalId`, which an ordinary
   PATCH never clears.
3. **Gallery multi-select** — both inputs, copied from `ActReceiptsSection` verbatim: `capture=
   "environment"` for the camera and a second `multiple` input for the pile already photographed.
   `useProjectReceiptBatch` saves every photo FIRST (priced 0 = «not read yet»), invalidates, and
   only then reads — QR locally on every photo, the model only if asked.
4. **«Поділитись списком»** — wired to the PDF endpoint from the shopping list.
5. **The «Матеріали» gate** — `useMaterialsAvailability` on `GET …/materials/availability`, asked
   before the entry points are offered rather than after.

Two consequences worth keeping: the shopping list's «Додати чек» no longer goes to `?tab=photos` but
to this screen (carrying `state.from` so «←» returns to the list), and the economy tab gained a
FREE-visible `MaterialsAxis` card beside `ActsAxis` — its own axis, never folded into
`contracted`/`acceptedByActs`, and absent until a receipt exists.

**Update 2026-09-11 — the shopping-list door is WITHDRAWN.** The master had it removed («прибираємо
те Додати чек зі списку матеріалів, не функціонал, а просто батон покищо, почекаємо що самі майстри
скажуть — чи воно їм взагалі потрібне»), so only the button is gone: the screen, the route and every
endpoint stand, and `shopping.addReceipt` stays in both locale bundles for the restore. That leaves
the economy card as the sole door, and it is gated on `receiptCount > 0` — see «Still open» below,
which the removal makes strictly worse rather than moot.

**No offline path, on purpose.** An object receipt has no outbox entity, so the batch checks the
connection once up front and refuses, the add buttons are disabled offline, and the screen says why.
The alternative — queueing bytes it cannot send — is a pile of photos that looks saved and is gone.

PWA gate green in CI order: `npm run lint` · `npx tsc -b` · `npm run typecheck:tests` ·
`npx vitest run` (916) · `npx vite build`. Tests: `ProjectReceiptsPage.test.tsx` (11 cases — the
whole-text-state PATCH, the flip back, the two file inputs incl. `multiple`, the offline refusal, the
unpriced warning gating nothing, the duplicate warning, both back paths, the empty state).

**Still open, and now sharper (2026-09-11):** with the shopping-list button withdrawn the economy
card is the only door left, and it renders nothing while `receiptCount === 0` — so a master with no
receipts yet has **no way into the screen at all** from the app. One who already has receipts keeps
his card and loses nothing, which is what makes the removal safe to ship as-is.

**Also open — the money model itself.** The master asked for the receipt→economy math to be derived
and it exposed two gaps: `sumReimbursable` filters only `reimbursable = true` with no «settled»
state, so the receivable never closes; and `received` is Σ of ALL `payment_receipt` for the object
with no purpose recorded, so a material reimbursement lands on the works axis and shrinks
«Залишок» as if the client had part-paid for the WORK. Money handed over in advance for materials
has the same problem plus nowhere to sit. He confirmed both cases must work and must not route
through acts («не все переводиться через акти, багато хто так не працює»). Recommended direction:
give `payment_receipt` a purpose («за роботу» / «за матеріал»), which closes both gaps; a
`settled_at` flag on the receipt alone closes only the first. **Nothing is approved** — and the
stated constraint is that existing clients' figures must not change.

---

## 11. Open questions touched

* *Object economy: photo of a receipt attached to an expense* → **IN_PROGRESS**, reframed: the
  primary landing place is a receivable in the visible half of the economy, and an `ObjectExpense` is
  what the minority «це моя витрата» case produces.
* **Not** touched, contrary to what a reader might expect: FREE's `MAX_RECEIPT_PHOTOS_PER_OBJECT`
  (5). An object receipt is capped by its own `MAX_RECEIPTS = 100` and stores its photo straight
  through `StorageService` — it never goes through `LimitService`, so that cap governs
  `project_photo` rows with `PhotoSource.RECEIPT` (the act-receipt copy and the estimate-side
  receipt import) and nothing here. `Limit.MAX_RECEIPT_PHOTOS_PER_OBJECT`'s javadoc still says
  «Reachable only on PRO/TEAM (receipt import is PRO-gated)», which V129 does not change but which
  the photo-folders round already made imprecise.
* Unaddressed and known: a hand-written товарний чек has no fiscal identity, so duplicate detection
  cannot cover it.

---

## 12. One paper, two tables — review item B-04 (V134)

### The hole

V129 warned about a duplicate *inside* `project_receipt`. The pair it could not see is the one that
costs real money: the master photographs a slip at the till (object receipt), then attaches **the
same paper** to an act (`work_act_receipt`, V110). `work_act_receipt` had no `fiscal_fn`/`fiscal_id`
at all, so the two tables had no comparable key and nothing could notice.

Two consequences, both silent:

* **The client is billed twice on screen.** Signing the act rolls the receipt into the ADDENDUM, so
  that money lands in «За договором» — while the object still lists it under «клієнт відшкодовує».
  The same debt on two screens, and a master who asks for it twice.
* **«Прибуток» is understated by the whole receipt.** With `receipts_to_expenses` on, the act posts
  a MATERIALS expense — and an object receipt flipped to «моя витрата» had already posted one.

### The shape

`V134` gives the act receipt the same two nullable columns, and `project_receipt` a
`billed_on_act_id`. **Nullable, no default, no backfill** — and the migration *asserts* it moved
nothing (`RAISE EXCEPTION` if any row comes out stamped or identified). Already-signed acts are
deliberately not rescanned: their history is frozen, and silently restating a master's past profit
is worse than the gap it would close.

Three pieces carry it:

* **`FiscalQrReceiptReader`** — ONE QR read for both surfaces. It exists because the two callers had
  already drifted in the way that kills a feature quietly: the object's path returned `fn`/`id`, the
  act's identical path threw them away. So an act receipt could never be identified no matter what
  the schema allowed. `ActReceiptRecognizeResponse` is now a TS **alias** of the object's type for
  the same reason.
* **`ReceiptIdentityIndex`** — the read-path answer, two queries per object whatever the list length.
  Its rules exist so the answer is STABLE beside a paper in the master's hand: **the earliest row
  wins** (the warning lands on the copy filed second, V129's behaviour) and **the same table beats
  the other one** (a warning sends him to the list already on screen). The warning **names** the twin
  — «цей чек уже в акті № 7» — because «схоже на дублікат» over forty receipts is a warning a master
  learns to ignore.
* **`ActReceiptReconciler`** — runs inside the sign transaction, on **both** sign paths. It asks
  nothing: two rows with the same `fn` + `id` are one slip from one till, and the alternative is an
  arithmetic question put to a master standing in front of a client.

### The half that is conditional, and why the other half is not

* **Always: stamp `billed_on_act_id`.** The ADDENDUM has just moved that money into «За договором»,
  so the receivable must let go of it. `sumReimbursable`/`countReimbursable` and the list's own
  `reimbursableTotal` all gained the same `billed_on_act_id IS NULL` filter — they describe **one
  number on two screens** and may never differ. The ROW stays, saying which act took it: a receipt
  he definitely photographed must not simply vanish.
* **Only when `receipts_to_expenses` is on: drop the object receipt's own expense.** With it OFF the
  act writes no expense at all, so the object receipt is the ONLY carrier of that cost and dropping
  it would **inflate** profit by the same amount. «They're duplicates, flip it» would be a new bug,
  not a fix — `ActReceiptReconcilerTest` pins both directions.

The key is the printed identity **alone**, never the amount: a partial return (V115) legitimately
makes the two rows disagree about money while they remain the same piece of paper.

**Nothing in the reconciler throws.** It runs in the transaction that signs the act — including the
client-facing portal sign — and no bookkeeping tidy-up may cost a master a signature.

### Left as it was

* A hand-written товарний чек still has no identity to compare, and that gap is real — better than
  guessing from a label and an amount that two different papers are one.
* Duplicates are never blocked, on either side. A shop can legitimately reprint a slip, and only the
  master is holding the paper.
