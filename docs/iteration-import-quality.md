# Iteration — import quality: what the sheet IS decides how it is read

Backend patches 0010–0024, PWA 0009–0014. No migration.

A long run of fixes to document recognition, driven by real project sets and two production
reports. They are separate patches but one argument, so they are documented together.

---

## The argument

**A sheet has a KIND, and the kind decides the rules.** Almost every fault below is the same shape:
one convention was applied to a sheet that follows a different one.

- Millimetres are the construction convention — except a **технічний паспорт**, which prints metres
  to two decimals.
- A comma is a decimal separator — except where it is a thousands separator.
- A fraction «45,2/62,8» is житлова over загальна for a flat; «7/4,3» inside a room is a number over
  an area. One slash, two meanings, told apart by the top half.

So the passport block is now gated on **positive evidence** — at least two of: a number-over-area
fraction, two-decimal sizes where a designer would print bare millimetres, «h=2,50», «Масштаб 1:100»
with «ПОВЕРХ», a БТІ title block. Fewer than two and the block is skipped entirely. Written flat, it
read as though every printed sheet were a passport, and it would have turned a designer's «3500»
millimetres into 3500 metres.

---

## The corrections that reversed an earlier answer

**The balcony area is already reduced.** An earlier pass stated that a plan carries a balcony's raw
area and only the totals apply a coefficient. That was an inference from document structure, and it
was wrong: Додаток 8 of Постанова КМУ № 488 says «балкон, площа якого **з урахуванням відповідного
коефіцієнта** становить 3 кв. метри». So «Балкон — 1,6» is about 5 m² of real floor, and the old
rule understated a balcony **threefold** — in the direction that quietly shrinks an estimate.

Worse, the signal is usually absent: of four real offices one writes «Балкон (30%)», one «k=1,0»,
one only «засклений», one nothing — and the current official form has **no coefficient column at
all**. So the row is recognised by its purpose text (балкон / лоджія / тераса / веранда) and by line
weight (п.59: a single outline is a balcony, a double line a partition). The figure is reported
unchanged and flagged; it is **never divided back out**, because glazed and unglazed take different
coefficients and the sheet rarely says which. A computed number would look precise and be wrong.

---

## The Г-shaped corridor, from both sides

Reported twice — Дубляни and Белградська — and it took three passes because the room fails in two
independent places.

**Server side (SheetMerge).** An L wraps a corner, which makes it the room most likely to straddle a
seam between two fragments, and each fragment sees one arm. `foldRoom` took the FIRST arrival, so
the room's gabarits became one arm — smaller than its own printed area, which the client rightly
refuses. Between **fragments** the larger extent now wins, because a fragment is a crop: it can miss
a metre of corridor, it cannot invent one. A **whole-page** figure still wins outright — that
distinction is the fix, not a detail, and the test that pins it carries a guard assertion saying so.

**Client side, first attempt (wrong).** The checksum's cut ceiling was raised 0.4 → 0.6 and paired
with an implied-arm check: for a box a×b with arms w, `area = ab − (a−w)(b−w)`, so
`w² − (a+b)w + area = 0` and the arm is solvable. Both conditions are kept, because either alone
lets a misread through — 15×6 over 26,5 m² solves to a tidy 1,35 m arm and is still a chain borrowed
from the next room.

**Client side, actual fix.** That handled `gross > areaM2` — a box with a corner cut out. **The
corridor arrives the other way round.** A room that wraps a corner has no single width and length,
so its chains describe ONE ARM: 12,4 m² printed against 7,74 × 1,35 = 10,45. `excess` comes out
negative, no rule written so far could fire, and the room fell through to `reject` — which threw
away the printed area along with the chains, when the printed area was the half never in doubt.

`partial-gabarits` keeps that half: floor and ceiling take the area, the walls are left empty at the
known height. **Uncapped, deliberately**, unlike its mirror — that branch keeps `w×l` and must prove
the gabarits believable; this one keeps only the area and discards the chains, so a wrong chain
costs nothing. Whether those chains are one arm of this room or a stray from next door cannot be
told from the numbers, and does not need to be: both answers are "keep the area, drop the chains".

