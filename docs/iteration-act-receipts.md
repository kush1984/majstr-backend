# Iteration: Чеки та рахунки в акті + дві UX-правки на екрані акта

**Status:** code complete, both builds green, NOT pushed (awaiting the user's approval).
**Source:** a live master's request (relayed 2026-08-21) — «зробити фінальний акт з матеріалами»;
he could not find any way to bill materials through an act.
**Migration:** **V110** `work_act_receipts`.
**PWA:** 1.19.2 → **1.20.0** (minor — new headline capability).

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

**V110 `work_act_receipts`** — `id`, `work_act_id` (FK `ON DELETE CASCADE`), `label`, `amount`
(`numeric(12,2) > 0` CHECK), `issued_at` (nullable), `storage_key` (nullable — a receipt may be
typed with no photo), `sort_order`, `created_at`. Plus `work_act.receipts_to_expenses boolean NOT
NULL DEFAULT true`.

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
