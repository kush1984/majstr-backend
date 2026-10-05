# The PWA half of review rounds 2 and 3, 2026-10-05

**Status:** built and green on both repos. Source: `C:\Work\prompts\FIXES-2.md` §4-§5 (P-18…P-32) and
`C:\Work\prompts\FIXES-3.md` §5-§6 (P-36…P-52). With this, **all three FIXES files are closed on
both sides** except the items that have their own entries in
[open-questions.md](open-questions.md) — **B-76** (`doc_hash` is not reproducible), B-73/B-78/B-84's
remainders, and the B-75 shared parity fixture.

The backend was touched in ONE place, and only because the PWA could not carry its answer otherwise:
`POST /api/acts/{id}/receipts` now accepts `fiscalFn` + `fiscalId`.

---

## The shape this round turned out to have

Three bugs written many ways.

**A figure recomputed from what is on screen, when the screen is not the whole answer.** The cash
totals summed `entries` — but the YEAR tab asks the server for MONTHS and carries no entries at all,
and a cut list holds 500 of the rows while the totals cover them all. Adding one row set «Прийшло»
to that row's own amount. The act editor's «Разом» had the same shape and was fixed in round 3's §0;
this round finished the family off (P-21, P-32's summary patch).

**A separator that is also a decimal point.** «33,3, 33,3, 33,4» split on commas is six numbers and
a 109 % payment split (P-49) — the same bug V145's B-49 fixed in the calculator's per-position
parameter, four weeks apart, in two repos.

**A guard that reads a snapshot instead of asking.** The focus refetch fired while the outbox still
held the master's lines and overwrote them (P-42); the act's sign guard trusted a React state map
loaded a tick after render (P-36); the picker's trade branches were built from the SEARCH RESULTS, so
a search that hid a branch re-filed the rows that remained (P-28). In each case the fix is to ask the
thing that knows — the queue, the dataset — rather than the copy nearest to hand.

---

## §4-§5 of round 2 (P-18…P-32)

### P-18 + P-31 — the outbox knows when it is too late
`dropPendingEntity(entity, id)` drops EVERY queued op of a row whose create is still waiting. Add a
row offline, tick it, delete it: dropping only the create left the tick queued, and it replayed as a
PATCH on a row the server had never heard of — a 404 the master then had to resolve in the sync
sheet, for a row he had already thrown away. Used by the shopping list and by «Мої гроші».

Beside it, an `inFlight` set of seqs: the window between «the request left» and «the op was
deleted». `patchPendingCreate` and both drops answer `false` for an op whose POST is already gone —
editing its payload then changes nothing the server will ever see, and dropping it would leave the
row ON the server with nothing left locally to delete it with. In memory rather than in IndexedDB on
purpose: only the tab that is flushing can have a request in the air, and a reload ends every one.

### P-19 — a week that crosses a month
`to` was a copy of the ANCHOR, so «31 August + 6» was counted in the anchor's own month: Wednesday
2 September asked the server for **31 Aug – 7 October**. WEEK is the default tab, so that was one
week in four. Now built from where the week STARTS, with the Sunday, month-boundary and year-boundary
cases pinned.

### P-20 — an amount nobody typed
`parseDecimal` answered `NaN` for «12а», «1.200,50» and a Unicode minus, and `NaN` fails neither
`== null` nor `<= 0`. Online the server refused it after the sheet had closed and the text was gone;
OFFLINE the `NaN` went into IndexedDB, rendered «NaN ₴», poisoned the totals and blocked the queue
hours later. `parseMoney` (P-35's helper) with the server's own bounds, and the sheet now **waits for
the write**: a refusal belongs under the field he has to fix, not in a toast over a screen he has
already left.

### P-21 + P-32 — the cash figures move by a DELTA
`applyCashDelta(flow, {removed, added})` adjusts the four figures by what the row contributes, and
`summaryDelta` does the same for the home strip — which used to be RESTORED but never patched, so
the strip and the screen disagreed about one number for the length of a refetch. `earned` is still
ONE subtraction (B-33).

### P-22, P-23, P-29 — the calculator
- «до списку» is disabled while the answer is being re-fetched: `keepPreviousData` leaves the
  previous rows on screen, and switching waste 10 % → 15 % then sending at once sent the 10 %
  figures under a screen that already said 15 %.
- The habits card **refuses to render a form it has no answers for**, and sends only the fields the
  master MOVED. With the GET failed, `isLoading` was false, `draft` was null, every input rendered
  empty — and one tap on «Зберегти» told the server to forget every habit he had. The bands are the
  server's own (`MaterialPrefs`, B-19), asked here so the refusal lands under the field.
- **A bound per QUESTION**, mirroring V145: thickness ≤ 150 mm, section ≤ 5 m, perimeter ≤ 1000 m.
  `badQuantity` gained the server's `@Digits(12, 3)`. `Math.ceil(2.1 / 0.3)` is 8 in binary floating
  point, so the package count subtracts an epsilon first.

### P-24 — «already answered» is a value, not a comparison
The batch's read compared each field against «what we saved a moment ago», which is not the same
question for a row that had been QUEUED: its local snapshot is the placeholder, so the server's
«Чек №N» read as an edit and beat the label read off the paper, while a `null` date against an
absent one read as an edit too and dropped the date just read. Now: the server's creation DEFAULT is
what «unanswered» means (amount 0, no date), and the LABEL — the one field with no sentinel — is
answered by where a label can have come from.

### P-25 + P-44 — what session replay may never record
`.ph-mask` on the object card, the estimate's client banner, both summary surfaces (which carry
«Бригаді / Твоя націнка» since the crew-margin round), a note's phone number, and the whole «Мої
гроші» screen and its home strip. And on **`Modal`, `InfoPopover` and `ActionMenu`** — all three
render through `createPortal` into `document.body`, so they sat OUTSIDE every mask their caller had
put around the screen: payment sheets, receipt forms, the markup preview and every confirm dialog
were recorded in full while the screen behind them was redacted. `Modal`'s `mask` prop defaults to
ON, so a sheet added next year is private unless somebody decides otherwise.
`src/lib/replayMasks.test.tsx` asserts it on the rendered DOM — the failure mode is a recording that
looks perfect and holds what it should not.

### P-26 — the microphone respects the field's cap
`appendSpoken` clamps to `MAX_TEXT` and «Розпізнати» is disabled past it. The textarea had
`maxLength`; the mic walked straight past it and the server answered 400 with nothing on screen
saying why.

### P-27 — the cash screen
The window is named under the tabs; the prefetch primes the WEEK as well as the MONTH (the screen
opens on the WEEK, so cold offline its own query errored and a queued entry was invisible); an
object expense offers only the three buckets `object_expenses` actually stores; the delete asks
first; an optimistic row is inserted BY DATE (a back-dated one opened a second group for a day
already on screen, with a duplicate React key); a cleared date sends `null`, not `''`; a month label
is parsed as a LOCAL date (`new Date('2026-03-01')` is February west of UTC); and an edit or delete
of a row whose create is still queued folds into that create.

### P-28 — the picker's trade branches
Built from the whole dataset, not from the filtered view: a search that hid every drywall row also
removed the DRYWALL branch, so a position both trades ship appeared under the other one — and
`CatalogFiling` then filed the line there.

### P-30 — the contract test
`extends` is part of an interface header, so `WorkActUpdateRequest extends WorkActCreateRequest` —
the DTO the act editor PATCHes every money field with — was outside the contract entirely. A nested
backend record is reached through a one-line name map (`TemplatePickRequest` ↔
`ApplyTemplatesRequest.TemplatePick`). `foo: string | null` no longer counts as a field the backend
can REQUIRE. Every `*Request` the api layer mentions must be in the snapshot. And the staleness check
takes `MAJSTR_BACKEND_DIR` and **warns** instead of skipping in silence.

### P-32 — the nits worth doing
An empty percent field is no longer «0 %» on either sheet (it confirmed a copy at the same prices
while saying it had marked them up); the toggle carries `aria-pressed`; the toast CARD takes no taps
and only its two buttons do (at `z-[70]` it sits over a sheet's own Save row); `label:active` no
longer dims a whole field because a tap landed inside it; `-webkit-touch-callout: none` skips
`tel:`/`mailto:`/`t.me` links, where a long press is the point; «Створити шаблон» undoes itself if
the master walks straight back out of the editor; `noopener` beside `noreferrer`; and
`i18nKeys.test.ts` names the keys its regex cannot see.

---

## §5-§6 of round 3 (P-36…P-52)

### P-36 + P-37 — nothing leaves this screen behind the master's back
A receipt still in the queue is money on screen that a server-side signature would leave out of the
document, out of its `doc_hash` and out of the ADDENDUM — the screen said 4 800 ₴ and the client
accepted 4 000 ₴. So Sign, Share and PDF all go through one gate: flush, and refuse by name what is
left. A **refused** (blocked) receipt is a different answer and gets a different one — it is listed
as «сервер не прийняв» and counted nowhere, because flushing will never move it.

Share and PDF also **save first**: the share sheet publishes on open, and the PDF fetch is a GET, so
a master who corrected a quantity and tapped «Поділитися» sent the client a link to the old figures
— signable — and checked it against an old PDF.

### P-38 — an open editor learns the client signed
`useAct` and `useActProgress` are `CLIENT_DRIVEN_QUERY` like the list, the push invalidates `['act']`
and `['act-progress']` (which `['acts', projectId]` does not prefix-match), and a signature landing
while the form is seeded re-seeds from the server and says that unsaved changes did not make it in.

### P-40 — the advance is work-only money
`received` includes the receipts ticked «повернення за матеріал». A 2 000 ₴ material refund on a
3 000 ₴ act offered «Зарахувати 2 000» and printed «До сплати 1 000» on a document the client then
signed, for work nobody had paid for. The split is the server's own `MaterialRefundSplit.workPaid`,
re-derived from the two figures the economy already sends.

### P-41 — a percentage is queued as the PRICES it produces
«+10 %» is not idempotent: the server applied it, the answer was lost on a dying link, the op stayed
queued, and the next flush applied it again — +21 %, on a sheet already shown to a client. Offline
the markup now queues one ordinary line update per affected row, carrying the target price computed
through the same `markedUpPrice` the server rounds with. Idempotent by construction, through an
endpoint that already exists. The old handler stays registered: a master's phone may still hold an
op written by the build before this one.

### P-42 — the focus refetch waits for the queue
`refetchOnWindowFocus` is now a function that answers `false` while anything is pending or flushing.
The flush runs on the same `visibilitychange` and the GET usually wins, so server state without the
queued ops landed straight over the optimistic cache: the master walked back in and his three lines
were gone, so he typed them again — and the replay then landed the originals too. Deliberately the
WHOLE queue, not «ops for this key»: an op names an entity id, not a query key, and a wrong mapping
would fail in the direction that costs work.

### P-43 + P-46 — one format rule for money
`formatMoney` shows kopecks when there ARE kopecks. It rounded to whole hryvnia everywhere, which
lied in two directions at once: a 0,40 ₴ unit price read «0 ₴/шт» and 12,50 ₴ read «13 ₴/м²» beside
an exact «125 ₴»; an estimate total showed «12 346 ₴» where the PDF the client signs says 12 345,50;
and the economy panel's rounded parts did not add up to its rounded sum. The cash screen and its home
strip now use the same function, so the two cannot disagree.

### P-39's remainder — the last three rounding sites
`catalogItemSchema.parsePrice`, the catalog import's `parseMoney` and the receipt import's total go
through `roundMoney`/`sumMoney`. `roundQuantity` is new, for the one place a quantity SUBTRACTION
reaches a document (`3.3 - 1.1` is 2.1999999999999997).

### P-47 + P-48 — invalidating what actually moved
`flushOutbox` reports `blocked` as well, and the flush refetches whenever it changed anything —
landed, gave up, or was refused. A write the server rejected used to leave its optimistic row
standing: «Отримано 20 000» on money that would never exist. And four keys were wrong or missing:
`['economy', …]` was never a key this app reads (the economy is `['object-economy', …]`),
`['act-progress']` was invalidated by nothing that moves an estimate's quantities, the dead
`['object-expenses', …]` went with the journal screen, and a till receipt flipped to «моя витрата»
now refreshes «Мої гроші», which is where its expense is shown.

### P-45 — «Період»
A date commits only once it IS one: an `<input type="date">` reports every intermediate year on a
keyboard, each answer became its own query key persisted for a week, and above 9999 the screen went
to its error state. A cleared field falls back to the other bound instead of sending `''`, which the
server reads as «no bound» and answers about its own default month. The test that «passed» on the
screen's own opening request is now asserted on the exact call against a fixed clock.

### P-49, P-50, P-51, P-52
- The custom split reads `;`/whitespace as separators and a comma as a decimal, and shows what it
  read with its sum. A RECEIVED stage row shows what ARRIVED, not what was planned.
- The act editor: deleting a draft drops its queued receipts (each would replay as a POST against a
  deleted act); an additional row shows its own amount and says what a 0 ₴ price means; «перевищує
  кошторис» is compared in thousandths (1.1 + 2.2 > 3.3 in binary floating point); the dirty check
  compares NUMBERS, so «12,5» re-seeded from a save the server echoes as «12.5» is not a change; and
  the share sheet invalidates the act it just published (the badge stayed «Чернетка» and the FAB went
  on offering «Видалити» on an act the client already had a link to).
- `crew_margin_viewed` is sent only from the surface that is visible — both copies of the line are
  mounted at once, so the desktop card's fired on every phone, which is 95 % of masters. «-0 ₴» is
  shown as «0 ₴», the recompute is memoised, and «{{count}} позицій» has its plural forms.
- Cleanup: five orphaned `economy.*` keys and `economyApi.listExpenses` are gone, and three stale
  comments now describe what the code does.

---

## What this round deliberately did NOT do

- **A per-item undo for the shopping list's optimistic patch** (P-31's third bullet). The whole-list
  snapshot is restored today, and `cancelQueries` closes the race that actually bit — a GET already
  in flight landing after the patch. Undoing one row out of a list whose OTHER rows may have moved
  meanwhile is a different and larger change, and nothing has reported it.

---

## The follow-up: `cancelQueries` everywhere, not only where the review pointed

P-31 named the shopping list, so the first pass fixed the shopping list and «Мої гроші». Asked
afterwards whether the same shape existed elsewhere, it did — in **nine** more hooks: the estimate
editor (10 sites), own templates (7), the catalog (4), objects (4), payments and receipts (6),
measurements (6), notes (3), clients (2).

Every one of them patches the cache inside `offlineMutate`'s `optimistic` callback, which made that
the seam: it takes an optional **`cancel`** and awaits it immediately before the patch. Each call
site names the keys it actually writes — the estimate's create cancels the detail AND the object's
estimate list, `useUpdateEstimate` the detail and every `project-estimates`, the rest one key each.
The markup is the one patch outside `offlineMutate` (it queues absolute prices, P-41) and cancels by
hand.

**`src/lib/outbox/cancelCoverage.test.ts` is why this is the real fix.** A missing `cancel` is
invisible: the code type-checks, lints, and works on every fast connection and in every test,
because the race needs a slow request to lose to. So the test reads the source and asks that every
`offlineMutate` whose `optimistic` touches the cache carries one; an op that patches nothing is
exempt by saying so (`optimistic: () => undefined`). Verified red by deleting one `cancel` — it
names the file and line.
- **`touch.test.ts` asserting CSS as text** (P-32's last bullet). It is a trade the test's own
  docstring states: there is nothing to render that would prove a base-layer rule is shipped, and the
  failure mode of losing one is invisible. A reformat breaking it is the price.
- **A snapshot-side coverage list** for the contract test. Asking «does every backend DTO have a TS
  counterpart» would redden this repo for every admin-only DTO the app has no screen for — the cry
  wolf the test's own docs warn about. The coverage is asked from OUR side instead: every request
  body `src/api` mentions must be in the snapshot.

- **An atomic save for a SENT act** (P-37's last sentence). A save is still header-then-lines, so a
  client who signs between the two requests leaves the header saved and the lines refused. That is
  not silent, and it is not lost money: `requireNotSigned` guards every write, so the second request
  answers 409 and the signature stands on exactly the figures the client was shown. Recalling to
  DRAFT first would take the link away from a client who may be reading it; one atomic endpoint is a
  new door on the act, which wants the owner's word rather than a guess.

## Backend: the one change
`POST /api/acts/{id}/receipts` takes optional `fiscalFn` + `fiscalId`, written together or not at all
(`FiscalIdentity.complete` — half an identity is not one, B-21). The printed QR is decoded ON THE
DEVICE, so a receipt authored with no signal can carry the identity that makes it the same paper as
an object receipt. The create is the only call a queued receipt ever makes — it replays as a create
and never as a PATCH — so without this the code read off the paper was lost, and V134's whole point
is that a dropped identity is how a duplicate stays invisible (review P-32).
