# Client portal — brand restyle (functionality unchanged)

- **Status:** ✅ Done (static HTML, no build needed). Verify locally with a real token.
- **Commit:** _(uncommitted at time of writing)_
- **Scope:** `src/main/resources/static/portal/index.html` — **presentation only**.
- **Goal:** Bring the public client portal into the Majstr brand (warm oak-bark
  `#3a2519` + bright amber `#f5821f`, the same palette as the app/landing), per
  `majstr-pwa/landing-portal-prompts.md` (Prompt 2). One consistent brand across
  app → landing → portal.

## What changed — and what didn't

**Changed: the `<style>` block only** (plus one cosmetic `<meta name="theme-color">`).
Every CSS selector kept its name because the page's `<script>` renders that exact
markup — so the visual layer was swapped with zero behavioural change.

**Untouched (byte-for-byte):** the whole `<script>` (token load via `?t=`, the
`/api/public/estimates/{token}` GET, `/sign`, `/question`, `/pdf`, money/date
formatting, unit labels, escaping, all states), the `#root` mount, and both
`<dialog>`s (sign + question) with their ids/inputs/`data-close`/button classes.
No backend code, `PublicEstimateService`, endpoints, or token generation touched.

## Design applied (from the shared design system)

- Page background warm paper `#f7f1e7`; cards white, soft 14px corners, thin warm
  border `#d9ccb5`, light shadow.
- **Contractor header**: dark oak-bark `#3a2519` band, light text, phone in
  monospace; the logo (`logoUrl`) sits on a small white chip so any logo stays
  visible on the dark band.
- **Section labels** (`h2`: «ОБ'ЄКТ», «РОБОТИ», «МАТЕРІАЛИ», «УМОВИ») — small
  amber monospace, letter-spaced (CSS `text-transform: uppercase`, JS text
  unchanged).
- Prices / quantities / totals — monospace with tabular figures so columns align.
- **«РАЗОМ»** row — bold, dark, strong top line.
- **«Підписати»** (the `.primary` action) — bright amber `#f5821f` background,
  dark ink text, prominent; secondary buttons (PDF, «Задати питання») are warm
  outline. Signed state = green badge; error/loading states restyled to match.
- Fonts: Inter (preferred) → system sans; JetBrains Mono → system mono. **No
  external font CDN** — pure system fallbacks keep this client-facing page fast
  and resilient on poor mobile connections.
- Mobile-first preserved: full-width action buttons under 480px, etc.

## Verify (with a real token — JS needs the API)

The page is data-driven; open it against a running backend:
`…/portal/index.html?t=<share-token>` (mint a token via the app's "share
estimate"). Confirm: estimate loads, all data present (contractor, object,
works, materials, sums, logo), **signing works**, question works, PDF link,
and the error (invalid token) / loading / signed states all render correctly,
incl. Cyrillic. With no token it shows the styled "Невірне посилання".
