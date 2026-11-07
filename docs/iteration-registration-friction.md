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

---

# Round 2 — the door asks three things (2026-11-07)

**Status:** built 2026-11-07, not committed (owner tests locally first). **Migration:** V190.
**PWA:** 1.72.0. Gate: backend `./gradlew build`; PWA lint, `tsc -b`, `typecheck:tests`, vitest (1789),
`vite build`, `test:e2e:offline:shell` 12/12. The real-backend e2e (`e2e/`, `e2e-offline/journey`,
`shared-phone`) were updated but not run in this round.

## Why
Round 1 left the form's fields alone «until the numbers had been watched». The first ad results
showed the same stop: a stranger had to give a name, a phone, a company and a trade before seeing
anything. Most of it is needed only later — and the code said so: the trade fills the catalog once,
the name/phone/company are printed only on what goes to a client.

## What shipped
- **Register = email + password + consent** (`RegisterRequest`: name, phone, company, trades lose
  `@NotBlank`/`@NotNull`, `isTradeChosen` gone; an older build still sends them and they are taken).
  V190 drops NOT NULL on `users.full_name/phone/company_name`; blank lands as NULL. Consent stays a
  checkbox (legal question, not decided here). Under «Створити акаунт» the PWA says what is missing
  («Заповніть: …»).
- **«Чим займаєтесь?» after login** (`/welcome`, `TradesOnboardingPage`): trade tiles + «Свій
  напрям», «Далі» disabled until something is chosen, «Пропустити» behind a confirm that says the
  cost. `ProtectedRoute` sends a master with no system AND no custom trade there unless he skipped
  on this device (`majstr.tradesSkipped.<id>` in localStorage). Saves via **`PUT /api/profile/trades`**.
- **An added trade fills its catalog unasked, on every door** (`ProfileService.replaceTrades` →
  `addTemplatesForTrades`, a merge). The profile's «додати набір?» / «Не зараз» is gone — «Не зараз»
  left the trade's bundles in the picker pricing every line at 0 ₴.
- **The name is asked when a document first leaves** — `SendReadiness.require` (verified email, then
  a name) on every share door (`ShareLinkService` / `ProjectPortalService.requireSharable`) and the
  estimate PDF; `requireName` on a live act PDF. 403 **`SENDER_NAME_REQUIRED`**. The PWA's one
  `SenderSheetHost` (mounted in `App`) asks «Як вас підписати в документі?» — name required, phone and
  company optional, a preview of the header, saved to the profile **automatically** via **`PATCH
  /api/profile/sender`** (writes those three only; the full PUT would clear the requisites).
  `useSenderGate` is called before every share/PDF; a PDF tab reserved in the tap is cancelled
  before the sheet (iOS) and the master taps again.
- **One rule for what a document says about the master** — `ContractorName.display` (company, else
  name), `signer` (name, else company), `phone` (blank = no line). The estimate PDF used to print
  `"Тел.: " + phone` unconditionally. The act's own company → legal name → name order is unchanged.
- **Empty template picker** explains a missing trade and leads to `/welcome` (back after).
- Profile: name/phone/company optional with hints; the header shows the email while no name.

## Gotchas found on the way
- `SharePortalSheet.handleError` mapped every 403 to «тільки в PRO»; `SENDER_NAME_REQUIRED` is a 403
  too, so it is branched first.
- `public/_redirects` must list `/welcome` (`redirects.test.ts` caught it).
- Test fixtures that build an owner without a name now stop at the gate (EstimateServiceTest,
  ShareLinkServiceTest, ActEditorPage.test) — they were given one.

## Not changed / open
- Consent wording, a public demo estimate before registering, asking the phone for sales calls —
  owner decisions, not built.
- The skip is per device; the empty-picker hint is the safety net on another phone.
- The trades-screen skip rate is visible server-side as users with no trades.

