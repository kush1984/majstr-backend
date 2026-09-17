# Iteration — taps that land (2026-09-16)

## The report

> «Не можливо анселектнути чекбокс, то просто мука капець таке зробити на телефоні і так само з
> деякими кнопками, наприклад, на порталі клієнта кнопка Підписати, з 5-того разу спрацювало
> натиснути.»

Two surfaces, two codebases, one symptom: a control that answers roughly one tap in five. The
screenshot was «Список покупок» with a bought row whose tick would not come off; the second example
was the portal's «Підписати», which is the single action that page exists for.

## What it was NOT

Worth writing down, because it is where the first hour goes. Every handler on both paths is correct:

- `ShoppingRow`'s tick is a 56 px `<button>`, a sibling of the row body rather than nested in it,
  and `disabled` is `archived` — not a pending mutation, so no write can lock it.
- `useShoppingActions.toggle` reads which way to flip off the CACHE, not off the render closure, and
  patches optimistically on both the online and the queued path.
- The portal's `[data-sign]` handler is bound on every render, and the delegated `document` click
  listener that dismisses the info popover does not stop propagation or cancel anything.

Nothing in application code was dropping these taps. The browser was.

## The two causes

**1. A press that lands on TEXT starts a selection, and the click is then never dispatched.**
Both controls carry their own label — the `✓` inside the tick's span, the word «Підписати» inside
the button. On Android and iOS a press held a moment too long over text begins a native selection
(and then the callout menu), and once that happens the `click` never arrives. A master in a work
glove presses slowly. He gets nothing, presses again, gets nothing — until one tap happens to be
quick enough. That is the whole complaint, and it is invisible to every test: the markup is right,
the handler is right, the event simply never fires.

**2. Double-tap-to-zoom makes the retry worse.** Both pages are pinch-zoomable by design, so the
browser holds a tap briefly to see whether a second one is coming. The natural reaction to a missed
tap — tap again, immediately — is exactly the gesture that reads as a zoom rather than two clicks.

## The fix

`user-select: none` + `-webkit-touch-callout: none` + `touch-action: manipulation` on everything
that is tapped, in both repos:

- PWA — a block in `src/styles/index.css`, inside `@layer base` so a Tailwind utility still wins
  where a surface needs the other behaviour (`touch-none` on a drag grip is the live example).
- Portal / message / admin — the same block in each page's own `<style>`.

Three things ride along:

- **`future.hoverOnlyWhenSupported` in `tailwind.config.js`**, and `@media (hover: hover)` around
  the portal's hover rules. A touch device has no hover but fakes one: the last-tapped control keeps
  its hover look until something else is tapped — and «it still looks pressed» is precisely how the
  master decides his tap did not land. `:active`, which a touch really does drive, gives the
  feedback instead.
- **`Button` gained `min-h-11`** (44 px). Padding alone left it a couple of pixels short.
- **The portal's 18 px ⓘ dot and its popover ✕ grew an invisible 44 px hit area** via `::after`;
  they sit inline beside a heading and cannot simply be made bigger.

One behaviour change came with it, in `CatalogAutocomplete`: a suggestion used to be picked on
`onMouseDown` + `preventDefault()` for every pointer. On a phone `mousedown` is not a press at all —
it is a compatibility event the browser synthesises after the touch has ended and drops whenever it
reads the gesture as something else, and cancelling it also cancelled the `click` that would have
been the fallback. The mouse keeps its pre-blur path (guarded on `pointerType`); touch falls through
to a plain `onClick`, which a browser fires for a real tap and never for a scroll — so dragging the
list to read it no longer risks picking a row.

## Why it is pinned by tests that read source as text

A base-layer CSS rule has no component to render and no behaviour to assert in jsdom, and its
failure mode is that everything looks perfect while the button misses taps. So it is asserted
against the file, the same trick `UnitRenderCoverageTest` and the PWA's `swUpdate.test.ts` already
use: `PortalTouchTargetsTest` here, `src/styles/touch.test.ts` there. **The two blocks are a
mirrored pair — change one, change the other**, exactly like the green progress strip.

## Left open

- 36 px icon buttons (`h-9 w-9`, the ← chrome in ~15 screens) are under the 44 px floor. Not part of
  the report and a layout change in every one of them, so not touched.
- Android's back-gesture strip runs down the left edge, where the shopping tick sits ~18 px in. A
  stationary tap gets through; a gloved press that drifts horizontally can be eaten by the system
  before the page ever sees it. Fixing it means moving the tick, which is a design decision.
