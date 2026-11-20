# The client pays from the portal, and tells the master — V217

**Status:** shipped 2026-11-20 — backend `b66207b`, PWA `3f043d4`.
**Migration:** `V217__client_payment_details_and_notice.sql`. **PWA:** 1.85.0.

## Why

A master asked (2026-11-19): can the client pay a deposit, or the whole sum, straight from the portal —
«просто щоб була кнопка оплати». Then the owner: many clients are asked to send a receipt, because a
transfer can take a day to land and the master wants to know it was sent.

Owner rulings: bank-neutral (no monobank-only anything — Приват, Ощад, ПУМБ…); the money never passes
through Majstr (no licence, no fees); not every master is a ФОП, so a card is fine; three ways (card,
IBAN with QR, payment link); one «Оплатити» button; «Я оплатив» with a receipt, telling the master;
the payment details live in «Налаштування» together with the document requisites moved there.

## What shipped

### «Налаштування» (PWA) and `PUT /api/profile/requisites`

- The profile's «⚙️ Налаштування» row (it used to toast «скоро») opens `SettingsModal`: «Реквізити
  для документів» (everything acts and PDFs print, moved out of «Редагувати профіль») and «Оплата
  від клієнта» — card number and payment link.
- Saved by its own full PUT (`RequisitesRequest`); `ProfileUpdateRequest` no longer carries the
  requisites, so neither form can blank the other's fields. An installed app from before still sends
  them with the profile: ignored, never a 400 (`ClientPaymentHttpIntegrationTest`).
- The card must pass Luhn, the link must be https with a host and no user-info — refused with a 400
  (`PayoutDetails`), because a client sends money to them. The document numbers stay warnings.
- The QR needs IBAN + РНОКПП (+ a name); the form says which is missing.

### The portal (`static/portal/index.html`)

- An «Оплата» card with one «Оплатити» button on every client page — signature (a deposit is paid
  off it), economy, act and the single-estimate `?t=` page — whenever the master filled anything
  (`payTo` on `PublicPortalView`, `PublicEstimateView`, `PublicActView`; null = no card). Not on the
  price list. Always the master's CURRENT details, even on a signed page.
- The dialog lists only what is filled: «Оплатити онлайн» (opens his link; the host is printed under
  it), «За реквізитами (IBAN)» (unfolds: QR, IBAN, purpose, copy buttons, «Зберегти QR»), «На картку»
  (one tap copies the number).
- The QR is the NBU credit-transfer QR, **format 002** (003 is not read by every bank yet), built
  server-side (`NbuPaymentQr`) and served as a PNG (`GET /api/public/payment-notice/{token}/qr.png`,
  `QrPng`, library `io.nayuki:qrcodegen`) — a PNG because a bank app's «scan from a photo» reads the
  one saved to the gallery. **No amount** in it: what is still owed differs per portal; the client types it.
- «Я оплатив ✓» with «Натискаючи, ви повідомите майстра, що оплату здійснено. Можна додати
  квитанцію.» → amount (required), up to 3 photos/PDFs, a comment → `POST
  /api/public/payment-notice/{token}` (multipart, like the message link; the question limiter).

### The notice (`PaymentNoticeService`)

- A `project_messages` row, `kind = PAYMENT`, `payment_amount` — the object's messages, the push
  («💰 Повідомлення про оплату · …»), the 6-month attachment retention all already existed for
  messages. CHECKs: a PAYMENT carries an amount and a `payment_status`, a MESSAGE neither.
- Opens through PORTAL / ECONOMY / ACT links (an act only while SENT/SIGNED) and the single-estimate
  link — never the MESSAGE link, and only while the master offers a way to pay (else one neutral 404).

### The receipt is checked, and a match records the money (owner ruling, same day)

The first build had «Записати оплату» on the message. The owner turned it round: a master who has
notifications off would never record it — so recording cannot hang on a tap; when the client attached
a receipt that matches, the money goes into the object by itself; otherwise the economy asks.

