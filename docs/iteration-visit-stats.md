# Iteration — anonymous visit statistics («👀 Відвідування»)

**Status:** built 2026-11-05. Backend `clean build --no-build-cache` green (2378 tests, 102 integration suites,
none skipped); PWA lint, both type checks, vitest (1738) and build green; e2e 5 and offline e2e 8 green on a
real backend; the beacon and the admin view checked in a browser at 375 px. **Migration:** V187 `site_visits`.
**PWA:** 1.70.0.

## Goal

The owner asked whether the admin could show people who opened the landing and never registered, «and as
much as possible about who they were». Until now such a visitor left no trace: PostHog starts only after
the privacy consent at registration, and the first-touch source reaches the server only with a
registration, so the admin knew where sign-ups came from but not how many visits they came out of.

What was agreed: anonymous counts, sources, pages, devices and a visit → registration funnel, with
conversion per channel. **Not** who a visitor is. That is not knowable without the person typing it, and
fingerprinting without consent is refused by the privacy policy and by EU law alike.

## What shipped

### The beacon (PWA, `src/lib/visit.ts`)
- `sendVisit(kind, path)` posts `VisitRequest` to `POST /api/public/visit` with `keepalive` and
  `credentials: 'omit'`. It sends the stored **first touch** (`getStoredFirstTouch`, `getStoredUtm`), the
  very values registration sends, plus the browser's IANA time zone and language.
- **Sends nothing** for a signed-in master (`tokens.hasAny()`), or when the browser asks not to be tracked
  (Global Privacy Control, `doNotTrack = '1'`). Never throws.
- `trackPublicVisits(router)` in `main.tsx`, after `captureFirstTouch` so the very first view already
  carries its source: the page the SPA opened on and every navigation after, for `/`, `/login`,
  `/register`, `/privacy` only. Token pages (`/reset-password`, `/verify-email`) and the app are never sent.
- The SEO pages are separate documents: `calcPage.ts`, `samplePage.ts` and `wallAreaPage.ts` call
  `sendVisit('PAGEVIEW', location.pathname)` after their own `captureFirstTouch`.
- `RegisterPage`: the form's first `input` event sends `REGISTER_STARTED` once per page. Only that it
  happened, never what was typed.

### The backend
- `PublicVisitController`: always **204**. `VisitRateLimiter` (60 a minute per IP, `BucketRegistry`); over
  it the row is dropped without saying so. Excluded from `PublicPortalRateLimitFilter`.
- `SiteVisitService.record` drops a bot, an unknown kind or a malformed path. `VisitorAgent` reduces the
  browser string to three words (`device`, `os`, `browser`) and refuses crawlers, link previews and
  scripts; the in-app browsers (TikTok, Instagram, Facebook) are named first, because they often send
  no referrer and their browser is then the only sign of the channel.
- Paths and hosts go through `FirstTouch` (path without query, host without `www.`), UTM is trimmed the
  way registration trims it, the language keeps only its primary subtag, and `Europe/Kiev` (with the two
  zones merged into it) is stored as `Europe/Kyiv`: a Windows browser in the local check still sent the old name.
- **No address is stored.** `visitor` = the first 16 hex of SHA-256(day salt ‖ IP ‖ 0 ‖ User-Agent). The
  salt is per **Kyiv day**, one row in `site_visit_salt` (first writer wins, so restarts and a second
  instance agree), and `purge()` deletes every salt older than today at 00:05 Kyiv, so a visitor is
  counted once a day and cannot be recognised the next. Rows live **400 days**.
- `GET /api/admin/metrics/visits?period=30d` → `VisitStatsResponse`, at most 366 days.

### The report
- **A visitor is one browser on one day** («visitor-day»), the sum of daily uniques.
- **Each visitor-day is taken once, by its first row** (`DISTINCT ON (day, visitor) … ORDER BY occurred_at,
  id`), and counted along every dimension in ONE pass with `GROUPING SETS`; `GROUPING()` tells a NULL
  bucket («без реферера») from «not this dimension».
- Funnel: visitors → opened `/register` → typed into the form → registered (masters with `role = USER`
  registered in the period).
- Channels (referrer host, UTM source, entry page) put visitors beside the registrations keyed by the first
  touch they registered with, so the conversion compares like with like. **Registrations count from
  `registeredFrom`**: the period's first day, or the first day any visit was counted if that is later. A
  month of sign-ups against one day of visits read as 1200 % in the local check; the page names the date.
- Also: pages (views and visitors), device, system, browser or app, time zone (in place of a city), language.
- Admin page: a «👀 Відвідування» view with a period select, the funnel tiles, a Chart.js bar chart of
  visitors and registrations per day, and the tables, every ratio with its denominator.

### Privacy policy
Section 3 gains a paragraph: what is counted before registration, no IP and no cookie, the daily code,
13 months, and that GPC / DNT send nothing. «Останнє оновлення» moves to 05.11.2026.

## Not changed / confirmed
- PostHog still starts only after consent; nothing here touches it.
- The first-touch storage (`referral.ts`) and what registration sends are unchanged.
- No `RateLimitProperties` component: the limit is a constant, so no record fan-out.

## Worth knowing
- **Unique counts depend on the real client address.** If Railway handed every request one address, every
  browser with the same User-Agent would be one visitor. See the open question «Does `ClientIp` see the
  real client address on Railway?»; a day whose visitors are close to 1 while page views are many is the sign.
- Cloudflare Web Analytics is also injected at the edge today (PWA open question «Cloudflare Web Analytics
  beacon vs the CSP»). With these numbers in the admin, turning it off is the simpler way out of that question.
- A local e2e run against a local backend leaves rows in that LOCAL database: current Playwright Chromium
  sends a plain Chrome browser string. Production never sees them; CI runs no backend.
- The admin page is about 9 px wider than a 375 px phone in every view (the container's padding), and the
  dashboard's tables much more. The visits view adds nothing to that; left as it was.
