# Iteration: reading the fiscal QR on a receipt

**Status:** code complete, backend build green, PWA gate green, NOT pushed (awaiting the user's approval).
**Source:** the master — «давай попробуємо зробити з тим куар кодом на чеках? але то має бути окремою
кнопкою чи опцією — прочитати куар, інші можливості також лишаємо, і це нам треба би було і для
кошторисів і для актів — робимо так само».
**Migrations:** none.
**PWA:** 1.23.5 → **1.24.0** (new headline capability).
**Open question promoted:** «ДПС QR receipt lookup» → `IN_PROGRESS`.

Goal: a **separate** «🔳 Зчитати QR» option beside the existing photo routes, on **both** receipt
dialogs — the estimate's receipt import and an act's «Чеки та рахунки» — reading the fiscal code
printed on every Ukrainian receipt instead of photographing it. Exact data instead of recognized
data, and no model call at all.

Two rules the master set, and everything below follows from them:

1. **The other ways stay.** A hand-written slip, a faded ККМ tape, a builders'-merchant invoice — none
   of those carry a fiscal QR, and reading the paper is the only path for them. The QR is a fast
   path, never the only one.
2. **For acts it works «так як було»** — «позиції додаємо тільки у випадку якщо галочка вибрана». The
   QR fills the data; the «перенести позиції» checkbox still decides what lands in the act.
   *(Superseded 2026-08-28: an act carries no receipt positions at all any more, so the QR read there
   fills the three footer fields and nothing else — `docs/iteration-receipts-batch.md` §15.)*

And one decision taken during the work: **everything the QR hands back is FREE**, positions included
(«Безкоштовно все, що дав QR»). No model runs on this path, so there is nothing for a plan to gate.

---

## 1. The fast path, and how it degrades

`service/fiscal/` is the whole of it. `FiscalQrService.read(payload)` is a ladder, and every rung
below the top **degrades rather than fails** — because a receipt the master is standing in front of
must always end up as a row, one way or another:

| what went wrong | what the master gets |
| --- | --- |
| the payload is not a fiscal QR | not recognized → the photo route, one tap away |
| the tax service is unreachable / refuses | **still recognized**: the QR's own total and date, no positions |
| the returned positions don't sum to the QR's total | positions dropped wholesale, total and date kept |

- **`FiscalQrPayload`** — parses what the printer encoded: a full URL, a bare query string, or one
  with a leading `?`; params lower-cased; `fn` + `id` + `sm` + `date` required. Date and time each
  come in several shapes on real paper (`yyyyMMdd`, `dd.MM.yyyy`, `HHmmss`, `HH:mm`), plus a glued
  `yyyyMMddHHmmss` and a space-separated combined value.
- **`FiscalQrService.lookup`** — `GET {base}?id=&date=yyyy-MM-dd HH:mm:ss&type=3&captcha=&fn=&sm=`,
  10 s read timeout. Any exception, and any body carrying `error`, returns `null` — logged at info,
  never thrown. This endpoint is undocumented; treating it as unreliable is the design, not a
  concession.
- **`FiscalCheckXml`** — a tolerant decoder for `checkXml` (windows-1251, integer-scaled money). It
  accepts both row layouts seen in the wild (`ROW` and `P`) and several spellings per field
  (`NAME`/`NM`, `PRICE`/`PRC`, `AMOUNT`/`Q`/`QTY`, …), reads a field as an attribute *or* a direct
  child, and treats a value that already carries a decimal separator as literal rather than scaling
  it. XXE-hardened (`disallow-doctype-decl`, empty external DTD/schema access, no XInclude); the
  bytes go in as-is so the document's own encoding declaration decides.

### The sum cross-check is the safety net

The XML is undocumented, so the decoder is *guessing* the integer scaling (money ×100, quantity
×1000). The QR's own `sm` is the independent witness: `trustedItems` keeps the lines only if they
sum to it within **0.02**, otherwise it returns nothing and the meta survives alone. A wrong price is
worse than a missing one — the master would have signed it.

`trustedItems` is package-private on purpose: it is the whole reason the undocumented XML is safe to
show a master, and reaching it through `read()` would need a live lookup to test.

**Config:** `app.fiscal-qr.base-url` (`FISCAL_QR_BASE_URL`), defaulted to the cabinet endpoint;
blank = the lookup is disabled and the QR still fills total + date. `application-test.yml` blanks it
so **no test can reach the tax service**.

## 2. The two entry points

> **Superseded (2026-08-28).** Carrying a receipt's positions into an ACT was removed —
> there is one read left on that path (label / date / total), it is free, and no endpoint takes
> `withItems` any more. See `docs/iteration-receipts-batch.md` §15. The ESTIMATE-side receipt
> import is unaffected: there the positions are the point.

Lines are carried in `EstimateExtractor.Extracted.Line` and normalized through the shared
`ReceiptLines.toParsedItems`, so a QR-read receipt is flagged exactly like a photo-read one — an
unknown unit still becomes `issues: ["unit"]` and the master is still asked. Nothing is invented.

- **Acts** — `POST /api/acts/{id}/receipts/qr` → `WorkActReceiptService.readQr`, answering the same
  `ActReceiptRecognizeResponse` as `recognize`. It took a `withItems` flag («the master's tick, not
  a plan check») until the transfer was removed; it now always reads the code alone. Signed act → 409. Soft on failure (`recognized: false`): the total and the date alone
  are worth having, and the dialog can fall back to the photo. Rate-limited by the existing
  `ReceiptScanRateLimiter`, like the recognition endpoint — the QR path persists nothing either, so
  no business counter bounds it.
- **Estimates** — `POST /api/estimates/{id}/receipt-items/qr` → `ReceiptImportService.parseQr`,
  answering the same `EstimateImportParseResponse` as `parse`. **Hard** here, not soft: this flow
  exists only to produce lines, so an empty review would read as «чек порожній» — 400
  `error.fiscal-qr.unreadable` / `error.fiscal-qr.no-items` instead.

## 3. What the free decision cost, and the trap it set

`ReceiptImportService.commit` **lost its `RECEIPT_IMPORT` gate**. It had to: a gated commit would
show a FREE master his own receipt's positions and then refuse to add them. Appending lines was never
the paid capability — the same lines can be typed one by one in the editor — reading a receipt
*photo* is.

`commit_isNotAPaidCapability` pins that, the same way `WorkActReceiptRecognitionTest` pins the
per-mode gate: tidying a `requireFeature` back to the top of a method is exactly how this would be
silently undone.

The same reasoning moved the PWA gate. The «перенести позиції» tick used to open the upsell on
FREE — with QR positions free, that would have made them unaskable. So:

- the tick is free to toggle for everyone;
- `runRecognition` gates the **photo** item-read (`upgradeApi.click('RECEIPT_IMPORT')` + the modal,
  and the footer pass still runs so the row is still usable)
  *(both superseded 2026-08-28 — the tick and the item-read it gated are gone from the act flow, and
  with them the last gate on it; the estimate sheet’s own PRO chip below is unaffected)*;
- the estimate sheet's 📷/🖼 buttons carry the PRO chip and open the upsell, while «🔳 Зчитати QR»
  above them does not;
- the estimate editor's 🧾 FAB action **no longer gates at all** — the sheet it opens is now partly
  free, so sending FREE to the upsell from the FAB would have hidden a free capability behind a
  paywall. (The page lost its `isPro`/`UpgradeIntentModal` with it; `me` stayed, for the custom
  trades on «зберегти як шаблон».)

## 4. PWA — the scanning itself

- **`lib/qr.ts`** — pure `input → string | null`. Native `BarcodeDetector` preferred (Chrome and
  Android WebView decode on the GPU and catch a code at an angle that the JS scanner misses),
  **`jsqr` as the fallback** — iOS Safari, where a good half of these phones live, has no native
  detector. A frame with no code is the normal case, so a miss is a `null` and a decoder that throws
  is treated as a miss too: one bad frame must not end a scan. `resetQrDecoder()` is the test seam.
- **`components/QrScanSheet.tsx`** — the camera lifetime, and nothing else. `getUserMedia` with
  `facingMode: { ideal: 'environment' }` (`ideal`, not `exact`, so a laptop still gets *a* camera),
  a 220 ms decode loop, an `aspect-square` viewfinder with an aiming frame so the buttons stay in the
  thumb zone at 375 px. A **picked-photo fallback is offered in both states**, not just on failure:
  iOS Safari denies `getUserMedia` over plain http, a master may have refused the permission once and
  never be asked again, and the receipt is often already in the gallery. A code that is not fiscal is
  named on the spot (`looksFiscal`) and scanning continues — a round trip to be told «це не чек»
  would be slower and less clear.
- **The photo stays mandatory on an act receipt.** The QR fills fields, not paper; the proof is the
  photograph. A test pins that «Додати чек» is still disabled after a successful scan.
- **The QR route on the estimate sheet skips the «зберегти фото чека?» offer** — it never held a
  file, so that dialog would be dead.
- **`api/client.ts`** — `/qr` joined `/parse` and `/recognize` in the long-timeout rule. It runs no
  model, but it waits on the tax service (10 s server-side), which is already past the 12 s write
  default once the round trip is counted. This rule has now bitten twice: **a new long verb must be
  added to it or it inherits a silent mid-call abort.**

## 5. Tests

Backend: `FiscalQrPayloadTest` (6), `FiscalCheckXmlTest` (8 — windows-1251 fixtures for both row
layouts, a doctype refusal, and **two golden fixtures taken from real receipts the master
photographed**, `src/test/resources/fiscal/real-{prro,rro}-receipt.xml`), `FiscalQrServiceTest` (6 —
five constructed with a blank base URL so nothing dials out, plus `theLookupUriIsNotEncodedTwice`
against a loopback `HttpServer`), plus the new cases in `ReceiptImportServiceTest` and
`WorkActReceiptRecognitionTest`. `./gradlew build` green.

PWA: `lib/qr.test.ts` (decoder preference, both fallbacks, `looksFiscal`), `QrScanSheet.test.tsx`
(the no-camera path, which is the one that matters), and new cases on both receipt dialogs — the QR
carrying positions on FREE, the tick still deciding for acts, the photo still mandatory, the upsell
now on the photo routes. Full gate green in CI order.

## 6. First live paper — two bugs the tests could not have caught

The master scanned two real receipts (Pull&Bear 21.08.2026 3008,00 ПРРО; RESERVED 21.08.2026
1011,00 hardware РРО). Both filled the total and the date and produced **zero positions** — which
reads exactly like "this receipt has no positions", so nothing looked broken. Two independent
faults, both in the rungs below the top one, and both invisible to a suite that never dials out:

**(a) The lookup URI was encoded twice, so the lookup never once succeeded.** `lookup` built the
URI with `UriComponentsBuilder…encode().toUriString()` and handed the **String** to
`RestClient.uri(…)` — which reads a String as a URI *template* and encodes it again. The space in
`date=2026-08-21 15:25:00` reached the tax service as `%2520`, it answered `Помилка обробки
запиту`, and the ladder degraded to rung 2 on **every** scan. The same URI sent by `curl` returned
the receipt, which is what made this so hard to see from the outside. Fix: `.encode().build()
.toUri()` and pass the `URI`. Pinned by `theLookupUriIsNotEncodedTwice`, which runs a loopback
`HttpServer` and asserts the raw query decodes **once** to the value itself.

**(b) `FiscalCheckXml` read payment and tax rows as positions.** The `CHECK` layout reuses `<ROW>`
inside `CHECKPAY`/`PAYSYS`/`CHECKTAX`, and those rows carry a `NAME` ("VISA", "ПДВ") with no
quantity. The document-wide sweep therefore returned a set containing incomplete lines — and
`trustedItems` correctly refuses a set it cannot check, so it dropped **all** of them, real
positions included. The safety net was working; it was being fed rubbish. `rows()` is now scoped to
`CHECKBODY` when the layout has one, falling back to bare `<P>` (the ПРРО layout, which has no
container) and only then to a document-wide sweep.

With both fixed, the two receipts read end to end: 3 positions / 3008,00 and 2 positions / 1011,00,
sums matching the QR in both cases.

**Still open from live paper:** the ПРРО (`RQ`) layout carries no `ORGNM`, so «Що це за чек» stays
empty for those receipts — the seller name lives in the response's plain-text `check` field, not in
`checkXml`. Left alone rather than guessed at. The endpoint's three known risks (undocumented,
captcha-optional-for-now, exact-match-only) still stand, so the open-questions item stays
`IN_PROGRESS`.

