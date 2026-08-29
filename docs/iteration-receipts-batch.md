# Iteration: a batch of receipts — save the paper first, price it later

**Status:** complete on both sides — backend green on `./gradlew build`, PWA green on the full CI
mirror (lint · tsc · typecheck:tests · vitest 724 · vite build). NOT pushed (awaiting the user's
approval).
**Source:** the master, after using the act receipts on a real job — «з недостатньою швидкістю
інтернету довго думає і додавати чек не хоче», plus «хочу закинути всі чеки одразу, а розібратися
потім» and «щоб зверху були найновіші».
**Migrations:** none — every column already existed; what changed is which values are legal and when.
**PWA:** the receipts UI is rebuilt on both sides — act receipts (`ActReceiptsSection`,
`useReceiptBatch`) and estimate import (`ReceiptImportSheet`). `QrScanSheet` is DELETED; rounds 3-4
added the shared `features/photos/ReceiptPhoto.tsx` (§13) and turned «Новий акт» into a draft the
server never sees until «Зберегти» (§14, the static `/acts/new` route).
**Open questions touched:** «ДПС QR receipt lookup» → `RESOLVED`; the offline-receipts item's
`X-Entity-Uuid` prerequisite → `DONE`, ahead of the offline work itself.

The act-receipts feature assumed a receipt arrives complete: the master types a label and an amount,
picks the photo, and the row is created once. On a real site with real reception that order is
backwards. Recognition (a model call, or a lookup against the tax service) runs **before** the row
exists, so a slow link does not merely delay the receipt — it loses it, together with the photo the
master already took. And the master does not photograph one receipt; he photographs the day's pile.

So the order is inverted. **The photo is saved first and priced afterwards.** Everything below
follows from that one change, including the things that had to be built to make it safe.

---

## 1. An unpriced receipt is a legal state — and a guarded one

`MIN_AMOUNT` drops from `0.01` to `0`. A receipt created with amount 0 means «saved, not read yet»,
not «worth nothing». That is what lets the upload finish before any recognition runs.

The cost is that a 0 ₴ receipt now exists and must never reach a document. Once an act is SENT the
client can sign it, and a SIGNED act is immutable, hashed into `doc_hash`, and rolls its receipts
into a SIGNED ADDENDUM estimate — a 0 ₴ line there is material the master silently gave away.

`ActReceiptCompleteness.requireAllPriced(actId)` is the one guard, and it sits on **all three doors**:

| door | where |
| --- | --- |
| publish to the client link (DRAFT → SENT) | `ProjectPortalService.updateAct` |
| offline signing | `WorkActService.signOffline` |
| portal signing by the client | `PublicActPortalService.sign` |

The portal one is not redundant with the publish one: receipt writes are governed by
`requireNotSigned`, **not** by "not sent", so a SENT act can still gain a receipt.

400 `WORK_ACT_RECEIPT_UNPRICED`. **Editing is deliberately not blocked** — an unpriced receipt is
work in progress, not an error; the PWA names the offenders in place.

It is its own `@Component` rather than a method on `WorkActReceiptService`, because that service
depends on `WorkActService` and the guard is needed inside it — injecting the service would be a
cycle.

## 2. The server names a receipt it was given no name for

A blank label is no longer an error: the server writes «Чек №N». The number is the receipt's own
position at creation, and it stays in the label — editable, and unaffected when the list re-sorts.

The **server** owns it because the client cannot: it does not know how many receipts the act already
holds, so a batch upload defaulting locally would name every photo «Чек №1». `requireValidFields`
now rejects only an over-long label (the column would truncate it), never a blank one.

## 3. A retried upload must not bill the material twice

`POST /api/acts/{id}/receipts` takes `X-Entity-Uuid`, the same shape as every other offline-capable
create in this codebase: the client UUID becomes the row's id, and a replay returns the receipt that
already landed. A replay naming a **different** act is a 404, never a peek at another act's receipt.

