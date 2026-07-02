# Real payments — PRO subscription via monobank acquiring (phase 1)

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate (lots
  of new Java + tests). V37 migration **drill PASSED** (prod backup → V37, billing
  schema applies cleanly). PWA wiring is the next step (separate repo).
- **Migration:** `V37__add_billing.sql` — `users.plan_expires_at` + `payments` ledger.
- **Goal (user, 2026-07-02):** Add real payment for PRO. Decided after a fee/recurring
  comparison: **monobank Acquiring** (1.3% vs WayForPay 2% vs LiqPay 2.75%; audience all
  use mono; has a full recurring API for later). Phase-1 model: **PRO for 30 days**
  (invoice → webhook → PRO + expiry; renew by a fresh checkout). Price **299 ₴**. On
  expiry: **revert to FREE, keep data** (soft) with a short grace.

## Flow

1. PWA "Оновити до PRO" → `POST /api/billing/checkout` (authed) → backend creates a
   monobank invoice (`/api/merchant/invoice/create`, `X-Token`), stores a PENDING
   `payment`, returns `pageUrl`.
2. PWA redirects to `pay.monobank.ua/...` (hosted page) → Apple/Google Pay / QR / card.
3. monobank POSTs the result to `POST /api/billing/webhook` (public) → backend verifies
   the `X-Sign` **ECDSA** signature against monobank's pubkey → on `success` (with a
   matching amount) flips the payment to SUCCESS and extends PRO.
4. `redirectUrl` returns the payer to the app; the PWA polls `/me` for the new plan.

## Trust & safety (money path)

- **PRO is granted only by the verified webhook**, never by the client. Checkout only
  mints an invoice + a PENDING payment.
- **Signature**: `MonobankSignatureVerifier` (SHA256withECDSA over the raw body, pubkey
  from `/api/merchant/pubkey`; BouncyCastle registered). An unsigned/forged webhook is
  ignored.
- **Idempotent** on `invoice_id` (UNIQUE) — a repeated `success` never double-extends.
- **Amount check**: webhook amount must equal the invoice amount, else not granted.
- Webhook always returns **200** (handling is idempotent + logged) so a forged request
  or our own transient error can't trigger a monobank retry storm.
- **Prod free-PRO guard:** dev-simulation (grant PRO with no token) is gated on
  `app.billing.allow-dev-simulation`, forced **false in `application-prod.yml`**. So on
  prod a missing `MONOBANK_TOKEN` makes checkout fail loudly (500) instead of handing out
  free PRO to anyone who clicks. (`BILLING_RETURN_URL`/`BILLING_WEBHOOK_URL` inherit the base
  env refs — a missing one breaks billing but is deliberately **not** fail-fast, so a
  feature-config slip can't brick the whole app's boot.)

## Backend (new unless noted)

- `config/BillingProperties` (`app.billing.*`) — env-gated like email/push:
  **no `MONOBANK_TOKEN` ⇒ dev-simulation** (checkout grants PRO immediately, no HTTP),
  so the flow is buildable/testable without a merchant account. Registered in
  `MajstrApplication`.
- `entity/Payment` (+ `PaymentProvider`, `PaymentStatus`) — plain `userId` (like
  `UpgradeEvent`). `User.planExpiresAt` added; `UserResponse` carries it (PWA badge).
- `billing/MonobankClient` (RestClient; create-invoice, pubkey, cached) +
  `billing/MonobankSignatureVerifier`.
- `service/BillingService` — checkout + handleWebhook (verify → idempotent → amount →
  extend). `extendPro` stacks onto a still-active subscription, else from now.
- `service/BillingExpiryService` — `@Scheduled` daily; downgrades PRO→FREE once
  `plan_expires_at + graceDays` passes. Only rows with a non-null expiry (so
  admin-granted PRO, which has none, is never auto-downgraded).
- `controller/BillingController` — `POST /checkout` (authed), `POST /webhook` (public,
  added to `SecurityConfig.PUBLIC_PATHS`).
- `PaymentRepository` (+ `UserRepository.findExpiredSubscriptions`).

## Not changed / confirmed

- Existing admin-manual plan change (`PATCH /api/admin/users/{id}/plan`) still works and
  sets **no** expiry → those PROs never auto-expire (admin owns them). Billing-granted
  PRO carries an expiry.
- Record fan-out: `UserResponse` gained a component → `AuthControllerTest`'s
  `new UserResponse(...)` updated (grep clean).
- Downgrade is soft (FREE limits only gate *creating new*, as today) — matches SPEC G1.

## Tests

- `BillingServiceTest` — dev-mode checkout grants + returns returnUrl; configured
  checkout creates an invoice and does **not** grant yet; webhook success grants +
  amount-checked; success stacks onto an active sub; amount mismatch / invalid signature
  / already-success are all no-ops.
- `BillingExpiryServiceTest` — lapsed PRO → FREE + null expiry; empty is a no-op.
- `MonobankSignatureVerifierTest` — genuine signature verifies; tampered body / blank
  inputs rejected.
- Drill: asserts `users.plan_expires_at` + `payments` exist after V37.

## What the user must provide for the real (non-dev) flow

- A **monobank ФОП account** + acquiring, then the merchant **`MONOBANK_TOKEN`** (env).
- `BILLING_RETURN_URL` (PWA return page) and `BILLING_WEBHOOK_URL` (this backend's public
  `/api/billing/webhook` — must be internet-reachable: prod/Railway, or a tunnel in dev;
  localhost is unreachable by monobank).
- Optional: `BILLING_PRO_PRICE` (default 299), `BILLING_PRO_DAYS` (30), `BILLING_GRACE_DAYS` (3).

## Next step — PWA (separate repo/commit)

- `api/billing.ts` → `checkout()`; profile "Оновити до PRO" → checkout → `window.location = pageUrl`
  (replaces the V34 painted-door modal path; keep the `upgradeApi.click` analytics event).
- `/billing/return` page: poll `/me` until `plan === 'PRO'`, show success.
- Show "PRO активний до DD.MM" from `me.planExpiresAt`.

## Phase 2 (later)

Tokenized **auto-renew** (monobank subscription API: `/api/merchant/wallet/payment`,
`/api/merchant/subscription/*`) instead of manual re-checkout — the schema (payments +
expiry) already supports it. An explicit `subscription_status` (ACTIVE/GRACE/EXPIRED)
machine (SPEC G1) can come with it.