## 7. The scan itself was reading the wrong code

With the backend right, the master reported the opposite failure: «кожен раз каже, що це не кюр
код». Nothing in the backend can produce that message — it is `looksFiscal` in `QrScanSheet`, and
the PWA had not changed. The photos said why.

**A receipt prints SEVERAL QR codes, and the fiscal one is the hard one.** Beside it the paper
carries the register vendor's code, a marketing link, a loyalty code — short payloads in sparse,
high-contrast codes that decode instantly, while the fiscal code is ~40 modules wide, printed a
couple of centimetres across on curling thermal paper. Measured on the two real photos:

- receipt 2 (720×1280): a plain `jsqr` pass over the whole photo finds **only**
  `https://shorturl.at/Qosce`. The fiscal code decodes only after an adaptive threshold.
- receipt 1 (3072×4096): `jsqr` never reads its fiscal code at all, under any crop, radius, bias or
  upscale tried — only the vendor code (`6700494601156513161866;804001;…`) comes out.

`decodeQr` took the **first** code the native detector returned and discarded the rest, so the
master was told his receipt was not a receipt about a code he never aimed at. Three changes:

1. **Prefer a fiscal payload among all codes seen** (`preferFiscal`), instead of `found[0]`. This is
   the fix that matters on Android, where the native `BarcodeDetector` does read the dense code.