---

## A photographed plan reaches the right conveyor

A master photographed his БТІ passport and tried both doors. Both failed, for different reasons.

**The import dropped photos before any model saw them.** An image row was created with no evidence
at all, so every signal the picker reads was absent: triage only sends rows with text, `defaultPicks`
only scores rows with evidence, and a camera's file name («IMG20260510130144») classifies as
nothing. Now a photo is ticked and its evidence says what is true — no text layer — the same
`raster: true` a scanned PDF page already gets.

**The sketch screen took one file at a time.** A flat is a page per floor, or a plan and its
schedule. The parameter stays named `file` and became an **array**, so Spring binds a repeated form
field and a client sending one photo is a one-element list. All sheets go in ONE call, each
announced («SHEET 2 OF 3») before its image — read together the model can carry a room's name from
the sheet that names it to the sheet that sizes it.

**Then the sketch screen turned out to be the wrong machine entirely.** Production report with a
screenshot: rooms missing, rooms without walls, areas wrong by 8 % and 16 %. The recogniser was not
at fault — every chain came back correct. **The printed area had nowhere to go.** That schema is
rooms → items → planes, with no field for a room's area, so the model read 12,4 and 10,4 off the
plan and then had to throw them away and multiply the chains instead. Every wrong figure was a chain
product, not a misreading — and where a room really is its rectangle the two agree, which is why it
read as "almost nothing recognised" rather than an obvious fault.

Nor could that path have covered for it: the import conveyor reconciles each printed area against
its gabarits, merges several sheets into one set of rooms, and guarantees every room a floor, a
ceiling and four walls even when nothing was legible. None of that exists on the sketch path.

So `sheetKind` is now the answer rather than a detail of it. `HAND_DRAWN` is read as before;
`PRINTED_PLAN` is **named and nothing more** — `rooms` comes back empty by design and the client
hands the same files to the import flow. **The default leans to `PRINTED_PLAN`, including when the
field is missing**: a model that forgets it must not quietly get the кроки treatment, which is the
exact failure this field exists to stop.

Costs one extra recognition call on a printed plan — the classifying call, then the import's own.
That is the price of asking the sheet what it is before deciding how to read it, and it is much
cheaper than the answer it replaces.

---

## Other items in the run

- **Sheet size vs. heap.** `SheetTiler.dpiFor()` solves DPI from the largest tile so it lands near
  1700 px, capped at 200. A3 is unchanged; A1 drops 118 → 74 MB, A0 236 → 74 MB.
- **Triage no longer loses sheets.** `withAnythingTheModelForgot()` adds back any sheet that was sent
  but not returned, with `worthReading = true`.
- **A cable journal** (`CableJournalBuilder`, ДСТУ Б А.2.4-24 Форма 6) built from the device list the
  electrical takeoff already counts. Lengths are deliberately empty: §5.13 mandates a «надбавка на
  вигини» that no Ukrainian norm quantifies, and an invented number would be copied into an order.
  **Not reachable from the product** — like the rest of `service/album/`, see open-questions.
- **PWA: a PDF that never parses no longer freezes the import sheet.** `readPdfPageTexts` is bounded
  at 10 s. It was also the cause of a long-standing flaky test, twice misdiagnosed before the log
  was captured.
- **PWA: «Сервер недоступний» stopped being said to a master in a basement.** `toAppError` forks on
  the live online state and carries `code: 'NETWORK'`.

---

## Deploy order (this one matters)

Backend `sheetKind` defaults to `PRINTED_PLAN` when the field is absent. If the **backend ships
alone**, an old PWA gets `rooms: []` and shows «Не вдалося нічого прочитати» over a printed plan.
The reverse is safe: a PWA without the backend simply never sees `sheetKind` and behaves as before.
**Ship them together, or the PWA first.**
