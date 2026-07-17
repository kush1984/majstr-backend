# Iteration: landing copy v2 (uk + en) — aligned with what actually ships

The landing still sold the v1 product ("estimate in minutes, client signs online").
Since then the app grew measurements + complex shapes + sketch/receipt/estimate
recognition + object economy. This rewrites the copy (SEO meta, the crawler shell, and
the whole `landing.*` block, uk+en) to match reality — and, more importantly, to be
**honest about what FREE actually includes**.

- **Status:** 🔨 PWA-only, code complete — tsc / 131 tests / build green, mobile verified.
- **App version:** PWA `0.13.0 → 0.13.1` (copy/polish → patch).
- **Migrations:** none. **Backend:** untouched.

## The honesty problem (the prompt was wrong — corrected with the user)

The source prompt asserted *"FREE = 2 objects with FULL functionality (portal, signing,
basic PDF)"* and framed the whole page around measurements + recognition + economy. But
`PlanConfig` says FREE gets **only** `CLIENT_PORTAL`, `ONLINE_SIGNATURE`, `PHOTO_REPORTS`
(2 projects, 3 estimates each, 5 photos). So **3 of the 4 headline benefits are PRO-only**:

| Landing benefit | Plan |
|---|---|
| 1. Заміряв раз — тягнеш у всі позиції | **PRO** (`MEASUREMENTS`) |
| 2. Сфоткав — внеслося саме | **PRO** (`SKETCH/RECEIPT/ESTIMATE_IMPORT`) |
| 3. Клієнт бачить і підписує онлайн | FREE |
| 4. Видно реальний заробіток | **PRO** (`OBJECT_ECONOMY`) |

A master arriving on "Заміряй об'єкт…" would hit the paywall on step one — exactly the
failure the prompt itself warned about. **User's decision: be honest** — an unobtrusive
`PRO` badge on benefits 1/2/4 and a hero micro-line naming what FREE really is. (The
alternative — moving MEASUREMENTS into FREE — was offered and declined.)

**Also stale:** the comment at `PlanConfig:32` claims *"Only BRANDED_PDF and AI_ASSISTANT
stay paid"* — untrue since measurements/economy/imports became PRO. Flagged, not touched
(out of scope for a copy change).

## The numbers (verified against the DB, not guessed)

The prompt claimed "670+ works, 100+ templates". Counted on the live dev DB (schema V58 —
the same Flyway migrations run in prod):

| Claim | Reality |
|---|---|
| 670+ works | **962 works** (+315 materials = 1277 catalogue positions) |
| 100+ templates | **140** default estimate templates |
| 8 trades | **9** — METAL (Металоконструкції, 66 positions) was missing |

Landing now says **"960+ робіт"** and **"140 готових шаблонів"** (rounded down, so they
stay true as the catalogue grows), and the trades strip lists all 9.

## What changed

**`index.html`** — title ("Majstr — кошторис за 5 хвилин: заміри, ціни, портал клієнта"),
description, og/twitter title+description, the `SoftwareApplication` JSON-LD description,
and the **static first-paint shell** inside `#root` (the crawler's copy): new H1, the
5-bullet "що всередині" with PRO markers, a trades line, and the honest free-tier note.
Verified the shell survives into `dist/index.html`.

**`landing.*` (uk + en)** — hero (title/lede/note), trades (5 keys → 9 + `tradesNote`),
problem/solution (`problemTitle` → "Знайомо?", new bad1-4/good1-4 mapped onto the existing
comparison grid), 4 benefits, 3 steps, final CTA. Key parity checked: **70 = 70**, no orphans
(`tradeAny` removed everywhere).

**`LandingPage.tsx`** — the trades strip renders 9 + a note; `Features` gained a `pro` flag
per card rendering the `PRO` badge. No structural redesign — the prompt kept the existing
section flow.

## Not changed / confirmed
- **Notes are NOT mentioned** — the tab isn't in prod yet (verified in the rendered page:
  no "Нотатк" anywhere).
- Tone: recognition is sold as removed routine ("сфоткав — внеслося"), never as "ШІ/AI".
- Address form stays **"ти"** (imperative), matching the existing landing and the onboarding deck.
- The phone mockup (`mockup*` keys) is untouched — still accurate.
- Backend, features, tests — untouched; this is copy + two small markup additions.

## Verified
- tsc clean, **131/131** tests, `vite build` green; both locales parse; uk/en key parity 70/70.
- Mobile 375px: no horizontal overflow, H1 renders, 9 trades wrap cleanly, 3 PRO badges,
  hero note + final CTA show the real numbers, console clean.

## Still on the user (from the prompt's pre-publish list)
- **og:image** — still the 512 icon, not a promo image.
- **Onboarding deck** (`majstr-onboarding.pptx/pdf`) — has neither measurements nor the LLM features.
- **Search Console** → Request indexing for "/" (the meta changed).
- Liga/rabotniki posts should link `majstr.pro/liga`, not the bare domain.
