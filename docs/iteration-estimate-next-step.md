# Iteration: the estimate editor's next step (estimate-editor-redesign)

**Status:** code complete, backend build green, PWA gate green. NOT pushed (awaiting approval).
**Source:** `Claude outputs/estimate-editor-redesign.md`.
**Migrations:** none. One nullable field added to an existing response DTO.
**PWA:** 1.40.0 → 1.41.0.

## Why

A field report, not a hypothesis. **90 masters created an estimate; 13 shared one — 14 %.** One of
them wrote in as many words that he «не розібрався, що є фаб батон який має меню всередині». The
sharing action was never missing: it was the *seventh* pill of a speed-dial behind a «＋» button,
and «＋» reads as «add». Everything occasional in the editor lived there — share, PDF, dictation,
receipts, duplicate, save-as-template — so a master who did not open the drawer had a finished
estimate and no visible way to send it.

Also the precondition for the material calculator (prompt B): the calculation has to be launched
from somewhere, and «somewhere» is this block.

## What shipped

### 1. `EstimateNextStep` — the block that closes the editor

`src/features/estimate/EstimateNextStep.tsx`, rendered at the END of the left column, after the
positions and the receipts. Only when `items.length > 0` — with an empty list the
existing `EmptyState` already says the one thing to do.

| Status | Heading | Primary | Secondary |
|---|---|---|---|
| DRAFT | «Кошторис готовий?» | 📤 Надіслати клієнту | 🧮 Матеріали до закупівлі · 📄 Зберегти PDF |
| SENT | «Надіслано. Чекаємо підпису» | 🔗 Показати посилання | same |
| SIGNED | «Підписано {дата}» | ✅ Створити акт | same |
| REJECTED | renders exactly as DRAFT | | |

**In the scroll, not sticky and not fixed.** Pinned to the viewport it would fight the mobile
summary sheet and the FAB for the same thumb zone — three floating things on one screen. In the
scroll it is simply the end of the document, which is where «what now» belongs.

**Exactly one primary action per state**, full width, ≥48 px; the other two are quiet text rows at
44 px. A test asserts the button COUNT is three — a fourth means a second loud action crept in and
the block has stopped answering the question in its own heading.

### 2. The FAB is one action again

`Fab` gained an `onClick` prop: pass it instead of `children` and the button acts instead of opening
a menu (no scrim, no rotation, no `aria-expanded`). The editor now renders
`<Fab ariaLabel="Додати позицію" onClick={…} />`, and only when the estimate is not signed — a
signed estimate cannot gain positions, and everything else it could offer moved elsewhere.

### 3. The header ⋮

The ✏️ button folded into an `ActionMenu` (the existing portalled row menu, reused unchanged), which
now carries every secondary action: ✏️ Перейменувати · ☑ Видалити позиції · 📑 Дубль ±% · 🎤
Диктувати · 🧾 Чек · 📤 Поділитися · 📄 PDF · 📋 Зберегти як шаблон. Header is now ← / title /
badge / ⋮ — one control fewer than before, not one more.

Every show condition was carried over verbatim (`!signed`, `items.length > 0`, `canDictate`), and
the `guard(...)` online wrapper stays on the four actions that reach the server.

**The duplicate 📄 is fixed**: «Дубль ±%» is 📑, the PDF keeps 📄. They were the same icon in the
old drawer.

### 4. `signedAt` on the estimate response (the one backend change)

`Estimate.signedAt` already existed and is stamped by `PublicEstimateService.doSign`; it simply was
not exposed. Added to `EstimateResponse` after `updatedAt` (and to `api/types.ts` as `?:`-optional,
read with `!= null`, per the `non_null` rule). Without it the SIGNED heading could not say a date,
and `updatedAt` is not the same fact — a `countInEconomy` toggle moves it.

There is no `sent_at` column, so the SENT heading carries no date. Adding one is a migration and was
out of scope for a PWA iteration.

## Decisions taken during the work

**«Нагадати клієнту» was dropped, not implemented.** The source prompt gave SENT the primary action
«🔔 Нагадати клієнту». No such flow exists — the cheapest implementation would have re-sent the
identical share email — and the master's call was to drop it: «не треба нічого ще раз нагадувати
поки немає такого запиту». That left SENT with no primary action, so it got **«🔗 Показати
посилання»**, which opens the same `SharePortalSheet` the DRAFT action does. It is the field report's
own complaint answered directly: the master who wrote in never found the link, and Ukrainian masters
nudge a client in Viber or Telegram, not by email. Logged DEFERRED in `docs/open-questions.md`.

**REJECTED gets no screen of its own, because nothing can produce it.** `EstimateStatus.REJECTED`
exists, but the only write is `EstimateService.updateEstimate` — a hand-made `PUT`. The client portal
has no reject action (`static/portal/index.html` contains no `reject`), and the PWA ships only the
type, a `danger` badge colour and the label «Відхилено». So REJECTED renders as DRAFT: the estimate
is editable and re-sendable, one branch, no dead UI. Whether the missing door should exist at all is
now its own OPEN item.

**The materials action points at the shopping list, not at a calculator.** «🧮 Матеріали до
закупівлі» navigates to `routes.shopping(projectId)` — V126's list, which is where the calculator's
answer will land anyway. The label is true today and stays true after V127; only the destination
gains a calculate step.

**Chunk 3's second entry point is deferred to V127.** The prompt also asked for the expanded summary
sheet's «Матеріали» row to read «Порахувати з робіт →» when materials = 0 and works > 0. That
sentence promises a calculation that does not exist yet, so it ships with the calculator rather than
as a link to a manual list. (Worth noting either way: the collapsed sheet shows only «До сплати», so
that row is invisible until the master expands it — it was always the reinforcement, never the
entry point.)

## Tests

`EstimateNextStep.test.tsx`, 11 cases: one per state (DRAFT / SENT / SIGNED with and without a date
/ REJECTED-as-DRAFT), the button-count invariant across all four states, the two secondary
callbacks, the absence of a «Нагадати» button (so the decision is recorded where it would be undone),
and the tap-target floor (48 px primary / 44 px rows, both full width) — jsdom computes no layout, so
that is asserted on the declared classes.

## Gate

- Backend: `./gradlew build` — **BUILD SUCCESSFUL** (the `signedAt` field plus seven test
  constructors; see the record/constructor fan-out rule).
- PWA, the full CI mirror in order: `npm run lint` → `npx tsc -b` → `npm run typecheck:tests` →
  `npx vitest run` (**120 files / 880 tests green**) → `npx vite build`.

## Not verified

**The 375×812 visual check did not happen.** A throwaway public route was stood up on a dev server
to render the three states, but the Chrome extension's screenshot injection timed out on every
attempt (five, across two tabs), so nothing was ever seen. The route and its file were removed and
`routes.tsx` is back to exactly its pre-check state. What IS verified is structural: no fixed
widths, no horizontal overflow risk, the tap-target floor asserted in a test, and the block sits
inside the page's existing `pb-44`, which already clears the summary sheet and the FAB. The layout
still wants a human eye on a phone.

## Not changed

The share sheet, the PDF flow, the dictation and receipt sheets, the selection bar, the summary
sheet's contents, the items board, and every online guard — all reached from new places, none
rewritten.
