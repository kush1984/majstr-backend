# Iteration — SEO: the sample estimate page and the brand line

- **Status:** built and verified locally, not pushed (GitHub write access pending), 2026-10-28.
- **Repos:** backend branch `master-18o5pt`; PWA branch `seo/zrazok-koshtorysu`, version **1.67.0**.
- **Migrations:** none.
- **Before the meetup (11 November):** this round and the earlier code-split/gzip round
  (`iteration-seo-calculators.md` addendum) are the SEO work meant to ship before it.

## Goal

Two cheap levers from the SEO brainstorm (the expensive one — public master profiles — is logged as an
open question): a page that answers «зразок кошторису на ремонт», and a brand line on every document a
master hands a client, so each shared estimate is also a way to find Majstr.

## 1. «Складено в Majstr · majstr.pro» (backend)

- `PdfBrandFooter` — a `PdfPageEventHelper` drawing the line, grey 7 pt, centred under the bottom
  margin of EVERY page, as a link to `majstr.pro/?utm_source=pdf&utm_medium=referral&utm_campaign=document_footer`.
  Installed by all three renderers: `EstimatePdfService`, `WorkActPdfService`, `ShoppingListPdfService`.
- **Shown to everyone, PRO included** (owner's call, 2026-10-28). PRO's logo stays where it was.
- **No digits in the line, on purpose:** several PDF tests compare a document's digits as a whole
  (`digitsOnly`), and a page number would shift them. The URL lives in the link annotation, not the text.
- **Signed documents:** they re-render from their snapshot (V150), so an old signed PDF downloaded
  today carries the line too. `doc_hash` is SHA-256 of the stored JSON, not of the PDF, so it is
  unaffected; the line is service branding, not a printed value of the agreement.
- Portal footer: «Технічний сервіс — Majstr» now links to `majstr.pro` (`utm_source=portal`); the
  client message page got the same footer (`utm_source=message`). The UTM rides the first-touch
  capture (V114) into the admin funnel, so the channel «a client of a master» is measurable.
- Tests: `PdfBrandAssert.assertBrandedOnEveryPage` (text on every page + the link's URI), one case per
  renderer; `ClientPagesBrandLinkTest` reads both static pages as text.

## 2. `/zrazok-koshtorysu` (PWA)

- A static SEO document like the calculator pages: extra Vite input, `NAVIGATION_DENYLIST` entry,
  sitemap, `llms.txt` (own «Зразки документів» section), linked from every calculator footer and the
  landing footer, its own OG card (`og-zrazok-koshtorysu.png`, `npm run og:images`).
- **Every position and price is the library's own:** the TILING bundle «Ванна кімната — стіни і
  підлога», each position's `catalog_templates.suggested_price` at V179 (read off a database migrated
  to the head, not grepped). Only the quantities are the example's (walls 17 m², floor 3,4 m²).
  The data lives in `src/seo/sampleEstimate.ts`; the build plugin prints the table, the count and the
  total (`<!--SEO_SAMPLE_ESTIMATE|TOTAL|COUNT-->`), summing in kopecks. Nothing is typed by hand.
- Mobile-first table: two columns, quantity × price under the name, so it fits 375 px.
- Visible FAQ + `FAQPage` JSON-LD, word for word (test-pinned). The page's one script records the
  visitor's first touch (`samplePage.ts`) — without it a master arriving from Google onto this page
  would be counted as «direct».
- Tests: `src/seo/samplePage.test.ts` (16).

## Not changed

- The calculator white list, the norms, the snapshot: the sample uses no calculator.
- The page claims only what the app does: templates (103 shipped), the master's own prices, the
  portal signature, PDF, the material calculator → shopping list, FREE = 2 objects without a card.

## Gotchas

- If the library re-prices or renames one of the 18 positions, `sampleEstimate.ts` does not follow by
  itself — re-read it off a migrated database (the session used Postgres in Docker + every
  `V*.sql` through `psql` in version order).
- «Зразок акта» (the act sample) is the natural next page; keep its wording off any legal-equivalence
  claim and off КБ-2в (open question «budget objects»).

## 3. `/zrazok-akta` (PWA 1.67.1, 2026-10-29)

- The FINAL act for the sample estimate: all 18 lines closed, one additional job from the library
  (TILING «Облицювання плиткою короба», 900 ₴/m², V179) in its own «ІІ. Додаткові роботи» part,
  the estimate's own 30 % advance offset, «До сплати» and the sum in words — the act PDF's shape and
  its statements verbatim (`WorkActPdfService`). Data in `src/seo/sampleAct.ts`, printed by the build
  plugin (`<!--SEO_ACT_DOCUMENT|PAYABLE|COUNT-->`); numbering runs on into the additional part, as
  the PDF's does.
- **«Сума прописом» is a new mirrored pair:** `HryvniaInWords` ↔ `src/seo/hryvniaInWords.ts`, the TS
  test carrying the Java test's cases verbatim; the sample's own two figures were cross-checked
  against the Java class in the session (identical).
- Wording rule kept: «Підтвердити приймання робіт», nothing about legal equivalence, no КБ-2в (a
  test forbids both); the closing note only says a budget institution may require its own form.
- Requisites are blank placeholders (РНОКПП, IBAN) and names are fictional, so the page cannot read
  as a real person's document.
- Wired like the estimate sample (Vite input, SW denylist, sitemap, `llms.txt`, OG card, every
  calculator footer, the estimate sample and the landing footer). Tests: `sampleActPage.test.ts`
  (17), `hryvniaInWords.test.ts` (4).
