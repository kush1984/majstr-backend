# Iteration: scroll the just-added position into view

- **Status:** 🔨 round 2 — the root cause found by driving the real app in Chrome; PWA gate green
- **Migration:** none (PWA-only)
- **PWA:** 1.23.2 → 1.23.3 (round 2)

## The complaint

> «коли додаємо позицію нову, то воно кудись додає, але треба прокручувати і шукати де та позиція
> додалась, треба зробити автоскрол до доданої чи зміненої позиції, щоб вона була в полі зору»

With a screenshot of an 18-line estimate: the new line sat in a «БЕЗ КАТЕГОРІЇ» group below
seventeen paving positions, correctly highlighted green — and completely off-screen.

## Why it happens (and why the highlight alone was not enough)

A position does NOT land at the end of the list. The board groups by type (works / materials) and
then by category, so a new line sorts into wherever its category belongs — which on a real estimate
is usually somewhere in the middle, or in a trailing «no category» group past a screenful of rows.
The session highlight (`touched` / `lastTouched`) was built for exactly this problem but only solves
half of it: it tells you which row is yours **once you find it**.

The same applies to an edit that changes a line's category — the row moves, and the master is left
looking at where it used to be.

## The fix

`ItemRow` now renders `data-item-id={item.id}`, and `EstimateEditorPage` scrolls to it:

- `markTouched(ids)` — already the one funnel for every add/edit — also records
  `scrollTo.current = ids[0]`. A multi-add (template apply, receipt import) scrolls to the first of
  the batch, which is where the block starts.
- An effect keyed on `[lastTouched, estimate.data]` looks the row up and calls
  `scrollIntoView({ behavior: 'smooth', block: 'center' })`.

**Both dependencies are load-bearing** — the order of "mark" and "row exists" is not fixed. An
offline add is in the query cache *before* `markTouched` runs (so the mark is the later event); an
online one lands on the refetch *after* it (so the data is). The target is cleared **only once the
node is actually found**, so whichever arrives last does the scroll and neither case silently drops
it. Switching estimates clears the target along with the highlights.

`block: 'center'` rather than `'start'`: the page has a sticky header and, on a phone, a bottom nav
plus a FAB — a row parked at either edge is half-covered.

## Tests

`EstimateItemsBoard.test.tsx` +1: every row carries `data-item-id`. That is the contract the page
depends on, and it is the failure that would be **silent** — remove the attribute and the query
simply returns null, nothing throws, the scroll just stops happening. The scroll itself is not
asserted: jsdom has no layout and stubs `scrollIntoView`, so a test of it would pin the mock, not
the behaviour.

## Not verified

Not opened in a browser at 375 × 812. Smooth scrolling and `scrollIntoView` are supported on iOS
Safari 14+/Chrome, so the risk is the *feel* on a phone (how far it travels), not support.

---

## Round 2 — it did not work at all on Windows (2026-08-23, PWA 1.23.3)

Master, after testing: «протестував зі скролінгом позицій після додавання — на віндовз воно не
працює від слова зовсім», then, narrowing it: «не працює скролінг для позицій доданих вручну, якщо
вибрати якусь з каталогу то все ок».

Two independent defects, the first proven by driving the real app in Chrome on Windows.

### 2.1 `behavior: 'smooth'` can be a complete no-op

Measured on the running dev server, on a 36-line estimate:

| call | result |
| --- | --- |
| `el.scrollIntoView({behavior:'auto',block:'center'})` | `scrollY` 0 → **2248** |
| `el.scrollIntoView({behavior:'smooth',block:'center'})` | `scrollY` 0 → **0** |
| `window.scrollTo({top:1200,behavior:'smooth'})` | `scrollY` 0 → **0** |
| CSS `scroll-behavior: smooth` + plain `scrollIntoView` | `scrollY` 0 → **0** |

So it is not our lookup, not the anchor, not React: **every** smooth scroll refuses to move the page
in this browser (Chrome 151, Windows 11), while the instant form works. This is the browser's
"Smooth Scrolling" setting being off — and the trap is that `prefers-reduced-motion` reads **false**,
so there is nothing to feature-detect before the call, and nothing throws.

**Fix — `src/lib/scrollRowIntoView.ts`:** ask for smooth, then after a 250 ms grace check whether the
page actually moved; if it did not and the row still is not fully in view, repeat the call in its
instant form. Where smooth works (mobile, most desktops) the animation is untouched; where it is
dead the row still lands on screen. Verified live on the same page: nothing at 150 ms, `scrollY`
2248 at 650 ms.

### 2.2 The target was burned while the add sheet was still open

This is why the manual add failed even where a catalog pick appeared to work.

`Modal` freezes the page for its iOS-safe scroll lock (`document.body.style.position = 'fixed'`), and
while it is frozen **nothing can scroll**. The catalog picker closes the sheet in the same batch as
`onAdded`, but the manual form deliberately keeps it open to offer «зберегти в каталог» — so the row
arrives, the effect finds it, clears `scrollTo.current`, and scrolls a frozen page. The scroll is
then lost for good: the target is gone before the sheet ever closes.

**Fix:** the effect bails out early on `bodyScrollLocked()` **without** clearing the target, and its
dependency list gained `addOpen` / `editing` so it re-runs when the sheet or the edit modal closes.
`bodyScrollLocked()` reads the same signal `Modal` uses for its own outermost-lock check, so the two
cannot drift apart silently.

### Tests

`src/lib/scrollRowIntoView.test.ts` (+4): the instant fallback fires when the smooth call moved
nothing; a working smooth scroll is left alone; an already-visible row is not re-scrolled;
`bodyScrollLocked()` reports the frozen body. Extracting the helper out of the page is what makes
this testable at all — the previous round could only pin the `data-item-id` anchor, because in jsdom
the scroll itself is a stub.

### Not verified

The end-to-end click-through (open the sheet, type a position, submit, watch it scroll) was **not**
run — it writes a line into the master's local data and screenshot capture was timing out during the
build. What *was* verified in the live page is the part that was actually broken: the fallback firing
and moving the page, and `bodyScrollLocked()` reporting a frozen body. Mobile layout is unchanged by
this round — no markup moved.
