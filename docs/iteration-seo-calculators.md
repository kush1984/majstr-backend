# SEO, step 1: link previews, a truthful llms.txt, two public calculators

**Status:** done 2026-10-14 (backend + PWA 1.54.0). **Source:** `C:\Work\prompts\seo-calculators-prompt.md`.
**No migration.**

## Why

To search engines Majstr was two addresses, `/` and `/privacy`, so it was found only by its own
name. Masters search «скільки клею на плитку», «калькулятор гіпсокартону». The calculators give
pages for those queries, and they answer with norms read off data sheets — which competitors' pages
do not have.

## One arithmetic, two doors

`POST /api/public/materials/calc` runs the estimate screen's own calculation. The core of
`MaterialCalculatorService.calculate` was lifted into `compute(List<CalcLine>, …)`; the estimate
turns its lines into `CalcLine`s, the public door builds them from a WHITE LIST, and both call the
same method — norms per position, the perimeter applied once per material, the allowance grown per
position, rounding UP to packages. A second copy of the formulas would drift, and the site would
then show a figure the app does not.

- **Anonymous by construction:** no owner, so the norm query (`owner IS NULL OR owner = :id`) reads
  the LIBRARY only, `pref()` reads no habit, the sheet keeps the dictionary's package. The one
  figure a visitor gives that a master gives as a habit — the tile joint — rides the same grout
  rescale (`Habits.anonymous`), including the wide-joint exemption.
- **White list (`PublicMaterialCalculatorService`):** `calculator` is `TILE | DRYWALL`; the request
  answers questions and never names a position. TILE = «Ґрунтування поверхні» + «Укладання плитки
  {100х100 … 1200х2400 мм, мозаїки, кабанчик}» (the format decides C1/C2 — no surface field: the
  library does not distinguish wall from floor). DRYWALL = «…на стелю рівну» (UD by PERIMETER) /
  «…на стіни» / the partition in 1 or 2 layers (two layers exist for a partition only — anything
  else is a 400). Joint tape and joint putty are NOT counted: the catalog counts them per metre of
  joint, not per m², and the page says so and points at the app. The Q2-Q4 finish levels were not
  added: since V121 they are bundles, not positions, and each starts with the joints.
- **Stores and logs nothing.** Bounds are 400s: area ≤ 10 000 m², perimeter ≤ 1000 m, joint ≤ 20 mm,
  waste 0-30 %.
- **Rate limit:** `PublicCalcRateLimiter`, `BucketRegistry` per IP, 30/min, 429 + `Retry-After`
  (`PUBLIC_CALC_MAX_PER_MINUTE`). The path is EXCLUDED from `PublicPortalRateLimitFilter`, so a
  visitor trying formats never spends the budget of a client signing an estimate from the same
  address. `ClientIp` is now the one reader of `X-Forwarded-For` for both.
- IDOR matrix: it skips `/api/public/` and routes without `{id}`, so nothing to add to `EXEMPT`.

Test: `PublicMaterialCalculatorIntegrationTest` — for EVERY tile format and every drywall job the
public answer equals `calculate()` on an estimate holding the same positions; a visitor's joint
equals a master's stored joint habit; every white-listed position still buys something; a ceiling
without a perimeter reports `PERIMETER` instead of guessing; anonymous 200, a stranger's token
changes nothing, bounds are 400s, the 31st request in a minute is a 429 with `Retry-After`.

## The pages (PWA)

- `kalkulyator-plytky.html`, `kalkulyator-gipsokartonu.html` — extra Vite inputs, emitted as real
  HTML (`dist/<slug>.html`; Cloudflare Pages serves `/<slug>` from it). H1, the form, «Як ми
  рахуємо», the typical-case table, CTA, the note; `WebPage` + `BreadcrumbList` JSON-LD (no FAQ —
  there is none on the page); their own 1200 × 630 cards. Script `src/seo/calcPage.ts` (≈5 KB, no
  framework) parses with the app's `parseDecimal` (moved to its own dependency-free module).
- **No number typed by hand:** `npm run seo:snapshot` asks the endpoint for each case in
  `scripts/seo-calc-cases.ts` and writes `src/seo/calc-snapshot.json`; a Vite plugin prints it into
  the HTML; `npm run seo:check` fails when the file and the live endpoint differ.
- **The service-worker trap:** `NavigationRoute` answered every navigation with the SPA shell, so a
  returning visitor's `/kalkulyator-plytky` would open the landing. Both paths are in
  `NAVIGATION_DENYLIST` (`src/sw.ts`), pinned by `seoPages.test.ts`.
- **No analytics on these pages:** PostHog captures only after a SIGNED-IN master's consent; an
  anonymous visitor cannot give one, so `calc_used` / `calc_cta_click` would never be sent and the
  SDK would be dead weight. Revisit if a consent banner for visitors is ever added.
- Link previews: `npm run og:images` renders `public/og/og-default.png` + one card per calculator
  from a real app screen; `index.html` now points `og:image` at it with `summary_large_image`.
- `llms.txt`: «120+ шаблонів» → «100+» (103 default bundles on V170), «1000+ робіт» kept (1081
  positions); a «Калькулятори» section. Sitemap, robots comment, landing footer links.

## Addendum 2026-10-15: the tile itself (V171)

The page's real question is «скільки плитки купити», and the engine counted everything but the
tile. The owner ruled that the app counts it too, from the same norm — no formula for the page.

