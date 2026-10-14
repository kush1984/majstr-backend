# Landing redesign (PWA 1.53.0)

**Status:** done 2026-10-14, PWA only. **Source:** `C:\Work\prompts\landing-redesign-prompt.md`,
mockup in `majstr-pwa/docs/landing-mockup/` (not committed).

- `LandingPage.tsx` rebuilt in the mockup's order: header (Можливості · Як це працює · FAQ ·
  **Увійти** · CTA; on a phone logo + Увійти + burger), hero (three lines, four checks, CTA, «Кошторис
  за 5 хвилин»), six feature cards (a list on a phone), four steps, «Для кожного…» (master /
  foreman / client), «Професійний вигляд…», FAQ (8th question: App Store / Google Play — it is a
  PWA), orange final banner, footer. No «Тарифи» (there is no prices section); no «Умови
  використання» (no such page — only /privacy).
- Owner ruling: `TradesStrip`'s nine trades stay as a visible line under «Для кожного…» (they are
  search queries); `ProblemSolution` is gone.
- Every phone is a REAL screen: `npm run landing:shots` (`scripts/landing-shots.ts`) starts Vite,
  answers every API call from fixtures via `page.route` (unanswered calls are listed), shoots five
  screens at 390 × 844 @3× and re-encodes them to WebP through a Chromium canvas
  (`public/landing/shot-{editor,share,portal,margin,money}.webp`). The portal is the backend's own
  `static/portal/index.html`, read from the sibling repo (`MAJSTR_BACKEND_DIR`). Demo data: «Квартира
  на Зубрівській», 48 750 ₴ = 10 000 + 12 500 + 15 000 + 11 250; crew 41 750 + margin 7 000.
- Hero photo slot: `HAS_HERO_PHOTO` in `LandingPage.tsx`, false until `public/landing/hero.webp`
  is supplied; until then the hero is the big phone.
- PRO badge: only «Заміри», driven by `TEMP_FREE_GETS_MEASUREMENTS_AND_ECONOMY` (off today).
- `index.html` static block rewritten to the new copy (it still said «Заміри (PRO)» / «Гроші по
  об'єкту (PRO)», both free today), FAQ JSON-LD gained the eighth Q/A, `llms.txt` updated.
- Tests: `LandingPage.test.tsx` — «Увійти», every CTA → /register, eight questions, none of
  «команд / компаній / аналітик / відгук» on the page, in index.html or llms.txt, and the FAQ
  JSON-LD equals uk.json word for word. `e2e/landing.spec.ts` updated (needs a running backend).

## Photos (PWA 1.53.2, `landing-photos-prompt.md`)

- Only `hero.png` exists so far (1536 × 1024). The originals live in `majstr-pwa/design/landing/`,
  OUTSIDE `public/`, so no PNG ships; `npm run landing:photos` (`scripts/landing-photos.ts`) writes
  `public/landing/<name>-{640,960,1280,1536}.webp` through Chromium's canvas (18-66 KB; no AVIF —
  the canvas cannot encode it).
- The hero is ONE `<img>` (`srcset`/`sizes`, `fetchpriority="high"`, `object-[72%_45%]`): under the CTA
  with rounded corners below `lg`, the right 54 % of the header edge to edge at `lg`, its left edge
  fading into the page. The big phone and `HAS_HERO_PHOTO` are gone.
- «Кошторис за 5 хвилин» is Caveat text plus an SVG arrow placed in % of the photo box — the crop
  keeps the phone at about (57 %, 59 %) of it at 390, 768 and 1440 px.
- The full nav now starts at `lg`: at 768 it wrapped. Card photos and `pro-bg` wait for their files.