This was listed in `open-questions.md` as a prerequisite for offline receipts. It was pulled forward
because it is not an offline concern at all: **a batch over a weak connection retries for exactly
the reason a queue replays**, so the duplicate-receipt hole — duplicate money in the act *and* in
its ADDENDUM rollup — was already reachable online, today.

## 4. `itemized` becomes editable after the fact

`WorkActReceiptRequest.itemized` is a **nullable** `Boolean`: `null` means «leave as it is», so a
plain rename cannot silently un-itemize a receipt.

It used to be settable only at upload, which stopped working the moment the photo started being
saved before it is read — the master now carries a receipt's positions into the act from the *edit*
dialog, long after the row exists. Getting this wrong bills the client twice: once as act lines,
once in `receiptsTotal` (and again in the ADDENDUM on signing).

## 5. One ordering, newest first

`findByWorkActIdNewestFirst` replaces `findByWorkActIdOrderBySortOrderAscCreatedAtAsc` at **every**
call site — the editor list, the PDF, the client portal and the ADDENDUM rollup — so the four can
never disagree about which receipt is «1.».

```
ORDER BY r.issuedAt DESC NULLS FIRST, r.sortOrder ASC, r.createdAt ASC
```

**Undated first** is deliberate: a receipt with no date is one the master still has to look at, so
it belongs where he lands. `sortOrder` only breaks ties — it is insertion order, which is what the
whole list used to be sorted by.

(The cap check no longer loads the list to measure it: `countByWorkActId`.)

## 6. Recognizing a photo that is already stored

`POST /api/acts/{id}/receipts/{receiptId}/recognize` — what «✨ Розпізнати» on a saved receipt card
calls. Same contract as the upload-time `recognize`: **persists nothing**, the client applies the
prefill and PATCHes. The only difference is which bytes are read.

That difference is the whole point. The photo is uploaded **once**, when the receipt is created, and
every later read runs against the stored copy — so a slow read can be abandoned, retried, or resumed
after a page reload without spending the master's uplink again.

The per-MODE gate was carried over exactly as the upload endpoint had it — the footer pass FREE, the
item pass behind `Feature.RECEIPT_IMPORT`. **§15 removed the item pass**, and the gate went with it:
this endpoint is free, and `WorkActReceiptRecognitionTest` now pins that. A SIGNED act still refuses
it, before the photo is fetched.

## 7. The QR read becomes local, and gets its own counter

Two changes, both because the QR read is no longer a deliberate act with its own button — it is the
free first rung inside «додати чек з фото», fired automatically on every photo of a batch.

**`FiscalQrService.read(payload, withPositions)`.** `withPositions=false` skips the ДПС lookup
entirely and answers from the code alone — no network call at all. This is not an optimisation
detail: the lookup adds only the seller name and the purchased lines, and since a receipt is now
named «Чек №N» by default, a caller that does not want positions has nothing to gain from an
undocumented third party with a 10 s timeout, and everything to lose. The flag therefore drove the
**lookup**, not just the response shape — and since §15 the act path passes `false` always, so the
lookup never runs there at all. The estimate import, which exists to produce lines, still passes `true`.

**`QrScanRateLimiter`** — per account, default **120/hour** (`app.rate-limit.qr-scan.max-per-hour`,
`QR_SCAN_MAX_PER_HOUR`), answering 429 `error.rate.qr-scan`. Counted **separately** from
`ReceiptScanRateLimiter` (master decision: «рахуємо кюар шлях окремо і не міняємо ліміти»): a QR read
spends no model call, and a batch of ten receipts spends ten of these while spending zero
recognitions. Share the bucket and one photo batch eats the budget for the pass that actually costs
money. Wired on **both** QR entry points — the act's `…/receipts/qr` and the estimate import's
`…/receipt-items/qr`, which previously had no counter at all. In-memory/single-node, like every
other limiter here.

## 8. What the batch cap does, and does not, govern

Recorded in `open-questions.md`: a batch can exhaust `MAX_RECEIPT_PHOTOS_PER_OBJECT` (5 on FREE)
mid-way. The chosen behaviour is to **check the remaining budget before uploading and say so
plainly** — «чеки додам усі, у галерею збережу перші N» — rather than silently dropping the
overflow.

