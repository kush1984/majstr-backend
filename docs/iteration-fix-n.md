# Fix N — everything left in the review, and what the acts and the economy owed it

- **Status:** ✅ Code + tests complete. `./gradlew build` **green — 1519 tests in 175 classes, 0 failures, 0 errors, 0 skipped** (+7 over the V137 round). Full PWA gate green in CI order: `npm run lint` ✓, `npx tsc -b` ✓, `npm run typecheck:tests` ✓, `npx vitest run` — **1175 tests in 133 files** ✓, `npx vite build` ✓. **`typecheck:tests` earned its place again** — it, and only it, caught the two call sites of the new `sent` prop; lint, `tsc -b` and vitest were all green while the test file did not compile.
- **Migrations:** **V138** `facade_paint_enamel_and_varnish_norms.sql` (data only — three dictionary
  materials and four norms).
- **Source:** `C:\Work\prompts\FIXES.md`, re-checked item by item against the working tree. Its new
  §0 carries the verdict for every one of the 48 items; this document covers only what this round
  actually changed. The master's instruction was «обовʼязково доробити все що стосується актів і
  економіки» — that is B-25, B-26, B-28, B-29 and B-31d, and they are §1 below.

## 1. The acts and the economy

### 1.1 B-25 — the photo was destroyed before the row that claimed it

Every delete here ran `storage.delete(key)` **inside** the transaction that removed the row:

```java
tryDelete(receipt.getStorageKey());   // irreversible
receiptRepository.delete(receipt);    // can still roll back
```

The order is backwards, and the failure it produces is the asymmetric one. If anything after the
delete fails — a constraint, an optimistic lock, a connection dropping on commit — **the row
survives and the file does not**: the receipt still claims a photo, the photo endpoint 404s, and the
master is looking at a document whose proof we destroyed on his behalf. The reverse leak costs
storage and nothing else.

`StorageCleanup` is the fix and the whole of it: `afterCommit(key)` registers a
`TransactionSynchronization` when a transaction is in progress and deletes at once when there is
none (the non-transactional create paths, which clean up a blob whose row never landed — same
guarantee: nothing references it). It is deliberately fail-soft inside the synchronization, because
by then the row is already gone and throwing would turn a successful delete into a 500 the master
cannot act on. Four call sites: the act receipt, the object receipt, the photo, and the project
cascade below.

### 1.2 B-26 — a deleted object kept its receipts

`ProjectService.delete` collected `project_photo` keys only. **Three** tables hold files: the
gallery, the object's own till receipts (V129) and the receipts frozen into that object's acts
(V110). The last two are the ones that matter — a photographed receipt is financial personal data,
and it outlived the object entirely.

Both repositories got a `findStorageKeysByProjectId`, and the keys are read **before** the cascade
for the reason the original comment already gave: the cascade takes the only pointer to them with
it. The act one reaches through `r.workAct.project.id`, so it needs no second query for the acts.

### 1.3 B-28 — a SENT act could be made unsignable, and only the client found out

While an act is SENT the client can sign it at any second, and `ActReceiptCompleteness` refuses a
signature over an unpriced receipt (400 `WORK_ACT_RECEIPT_UNPRICED`). Nothing stopped the master
zeroing a receipt on an act already sent — so the client tapped «Підтвердити приймання» and got an
error that was the master's to fix and that nothing on his screen explained.

**Narrower than the review proposed, and on purpose.** Only `update` refuses a zero on a SENT act.
`add` still accepts one, because a zero there is the receipts-batch state («save the photo first,
price it later») and it is also what an **offline queue replays** hours after the act went out;
refusing it there would trade an awkward window for a lost photo. An explicit edit to zero has
neither excuse, and the master always has V108's door: move the act back to DRAFT, fix it, re-send.

No `unpricedReceipts` field was added. `WorkActResponse.receipts` is fully populated on both the
list and the get, so the PWA already counts them and already badges them — what it gained is a
**different sentence on a SENT act**: «Акт уже в клієнта, і поки сума не вказана, він не зможе його
підписати». The generic wording said «не можна надіслати чи підписати», which is not what is
happening once it has been sent.

