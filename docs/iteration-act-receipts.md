# Iteration: Чеки та рахунки в акті + дві UX-правки на екрані акта

**Status:** code complete, both builds green, NOT pushed (awaiting the user's approval).
**Source:** a live master's request (relayed 2026-08-21) — «зробити фінальний акт з матеріалами»;
he could not find any way to bill materials through an act.
**Migrations:** **V110** `work_act_receipt` + **V111** (round 2 — see §8).
**PWA:** 1.19.2 → **1.20.0**, then **1.21.0** (round 2), then **1.21.1** (round 3 — the recognition fix, §9) and **1.21.2** (§9.5).

Goal: an act can carry the materials the master paid for out of pocket, as **receipts**, and re-bill
them to the client — photo of the paper + an amount typed by hand, a «чек — сума» list with a
subtotal, visible in the act portal and in the PDF. Plus the two UX complaints that came with it.

The master was explicit that **the receipt's line items must NOT be carried into the act**: «не
обов'язково перенести матеріали з чеку у акт, але мати щось типу такого як чек1 — сума така, чек2 —
сума така і загально». So this is deliberately *not* a parsed line list — the photo is the proof,
the amount is what counts. (The existing `RECEIPT_IMPORT` flow, which *does* parse lines into an
estimate, is untouched and unrelated.)

---

## 1. Backend — receipts on an act

**V110 `work_act_receipt`** (singular — the CLAUDE.md line said `work_act_receipts` for a
while) — `id`, `work_act_id` (FK `ON DELETE CASCADE`), `label`, `amount`
(`numeric(12,2) > 0` CHECK), `issued_at` (nullable), `storage_key` (nullable in the schema —
**round 2 made the photo mandatory at the service**, see §8.1), `sort_order`, `created_at`.
Plus `work_act.receipts_to_expenses boolean NOT NULL DEFAULT true`.

- `entity/WorkActReceipt.java`, `repository/WorkActReceiptRepository.java`,
  `dto/WorkActReceiptRequest.java`, `dto/WorkActReceiptResponse.java`
  (`id, label, amount, issuedAt, hasPhoto, sortOrder`),
  `service/WorkActReceiptService.java`.
- Five endpoints on `WorkActController`: `POST /api/acts/{id}/receipts` (multipart — optional
  `file` + `label`/`amount`/`issuedAt`), `PUT …/{receiptId}` (text only), `DELETE …/{receiptId}`,
  `GET …/{receiptId}/file` (owner-authenticated stream), and the receipts ride along in
  `WorkActResponse`.
- **Frozen on sign** like every other act field: `requireNotSigned` guards all three writes.
- `WorkActResponse` grew `receiptsToExpenses`, `receipts[]`, `receiptsTotal`, and `payable`
  (= works `total` + `receiptsTotal` − `advanceOffset`, floored at 0).

**Why a photo endpoint and not `/api/files/**`** — same rule as object photos and shared receipts:
act paperwork streams only through an authenticated owner endpoint or a portal-token one, never a
public file path.

## 2. The economy invariant, extended

«Прийнято актами» ⊆ «За договором» must still hold with receipts in play. Receipts are money the
client owes that no signed estimate ever promised, so — exactly like off-estimate act lines — they
are folded into the act's **ADDENDUM** estimate on sign, through the shared `ActAddendumCreator`.
Both sign paths (portal and offline) go through it, so both axes move together:

- `sumSignedActLineTotals` **and** `sumSignedActReceipts` feed `acceptedByActs`;
- the ADDENDUM they create is SIGNED + `count_in_economy = true`, so `contracted` rises by the same
  amount in the same transaction.

Drop either half and the ratio goes past 100 %. The integration test
`signedReceipts_landInBothEconomyAxes_soAcceptedNeverExceedsContracted` pins it (contracted 16 900,
accepted 9 650).

**`receipts_to_expenses` (default true)** — a receipt is pass-through money: the master already paid
it. On sign each receipt is posted as a MATERIALS/RECEIPT `ObjectExpense`, so
`profit = contracted − expenses` doesn't count the client's reimbursement as margin. The master can
switch it off per act (he may have logged the same spend manually already); then the client is still
billed and no expense is posted — `receiptsToExpensesOff_stillBillsTheClientButPostsNoExpense`.

## 3. Portal + PDF

- `PublicActView` gained a nested `Receipt` record (`label`, `amount`, `issuedAt`, `hasPhoto`),
  `receiptsTotal` and `payable`; `PublicActPortalService.receiptFile(token, receiptId)` streams a
  receipt photo through the act's own token — no auth, no file path.
- `static/portal/index.html` — the `?a=` `renderAct` branch lists «Чеки та рахунки» with thumbnails
  the client can tap, the subtotal, and «До сплати».
- `WorkActPdfService` — a `ReceiptRow` block after the works table, then the payable line.
  **Receipts are inside the canonical (hashed) render** — unlike the «ДОВІДКОВО» cumulative block,
  which is live and object-wide. Receipts are frozen act content, so they belong to the
  tamper-evidence, not outside it.
- `ActSignedCopyService.computeDocHash`/`emailClientCopy` took a third argument (the receipts) so
  both sign paths hash and email the same document.

## 4. UX fix 1 — «Зберегти» on the screen itself

The loudest complaint: to save an act the master had to leave the editor and hunt in the FAB.
`ActEditorPage` now opens with a **sticky top bar** (back · «Акт № N» · status badge · 🔗 · Зберегти)
that stays put while scrolling a long act. `dirty` adds a «•» to the label.

**Why a sticky top bar and not a fixed bottom one** — the bottom of a phone screen is already taken:
mobile nav ≈60 px plus the FAB at `bottom-20 right-4`. A bottom save bar would have collided with
both. The FAB sheet «Дії з актом» keeps Save/Sign/PDF/Delete unchanged — nothing was moved, only
added.

## 5. UX fix 2 — share an open act

«Поділитися з клієнтом» existed only on the object's Акти tab. The 🔗 button in the new top bar
opens the same `ActShareSheet` for the act you already have open.

## 6. PWA

- `api/types.ts` — `WorkActReceiptResponse`; `WorkActResponse` gained `receiptsToExpenses`,
  `receipts`, `receiptsTotal`, `payable`; `WorkActUpdateRequest` gained `receiptsToExpenses`.
- `api/acts.ts` — `addReceipt` (multipart, `'Content-Type': undefined`), `updateReceipt`,
  `removeReceipt`, `receiptFileUrl`.
- `features/acts/useActs.ts` — `useAddActReceipt` / `useUpdateActReceipt` / `useDeleteActReceipt`.
- `features/acts/ActReceiptsSection.tsx` (new) — cards with a photo thumb or 🧾, label, amount,
  date, edit/delete; subtotal row; «+ Додати чек»; the `receiptsToExpenses` checkbox (only once a
  receipt exists). The add form's file input is `accept="image/*" capture="environment"` so a phone
  opens the camera straight at the paper.
- Receipt photos stream through the authenticated endpoint, so `<img src>` can't carry the token —
  reuses `usePhotoBlobUrl` (bearer-fetch → object URL), the same path object photos use.

**Receipts save immediately, unlike the rest of the editor.** A multipart photo can't ride the
header's JSON «Зберегти», and a picked photo must not be lost by leaving the screen. Only the
`receiptsToExpenses` flag lives in the editor's dirty snapshot.

## Tests

Backend (`./gradlew build` green, 1028 tests): `WorkActIntegrationTest` ×3 (billed on top + frozen
on sign; both economy axes; `receiptsToExpenses=false`), `PublicActPortalServiceTest`
(`view_withReceipts_listsThemAndBillsThemOnTopOfTheWorks`), `WorkActPdfServiceTest`,
`ObjectExpenseServiceTest`.

PWA (full CI mirror green — lint, `tsc -b`, `typecheck:tests`, 102 files / 658 tests, `vite build`):
`ActReceiptsSection.test.tsx` (new — multipart add with the file, subtotal + everything hidden once
signed, delete behind a confirm) and three new `ActEditorPage` cases (receipts + subtotal + payable,
save from the on-screen button, share from an open act).

`src/test-setup.ts` gained `URL.createObjectURL`/`revokeObjectURL` stubs — jsdom has neither, and
any component that streams an authenticated image throws on unmount without them.

## Gotchas

- **`WorkActUpdateRequest` is a 12-component record now** (slot 11 = `receiptsToExpenses`). Calling
  `updateHeader` directly from a test bypasses bean validation, so an all-nulls request nulls
  `kind`/`issuedAt`/`periodFrom`/`periodTo` and dies on the NOT NULL — pass real values.
- Both `sumSignedActLineTotals` and `sumSignedActReceipts` must keep the `count_in_economy` filter
  and the `estimate_id IS NULL` branch, or the ⊆ invariant breaks.

## Not verified

Live mobile layout at 375×812 could not be checked in the moment — the running dev server on
:5173 would not respond to the browser tooling (screenshot/read timed out repeatedly). The sticky
bar is built to truncate the title and `shrink-0` the badge and the action pair, so it cannot
overflow 375 px, but that is reasoning, not an observed screenshot.

---

# Round 2 (V111, PWA 1.21.0) — recognition, itemized receipts, photo folders

**Status:** code complete, both builds green, NOT pushed.
**Source:** the same master, testing round 1. Three asks: read the receipt so the amount doesn't
have to be typed; carry the receipt's positions into the act when he *does* want them itemized;
and stop the Фото tab from being one undifferentiated pile once receipt photos land in it.

## 8.1 The receipt photo is now MANDATORY

`WorkActReceiptService.add` refuses a receipt with no file (400 `WORK_ACT_RECEIPT_PHOTO_REQUIRED`).
Round 1 allowed a typed-only row; the master's own reasoning killed it — **a receipt row with no
paper behind it is just a number anyone could type**, and the whole point of the block is proof the
client can look at. `storage_key` stays nullable in the schema (V110 rows may predate the rule).

`requireValidFields` now enforces the label/amount bounds by hand on the multipart path too
(`MAX_LABEL` 160, 0.01 ≤ amount ≤ 99 999 999.99). That path has no bean validation, so a negative
amount used to reach the DB CHECK and surface as a 500.

## 8.2 Recognition — `POST /api/acts/{id}/receipts/recognize`

> **Superseded (2026-08-28).** Carrying a receipt's positions into an ACT was removed —
> there is one read left on that path (label / date / total), it is free, and no endpoint takes
> `withItems` any more. See `docs/iteration-receipts-batch.md` §15. The ESTIMATE-side receipt
> import is unaffected: there the positions are the point.

Two depths. **The gate is per MODE, not per endpoint** (master decision, 2026-08-23 — see
Round 5 below): the meta pass is FREE, only `withItems` is behind `Feature.RECEIPT_IMPORT`.

- **meta only** (the default, `AiFlow.ACT_RECEIPT`, haiku-4-5) — label, date, total off the footer.
  The cheapest vision job in the codebase; it exists to save typing, nothing more.
- **`withItems=true`** (`AiFlow.RECEIPT`, sonnet-5) — the full item table, review-shaped through the
  shared `ReceiptLines.toParsedItems` (extracted out of `ReceiptImportService.toReview`, so
  estimate-receipt-import and act-receipt recognition normalize identically).

**Recognition never decides anything.** It prefills fields the master then confirms or overwrites;
`AiExtractionException` is a SOFT outcome (`ActReceiptRecognizeResponse.failed()` → «введіть
вручну»), never a blocked flow. `ActReceiptExtractor` uses sentinel answers (`""` / `0` = unreadable)
instead of letting the model guess, and `date()` drops anything in the future or older than 3 years.

**`recognize` is deliberately NOT `@Transactional`** — a vision call runs for seconds and would pin
a pooled connection for its whole duration. Ownership + not-signed are checked up front, each in its
own short transaction, so a foreign or frozen act still never spends a model call. Same shape as
`ReceiptImportService.parse`.

## 8.3 `itemized` — the second way to bill a receipt

`work_act_receipt.itemized` (V111, default false). When the master carries the recognized positions
into the act, they become act lines and bill the money themselves — so the receipt stays attached as
**photo proof only** and its amount must not be counted a second time. It is excluded from:
`receiptsTotal` / `payable`, the ADDENDUM rollup, `sumSignedActReceipts` (the accepted-by-acts axis)
and `sumByWorkActId`; the PDF money table and the portal subtotal skip it too.

**The one place it is NOT excluded is the expense posting** — `ActAddendumCreator` posts expenses
for *all* receipts and rolls up only the non-itemized ones. The master's own spend is real whichever
way the client is billed; only the billing path changes.

This keeps the ⊆ invariant intact: itemized money reaches «За договором» through the act's own
lines → ADDENDUM, non-itemized money through the receipts rollup → ADDENDUM. Never both, never
neither.

## 8.4 `show_receipt_photos` — PDF appendix toggle

V111, default true. A master printing a formal act may not want ten photos stapled to it. The
receipts **money** table always renders (it explains «До сплати») and the portal always shows the
photos — this flag only drops `addReceiptPhotos` from the PDF.

## 8.5 Photo folders in the Фото tab

`project_photo.folder varchar(100)` + `project_photo_folder` (V111). Two defaults are **virtual**,
never stored: «Чеки» = the reserved value `RECEIPTS`, «Інше» = `NULL`. Custom folders are
**persisted** so an empty one survives — the master creates a folder ahead of the photos it will
hold. Moving a photo into a new name auto-creates the row; a folder can be deleted only while no
photo carries its name (photos reference folders by name, so a delete must never silently re-file
someone's photos). Existing `source = 'RECEIPT'` photos are backfilled to `RECEIPTS`, so the two
default folders are truthful from day one.

Four endpoints on `ProjectPhotoController`: `GET/POST /folders`, `DELETE /folders/{folderId}`,
`PATCH /{photoId}/folder`.

Receipt photos can also be filed into the tab as a **second copy** (`saveToPhotos` on the receipt
add, default OFF). The act keeps ITS own copy untouched, so deleting or re-filing the gallery one
can never change a signed act — the frozen-copy rule again. `ProjectPhotoService.saveReceiptCopy`
runs `REQUIRES_NEW` and the caller swallows what escapes: the copy is a convenience and must never
cost the master the receipt.

## 8.6 Fixes found in review (same round)

- **Folder errors answered 415.** They reused `UnsupportedMediaTypeException`, which the handler maps
  to `UNSUPPORTED_MEDIA_TYPE` — so «delete a non-empty folder» returned 415. Now
  `PhotoFolderValidationException` → 400 `PHOTO_FOLDER_INVALID` and `PhotoFolderInUseException` →
  409 `PHOTO_FOLDER_NOT_EMPTY`.
- **`normalizeFolder` compared against Ukrainian literals.** The reserved display names moved to
  `ProjectPhoto.RECEIPTS_FOLDER_ALIASES` / `DEFAULT_FOLDER_ALIASES` (uk + en), so the twin-folder
  guard works in an English UI too and the logic stays English.
- `EstimateService.setCountInEconomy` now refuses an ADDENDUM (409 `ESTIMATE_ADDENDUM_LOCKED`) —
  excluding one from the economy would break the ⊆ invariant from the other side.
- `ProjectPortalServiceTest` gained the `WorkActReceiptRepository` mock: `updateAct` accepts
  receipts as act content now, and a missing `@Mock` is a silent null, not a wiring error.

## Round 2 tests

Backend (`./gradlew build` green): `WorkActIntegrationTest` (+141 — itemized excluded from the
axes but present in expenses, mandatory photo, folders), `ProjectPhotoServiceTest` (+4 — the folder
statuses and the alias folding), `WorkActPdfServiceTest`, new `ActReceiptExtractorTest`.

PWA (full CI mirror green): `ActReceiptsSection.test.tsx`, `ActEditorPage.test.tsx`,
`PhotosSection.test.tsx`.

## Round 2 gotchas

- **`sumSignedActReceipts` and `sumByWorkActId` both carry `AND r.itemized = false`.** Drop it from
  one and «Прийнято актами» double-counts an itemized receipt.
- **The PDF receipts block is inside the canonical (hashed) render**, `show_receipt_photos` or not —
  the flag drops the photo appendix, not the money table, and the appendix is what varies.
- The camera and the gallery are two separate `<input>`s in the PWA. `capture="environment"` alone
  locked phones out of receipts that were already photographed.

## Not verified

Live mobile layout could not be checked here (the dev server did not respond to browser tooling in
round 1 and was not retried in round 2). The new controls are the same full-width sheet/checkbox
patterns already in the file, but that is reasoning, not an observed screenshot.

---

# Round 3 (PWA 1.21.1) — the recognition fix after the master's first test

**Source:** the master tested round 2 on a real Епіцентр fiscal receipt (`check1.jpg`, 12 positions,
СУМА 482,75) and reported: the total was read, **the date was not read at all**, and **«перенести
позиції» did nothing**. Backend-only + one PWA line; no migration.

## 9.1 The real cause of «нічого не зробило» — a 12-second client timeout

`api` (PWA) has a **12s** default timeout, deliberately short so an offline write fails fast into the
outbox. LLM endpoints are exempted **by path** in `client.ts`, and the rule read
`req.url?.endsWith('/parse')` — written when every recognition endpoint happened to end in `/parse`.
The act's read is `…/receipts/recognize`, so it got the 12s default: the cheap footer pass (haiku,
three fields) usually squeaked in — which is why the AMOUNT arrived — and the full table read never
did. Axios aborted a call the server was still working on and would finish, so nothing looked wrong
server-side and the tokens were billed anyway. This is the SAME failure the comment above that rule
already described from the sketch/import era; only the verb was new.

Fix: `endsWith('/parse') || endsWith('/recognize')`, with a note that a new verb must be added there
or it inherits the same silent failure. Covered by a test in `client.test.ts` (twin of the `/parse`
one).

## 9.2 Positions are now read by the ESTIMATE import's receipt prompt

Master's own words: «так як ми в кошторисах використовуємо розпізнати з чеку, то таке саме треба і
тут». `ActReceiptExtractor.extractWithItems` used to send a shorter prompt of its own; it now sends
`EstimateExtractor.RECEIPT_SYSTEM_PROMPT` (made package-private, shared, not copied) plus a short
`FOOTER_TAIL` for the three act-only fields, with a schema of `EstimateExtractor.lineSchema()` +
label/issuedAt/total. Still ONE call, still `AiFlow.RECEIPT` (sonnet) — the local prompt's own
`fullSchema`/`FULL_PROMPT` are gone.

That prompt is the one tuned against real Ukrainian fiscal receipts: the «#article» line as the
per-item anchor ("return the SAME number of items as there are # lines"), the 3-line layout, unit
from the parentheses, "do not stop after the first few". The act's copy also told the model to send
`category: null` while its schema declared category a string — a contradiction that is simply gone
with the shared prompt.

## 9.3 Dates — read the shape that is printed, and stop discarding old ones

Two independent reasons the date never arrived:

- **Format.** `date()` accepted ISO only, so a model echoing the paper's own «04.06.2026» was
  dropped. It now tries ISO, `dd.MM.yyyy`, `dd/MM/yyyy`, `dd-MM-yyyy` and the 2-digit-year variants
  (20YY).
- **The 3-year window.** `issuedAt` older than `now.minusYears(3)` was blanked as "a mis-read year".
  The master's test receipt is genuinely from 2009 — correctly read, silently thrown away. The lower
  bound is **gone**; only a FUTURE date is refused (that one really is a mis-read year). The value
  only prefills a field the master sees and can correct before «Додати чек», so a visible old date
  beats teaching them "recognition doesn't take dates".

The prompt also now describes the ККМ footer line (`DD-MM-YY HH:MM:SS № NNNN` above ФН /
«ФІСКАЛЬНИЙ ЧЕК», first pair = day) and says a readable date is wanted **even if the receipt is
years old** — the old wording invited "" on anything ambiguous.

## 9.4 A 30-second spinner needs to say so

`recognizing` went from `boolean` to `null | 'meta' | 'items'`; with items in flight the dialog shows
`acts.receiptRecognizingItems` («Читаємо позиції чека — це може зайняти до хвилини…»). The full table
read legitimately takes tens of seconds, and an unexplained spinner that long reads as a hung screen
on a phone.

## 9.5 «Поділитися з клієнтом» in the FAB sheet too (PWA 1.21.2)

Round 1 put the share entry on the act's top bar and deliberately left the FAB sheet alone. The
master's screenshot (`screen1.png`, act «Заземлення» scrolled deep into its lines) shows why that was
half a fix: the top bar is a STATIC header, so by the time he is picking lines it is far above the
viewport, and the sheet he does reach lists only PDF / Підписати / Зберегти. `ActEditorPage` now
renders a `🔗 acts.share` `FabAction` between PDF and Підписати — the same `setShareOpen(true)`, the
same `ActShareSheet`, no new state. It is shown for a SIGNED act too (sharing a signed act is normal:
the client opens it to read what he accepted).

Sheet height on a DRAFT is now five rows — 5 × 44 px + gaps + the 56 px button ≈ 320 px above the
bottom edge, which fits a 375 × 812 viewport with room to spare; the pills stay right-aligned and
thumb-reachable. Test: `shares an open act from the FAB too, deep in a long editor`.

## Round 3 tests

`ActReceiptExtractorTest` rewritten: date shapes (`04.06.2026` / `04/06/2026` / `04-06-26`), an old
receipt keeps its date, a future one does not, and a `Flows` nest asserting the item pass runs on
`AiFlow.RECEIPT` with a prompt that **starts with** `EstimateExtractor.RECEIPT_SYSTEM_PROMPT` and a
schema requiring `items/depositAmount/label/issuedAt/total`, while `extractMeta` stays on
`AiFlow.ACT_RECEIPT`. PWA: the `/recognize` timeout test. Both gates green (backend `./gradlew build`,
PWA lint → tsc -b → typecheck:tests → vitest → vite build).

## Round 3 not verified

The fix cannot be proven here: this machine has no `ANTHROPIC_API_KEY` (`.env` carries none), so no
real vision call was made against `check1.jpg`. What is proven is the timeout path, the prompt
wiring, the schema shape and the date parsing; what the model returns for that particular photo is
for the master's next test.

---

# Round 4 (PWA 1.22.0) — the Фото tab reworked into Windows-style folders

Round 2 added folders to the DATA model but left the tab as one long scroll with a filter row. The
master's report: «галочка зберегти у Фото… нічого не зберігає», «якщо додавати фото у розділі Фото,
то воно пише, що воно додало, але по факту нічого немає», and the shape he actually wanted —
«папочки такі як у віндовз, щоб туди якщо файл перемістити, то його не видно».

## 10.1 The two "nothing is saved" reports were ONE client bug

`spring.jackson.default-property-inclusion: non_null` means a null field is **absent** from the JSON,
not `null`. `ProjectPhotoResponse` therefore arrives without `estimateId` at all for a standalone
receipt — and `PhotosSection` filtered the tab with `p.estimateId !== null`, which is `true` for
`undefined`. Every standalone receipt (the act's gallery copy included) was uploaded fine, answered
201, toasted «Додано» — and was then filtered out of the very list the master was looking at. Git
history shows the pre-V111 form had the same test, so a standalone receipt was never once visible.

Fixed at the root: `ProjectPhotoResponse`'s `caption`/`estimateId`/`estimateName`/`folder` are now
`?:`-optional in `api/types.ts` (with the `non_null` explainer on them), and the two other
`!== null` reads of `estimateId` (`EstimateEditorPage`, `EstimateReceipts`) became `!= null`.
**Any new nullable backend field is `?:` in the PWA types — `non_null` makes "null" mean "missing".**

## 10.2 FREE could not save a receipt photo at all

`Limit.MAX_RECEIPT_PHOTOS_PER_OBJECT` was **0** for FREE — a backstop from when the only way to make
one was the PRO-gated receipt import. Both new paths (an act's gallery copy, a photo filed into
«Чеки» by hand) hit it and failed. Master's call: «наразі для фрі має працювати також, не ховаємо
поки що це за про» → FREE is now **5**, same as `MAX_PHOTOS_PER_OBJECT`. The justification is real,
not just a concession: photographing a receipt into a folder calls **no LLM**, so there is no cost
argument for treating it differently from a progress photo. Receipt *import* (which does call one)
stays PRO — a limit and a feature gate are different things.

## 10.3 Drill-in, and no photo outside a folder

`PhotosSection` is now a two-level browser, not a scroll:

- **Level 1 — the folder list.** Full-width rows (`min-h-[56px]`, icon · name · «N фото» · `›`),
  «Чеки» and «Інше» always first, then the master's own folders, then «+ Нова папка». No photo
  tiles and **no file inputs at this level** — there is no root to upload into.
- **Level 2 — inside a folder.** Back `‹`, the folder name, the two upload buttons, the grid.
  «Видалити папку» appears only for a custom folder that is empty (mirrors the server's 409).

The move sheet keeps its old job, and now it actually behaves like Explorer: a photo moved into
«Санвузол» disappears from «Інше». That is the assertion the master's sentence turned into a test.

## 10.4 An upload lands where the master is standing

`POST …/photos` gained a `folder` request param, and the PWA sends the folder it is currently
showing. Server-side `resolveUploadFolder` distinguishes three cases deliberately:

| `folder` param | meaning |
|---|---|
| absent (`null`) | nothing said → the source's default: `RECEIPT` → «Чеки», else «Інше» |
| `""` | an explicit «Інше» |
| a name | that folder, created on the fly if new (aliases fold «Чеки» onto `RECEIPTS`) |

So every entry point files itself: the act's gallery copy, the receipt import, a hand-uploaded
photo. «фотки в ніякий рут не летять» holds by construction — a photo with a null folder IS in
«Інше», which is a real folder in the UI, not a leftover bucket.

Inside «Чеки» the upload buttons post `source: 'RECEIPT'`; everywhere else `MANUAL`. The per-source
caps still apply, so the upgrade banner is now the *current folder's* cap, not a global one.

## 10.5 Judgement call — «Чеки» shows estimate-linked receipts too

The old tab hid a receipt that belonged to an estimate (it lives under that estimate's Materials
section). Under folders that is exactly the bug the master described — a folder that quietly holds
back part of its contents. «Чеки» now lists **every** `RECEIPT` photo of the object; the tile still
carries its «Чек: <estimate name>» label, so provenance is visible and nothing is duplicated in the
data. This reverses a documented behaviour on purpose; it is the master's to veto.

## Round 4 tests

Backend `./gradlew build` green: `ProjectPhotoServiceTest` +3 (source-routed default, upload into a
custom folder mints it, «Чеки» typed by hand folds onto `RECEIPTS`), `LimitServiceTest` FREE receipt
cap 0 → 5. PWA full CI mirror green (102 files / 670 tests): `PhotosSection.test.tsx` rewritten to 13
tests over the two levels — including the server-shaped fixture with the null keys **omitted**, which
is the one that would have caught 10.1.

## Round 4 not verified

The layout is mobile-first by construction (56 px folder rows, 44 px upload buttons, 36 px back
target, truncating names) but was **not** opened in a browser at 375 × 812 — no visual check was run.

---

# Round 5 — the receipt gate splits per mode (2026-08-23)

> **Superseded (2026-08-28).** Carrying a receipt's positions into an ACT was removed —
> there is one read left on that path (label / date / total), it is free, and no endpoint takes
> `withItems` any more. See `docs/iteration-receipts-batch.md` §15. The ESTIMATE-side receipt
> import is unaffected: there the positions are the point. The split described below is history: with the
> paid mode gone, this path has no gate left.

> «тільки шапка (назва / дата / сума з підвалу, haiku) - оцей режим у нас нехай тоже буде фрі,
> а оце withItems=true — з таблицею позицій (sonnet, тим самим промтом, що й імпорт кошторису)
> вже платне»

## 5.1 What changed

`WorkActReceiptService.recognize` no longer gates the whole endpoint. The order is now:

1. not-signed check (both modes — a frozen act never spends a model call),
2. `if (withItems)` → `featureGuard.requireFeature(owner, Feature.RECEIPT_IMPORT)`,
3. the extractor call.

So a FREE master photographs the paper and gets label + date + total prefilled; ticking «перенести
позиції» is what asks for PRO. The reasoning is the product one: reading a footer is what turns a
photographed slip into a receipt row at all — charging for it makes the mandatory-photo flow feel
like a paywall on data entry. Reading the *item table* is the expensive, genuinely PRO-shaped job
(sonnet, the estimate-import prompt, tens of seconds).

**This is the one guard in the codebase that must NOT be hoisted to the top of its method.** Every
other PRO gate here is a first-line `requireFeature`, which is exactly why a later tidy-up would
undo this silently. `WorkActReceiptRecognitionTest` pins all three facts: the meta pass touches
`featureGuard` **not at all** (`verifyNoInteractions`), the item pass throws before either extractor
runs, and a SIGNED act spends nothing in either mode.

## 5.2 `ReceiptScanRateLimiter` — because FREE can now reach a model

The meta pass is the **first LLM call an unpaid account can make**, and recognition **persists
nothing** — no receipt row, no photo, no counter. Every other AI flow is bounded by something
business-shaped (an estimate limit, a receipt-photo cap); this one had nothing behind it but the
account existing.

New `ReceiptScanRateLimiter` — the same shape as `EstimateEmailRateLimiter`: Bucket4j,
`ConcurrentMap<UUID, Bucket>` keyed on the account, `refillIntervally`, consumed in
`WorkActController` before the service call, surfaced as 429
`TooManyRequestsException("error.rate.receipt-scan", retryAfterSeconds)`. Config
`app.rate-limit.receipt-scan.max-per-hour` = `${RECEIPT_SCAN_MAX_PER_HOUR:30}`; deliberately
generous — a master photographing a whole day's receipts is nowhere near 30. In-memory, so
single-node only, same documented limitation as every other limiter here.

`RateLimitProperties` gained a 9th component (`ReceiptScan`) — which broke three positional
`new RateLimitProperties(...)` calls in the limiter tests. Third time that record has bitten; the
fan-out check stays worth running.

## 5.3 PWA

`ActReceiptsSection` now reads the plan (`useMe`) and passes `itemsAllowed` / `onItemsBlocked` into
`ReceiptForm`. For FREE the «Розпізнати і перенести позиції з чека в акт» checkbox carries a PRO
chip and, on tap, fires `upgradeApi.click('RECEIPT_IMPORT')` + opens `UpgradeIntentModal` instead of
toggling — the same painted-door pattern as the estimate editor's «Додати з чеку». The box never
appears ticked when blocked, so no request is ever sent with `withItems=true` from a FREE device.

Everything else on the FREE path is unchanged: picking a photo still runs the footer read
unprompted.

## Round 5 tests

Backend: new `WorkActReceiptRecognitionTest` (3 tests) + the three rate-limiter tests fixed for the
record fan-out. PWA: `ActReceiptsSection.test.tsx` seeds `me` through `ME_QUERY_KEY` and gains a
FREE test asserting the footer read still runs (`recognizeReceipt(…, false)`) while ticking the
items box opens the upsell, leaves the box unticked, and never calls `onTransferItems`.

## Round 5 not verified

The PRO chip + upsell were **not opened in a browser** at 375 × 812. The chip is an inline
`text-[10px]` span inside a `flex-wrap` label, so it wraps rather than pushing the row wide, but no
visual check was run.
