# One object without a subscription — 149 ₴

- **Status:** ✅ SHIPPED 2026-11-17 — backend `a3d2e5d`, PWA `82d9091` (1.84.0).
- **Migrations:** V214 (`users.object_credits`, `projects.paid_at`), V215 (`payments_kind_check` admits `OBJECT`).
- **PWA:** 1.84.0.
- **Goal (webinar, 2026-11-11):** «Є можливість користуватися разово, без підписки?» (Сергій). A master
  who needs one estimate for one client had no way to pay except a monthly PRO.
- **Owner's decisions (2026-11-17):** 149 ₴; the paid object gets PRO's limits **except AI**; it stays
  forever; after a second one bought in a month, say PRO is cheaper.

## What the master sees

1. A FREE master with both free objects used (the lifetime cap, V107) hits the object limit. **«Новий
   обʼєкт» stays live** (home header, mobile CTA, quick action, objects list, FAB, «Створити обʼєкт» on
   the form) and opens the purchase sheet on the object tab (`useObjectPurchase`). The banner button reads
   **«Ще обʼєкт →»** (other limits keep «Дізнатися про PRO»). The profile has **«Купити обʼєкт разово —
   149 ₴»** under «Оновити до PRO», same orange.
2. The sheet is **«Обрати тариф»** (FREE; a PRO master still sees «Оновити до PRO» with no tabs) with two
   tabs, **«PRO · підписка»** and **«Один обʼєкт · 149 ₴»**, each its own panel and ONE orange button:
   PRO = periods + auto-renew + «Оплатити»; object = four ✓ points (unlimited estimates, 50 photos + 50
   receipts, logo, forever), «ШІ — лише в PRO» and **«Оплатити 149 ₴»**. If he already bought an object
   in the last 30 days: «PRO за 299 ₴ вигідніший: без обмежень і з ШІ».
3. monobank → back to `/billing/return?for=object`, which waits for the credit (not for PRO) and offers
   **«Створити обʼєкт»** → the new-object form, which says «Цей обʼєкт — оплачений, без підписки…».
4. Creating it spends the credit. The object is ordinary in every way except its caps and the logo.

### Owner's second local test (2026-11-17)

- **The sheet opens for what he came for** (`mode`): «Оновити до PRO» → PRO alone; «Купити обʼєкт разово»
  → the object alone («Купити обʼєкт разово»); only the object cap (banner, «Новий обʼєкт») shows
  «Обрати тариф» with the two tabs, starting on the object.
- **No jumping**: in the choice both panels share one grid cell and the hidden one is `invisible`, so
  the sheet keeps the taller panel's size.
- «Оплата через monobank» is gone from the sheet (any card pays there).
- **A spent credit was still offered**: the PWA never refreshed `/api/plan/limits` after creating an
  object (staleTime 5 min), so after the paid object the cache still showed a credit, «Новий обʼєкт»
  led to the form and the server refused with the PRO message. Now `useCreateProject` counts the object
  in the cached limits at once (and spends the credit at the cap, online or queued) and refetches them on
  success; the return page refetches limits too (a new PRO was also stuck behind the FREE cap); a
  `PROJECT_LIMIT_REACHED` on the form opens the purchase instead of a toast.
- **«Still FREE» is said**: at the cap with a bought object waiting, home and the objects list show
  «Ви на безкоштовному плані. Один обʼєкт оплачено — створіть його кнопкою «Новий обʼєкт»».

## How it works

- **Payment:** `POST /api/billing/checkout-object` → `BillingService.checkoutObject`. Same trust model as
  PRO: the PENDING row commits before monobank is called; only the verified webhook (signature + exact
  kopiyka amount, claimed once by `claimForGrant`) grants. `payments.kind = OBJECT`, `plan = FREE` (the
  column means «what this payment grants»), `days = 0`, amount from `app.billing.object-price`
  (`BILLING_OBJECT_PRICE`, default 149). The invoice's `redirectUrl` is the return URL + `?for=object`
  (new `MonobankClient.createInvoice` overload). Dev simulation adds the credit at once.
- **The credit:** the webhook calls `UserRepository.addObjectCredit` — an UPDATE in the database, so two
  payments landing together both count. No PRO, no referral reward (that pays out on a subscription),
  no card fetch (a wallet id left by an earlier PRO checkout is not this payment's).
- **Spending it:** `LimitService.reserveProjectSlot` (user row locked) spends one credit only when the
  create is AT the cap; below it, or on PRO, the credit is kept. It returns whether it spent one, and
  `ProjectService.create` stamps `projects.paid_at`.
- **Per-object caps:** `LimitService.limitOn` — on a paid object the estimate and photo caps are PRO's
  (never tighter than the plan's). AI features are untouched: they stay on the plan.
- **Logo:** `PaidObject.branded(guard, owner, project)` in the estimate PDF, the act PDF and both signed
  snapshots — plan's `BRANDED_PDF` or a paid object. The price-list PDF has no object and stays on the plan.
- **What the PWA reads:** `/api/plan/limits` gains `objectCredits`, `objectPrice`,
  `objectsBoughtLastMonth`, `paidObject` (PRO's per-object caps); `ProjectResponse.paid`. PWA helpers
  `isAtObjectLimit` (cap reached AND no credit) and `objectCaps(limits, paid)`.

## Texts changed

Landing hero note, FAQ «Скільки коштує» (in `uk.json`/`en.json` and the JSON-LD in `index.html`),
`public/llms.txt` tariffs, `limits.objectsHint`.

## Not changed / confirmed

- Transferred objects («Перенести в Majstr», V201) and the training sample stay outside the cap and
  never touch a credit.
- Deleting a paid object does not return the credit (same rule as the lifetime cap).
- Admin «Платежі»: an object payment counts in revenue and «ever paid»; it is not «платить зараз»
  (that needs a live paid plan). The recent-payments list shows its kind as `OBJECT`.
- Offline: creating an object at the cap still needs the credit on the server; paying is online.

## Gotchas

- **V40 declared `payments.kind` with an inline CHECK** (`payments_kind_check`: CHECKOUT, AUTO_RENEW). The
  first build passed every test — the unit tests mock the repository and the first IT never wrote a
  payment — and the owner's first local click failed on the INSERT. V215 widens it;
  `OneOffObjectCheckoutIntegrationTest` now writes a real OBJECT payment (dev simulation on Postgres).

- `BillingProperties` is a record: a new field means every `new BillingProperties(` in tests (six).
- `ProjectResponse` gained a field: `ProjectControllerTest` builds it by hand (four).
- There is **no public offer (оферта) page** in the app. A one-off purchase and its refund rule would
  belong there; the owner should decide whether monobank needs one.