### 1.4 B-29 — the one photo write that asked no permission

`saveReceiptCopy` (the act receipt's second copy into «Чеки») checked the photo LIMIT but not the
`PHOTO_REPORTS` FEATURE. Harmless today — FREE has the feature — and wrong the moment that changes,
because it is the only door into the gallery that never asks.

### 1.5 B-31d — one receipt, two sums, on two pages of one document

The act PDF's photo caption printed gross `amount()` while the money table billed `billedAmount()`
(V115: paid less returned). A receipt with a partial return therefore read as two different figures
in one document — and the **bigger** one sat under the photo that proves it. The caption now prints
what is billed, and both figures when there is a return, because the paper itself says the gross
one: «1 500 (на чеку 2 000, повернуто 500)».

## 2. The rest of the review

- **B-18** — `@Digits(integer = 12, fraction = 3)` on the three quantity requests (the columns are
  `numeric(15,3)`, so an unbounded figure died as a 500 on the database), and `parsePerPosition`
  now clamps a per-position figure to (0, 1000]. Out of range is **ignored, not rejected** — the
  position then asks again, which is a screen the master can act on, and it is the rule that
  parameter already followed for a malformed entry.
- **B-20** — `NameKeys.of` and both `CatalogTemplateService` key builders lower-case on
  `Locale.ROOT`. The same key is computed in SQL by `lower()`, and a JVM booted in a Turkish locale
  would disagree with it on «I» — which shows up as a position that quietly matches nothing.
- **B-22** — one `BucketRegistry<K>` behind all eleven rate limiters, replacing eleven bare
  `ConcurrentHashMap`s that only ever grew. Six of them are keyed by something a stranger picks
  (`email|ip`, an IP, a portal token), so the map was unbounded by **request content**. An entry
  idle for twice its own refill period is dropped, and that is unobservable: by then the bucket has
  refilled to capacity, which is exactly what a first-time key gets. The period is passed in rather
  than assumed, because the login and portal windows are configuration. The sweep is opportunistic —
  no scheduler, no new dependency, nothing to shut down.
- **B-23** — with the ДПС lookup switched off, a QR carries a total and a date and no positions, and
  the estimate import answered «позицій у чеку немає»: our configuration, reported as a fault of the
  master's receipt. Now `error.fiscal-qr.lookup-disabled`, because his next move differs — photograph
  it, do not re-scan.
- **B-30** — the migration half shipped with V133. The stale claim it also named is corrected here:
  `MaterialNorm`'s javadoc said a shipped norm «is recreated by every catalog rebuild», which no
  migration does. The natural key is still the right thing to match a fork on, for a different and
  now written-down reason.
- **B-31e / B-31f** — the shopping-list PDF dates in `LocalizationConfig.ZONE` like every other PDF;
  the quality-note truncation cuts on a whitespace boundary and never through a surrogate pair.
- **Dead code** — four of the six went. `MaterialNormRepository.findByTradeAndKey`/`findByKey`
  **stay**: the review called them tests-only, but their caller pins that (name, unit) IS the key
  and that the trade is a filter over it. Deleting a query to satisfy a tidiness rule would delete
  that guard with it.

## 3. V138 — three coatings the survey turned out to have

V137 listed what it deliberately left unnormed. Most of that list is still right, but three
coatings turned out to be ordinary once they were looked up, and each is now sourced from two
unrelated manufacturers agreeing inside a narrow band — the standard the drywall round used:

- **facade paint 0,35 л/м²** over two coats (Ceresit CT 42 / CT 48: 5–8 м²/л absorbing, two layers;
  Caparol Muresko / AmphiSilan-plus: 150–200 мл/м² per coat), plus the deep primer at the 0,15 this
  catalog already uses;
- **enamel on wood 0,22 л/м²** (Śnieżka Supermal «до 12 м²/л»; Eskaro Condor Aqua Email 30 6–10;
  Maxima 80–125 мл/м² per coat → ~9 м²/л, two coats);
- **clear varnish 0,20 л/м²** on concrete and microcement (Aura Aqua Lack 70 8–10 м²/л per coat;
  Ukrainian PU/acrylic concrete varnishes 70–120 г/м² per coat).

Filed under **PAINTER, never trade-less.** BUILDER also ships «Фарбування фасаду» and FLOORING three
lacquering positions, but those trades have no norms at all — a trade-less row would flip
`/materials/availability` ON for them and offer a buying list of one line out of forty, which is the
rule that endpoint exists to enforce.

**Three things stayed refused after being looked up**, and the numbers are in the migration header
so the next person does not repeat the search: **epoxy grout** (Ceresit CE 79's own table spans
0,08–12,40 кг/м²; Litokol Starlike ~1,6 where our CEMENT figure for the same geometry is 0,6 — most
of the gap is washing loss, which no sheet states); **decorative plasters** beyond короїд/баранець
(microcement 0,4–1,4 per base coat and 1,2–3,5 over the finish «depending on the effect»; venetian
0,5–1,0 — a threefold spread inside one product name); and **painting a moulding, a baguette or a
door**, where the RATE now exists and the AREA does not — no sheet says how many m² a metre of
«багет до 6 см» presents. That last one is a question for the master: he bills them by the metre.

`PAINT_FACADE` is rescaled by the master's paint habit like the other two paints, and correctly so —
the habit is applied as a **ratio**, so it crosses a norm written against a different base (6,5 м²/л
on rendered wall, not 9). `MaterialCalculatorIntegrationTest`'s «the shipped figure and the Java
constants are one statement made twice» guard now names the two INTERIOR codes explicitly instead of
matching the `PAINT_` prefix, which would have caught the facade figure and failed on it.

## 4. Tests

- `WorkActReceiptGuardsTest` (4) — a SENT act refuses a zero, a DRAFT does not, a SENT act still
  takes a real figure, and a deleted receipt's photo goes through the cleanup rather than inline.
- `BucketRegistryTest` (3) — a key keeps its bucket while in use; an idle key is dropped and comes
  back FULL (the licence to evict at all); a flood of one-off keys does not grow forever.
- `ProjectServiceTest.delete_alsoRemovesEveryStoredFileTheObjectHeld` — rewritten for all three key
  sources and for the after-commit hand-off.
- `ReceiptImportServiceTest.parseQr_saysTheLookupIsOffRatherThanBlamingTheReceipt`, and the existing
  no-items test now asserts the message key rather than only the exception type.
- `MaterialCalculatorIntegrationTest` — the paint-constant guard narrowed to the interior pair.

## 5. The two the master ruled on (2026-09-24)

Both were carried as decisions, not bugs, and both were answered in one message.

- **B-31b — deleting a BOUGHT shopping row: HIDE it.** `ShoppingListService.delete` now branches on
  `settled()` — an OPEN row is really deleted, a settled one is stamped `cleared_at` and vanishes
  from the screen exactly like a deleted row, while its quantity stays inside `covered` so the next
  recalculation cannot re-add the material unbought. Deliberately **not** a 409: this swipe replays
  from the outbox hours later, where a refusal is unactionable — the same argument that narrowed
  B-28. «I did not buy it after all» is still un-tick (which clears both flags) and then delete.
  Three tests in `ShoppingListIntegrationTest` pin the three paths.
- **The baguette/moulding/door AREAS — derived, suggested, editable (V139).** The missing half was
  never a rate: `NormBasis.SECTION` already asks a moulding's question, so no Java changed. The
  suggested розгортка comes off two manufacturers' full profile tables (≈1,15 × √(h²+w²), which
  reproduces both inside ~10 % and always lands slightly high), and the ДБН norm books measure a
  cornice the same way — it is the trade's method, not our geometry in a manufacturer's clothes. A
  door is asked nothing: the m² is folded into a QUANTITY coefficient, since a leaf does not vary
  the way a profile does. Every figure is a `default_param` — pre-filled and labelled, never applied
  silently — and the ordinary V126 fork corrects it permanently. Full derivation in V139's header.

## 6. Still open

- The three refused norm families above (epoxy grout, the decorative plasters, and the hidden
  skirting V139 left out — anodised aluminium wants an adhesion primer this dictionary lacks).
