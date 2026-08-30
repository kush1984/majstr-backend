# Iteration: the economy strip greens as it closes

**Status:** complete — PWA green on the full CI mirror (lint · tsc · typecheck:tests · **752**
vitest · vite build), backend green on `./gradlew build`, portal HTML syntax-checked. NOT pushed
(awaiting the user's approval).
**Source:** the master, in the same breath as a Gantt-chart question: «я ще хотів в нашій економіці
щоб та полоска яка показує скільки закрито по обєкті потехенько зеленішала помірі сплати чи
завершення виконання робіт». Approved as scoped: «давай роби зелену смужку».
**Migration:** none. **PWA version:** 1.29.0 → **1.29.1** (patch — polish on shipped work).

---

## 0. The Gantt half of the question, answered with "no"

Worth recording, because it will be asked again. A Gantt chart is not a rendering problem here, it
is a **data** problem: nothing in the schema carries a start and an end date for a piece of work.
`estimate_items` hold quantity and price, `work_act` holds only signature dates, and the single
planned date in the whole object is `project_payment.due_date` — which is deliberately framed as a
condition that unblocks the next stage, not a schedule. Drawing a Gantt would mean first asking the
master to date every position, i.e. inventing a planning product on top of an estimating one, and
then rendering it on a 375 px phone, which is the one shape a Gantt is worst at. Declined.

The progress bar is the part of that wish that the existing data can actually answer, so it is the
part that shipped.

## 1. Colour is a POSITION, never a verdict

The obvious implementation — interpolate the fill's hue from orange to green by percent — was
rejected. Halfway between our two tokens sits a **muddy olive** (`rgb(129,146,80)` in the app,
`rgb(146,126,47)` in the portal), and that version paints the *entire* bar in it at 50–60 %. On a
money screen a whole bar in that colour reads as a warning when nothing at all is wrong, and the
brand colour disappears from every half-finished object.

The window version contains the same olive — colour comes from the same ramp — but only ever as the
**leading edge** of a bar that is still mostly brand orange. That is the difference: a transition,
not a verdict.

What ships instead: the **gradient always spans the whole track, and the fill is a window onto it**.

```
track  |brand ──────────────────────────────── success|
fill   |brand ────────|                                   30 %  → only the orange end is visible
fill   |brand ────────────────────────── success|         90 %  → a green tail has appeared
```

Mechanically that is two numbers on one element: `width: {pct}%` and
`background-size: {(100/pct)*100}% 100%`, which stretches the gradient image to the TRACK width
while the element itself is only as wide as the fill. Default `background-position: 0% 0%` does the
rest. Drop the `background-size` and every bar shows the full orange→green ramp squeezed into
whatever width it happens to have — which is exactly the interpolation we rejected, arrived at by
accident. That is why it is asserted in a test rather than left to a class name.

No point on the bar ever changes colour; the bar greens **because it grows**.

**100 % is a separate state — solid green**, no gradient. "Closed" is something the master should be
able to spot from across the room, not one more percent of a ramp.

## 2. One bar, four places — the divergence trap

This strip already existed three times, and one of the copies said so out loud (`{/* twin of
PaymentStrip */}` in `AxisStrip`). Copies of a visual rule drift; this one was one edit away from
drifting on its first change.

| Where | What draws it now |
|---|---|
| Платежі card, «Отримано X з Y» | `PaymentStrip` → `<ProgressStrip>` |
| Works axis, «Прийнято актами» | `AxisStrip` → `<ProgressStrip>` |
| Works axis, «Отримано» | `AxisStrip` → `<ProgressStrip>` |
| **Client portal** payments card | `.paybar-fill` in `static/portal/index.html` |

The first three are now literally the same component, `majstr-pwa/src/components/ProgressStrip.tsx`
(the `ReceiptPhoto` precedent). The fourth **cannot be** — `static/portal/index.html` is a
standalone page that never loads the PWA bundle, in the other repo, with its own palette
(`--amber: #f5821f`, `--success: #2f7a3f`). It therefore keeps a hand-written copy of the rule, and
both files carry a comment naming the other. **Change one and the same commit changes the other.**

`AxisStrip`'s signature changed from a precomputed `pct` prop to `total`, so the percent is now
derived in exactly one place; `ActsAxis`'s local `pct` helper is gone.

## 3. The `Math.min(100, …)` lie, fixed on the way past

All three call sites capped the **label**, not just the bar:

```js
const pct = total > 0 ? Math.min(100, Math.round((received / total) * 100)) : 0;
```

A client *can* pay more than the contracted total — the payments code has a whole
`PaymentOverflowResolution` enum about it — and 25 000 ₴ received against a 20 000 ₴ contract read
as «100 %», exactly like a contract paid to the last hryvnia. That is the screen rounding away
money the master then cannot find.

Split in two: `progressPct()` is **uncapped** and feeds the label and `aria-valuenow`; the width
clamps to 100 and the fill goes solid green. The bar cannot draw past its track, but the number
tells the truth. Same split in the portal (`pct` for the label, `barWidth` for the bar).

## 4. Not done

- **No overpayment-specific colour.** A third state (e.g. an over-filled bar in a distinct hue) was
  considered and left out: the honest number plus the closed-green bar already says it, and a
  fourth colour on a 8 px strip is noise. If the master reports that overpayments are hard to
  notice, that is where to add it.
- **No animation beyond the existing `transition-[width]`.** «Потехеньку зеленішала» is satisfied by
  the growth transition already on the element.
- **The estimate board's own progress chips** («✓ закрито», done/total) are untouched — they are
  text, not a bar, and they answer a per-line question, not an object-wide one.

## 5. Tests

`src/components/ProgressStrip.test.tsx` (new, 9 cases) pins the parts that a plausible-looking
rewrite would silently break: the `background-size` math at two fill levels (`25 % → 400%`,
`80 % → 125%`), the solid-green switch at 100 % including the *absence* of the gradient class, the
clamped width on an overpayment, the zero guard, and the uncapped `progressPct` in both the label
and `aria-valuenow`.

`PaymentsBlock.test.tsx` gains two call-site cases — the true «125%» on an overpayment and the
solid-green fill at exactly 100 % — because the uncapping is a behaviour change at the call site,
not inside the component.

The portal copy has no test in this repo (it is a static page with an inline script); it was
syntax-checked by extracting the `<script>` and running `node --check`.

## 6. Mobile

Geometry is unchanged: same `h-2` track, same `mt-1.5`, same rounded ends, same wrapper. The only
new pixels are inside the fill (`background-image` / `background-size`), so there is nothing new
that can overflow or shrink a tap target. The one label that can grow is the percent, from `100%`
to `125%` — it sits in a `flex-wrap` line that already handles a much longer money string.

**Not verified visually.** The browser pane could not screenshot a local preview page (script
injection timed out repeatedly, machine idle), so the 375 px look was not seen with an eye. What
*was* checked instead: the geometry is byte-identical to the bar that shipped, and the gradient
endpoints were computed numerically to confirm the transitional colour is an olive rather than a
warning yellow. If the first live look disagrees, the two numbers to reach for are the gradient
endpoints, not the layout.