The cap governs only the **second** copy in the object's gallery (`saveToPhotos`). The act's own
frozen receipt photo is never counted against it, so a FREE master can never lose receipt proof to
this limit.

---

## 9. The PWA: one picker, no QR button

**«не потрібно мати окремий екшин на юаї, бо майстрам обовязково потрібне фото»** — the master's own
framing, and it deletes a component. `QrScanSheet` is gone from both dialogs and from the repo. A
fiscal code read on its own produced numbers with no paper behind them, which is exactly what the act
receipts may not be. So the QR became invisible: **the first rung of «додати чек з фото»**, decoded
locally off the photo the master took anyway, on every photo of the batch.

«Вибрати з галереї» is `multiple`. The camera input stays single — that is one receipt by definition.

`useReceiptBatch` runs the inverted order literally:

1. **Save every photo first**, each with its own `newUuid()` (§3) and `amount: 0` (§1), then
   `invalidate()` — the rows are on screen, named «Чек №N» by the server, before anything is read.
2. **Then read**, receipt by receipt: `decodeQrFromFile(file, { budgetMs: BATCH_QR_BUDGET_MS })` →
   `looksFiscal` → the free QR endpoint; failing that, and only if the master asked for it,
   `recognizeStoredReceipt` against the bytes already in storage (§6).
3. A read that fails, times out, or is cancelled leaves an **unpriced row** — the state §1 made
   legal. Never a lost receipt, never N identical toasts (only the first error is surfaced).

`BATCH_QR_BUDGET_MS` is **1500 ms**, against `SWEEP_BUDGET_MS`'s 6000 for a single deliberate read:
`jsqr` is synchronous, and ten full ladders is a minute of frozen phone for an enrichment that is
optional by construction.

**One question per batch, not per receipt.** `BatchChoiceSheet` asks once — read the sums with the
model? carry the positions across? keep a copy in «Чеки»? — and the answers are deliberately
**sticky** between batches: a master who reads his receipts one way reads the next pile the same way,
and re-ticking three boxes every time is the friction this replaced. Unticking the model is the
answer to «довго думає»: the photos are added, the sums are typed. «✨ Розпізнати» stays on each card
as a top-up, so nothing is lost by saying no now.

The gate sat on the paid **action**, never on the tick: the «перенести позиції» checkbox was togglable
on FREE and opened `UpgradeIntentModal`, and the batch then called `recognizeStoredReceipt(..., false)`,
because everything a QR hands back is free. **§15 removed the tick, the mode it asked for and the
gate behind it** — the batch now asks two questions, and the read costs nothing either way.

## 10. An unpriced receipt is visible, countable, and fixable in place

Per card: a warning border, «Сума не вказана» instead of the amount, a line of text saying the
information is incomplete and must be entered by hand, and a «✨ Розпізнати» button that tries again.
Above the list: «Чеків без суми: N. Поки сума не вказана, акт не можна надіслати чи підписати» —
naming §1's guard **before** the master hits it at the share sheet.

**Numbering is positional, the name is not** (master decision). The ordinal on the left (`1.`, `2.`)
follows the sort — newest first, §5 — while the label stays the «Чек №N» it was created with. The two
may diverge, and that is the point: the label freezes into the PDF and the `doc_hash` on signing, so
it must never renumber itself under a document that has already been sent.

## 11. The same ladder on the estimate side

«От чеки для кошторисів — там може бути класно, також» — so `ReceiptImportSheet` got the identical
treatment: multi-pick, per-photo QR-then-model, and **one shared review** for the whole pile, appended
by a single «Додати». Each group carries a divider naming its slip and how it was read
(«Чек 2 · з фото»), because a QR row is the tax service's own record while a photo row is a guess
worth checking.

Two decisions differ from the act side because the flows differ:

- **The upsell fires late, and only on evidence.** The picker is free (a coded slip costs nothing to
  read); `UpgradeIntentModal` opens only after a photo actually failed to read on FREE. Gating the
  picker would hide a free capability behind a PRO wall.