- **Read** — `PaymentReceiptReader` on the new `AiFlow.PAYMENT_RECEIPT` (`AI_FLOW_PAYMENT_RECEIPT`,
  default the small model): amount (the transfer, not the commission), currency, status, date,
  recipient IBAN, recipient card's last four, recipient name. A bank PDF's own text is sent when it
  has one (PDFBox), a scan or a photo as itself. Runs for every master, FREE included (owner ruling);
  the portal's write limiter and the flow's hourly budget bound it.
- **Decide** — `PaymentReceiptCheck`, plain code: not FAILED / IN_PROGRESS; amount equal to the
  kopeck; recipient = the master's full IBAN, or his card's last four (banks mask the rest); dated
  within 7 days, not in the future. Every «no» carries the reason in Ukrainian («У квитанції 9 000 ₴,
  а клієнт вказав 10 000 ₴.»). The first matching file of up to three wins.
- **Matched** → `PaymentService.addReceipt` (the one door, so the economy gate and ownership hold) in a
  transaction of its own, labelled «Оплата з порталу · квитанція звірена» and dated by the receipt;
  the notice becomes `RECORDED` with `payment_receipt_id` (ON DELETE SET NULL: the master may delete a
  payment he does not trust, and the message then says «запис видалено»).
- **Not matched / no receipt / unreadable / the write refused** → `PENDING` with `payment_note`. The
  economy tab opens with an amber card per waiting notice — amount, client, the reason, the receipt —
  and «Кошти прийшли» (`POST /api/projects/{id}/messages/{messageId}/payment/confirm` → `addReceipt`
  «Оплата з порталу», today; row-locked, a second tap records nothing) / «Не надійшло» (`…/dismiss` →
  `DISMISSED`, nothing written).
- The push says which of the two happened and carries no button; the message card shows the state
  line only. The client waits for the check («Перевіряємо квитанцію…»), a few seconds.
- A match proves the client SENT money to the master's details — not that it arrived, nor that the
  picture is genuine (open question).

## Not done / decided

- No bank-confirmed «оплачено» (would need the master's own acquiring API — LiqPay/WayForPay/mono —
  keys per master; deferred until ФОП masters ask for exactly that). A payment link's receipt names the
  payment service, not the master, so it always waits for «Кошти прийшли».
- The client cannot «Поділитися → Majstr» from his bank app: a web page is not a share target, only an
  installed app is. The hint says «Поділитися → Зберегти, або скріншот».
- The QR layout was checked against the published 003 library structure and the 002 field rules, not
  yet by scanning with a real bank app — **the owner should scan it with Приват / mono before telling
  masters** (open question).

## Tests

- `NbuPaymentQrTest` — the 13 lines in order, Windows-1251 cleaning, no QR without IBAN/code/name, PayTo
  shapes, Luhn, PNG.
- `ProfileServiceTest` — requisites + payout saved, bad card / non-https link refused, profile save
  leaves requisites alone.
- `PaymentReceiptCheckTest` — every reason a receipt does not back the notice; `PaymentReceiptReaderTest`
  — sentinels, IBAN/date normalising, a masked «**34» not taken for the last four.
- `ClientPaymentHttpIntegrationTest` (real socket, the reader mocked) — settings 200/400, the old
  profile body accepted and harmless, portal offers nothing until filled, QR 404 without РНОКПП and PNG
  with; a matching receipt records the payment and links it (and a deleted payment reads as such); a
  mismatch waits and «Кошти прийшли» records once on a double tap; no receipt / unreadable wait with the
  reason, «Не надійшло» writes nothing and blocks a later confirm; an ordinary message is no notice;
  MESSAGE-link and no-payout 404s; the CHECKs. `IdorMatrixIntegrationTest` covers confirm/dismiss.
- PWA: `SettingsModal.test.tsx`, `requisites.test.ts` (card/link), `MessagesSection.test.tsx` (badge
  and state line, no record button), `PaymentNotices.test.tsx` (only PENDING shown, confirm/dismiss).
- Mobile check: portal at 375 px (Playwright) — card, dialog, IBAN unfolded with QR, «Я оплатив» form
  and its error; no horizontal overflow, no console errors.