- **V171** adds one material per format, `CERAMIC_TILE_<format>` («Плитка 300×600 мм»), unit m²,
  `package_size` = one piece's area, `package_name` «плитка»; and a 1,00 norm on each of the 25
  «Укладання плитки …» / «Укладання мозаїки» positions, `sort_order 0` so it is the first line.
  Per format because a bathroom mixes floor and wall tile and one summed «Плитка» cannot be bought;
  per piece because the engine's existing round-UP-to-a-package then IS the piece count. Mosaic,
  «кабанчик», plank and «більше 3200 мм» name no single size and stay in whole m². Allowance stays
  the master's/visitor's (`waste_percent` 0, V147's reasoning). No existing row changes
  (`TileCoveringNormsOnLiveDataIntegrationTest`), so adhesive, grout and primer on every existing
  estimate are untouched; masters see one new line.
- **Diagonal = 1,05**, V147's laminate figure (owner's ruling), as `DIAGONAL_LAYOUT`, pinned equal
  to that norm. The app reads it off the estimate's «Укладання плитки по діагоналі (плюс % до
  м.кв.)» line (POSITION base → that position, TOTAL → all); no line = straight, no new field. Only
  the tile scales; the page says so under the choice: «По діагоналі більше підрізки — додаємо 5 %
  до вашого запасу».
- **Boxes:** optional `boxArea` (≤ 10 m²) → `boxes` = ⌈m² with allowance ÷ box⌉ on the tile line,
  same `line()`; the app does not ask for it yet (open question, with «ялинкою»).
- **Page:** title/H1 «Калькулятор плитки онлайн — скільки плитки, клею й затирки купити»; the first
  paragraph answers it on the bathroom example (2×1,7 m, 2,5 m high, behind the bath too, minus a
  0,7×2,0 door = 17,1 m², 300×600, straight, 10 %), its figures printed from the snapshot case `bath`
  via `<!--SEO_EXAMPLE:bath-->`; result = the tile block (m² with allowance, pieces, boxes) then
  «Клей, затирка, ґрунт»; the button under it «Порахувати весь ремонт із кошторисом для клієнта —
  безкоштовно» (both pages share the script, so the drywall page says it too).

**Release note for masters:** «Калькулятор матеріалів тепер рахує й саму плитку: м² з запасом і
коробки». (In the app the box count appears once the box-size question lands — until then it is
м² and pieces.)

## Addendum 2026-10-15: «Своя норма з упаковки»

- **TILE only.** The main material is the adhesive (C1 or C2 by format). DRYWALL has no such field:
  the brief made it conditional on a Q-level choice and its finishing putty, and the drywall
  calculator has neither (joint putty is not counted), so `ownNorm` there is a 400
  (`error.public-calc.own-norm-tile-only`) rather than silently ignored.
- **Same mechanism as a master's own norm, no new formula:** `MaterialCalculatorService.OwnNorm`
  replaces the coefficient of materials whose code starts with `TILE_ADHESIVE_`, is never rescaled
  by a habit, and marks the arithmetic line `ownNorm = true` — exactly what an owned
  `material_norm` row does. Pinned by `aVisitorsOwnNormBuysWhatAMastersOwnNormBuys` (an estimate
  with the master's own row of the same figure buys the same list).
- **Bounds are relative:** 0,1-10× our coefficient for the chosen format
  (`libraryCoefficient`), else 400 `error.public-calc.own-norm-range`. Every white-listed adhesive
  norm is `QUANTITY`, so the unit is kg/m², written next to the field.
- **Page:** a collapsed «Своя норма з упаковки» under the adhesive line, one field «Клей з
  упаковки, кг/м²» with our figure as the placeholder; the line then reads «За вашою нормою 1,6
  кг/м²»; an empty field brings ours back; a refusal is shown inside that block. Beside the bottom
  CTA: «Збережи свої норми — і застосунок рахуватиме під тебе на кожному об'єкті.» The page now
  sends `Accept-Language: uk`, so server messages are Ukrainian whatever the browser's language.
- **Snapshot and tables use OUR norms only** — pinned in `seoPages.test.ts`.

## Still to verify on a Cloudflare preview deploy

`_redirects` ends in `/* /index.html 200`; static files are served before it (as its own comment
notes for `/assets`). `curl` the preview's `/kalkulyator-plytky` and check the raw HTML is the
calculator, not the SPA shell — if the catch-all wins, add explicit rules above it.

## Addendum 2026-10-28: backend-side SEO/Core-Web-Vitals audit

Backend half of a cross-repo SEO push (the PWA owns meta tags, sitemap, manifest, OG assets and
new landing pages).

- **gzip was off (fixed).** Spring Boot ships `server.compression` disabled and Railway proxies the
  app as-is, so every JSON body — including the public calculator's answer — went out
  uncompressed. Now on in `application.yml` for textual types; PDFs/images stay untouched.
  `ResponseCompressionIntegrationTest` pins it over a real socket. Caveat: `min-response-size`
  only bites when `Content-Length` is known before the write; a dynamically serialized JSON body
  is compressed whenever the client accepts gzip, whatever its size.
- **`Cache-Control`/`ETag` on `/api/public/materials/calc`: not applicable.** It is a POST
  (never cached by browsers/CDNs), and the figures an SEO page renders on first paint come from the
  build-time `seo:snapshot`, not a live call.
- **Crawler handling unchanged.** The quiet 404/406 in `GlobalExceptionHandler` cannot swallow an
  OG-preview bot: the backend serves no SEO-facing HTML (`api.majstr.pro` is `Disallow: /`; the
  landing, calculator pages, sitemap and `llms.txt` are PWA/Cloudflare Pages assets).
- **The calculator white list is a closed enum.** `PublicMaterialCalcRequest.PublicCalculator` is
  `{TILE, DRYWALL, PLASTER, SCREED}`. A new landing page around an EXISTING calculator needs nothing
  here; a new calculator DOMAIN (flooring, paint, profiles) needs an enum value, a position mapping,
  bounds and `PublicMaterialCalculatorIntegrationTest` coverage first — it cannot ship from the PWA
  repo alone.