- **Photos are offered to the gallery afterwards, all of them**, one upload per file, and a failure
  never stops the rest — on FREE the object's receipt-photo cap is reachable mid-pile, and «сім
  збереглося, восьме ні» is the honest report of that (§8).

---

## 12. Round 2 of the master's own screens: the row, the paper, and a button with nothing to do

Three small things, all from the same session on a real act.

**The ordinal leads the row.** It sat inside the title as `1. Чек №1`, reading like part of the
name. It is not — it numbers the receipt, not what the receipt is called, so it moved out in front
of the photo as its own column. The list is a `<ul>/<li>` now, which is what it always was. Nothing
about §5 changes: the ordinal still follows the date order and the label still never renumbers.

**The edit dialog shows the paper.** «Приедітані немає можливості переглядати чек для звірки
вірності даних» — the dialog covered the only copy of the receipt while asking the master to confirm
a sum a reader had guessed. It now opens with the photo above the fields (`max-h-48`, so the fields
stay on screen on a phone) and a tap opens it full-size in a modal over the sheet, with the typed
values intact behind it. `ReceiptFullPhoto` took a `heightClass` prop for the two sizes.

**«✨ Розпізнати» disables itself when it has nothing to read.** Label, sum and date all filled means
every field the footer pass returns is already there — so the only thing the button can still do is
spend twenty seconds overwriting what the master just typed off the paper in front of him. It greys
out with the reason under it, and clearing a field gives it work again and brings it back. (Until
§15 the rule also had to account for «перенести позиції»: ticking it gave the button work to do.)

**The recognize block moved under the fields.** It used to be the first thing in the dialog. But the
receipt was already read once at upload (§9); on an edit the reader is a top-up for what is still
blank, not what the master came in for. It sits between the fields and «Зберегти» now, behind a
divider.

## 13. Round 3: one control for the paper, on both receipt screens

«Зроби те саме для чеків з кошторисів» — and then the rule behind it: «якщо десь той самий чи
подібний функціонал, то едідати всюди і робити одинаково, а ще ідеально виклики мати з одного
місця». So §12's two additions were not copied onto the estimate side; they were **extracted**.

`features/photos/ReceiptPhoto.tsx` is now the one place a receipt's paper is shown. It takes a
`ReceiptPhotoSource` — `{kind:'stored', fileUrl}` for a saved act receipt (bearer-fetched to a blob
URL, since the stream is authenticated and an `<img src>` carries no token) or `{kind:'file', file}`
for a photo the estimate sheet is still holding in memory — behind ONE effect, and renders either
`variant="thumb"` (the row's 56 px square) or `variant="preview"` (the edit dialog's `max-h-48`
strip). It owns its own zoom `Modal`, so no caller keeps zoom state; it is `disabled` until the bytes
are in (there is nothing to zoom into yet); and the full-size copy is the **only** one with an
`alt` — the thumbnail and the preview sit next to the fields they belong to, the zoom IS the receipt.
`ReceiptOrdinal` moved with it, for the same reason: the number in front of a row is the same number
on both screens.

What that buys on the estimate side: the review list now shows the picked photo beside each slip's
«Чек N · з фото» divider, and each parsed position leads with its own ordinal. **The ordinal restarts
inside each slip** — it numbers the positions ON that receipt, which is the only numbering the master
can check against the paper in his hand.

The master's own question — «але ми там ніби нічого не редагуємо, правда?» — is half right, and the
half that is wrong is why the photo matters more here, not less. There is no receipt ROW on the
estimate side (no label, no stored amount, no «✨ Розпізнати» button), so §12's disable rule has no
analogue. But the parsed POSITIONS are fully editable, and confirming a name, a quantity and a price
a model guessed is exactly the moment the paper needs to be on screen.

## 14. «Новий акт» stops creating an act

«Кожен раз коли ми натиснули Новий акт і повернулись назад, то акт вже створюється і це не
правильно, бо може випадково натиснули.» It did: `useNewAct` POSTed a DRAFT the instant the button
was tapped, so a mistaken tap left a real, numbered act on the object — and act numbers are
continuous per master and never reused, so the mistake was permanent and visible.

