# Iteration — registration friction (2026-11-06)

**Status:** built 2026-11-06/07. Backend `clean build --no-build-cache` green (2373 tests, none failed or
skipped); PWA lint, both type checks, vitest (1762) and build green; e2e 7/7 and offline e2e 12/12 on a
real backend, and the CI subset `test:e2e:offline:shell` 10/10 with the backend down; checked in a
browser at 375 px. **Migrations:** none. **PWA:** 1.71.2.

## What the visit statistics showed
The first day of round 2 of the visit statistics ([iteration-visit-stats.md](iteration-visit-stats.md)):
7 visitors opened /register, 3 filled the whole form and pressed «Створити акаунт», 1 registered.
«Що зупинило»: two were refused by the email policy («пошта не приймається»), one got a 429. The three
pressed the button 10 times between them.

The mechanism, read off the code:
- `RegisterPage` wrote the server's refusal under the email field with `setError`, which neither
  focuses nor scrolls. On a phone that field is a screen above the button, so the press looked like it
  did nothing, and was repeated.
- `RegisterRateLimitFilter` counted every attempt, refused ones included: 5 per IP per hour, so the
  fifth press locked the person out for up to an hour.
- The refusal said «Одноразові адреси не приймаються» for a typo too: `gmal.com` exists and takes no
  mail, so a person whose gmail is real was told it was a throwaway.
- And the opposite hole: a domain that does not exist (`gmail.con`, `ukr.ne`) made JNDI throw
  `NameNotFoundException`, which the fail-open catch let through. That account never sees its
  verification letter, and an unverified email cannot share an estimate.

## What shipped
- **Backend, `EmailPolicyService`:** NXDOMAIN is refused like a domain without MX/A; every other lookup
  failure still fails open. `EmailDomainNotAllowedException.Reason` gives two codes and two texts:
  `EMAIL_DISPOSABLE` («Тимчасова пошта не підійде…») and `EMAIL_NO_MAIL` («Схоже, в адресі помилка…»).
  `EMAIL_DOMAIN_BLOCKED` is gone. A refusal is logged at INFO with the DOMAIN and the reason, never the
  address. The DNS lookup is handed in, so the unit test covers every answer without the network.
- **`app.email-policy.dns-check`** (default true; false in the test and dev profiles): the integration
  tests and the PWA's e2e register made-up addresses (`@majstr.test`, `@t.ua`), and with NXDOMAIN
  refused they would have passed on Windows, where JNDI finds no resolver, and failed on CI.
- **Backend, `RegisterRateLimiter.refund`:** a 400 from register gives its try back, at most
  `REFUNDS_PER_WINDOW` (20) per window. A 409 (address taken) still counts, or the endpoint would test
  addresses for free.
- **PWA:** a refusal (`EMAIL_NO_MAIL`, `EMAIL_DISPOSABLE`, the 409) focuses the email field and scrolls it
  to the middle of the screen. Leaving the field offers «Може, ivan@gmail.com?» (`lib/emailSuggestion.ts`:
  popular domains, ONE edit — a missing, extra, wrong or swapped letter — and real neighbours such as
  `mail.com` left alone); one tap takes it. The tap is counted as `CLICK register:email-fix`. Two edits
  was the first draft, and the browser check caught it offering `hotmail.com` for `yopmail.com`: at two,
  a real domain is already someone else's mailbox.
- **Admin:** names for `server:email_disposable`, `server:email_no_mail`, `server:429` and the tap.

## Not changed
- The form keeps its fields. Asking only email + password + consent at the door and the rest inside is
  the bigger lever, deliberately left for after these numbers have been watched for a while.
- Real providers were checked against DNS: gmail.com, ukr.net, i.ua, meta.ua, icloud.com, outlook.com,
  bigmir.net and email.ua all have MX. The blocklist holds 65 known throwaway services and none of them.

## Tests
Registration is the door every master comes in by, so it is pinned at three levels (owner's ask).

- **Units:** `EmailPolicyServiceTest` (every DNS answer, the cache, DNS off), `AuthControllerTest` (both
  codes and the Ukrainian text), `RegisterRateLimiterTest` (refund and its cap), `RegisterRateLimitFilterTest`
  (400 refunds, 409 and 201 do not); PWA `emailSuggestion.test.ts`, `RegisterPage.test.tsx` (the hint, and
  focus + scroll on a refusal).
- **`RegistrationEmailIntegrationTest`**, the real server over HTTP (random port, the production
  forwarded-header setup, one `X-Forwarded-For` per test so the limiter counts each alone, and
  `FakeDnsEmailPolicyConfig` answering DNS for `gmal.com`/`gmail.con`): a real address registers (201, the
  user exists); a throwaway is `EMAIL_DISPOSABLE` and a typo `EMAIL_NO_MAIL`, both in Ukrainian, no user
  created; eight refusals and the ninth attempt still registers; a taken address counts down to 429;
  25 refusals exhaust the refunds.
- **e2e without a backend, in CI** (`e2e-offline/register-email.spec.ts`, production build, 375 × 812,
  added to `test:e2e:offline:shell`): the hint corrects and the corrected address is what is sent; a real
  domain gets no hint; a refusal brings the field back on screen with the cursor in it (asserted to be off
  screen at the button first); «описка» from the server shows the hint and the retry sends the fix.
- **e2e against the real backend** (`e2e/register-email.spec.ts`, local like the rest of that suite):
  a throwaway is refused, seen, corrected and registers; a typo corrected by the hint registers (201).