## Tests
`ContractorNameTest` (rule + `SendReadiness`), `AuthControllerTest`/`AuthServiceTest` (bare
registration, blanks → NULL, zero trades), `ProfileServiceTest` (only NEW trades fill, sender writes
three fields), `EstimatePdfServiceTest` (no phone/company → name, no «Тел.»), `ShareLinkServiceTest`,
**`RegistrationOnboardingIntegrationTest`** over HTTP (bare door, trades fill the catalog idempotently,
profile saves nameless, share/portal/PDF refuse until `PATCH /sender`, the printed PDF). PWA:
`RegisterPage`, `TradesOnboardingPage`, `SenderSheetHost`, `ProtectedRoute`, `TemplatePickerSheet`,
`SharePortalSheet`, `ProfileEditModal`; offline e2e `register-email.spec.ts` (+2: the three-field
door, register → `/welcome` → one PUT).

## Round 2, part 2 — the call offer, an optional address, the sample on the first screen (2026-11-07)
**Migrations:** V191, V192. Still uncommitted.

- **«Потрібна допомога з налаштуванням? Залиште телефон — передзвонимо»** (V191
  `users.help_requested_at`): a card on the home screen while the account has no phone and has not
  asked; «Ні, дякую» hides it on the device. **`POST /api/profile/help-request`** saves the phone to
  the profile and stamps the FIRST ask; the admin lists the askers in «Просять допомогти з
  налаштуванням» (`GET /api/admin/metrics/help-requests`, newest first, a `tel:` link). Kept apart from
  the PRO «теплі ліди» on purpose — mixing them would distort the PRO-interest numbers.
- **Privacy policy (07.11.2026):** the master's phone was missing from the data list entirely; added,
  with the callback as a purpose; name and company marked optional until the first document.
- **An object needs a name, not an address** (V192 drops NOT NULL on `projects.address`;
  `ProjectRequest.address` optional; blank on an edit CLEARS it). Printers: the estimate PDF drops its
  «Адреса» row, the portal its line; acts and the shopping list already skipped a blank one.
- **The object header edits what it shows** — owner's condition: whatever became optional must be
  addable and changeable later. The PWA had NO way to edit an object's name or address at all; both
  are now `InlineTextCell`s in the header (the address one takes `allowEmpty` + «Додати адресу»).
  Saves through the offline-capable `useUpdateProject`.
- **The sample estimate on the first screen** (`/zrazok-koshtorysu`, `data-track="hero:sample"`, named
  in the admin): it was linked only from the footer, which the visit stats showed nobody reached.
- Home greeting reads «Добрий день, майстре» while there is no name (was «...», which read as loading).
- Consent stays a checkbox (owner, 2026-11-07).

Tests: `ProfileServiceTest.requestHelp…`, `ProjectServiceTest` (no address, edit adds/clears),
`EstimatePdfServiceTest` (no «Адреса»), integration (call ask idempotent stamp, object without address
then edited); PWA `HelpOfferCard`, `InlineTextCell` allowEmpty, `NewObjectPage`, `ProjectDetailPage`
header edits, `LandingPage` sample link. Gate: PWA lint/tsc/typecheck:tests/vitest 1802/build, offline
e2e 12/12; backend affected suites + IDOR/security matrices green. Full `./gradlew build` NOT re-run
(the last one was stopped for low memory).

## Round 2, part 3 — a light reminder for an empty company or phone (2026-11-07, PWA only)
Owner's report: an existing master who clears the company and the phone in his profile sends
documents with the name alone, silently. Now `useSenderGate` has two modes: `name` (required, as
before) and `extras` — when the name is there but the company OR the phone is empty, the same sheet
asks «Додати назву компанії і телефон?» with «Пропустити» / «Зберегти в профілі». Closing it is a skip
(result `skipped`, the document goes as it is); it is asked ONCE per app session, so a PDF and a share
in a row ask once, and the next session asks again while they are still empty. A value «only for this
document» is deliberately not offered: the header has no source but the profile, so it would print
nothing — the button says it saves for the next estimates too. A PDF after the sheet needs a fresh tap
(iOS), as for the name. Tests: `SenderSheetHost.test.tsx` (once per session, skip, save).