The act is now born on «Зберегти». `useNewAct` only navigates: `routes.newAct(projectId)` →
`/acts/new?project=…&from=…&scope=…`, a **static** route registered above the dynamic `/acts/:id`.
The defaults ride the query string because there is no row to read them from — the period start is
computed off the object's acts, which the Acts tab has loaded and the editor does not.

In the editor, `isNew` (`id === ''`) gates four things:

- **A second seed effect** fills today's date and the query string's period start; the `act.data`
  seed and the loading/`loadError` gates are skipped.
- **`persist()`** is the one door both «Зберегти» and «Підписати» go through. On an existing act it
  is the old header-then-items pair; on a new one it calls `create` (with an `X-Entity-Uuid` held in
  a ref, so a double tap replays the same create rather than numbering the object twice), then
  `replaceItems` on **the id the create answered with**, then navigates onto `/acts/:id` with
  `replace: true`. The two receipt toggles are update-only fields and cannot have been touched yet,
  so create sends the header alone.
- **`dirty` is unconditionally true** while `isNew`. There is no server row behind the screen, so
  leaving always asks — with its own copy («акт ще не збережено — якщо вийти, він не створиться»),
  not the generic unsaved-changes one. `skipLeaveGuard` covers the post-save navigation.
- **Everything that addresses a row is hidden**: the number and status badge (both the server's, and
  minted on save), 🔗 share in the top bar and the FAB, PDF, delete, and `ActReceiptsSection` — a
  receipt is a multipart upload against a real act, so the section is replaced by «Збережіть акт, щоб
  додати чеки та рахунки.» rather than holding a pile of files in memory.

`useActsInvalidator(projectId)` is `useActWriter` with the act id passed at call time; the new-act
path only learns its id after `create` answers, so it cannot use an id-bound hook. `useActWriter`
delegates to it, so there is still one list of what an act write invalidates.

## 15. Round 4: carrying the receipt's positions into the act is removed

The master looked at his own act and asked the question the feature could not answer: «для чого ми
зробили щоб позиції переносились? може цього взагалі не потрібно?» — after describing what he saw,
which was a receipt greyed out under the line «позиції цього чека включено в акт», billed nowhere he
could point at.

Nothing was broken. The transferred lines were billed, as «Додаткові роботи», exactly as designed —
and that is the problem the question names. One receipt could be billed two different ways, so a
flag (`itemized`) had to exist to stop it being billed twice, and every query that touched receipt
money had to carry `AND r.itemized = false` or the client paid for the same glue twice. It filed
hardware-store goods under a section about agreed WORK. It spent the one paid model call in this
flow on a table the client can read off the receipt photo in the portal and in the PDF anyway. And
it produced the screen that started this: a sum with a line of explanation under it.

A receipt in an act answers one question — how much the master paid for material, and what paper
proves it. A sum plus the photo answer it whole.

**Removed**: the item pass in `ActReceiptExtractor` (three footer fields are the whole job now);
`withItems` on all three act read endpoints and their PWA callers; the `itemized` request field;
the `Feature.RECEIPT_IMPORT` gate that only ever guarded the item mode, and with it the last gate
on this path; «перенести позиції» in `BatchChoiceSheet` and in the edit dialog, the PRO chip and
`UpgradeIntentModal` beside them; `transferReceiptItems` in `ActEditorPage`; five locale keys.
`EstimateExtractor.RECEIPT_SYSTEM_PROMPT`/`lineSchema()` went back to `private` — the act was
their only outside reader.

**Kept, deliberately**: the `itemized` column, the entity and response field, the muted amount, the
badge line, and every `AND r.itemized = false` filter. Receipts created the old way may already be
frozen into a SIGNED act, its `doc_hash` and its ADDENDUM; those must keep reading exactly as they
were signed. So there is **no migration** — what changed is that no new row can ever get the flag.
The integration test that pins the legacy invariant now sets it on the row directly
(`markItemized`), and says why in a comment.

