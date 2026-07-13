# Iteration — anti-abuse: email verification gates + registration hardening

The master flagged a real hole: unverified throwaway accounts could do almost
everything on FREE (6 estimates across 2 objects) **and** claim the 5-day PRO
trial — which unlocks the LLM features (estimate/receipt import) that cost real
Anthropic API money. Churn a fake email → repeat. Cost-weighted, the trial→free-AI
leak is the only one that spends live money; the FREE churn is just deferred
revenue.

Scope agreed with the user: **A + B + C + D + F** (not the AI-quota option E).

## What shipped

### A. No PRO without a verified email (trial AND paid)
`BillingService.startTrial` **and** `BillingService.checkout` throw
`EmailNotVerifiedException` (403 `EMAIL_NOT_VERIFIED`) before granting/charging PRO
unless `emailVerified`. A throwaway address that never receives mail can't verify →
can't reach the paid AI features for free, and can't buy its way past verification
either (follow-up: the master asked to close the paid path too — a verified email
also keeps every PRO account reachable for receipts/renewals/recovery).
`chargeAutoRenew` is not gated — it charges an already-tokenized card for an
existing subscriber, who was verified at their first checkout. Dev-seeded users are
`emailVerified(true)` so the PRO/share/trial flows stay testable locally.

### F. Client-facing PDF requires a verified email (even on FREE)
`EstimateService.renderPdf(id, ownerId)` gates on `owner.emailVerified` (the shared
`renderPdf(Estimate)` used by the portal is left alone — the portal is already
behind the verified-only share gate). So an unverified account can build estimates
but can't produce the finished client deliverable.

### B + D. Registration domain policy (`EmailPolicyService`)
- **Disposable-domain blocklist** — `resources/anti-abuse/disposable-email-domains.txt`
  (~60 curated temp-mail providers; extend as abuse appears). Exact domain match.
- **MX/A check** — a made-up domain that can't receive mail is rejected. JNDI DNS,
  2s timeout, **fail-open**: any lookup error/timeout allows (a DNS hiccup must never
  block a real signup).
- Rejection → `EmailDomainNotAllowedException` → **400 `EMAIL_DOMAIN_BLOCKED`**
  (message `error.email.domain-not-allowed`, uk base + en).

### C. Canonical-email dedup (gmail aliases)
- **V55** adds `users.email_canonical` (+ non-unique index), backfilled with the
  same rule (gmail/googlemail: drop dots + `+tag`, normalize domain to gmail.com;
  others: lowercase). **Login address is unchanged** — canonical is dedup-only.
- `EmailPolicyService.canonicalize` collapses gmail dots+plus and strips `+tag` for
  a known set of plus-aware providers (outlook/hotmail/live/yahoo/proton/icloud/
  fastmail). Register + unverified email-change compute it and pre-check
  `existsByEmailCanonical` → `EmailAlreadyExistsException` (409).
- **Non-unique index on purpose:** legacy duplicates are grandfathered (a unique
  index would fail the backfill); new collisions are caught by the app pre-check.

Both `AuthService.register` and `ProfileService` (unverified email change) run the
full policy (assertAcceptable + canonical dedup) — a typo-fix path must not become a
bypass.

## PWA (v0.9.4)

- **PDF** (`EstimateEditorPage.onPdf`): an unverified master is bounced to the
  existing `EmailVerifyModal` (proactively, and on a `EMAIL_NOT_VERIFIED` response)
  instead of a dead "pdfFailed" toast.
- **Share** (`EstimateEditorPage.onShare`): checks `emailVerified` **before** opening
  the share sheet — an unverified master gets the verify prompt immediately instead
  of a share dialog whose every option dead-ends (user feedback).
- **Trial + upgrade buttons** (`ProfilePage`): the trial button is visible to any
  FREE-never-used master; both the trial and "Оновити до PRO" buttons pre-check
  `emailVerified`. An unverified click opens one reminder modal (`proVerify*`) with a
  single **Підтвердити email** CTA → `EmailVerifyModal`. (An earlier revision offered
  a "pay now instead" bypass; removed once the paid path was also gated — no PRO
  without verification, so there's nothing to bypass to.) A verified click runs the
  real action. `UpgradeIntentModal`'s existing catch already toasts the localized
  verify message if any other entry point reaches checkout while unverified.
- **Register** (`RegisterPage`): `EMAIL_DOMAIN_BLOCKED` renders inline under the
  email field (server message is already localized).

## Tests

- `EmailPolicyServiceTest` — canonicalize (gmail dots+plus, googlemail→gmail,
  outlook +tag, custom lowercased), disposable-domain throws, malformed no-op. MX
  fail-open path not unit-tested (real DNS, always allows).
- `BillingServiceTest` — trial now needs a verified user; new
  `startTrial_unverifiedEmail_throws`.
- `EstimateServiceTest` — `renderPdf_unverifiedOwner_throwsEmailNotVerified`.
- `AuthServiceTest` / `ProfileServiceTest` — mock `EmailPolicyService`, stub
  `existsByEmailCanonical`; new disposable-domain-change rejection test.
- PWA `ProfilePage.trial.test` — trial button hidden when unverified.

## Validation

- V55 dry-run on the live dev DB (`BEGIN … ROLLBACK`): 78 rows backfilled; the gmail
  rule verified on literals (`J.o.hn+tag@Gmail.com` → `john@gmail.com`,
  `a.b@googlemail.com` → `ab@gmail.com`).

## Not done / deferred (open-questions)

- **E (AI-call daily quota)** — not taken this round; a hard per-user/day cap on
  LLM extraction is the strongest cost ceiling if trial abuse persists.
- **MX-vs-app canonical divergence for non-gmail plus** — the SQL backfill only
  normalizes gmail; legacy outlook-plus dupes aren't retro-caught (new ones are).
- Blocklist is curated, not exhaustive — extend as abuse is seen.

## Noticed in passing (fixed here)

`AdminSeeder` / `DevDataSeeder` built a `User` without `referralCode` (NOT NULL
since V41) — a fresh seed would have failed on that column (dormant: existing DBs
were backfilled). Fixed in this iteration: both seeders now inject `ReferralService`
and set `.referralCode(generateUniqueCode())` alongside the new `.emailCanonical(...)`;
`AdminSeederTest` asserts both NOT-NULL columns are populated.
