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

## Deferred — prerender / SSR (open-questions)

The landing **body text** is still client-rendered. Wiring SSG/prerender
(`vite-react-ssg` etc.) into this Vite + `vite-plugin-pwa` + React-Router app
restructures the router/entry and is risky for a marginal gain in a weak SEO
channel — so it's **not done**, logged in open-questions. The static head already
gives Google the decisive signals (title/description/og/canonical/JSON-LD), and
Google does execute JS, so the page is indexable today; prerender would only make
body indexation more reliable.

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
