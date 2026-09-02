# Iteration: offline act receipts — photo + amount + date, and nothing else

**Status:** DONE (2026-09-02), PWA v1.36.0. Awaiting the master's own testing — not pushed.
**Migrations:** none. **Backend changes:** none expected — `POST /api/acts/{id}/receipts` already
takes `X-Entity-Uuid` and is idempotent on replay (pulled forward in receipts-batch, 2026-08-24,
for exactly this reason), already accepts `amount = 0`, `issuedAt` and `saveToPhotos` on the
multipart add. The one backend edit this round makes is unrelated to acts: a blocked outbox op now
stores the SERVER's message instead of «Request failed with status code 409» (PWA-side).
**Source:** [open-questions.md](open-questions.md) → *Offline act receipts*, promoted to
IN_PROGRESS 2026-09-02. It is the first real slice of offline follow-up **O6 (blob outbox)**.

## 0. What the master decided (2026-08-23), unchanged

> Offline he **photographs the receipt and types the amount and the date by hand — that is all.**
> No recognition offline and **no deferred recognition**: nothing is re-read or re-proposed after
> the fact. The numbers are his responsibility. **The photo stays mandatory** — «фото має бути
> обовязково».

That last rule is what makes the slice small. An invariant a client can opt out of by asserting
«I was offline» is not an invariant, so the offline form is the online form minus the reading.

## 1. The defect

Every other daily flow authors offline. An act receipt cannot be authored offline **in any form** —
not even the number — because the photo is mandatory (400 `WORK_ACT_RECEIPT_PHOTO_REQUIRED`) and
there is no blob outbox. A master standing in a flat with no signal, holding a paper receipt, has
nowhere to put it. The receipts-batch round rebuilt this whole flow around «save first, price
later» precisely because a weak connection was losing receipts — and stopped at the point where
there is no connection at all.

## 2. What ships

- **A blob rides the outbox.** The queued op carries the photo as **bytes, not a `File`** —
  `{bytes: ArrayBuffer, fileName, mimeType}`. `Blob` is structured-cloneable and IndexedDB is
  specified to store it, but Safari has a long history of Blob-in-IDB bugs, `fake-indexeddb`
  cannot be trusted to model it, and an `ArrayBuffer` makes the byte size **knowable up front**,
  which is what a quota story needs. The photo is downscaled through the existing `downscaleImage`
  BEFORE it is queued, so what lands in IndexedDB is the few hundred KB that would have been
  uploaded, not a 6 MB phone photo.
- **A new outbox entity `actReceipt`,** create only. Its handler rebuilds the `File` and replays
  the same multipart POST with the same client UUID, so a replay of a receipt that already landed
  returns the existing row instead of duplicating money in the act AND its ADDENDUM.
- **The optimistic row is unnamed on purpose.** «Чек №N» is the SERVER's name — it is the only side
  that knows how many receipts the act holds — so the offline row carries a blank label and the UI
  falls back to its positional ordinal until replay names it. Guessing N from the cached act would
  print a number that is wrong the moment another device adds one.
- **A queued receipt is editable and deletable, and neither is a new op.** A typo or a bad photo
  made offline must be fixable, and the row does not exist server-side yet, so an update would have
  nothing to update: editing **patches the pending create's payload in place**, deleting **removes
  the op**. Both are local Dexie edits with no replay semantics of their own. Editing or deleting an
  already-synced receipt stays online-only, as today.
- **Recognition is visibly off, not silently missing** — «✨ Розпізнати» and the batch choice's
  «прочитати суми» are disabled offline with «доступно онлайн». The QR rung is local and free but
  goes too: it posts the payload to the server to be parsed.
- **The 409 is not swallowed.** All three receipt writes sit behind `requireNotSigned`, so an act
  signed while the queue waited answers 409 `WORK_ACT_SIGNED` on drain. The classifier already
  files that as `blocked` → `SyncReviewSheet`; what was missing is that the sheet showed only
  «сервер не прийняв». A blocked op now keeps the server's own localized message and the sheet
  prints it per row, so the master reads «Акт підписано — редагувати не можна» about his own money.

## 3. Deliberately NOT in this slice