## Round 2 — final check before release (2026-11-07)
Run: backend `./gradlew clean build` 2403 tests (the one failure — `CLAUDE.md` over its 60k budget —
fixed by folding the new index line into «Email verification is soft»; its twin merged in the detail
file); PWA lint, `tsc -b`, `typecheck:tests`, vitest 1808, `vite build`; offline e2e shell 12/12; the
real-backend e2e 7/7; offline `journey` + `shared-phone` 2/2 (against a second backend on :8081 — the
local one on :8080 had been started with a CORS list without :4173); a 375×812 walkthrough of
register → trades → home → object without an address → the sender sheet.

What it found and what was fixed:
- **A reload right after picking trades bounced him back to «Чим займаєтесь?»** — the cache is
  restored from the device up to a second stale, and the gate read it before `/me` answered.
  `ProtectedRoute` now waits while `me` is fetching; the trades screen steps back by itself when it was
  reached by redirect and the fresh `me` has trades (opened from the picker it stays). Test proven to
  fail without the fix.
- **The consent text still listed «ім'я, назва компанії»** as collected at registration; it now says
  «email, а також імені, назви компанії й телефону, якщо я їх укажу».
- «Металоконструкції» was cut in a half-width tile at 375 px: `lang="uk"` + `hyphens:auto` +
  `overflow-wrap:anywhere`. The 📍 in the object header aligned to its text.
- Test fixes, not app bugs: the offline e2e `/me` mock now answers what was saved; the real-backend
  register spec used a gmail «+tag», which the canonical-email check folds onto the previous run's
  account (409) — it uses a «-» now.

## Round 2, part 4 — review fixes (2026-11-07, after release) — V193
A second review of the shipped round found four bugs and several flow issues; all are fixed:

- **An act signed on paper by a master with no name kept an empty «Виконавець» forever** (and its copy
  was mailed): `signOffline` now runs `SendReadiness.requireName` first; the PWA asks the name before
  the signature sheet (`emailFirst:false` — paper needs no verified email) and answers a refusal by
  opening the name sheet.
- **A given name could be erased in the profile** after documents left with it — a portal signature
  afterwards would freeze no contractor. Optional until first given, then 400
  `PROFILE_NAME_REQUIRED` (`ProfileNameRequiredException`); the PWA field turns required.
- **The phone reminder came right after the name sheet, and a first PDF cost three taps.** The name
  sheet now counts as this session's reminder; a PDF never reminds (`remind:false`) — only a link to a
  client does. The reminder is about the PHONE only; the company is no longer chased.
- **A master who unticked every trade was sent back to «Чим займаєтесь?», per device.** V193
  `users.trades_onboarded_at` is the answer, stamped on every door that sets trades (the skip is
  `PUT /api/profile/trades` with the trades he has); backfilled from `created_at` for everyone with a
  trade. The PWA asks only when there is no stamp AND no trade (an old cached `me` has no stamp). It also
  separates «skipped» from «never got there» for the stats.
- **Email before name everywhere**: `useSenderGate` leaves an unverified master to the action's email
  gate (as the backend orders them) unless the action needs no email (act paper signature, act PDF).
- **The call number no longer lands on documents**: V193 `users.help_phone` (the V191 value copied
  over); the policy names it apart. The card shows once he is stuck (a day in, no object), checks the
  number (`lib/phone.ts`: Ukrainian in any usual spelling, or `+` international) and stores it
  normalised. The admin list gets «Оброблено» (`help_handled_at`,
  `POST /api/admin/metrics/help-requests/{userId}/handled?handled=`), open calls first; a new ask
  reopens a handled one.
- `ContractorName.actDisplay` (company → legal name → name) replaces the acts' two hand copies.