**Not touched**: the estimate-side receipt import (`ReceiptImportSheet`, `ReceiptImportService`,
`POST /api/estimates/{id}/receipt-items/qr`). There the positions ARE the point — that flow exists
to turn a receipt into estimate lines, and it keeps its `RECEIPT_IMPORT` gate on the photo read.

## Tests

- `WorkActIntegrationTest` — blank label is named and numbered by the server; newest-first with
  undated on top; the create is idempotent on the client UUID (and does not double `receiptsTotal`);
  an unpriced receipt is **saved** but blocks both publish and signing, and both doors open once it
  is priced. Since §15 the `itemized` case is a LEGACY one: the flag is set on the row directly
  (`markItemized`) because no API can set it any more, and the queries that honour it must still be
  exercised for acts signed before the removal.
- `WorkActReceiptRecognitionTest` — rewritten in §15 to pin the *inverted* rule: there is no gate on
  this path at all, one read, and no lookup on the QR path. The file exists so a refactor cannot
  quietly change the gate — that intent applies to «there is none» just as much. The QR stub is on
  `read(QR, false)` **exactly**, so a regression that reintroduces the lookup finds no stub and fails.
- `QrScanRateLimiterTest` — the new counter.
- The `RateLimitProperties` record gained a component, so its construction sites in the limiter
  tests were updated (the record/constructor fan-out rule).
- `ActReceiptsSection.test.tsx` (rewritten) — the **order** is pinned literally
  (`['save','save','save','read','read','read']`) with distinct client UUIDs and `amount: 0`, since
  a refactor that reads before saving re-opens the exact hole this iteration closed; unticking the
  model calls no model; a fiscal QR is still read with the model off; a batch PATCH sends
  the unpriced card is flagged, counted and re-readable; the ordinal diverges from the name; a
  signed act shows no picker. Round 2 (§12) added two: the edit dialog renders the receipt's photo
  and a tap opens a second, full-size copy over the sheet with the typed sum still behind it; the
  re-read is **disabled** on a complete receipt, and clearing a field re-enables it. §15 replaced
  the two gate tests with one: the read takes no mode argument and costs nothing, and the legacy
  `itemized` row is still badged and still excluded from «Разом за чеками».
- `ReceiptImportSheet.test.tsx` (rewritten) — the ladder runs per photo and the **paid** read fires
  only where no code decoded; every receipt's lines land in one commit; the whole pile is offered to
  «Чеки» and one failing upload does not stop the others; FREE reads a QR with no upsell but is told
  why a codeless slip did nothing; non-photos are skipped; one failed read does not renumber the
  survivors. `looksFiscal` is left **real** — "is this the fiscal code or the shop's loyalty one" is
  the decision this sheet delegates to it.
- `QrScanSheet.test.tsx` deleted with its component.
- `ActEditorPage.test.tsx` — a new `ActEditorPage (new act)` block: opening `/acts/new` calls
  neither `get` nor `create`, shows «Новий акт» instead of a number, and seeds the period start off
  the query string; «Зберегти» then creates exactly once, with a client UUID, and puts the lines on
  the id the create answered with (not the `''` the URL carried); the receipts section is replaced by
  the save-first hint; and Back on an untouched new act still asks, naming the consequence.
- `ActsSection.test.tsx` — «+ Новий акт» POSTs nothing and navigates to
  `/acts/new?project=…&from=…`, with the period start still computed on the tab that knows the acts.
- `ReceiptImportSheet.test.tsx` — the shared control on the estimate side: each slip's photo opens
  full-size from the review, and the ordinals restart per receipt (`['1.','2.','1.']`).

## Follow-ups

- **Not verified on a phone**: no live batch has been photographed. Both sides are green
  (`./gradlew build`; lint · tsc · typecheck:tests · 719 vitest · vite build) and the behaviour is
  measured by tests, but the camera, the picker's multi-select and the QR budget on real thermal
  paper have only been exercised in jsdom.
- **Offline receipts** stay open; this iteration removed their hardest prerequisite (§3) but not the
  blob outbox itself.