- **No deferred recognition** (the master's call, §0) — and note it would also mean a burst of
  vision calls on reconnect.
- **No offline update/delete of a SYNCED receipt** — it is a different op with a real conflict story.
- **Not the general photo programme.** Progress photos and the estimate-side receipt import stay
  online-only; O6 stays OPEN with this slice named as shipped.

## 4. What actually shipped, file by file

All of it is PWA-side; the backend was not touched.

- **`src/lib/outbox/queuedFile.ts`** (new) — `QueuedFile {bytes: ArrayBuffer, fileName, mimeType}`
  plus `toQueuedFile` / `fromQueuedFile` / `queuedFileUrl`. Callers downscale BEFORE queueing.
- **`src/lib/outbox/outbox.ts`** — `patchPendingCreate` / `dropPendingCreate` (both answer
  `false` once the op has drained, so the caller can fall back to the server row), and `errMessage`
  now goes through `toAppError`, so a blocked op keeps the server's own localized sentence.
- **`src/lib/outbox/init.ts`** — the `actReceipt` handler: create only, anything else `throw`s
  (a handler that falls through resolves, and a resolved op is deleted as synced).
- **`src/features/acts/offlineReceipts.ts`** (new) — the whole domain: `addActReceipt` (online
  first, `isNetworkError` → queue; the payload is built only on the branch that queues, because a
  re-encode plus a full byte copy is real work to do ten times per batch for nothing),
  `queuedReceiptRow`, `patchQueuedReceipt`, `dropQueuedReceipt`, `usePendingActReceipts`,
  `mergeQueuedReceipts`.
- **`ActEditorPage.tsx`** — merges queued rows into the server list at ONE point and hands both the
  merged list and the queued map down. Deliberately not written into the query cache: a reconnect
  refetch can land before the flush drains, and a receipt blinking out of existence for a few
  seconds is the exact fear this feature removes. Merging at the source is also what keeps the
  panel's «Разом за чеками» and the header's «До сплати» from disagreeing about what exists.
- **`ActReceiptsSection.tsx`** — a queued row renders its photo from the queued bytes, is named
  «Чек з телефону» in muted text, carries «Ще не надіслано — чекає на звʼязок», and its edit and
  delete route into the queue. Two capabilities are hidden rather than failed for it: the read
  (it works on the STORED photo, and every rung of the ladder needs the network) and the return
  field (`returnedAmount` is not on the create endpoint at all). Offline the batch sheet's
  «прочитати суми» tick is **disabled, not hidden** — the master ticked it last time and needs to
  see why it is not happening now.
- **`useReceiptBatch.ts`** — the read phase is skipped wholesale offline, and the outcome carries
  `offline` so the toast says «збережено на телефоні» instead of naming a read failure that never
  happened.
- **`src/components/SyncReviewSheet.tsx`** — a blocked row now prints the server's own sentence
  (`op.lastError`) instead of leaving «сервер не прийняв» to cover every refusal. A **stuck** op
  still says «спроби вичерпано» and its `lastError` is deliberately NOT shown: nobody refused it,
  and «Network Error» is a transport message the master can do nothing with. `opName` also reads
  `label`, so a named receipt says which one it was.
- **i18n** — five new `acts.*` keys in uk + en, plus four `sync.entity.*` labels
  (`actReceipt`, and the three that were already missing: `estimateItemsBulkDelete`,
  `estimateItemOrder`, `templateItemOrder`). Those are composed at runtime
  (`t(\`sync.entity.${op.entity}\`)`), so `i18nKeys.test.ts` cannot see them — they were found
  by hand and are worth re-sweeping whenever an entity is added.

## 5. Tests

- **`src/features/acts/offlineReceipts.test.ts`** (new, 12) — online uploads queue nothing; offline
  queues with the photo's bytes and hands back a showable row; a wire death while `navigator.onLine`
  queues too (the building-site case); a 409 `WORK_ACT_SIGNED` propagates and queues NOTHING
  (queueing it would retry forever while telling the master his receipt is on its way); the replay
  sends `req.id === entityId` with the patched fields and a real `File`; `patchQueuedReceipt`
  writes into the create without a second op and leaves the photo alone; `dropQueuedReceipt` removes
  it; both answer `false` once the queue drained; `mergeQueuedReceipts` ordering, dedup-on-landing
  and identity return; `queuedReceiptRow` is unnamed with `returnedAmount: 0`.
- **`SyncReviewSheet.test.tsx`** (new, 2) — the server's refusal is printed per row; a stuck op
  shows «спроби вичерпано» and hides its transport error.
- **`ActReceiptsSection.test.tsx`** (+4) — a queued row is named, badged and offered no read; its
  edit calls `patchQueuedReceipt` and never `actsApi.updateReceipt` (and shows no return field);
  its delete calls `dropQueuedReceipt` and never `actsApi.removeReceipt`; offline the batch tick
  is disabled and the sheet says why.

**Gotcha worth keeping.** `initOutbox` both registers the handlers and subscribes the queue to
reconnects, and it is the only door to the first. Calling it per test left a live subscription, so
`onlineManager.setOnline(true)` scheduled an auto-flush that replayed queued ops through a bare
`vi.fn()` — which resolves, so the op was deleted as synced and two assertions measured the test's
own scheduler. The fix is one line and is commented in place:
`beforeAll(() => initOutbox(new QueryClient())())` — register once, drop the subscription
immediately, flush explicitly.

**Gate:** green in CI order — `npm run lint` · `npx tsc -b` · `npm run typecheck:tests` ·
`npx vitest run` (**836** tests, 115 files) · `npx vite build`.

## 6. Not verified

The mobile layout was not opened in a browser this round: every change is inside components whose
layout is unchanged (a badge line, a muted name, two hidden blocks, one disabled checkbox), but that
is an argument, not a measurement. Worth a look at ≈375 px on the master's next pass.
