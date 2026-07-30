# Iteration: categories in the client portal

> **Retrospective doc.** Written during the 2026-07-27 catch-up from `359b27f`. The smallest of
> the catch-up batch, recorded because it changes what the CLIENT sees.

- **Status:** ✅ shipped
- **Migration:** none
- **PWA:** none (the portal is server-rendered HTML)

## What changed

`PublicEstimateItemView` now carries the line's **category**, and
`static/portal/index.html` groups the estimate by it — so the client reads «Плитка», «Сантехніка»,
«Електрика» instead of one flat list of forty lines.

This closes the loop opened by the two changes before it: the catalog rework (V72) made categories
meaningful rather than trade-name noise, and the ordering work let the master arrange lines within
and across them. Without this, all of that stopped at the app boundary and the client still got a
flat wall of text.

## Worth noting

- **The portal has its own check script.** `src/test/resources/portal-check.mjs` (+76 lines) —
  the portal is hand-written HTML/JS with no build step and no component tests, so a scripted
  check is the only regression net it has. If you touch `portal/index.html`, run it.
- `PublicEstimateService` exposes category on the view; nothing else about the public contract
  changed, and the portal keeps rendering per visible estimate as before.
- `application.yml` picked up a related config line in the same commit.

## Gotchas
- The portal renders for **both** token families (project token `?p=` and the legacy per-estimate
  `?t=`). A change that only works for one of them looks fine in testing and breaks links already
  sent to clients.
- Category is display-only everywhere — including here. Don't start matching on it.
