# Master→master referrals + half-year tariff (V41)

- **Status:** 🔨 Backend code complete; `./gradlew build` pending at the gate. V41
  migration **drill assertions added** (run pending). PWA verified: **tsc + vitest(66)
  + vite build green**.
- **Migration:** `V41__master_referral_and_period.sql`.
- **Goal (user prompt, 2026-07-05):** Two retention add-ons over live billing (V37) and
  attribution (V38): (1) a master→master referral program — a personal link, and a free
  month of PRO to the referrer when their invitee **first pays** ("майстра платити не
  заставиш" → a master can *earn* PRO); (2) a half-year tariff — 1494 ₴ for 6 months
  (249/mo vs 299), the anti-churn play while recurring billing matures.

## Key interaction decision (not in the prompt — it predated V40 auto-renew)

Half-year and tokenized auto-renew (V40) both fight churn and could conflict. **User
chose: auto-renew recharges the SAME period.** So a 6-month subscription with auto-renew
on recharges 1494 ₴ for another 6 months. Implemented via `users.renew_period` (set at
the opt-in checkout) which `chargeAutoRenew` reads back; the T-3 reminder / failed-charge
emails show the period's amount.

## Backend

- **V41:** `users.referral_code` (unique, backfilled from `md5(id)`), `referred_by_user_id`
  (FK, ON DELETE SET NULL), `users.renew_period`; `payments.period` (MONTH|HALF_YEAR,
  backfilled MONTH); `referral_rewards` (id, referrer_id, referred_user_id **UNIQUE**,
  payment_id, granted_days, created_at) — the audit + idempotency guard.
- **Referral resolve** (`ReferralService.resolve` → `Attribution{source, referredByUserId}`):
  a personal `m-<code>` resolves to `MASTER` + the inviter's id; **partner codes (LIGA…)
  resolve exactly as before** — master codes are only *added* to the front of the resolver,
  first-touch priority unchanged. Each new master gets a unique code (`generateUniqueCode`,
  retry on collision).
- **Reward** (in `BillingService.onSuccess`, the first-successful-payment point): if the
  payer was `referred_by` someone and no reward exists for them yet, the referrer earns
  `referralRewardDays` (30) of PRO. FREE referrer → PRO+30d; PRO-with-date → +30d;
  admin **dateless** PRO → recorded with 0 days (nothing to extend — open-question).
  **Idempotent:** `existsByReferredUserId` short-circuits, `referral_rewards.referred_user_id`
  UNIQUE is the hard backstop — a retried webhook or a second payment never double-grants.
- **Periods** (`CheckoutRequest{period, autoRenew}`): amount + days are **server-side**
  (`BillingProperties.priceFor/daysFor`) — the client sends only the period, never a price.
  `extendPro` now credits `payment.getDays()` (30 or 180). `chargeAutoRenew` recharges the
  user's stored `renewPeriod`. Dev-simulation covers both periods.
- **Admin:** per-user card shows who invited them + how many they invited/paid; overview
  gains a "referral rewards granted" stat. `MASTER` falls into the existing by-source report
  naturally (`referral_source`).
- **Stats API:** `GET /api/referrals/me` → `{referralCode, invited, paid, monthsEarned}`
  (read-only `ReferralQueryService`). The code itself also rides on `/auth/me`
  (`UserResponse.referralCode`) so the PWA can build the link.

## PWA

- **PRO modal:** two period cards (1 month 299 ₴ / 6 months 1494 ₴, the half-year marked
  "Вигідно · економія 300 ₴") + the auto-renew checkbox (hint text switches to the
  half-year amount when 6 months is picked). `checkout(period, autoRenew)`.
- **Profile "Запроси майстра":** personal link `origin/?ref=m-<code>`, native Share (copy
  fallback), the invite explainer, and invited/paid/months-earned stats.

## Not changed / confirmed

- Partner attribution (LIGA / `/liga` / rev-share report) untouched — master codes are a
  separate dimension, no double rewards. Billing idempotency (`invoice_id`), grace,
  downgrade, and the prod dev-sim guard are unchanged. Admin dateless PRO is never
  auto-downgraded. Tariff amounts live only on the server.
- **Fan-out:** `BillingProperties` (+proHalfYearPrice, +referralRewardDays),
  `UserResponse` (+referralCode), `AdminUserDetail` (+3), `MetricsOverviewResponse`
  (+referralRewards), `CheckoutRequest`, `BillingService`/`AdminUserService`/`MetricsService`
  constructors, `ReferralService` (resolveSource→resolve) — all call sites + tests updated.

## Tests

- `BillingServiceTest` — half-year checkout (1494/180 + period stored for auto-renew);
  referral reward on first payment (referrer +30d + audit row); reward **not** granted twice.
- `ReferralServiceTest` — master `m-<code>` → MASTER + referrer id; case-insensitive;
  unknown master code → DIRECT (not partner); partner path unchanged; code-gen retries on
  collision.
- `AuthServiceTest` — new `resolve` + minted referral code. PWA: `UpgradeIntentModal` period
  selection (default MONTH, HALF_YEAR, autoRenew off). Drill: V41 columns + code uniqueness +
  MONTH backfill.

## Verify (after backend build green)

1. Profile → invite link copies; a new registration via `?ref=m-<code>` →
   `referral_source=MASTER`, `referred_by` set.
2. Invitee's first payment (dev-sim) → referrer FREE→PRO+30d / PRO+30d; a **repeat** webhook
   or the invitee's **second** payment grants nothing.
3. PRO modal: both periods; HALF_YEAR → invoice 1494 → webhook → +6 months.
4. LIGA (`/liga`, promo `LIGA`) still first-touch DIRECT/LIGA — unaffected.
5. Admin: MASTER in the by-source report; referred-by + invited/paid on the user card.