2. **A preprocessing ladder on a still photo** (`sweep`): plain → adaptive threshold at
   `(r,1.05) (r,1) (2r,1.05) (2r,1)` → the same thresholds over overlapping halves and thirds,
   stopping at the first fiscal payload. The threshold is a summed-area table, so the window radius
   is free. Receipt 2 now reads its fiscal code in **2 `jsqr` calls / ~0.6 s**.
   A **6 s budget** bounds it: the full ladder on receipt 1 is 141 calls / **29 s** on a desktop,
   which on a phone is not a wait but a hang. The pass that pays is the first one.
3. **The camera asks for resolution.** `getUserMedia` requested none, so a phone may hand back
   640×480 — at which a 40-module code is under two pixels per module and cannot be read, while the
   sparse code beside it reads fine. Now `width/height: {ideal: 1920/1080}` + `focusMode:
   continuous`, all `ideal` so a laptop webcam still opens.

The sheet also **names the code it did read** («Прочитано: https://shorturl.at/…»). A bare «це не
чек» reads as the feature being broken; the payload tells the master he aimed at the wrong code.

Receipt 1's fiscal code still does not decode through `jsqr` from that photo — that is an iOS-path
limitation of the JS decoder, not something preprocessing fixed. On Android the native detector is
the one that reads it, which is what change 1 unblocks.

## 8. Not verified

The QR sheet was not opened in a real browser at 375×812; it is built mobile-first (full-width
buttons, square viewfinder, nothing below the fold) but that is a design claim, not a verified one.

Nor was the fixed scanner: the decoding was verified against both real photos through a node
harness running the shipped `sweep` verbatim (receipt 2 → its fiscal payload in 2 calls / 0.6 s;
receipt 1 → the vendor code, bounded at 6.1 s), and the camera-resolution and fiscal-preference
changes are reasoned from those measurements, not observed on the master's phone. The amber notice
grew a second line — `break-all` keeps a 60-char payload from scrolling a 375px sheet sideways,
which is a design claim too.
