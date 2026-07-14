# SEO / Google indexation (landing hygiene)

- **Status:** ✅ Done. PWA `vite build` green; backend is a one-line security
  allowlist + two static files (pending `./gradlew build`).
- **Migration:** none.
- **Goal:** Make majstr.pro findable in Google for direct/brand queries and look
  legit when shared. Expectations are realistic — SEO is a weak channel in this
  niche; this is hygiene, not a growth driver (the channel is personal contact).

## Recon (what already existed)

- Meta in static `index.html` (no react-helmet, so crawlers see them without JS):
  `<html lang="uk">`, title, description, `og:title/description/type`.
- No `robots.txt`, no `sitemap.xml` (neither repo).
- No prerender — Vite has only `react` + `vite-plugin-pwa`; the built `index.html`
  is `<div id="root">` + JS (head meta are static; landing body is JS-rendered).

## What shipped

### PWA (majstr.pro)
- `index.html` head completed: **canonical** `https://majstr.pro/`, `og:url`,
  `og:site_name`, `og:locale`, `og:image` (icon-512, with width/height), Twitter
  card, and **JSON-LD** `SoftwareApplication` (free BusinessApplication, uk).
  Verified present in the built `dist/index.html` — Google reads them without JS.
- `public/robots.txt` — allows `/` (the landing), disallows the auth-gated app
  routes (`/login /register /verify-email /projects /catalog /profile /new
  /estimates`), links the sitemap.
- `public/sitemap.xml` — just the homepage.

### Backend (api.majstr.pro)
- `static/robots.txt` — `Disallow: /` (the API domain serves the API, the
  **token-bearing client portal**, and admin — none should be indexed).
- `SecurityConfig` — `/robots.txt` added to `PUBLIC_PATHS` (else it 401s and
  crawlers can't read it → they'd assume crawlable).
- Token portal `static/portal/index.html` — `<meta name="robots" content="noindex,
  nofollow">` as a second layer beyond robots.txt (a portal URL with a token must
  never be indexed even if discovered).

## Follow-up — crawlable body without SSG (static first-paint shell)

Rather than wire a full SSG/prerender pipeline (risky in Vite + `vite-plugin-pwa` +
React-Router), the landing **body content** is now crawlable via a **static
first-paint shell inside `#root`** in `index.html`: a semantic `<h1>` + hero lede +
"Що всередині" feature list + trade keywords + CTA links, hand-written to mirror the
rendered landing. `main.tsx` uses `createRoot(#root)`, which **replaces** the shell
on mount — so the same HTML is served to everyone (no cloaking), and crawlers /
no-JS clients get real copy + headings without executing JS. Verified: built
`dist/index.html` contains the `<h1>` and the feature list; the dev server renders
the full React landing over it with **no console errors** (clean createRoot replace).
Also added an **`Organization`** JSON-LD block (brand queries → the site).

This closes the practical gap the earlier "prerender deferred" note described,
without the SSG risk. A true SSG/prerender remains possible later but is unnecessary
for this weak channel.

## Verify
- Built `dist/index.html` `<head>` contains the title/description/canonical/og/
  JSON-LD (confirmed). `dist/robots.txt` + `dist/sitemap.xml` present.
- Private app routes + the client portal are kept out via robots + (portal) noindex.
- After deploy: `curl https://majstr.pro/robots.txt`, `…/sitemap.xml`,
  `curl https://api.majstr.pro/robots.txt` (must be 200, not 401).

## Manual next steps (Google Search Console — can't be automated)
1. search.google.com/search-console → Add property → `majstr.pro`.
2. Verify ownership via Cloudflare DNS TXT (easiest).
3. Sitemaps → submit `https://majstr.pro/sitemap.xml`.
4. URL Inspection → `https://majstr.pro/` → Request indexing.
5. Wait — new-domain indexation takes days/weeks. Then check `site:majstr.pro`
   (no private paths should appear).
