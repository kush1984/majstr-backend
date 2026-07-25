# Iteration: annual PRO tariff (229 ₴/mo paid for 12 months)

A third tariff beside monthly and half-year, at the deepest per-month price. Same shape as
the half-year tariff ([iteration-master-referral-halfyear.md](iteration-master-referral-halfyear.md)) —
the client sends only the period, the server owns the amount.

- **Status:** 🔨 code complete; backend build on the user
- **Migration:** **V65** — widens the `payments.period` CHECK to allow `YEAR`
- **PWA:** 0.34.0

## The tariff

| Period | Price | Per month | Saving vs monthly |
|---|---|---|---|
| MONTH | 299 ₴ | 299 ₴ | — |
| HALF_YEAR | 1494 ₴ | 249 ₴ | 300 ₴ |
| **YEAR** | **2748 ₴** | **229 ₴** | **840 ₴** |

`YEAR` = `proDays × 12` = 360 days (consistent with HALF_YEAR = `proDays × 6` = 180 — the
day count is a multiple of the 30-day PRO month, not a calendar year).

## Backend

- `BillingPeriod` gains `YEAR`.
- `BillingProperties` gains `proYearPrice`; **`priceFor`/`daysFor` became exhaustive switch
  expressions** instead of ternaries — now adding a period is a compile error until it is
  priced, rather than silently falling through to the monthly price.
- `app.billing.pro-year-price` = `${BILLING_PRO_YEAR_PRICE:2748}`.
- **V65** widens the `payments.period` CHECK. This was the one hard blocker: without it a
  YEAR checkout would pass every Java check and then fail at INSERT.
- `users.renew_period` has **no** CHECK (V41 added it as a plain VARCHAR), so auto-renew
  needed no migration — an annual subscription recharges for a year via the existing
  period-matched `AutoRenewService.amountFor`.

## PWA

- `BillingPeriod` type gains `'YEAR'`; a third card in `UpgradeIntentModal`.
- **Layout changed from a 2-column grid to stacked full-width rows** (title + saving note on
  the left, price on the right). Three tariffs side by side crush the «229 ₴/міс · економія
  840 ₴» note at 375px — and that note is exactly what sells the longer period.
- The «Найвигідніше» badge moved from the half-year card to the annual one (it is now the
  best deal); the half-year card keeps its saving note without a badge.
- The auto-renew hint is a `Record<BillingPeriod, string>` lookup instead of a two-way
  ternary — a new period can no longer silently inherit the monthly wording.
- i18n uk+en: `periodYear`, `periodYearPrice`, `periodYearNote`, `autoRenewHintYear`;
  `periodBadge` retitled «Найвигідніше» / «Best value».

## Tests
- Backend (on the user): `BillingServiceTest.checkout_year_…` — 2748 ₴, 360 days, period and
  `renewPeriod` both `YEAR`. The three `new BillingProperties(...)` call sites in tests were
  updated for the new record component (the record fan-out check — this is the third time it
  has bitten, so it is worth grepping every time).
- PWA: `UpgradeIntentModal.test` — the 12-month card sends `YEAR`, shows the per-month saving,
  and swaps the renewal hint to the annual wording. Full suite + build green.

## Not changed / confirmed
- The client still never sends an amount — only the period.
- Dev-simulation, grace, the downgrade job, referral rewards: untouched.
- No other place displays a tariff price (`billing.price` is a dead key; the landing has no
  price block) — so 229/2748 lives only in the i18n period keys + the server config.

## Gotchas
- **V65 drops the old CHECK by lookup, not by guessed name.** V41 created it inline on
  `ADD COLUMN`, so Postgres generated the name. Dropping a guessed name would silently
  no-op and leave the old constraint rejecting `YEAR` at runtime — the migration therefore
  finds every CHECK on `payments` mentioning `period` and drops it before adding the new one.
- Prices live in **two** places by design: the server config (authoritative, drives the
  charge) and the i18n strings (display only). Changing a price means changing both.
