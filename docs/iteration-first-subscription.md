# Iteration — the first paid subscription, and the metric that missed it (1.0.0)

PWA `0.47.0 → 1.0.0`. Backend: no migration.

## What happened

On 2026-07-31 a master bought PRO — 299 ₴ through monobank, CHECKOUT, settled at 295.11 ₴. The
first real revenue in the product's life.

**The admin dashboard did not change.** That is the whole reason this iteration exists.

## Why the payment was invisible

Two separate faults, and the second is the one worth remembering.

**1. There was no payments panel at all.** `MetricsOverviewResponse` never touched the `payments`
table, and `PaymentRepository` had only per-user lookups (`findByInvoiceId`, `findAutoRenewSince`)
— not one aggregate. The row was in the database and nothing on the screen read it.

**2. «Конверсія в платні» was computed from the plan column:**

```java
long paid = planDistribution.get(Plan.PRO) + planDistribution.get(Plan.TEAM);
```

A five-day trial sets `plan = PRO`. An admin grant sets `plan = PRO`. So the figure read 2.24 %
before the sale and would have read 2.24 % after it. **A business metric derived from a state that
several non-business paths can set is not measuring the business** — that is the transferable
lesson, and it is why the fix was not "add a tile" but "change what the number counts".

## What shipped

**A `SUCCESS` row in `payments` is now the only thing that counts as revenue.** New aggregates on
`PaymentRepository` (payers, payment count, gross, gross-30d, the recent list), and the conversion
rate is `everPaid / total`.

**Paid plans split three ways**, in this order — the order is load-bearing:

| | rule | why the order matters |
|---|---|---|
| **Куплено** | has a `SUCCESS` payment | checked FIRST, so a master who trialled and then bought is a customer, not a trialist — and the trial is how nearly everyone arrives |
| **Пробний** | no payment, `trialStartedAt` set | `trialStartedAt` is never cleared, so it cannot be the primary signal |
| **Видано адміном** | no payment, no trial | staff and comps; usually dateless, never auto-downgraded |

**`payingNow` and `everPaid` are both reported.** Ever-paid is the honest conversion number and
never goes down; paying-now drops when someone does not renew. One figure cannot show both, and the
difference between growth and churn is exactly what a founder needs on day one of revenue.

**Admin gets a «Підписки та гроші» section**: the four counts, gross total and 30-day gross, and the
last ten payments with who paid, how much, and whether it was `CHECKOUT` (paid by hand) or
`AUTO_RENEW` (a token charge) — identical in the money, very different in what they say about
retention.

The plan tiles were left as plain plan counts. Attributing a share of the split to PRO specifically
would have been a number nobody computed.

## 1.0.0

The version was waiting on a business milestone, not a technical one (user decision, 2026-07-23):
**1.0.0 ships with the first paying user.** It has. The tiling-catalog rebuild (V81–V84) rides
along in the same release.

## Not changed / confirmed

- No migration — `payments` already had everything needed. The gap was in what was read, not stored.
- The trial mechanism, `PlanConfig` and the auto-renew scheduler are untouched.
- «Автопродовження увімк.» still reads 0: the first customer paid by hand and has not switched
  auto-renew on. That is now visible as a fact rather than as an absence.

## Gotchas

- `MetricsService` gained a constructor argument (`PaymentRepository`). It is Lombok
  `@RequiredArgsConstructor`, so nothing breaks at the call sites — but `MetricsServiceTest` needed
  the matching `@Mock` or Mockito would have injected null.
- `SUM` over no rows is `NULL`, not zero — the aggregates `COALESCE`. On the day before the first
  payment an empty tile and a «0 ₴» tile look the same and mean different things.
- The test that asserted `30.00 %` from PRO+TEAM was rewritten rather than deleted: it encoded the
  old, wrong idea, and the replacement pins the new one (three paid plans, one payer → 10 %).
