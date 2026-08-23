# Iteration: templates as sequences (V112) + full template CRUD (V113)

Two halves of one ask from the master, done back to back.

**D1** — the ready-made PAINTER bundles were rebuilt as three ordered sequences, and the rule they
follow was written down. **D2** — templates became fully editable: delete, rename, add / edit /
remove a position, and drag the positions into the order the work is actually done in.

- **Status:** 🔨 code complete; backend `./gradlew build` green, PWA gate green. Not pushed —
  waiting on the master's approval.
- **Migrations:** **V112** (PAINTER bundles → 3 sequences), **V113** (`template_default_override`)
- **PWA:** 1.23.0

---

## D1. A template is a SEQUENCE, not a set (V112)

The master's verdict on the shipped bundles was the whole brief: «ВНУТРІШНЄ ОЗДОБЛЕННЯ ПРИМІЩЕНЬ -
це просто набір якихось незрозумілих позицій, без будь-якої послідовності», and «коли буде заходити
майстер на об'єкт і йому треба буде шаблон з 3-х позицій, то він і кошторису на таке не складає».

So: **21 PAINTER default bundles → 3**, each an ordered cycle, built from the three painter price
lists the master collected plus the numbered 1-19 cycle one of them wrote out by hand.

| bundle | what it is |
| --- | --- |
| **Малярні роботи** | the full cycle, bare wall → paint: шліфування → обезпилення → грунтовання → базове шпаклювання → скловолокно → фініш → грунт-фарба → фарбування |
| **Шпаклювання** | the putty half on its own, for a master called in only for that |
| **Фарбування** | the painting half on its own |

The eight-point rule this establishes is in
[docs/architecture.md](architecture.md) → *A default bundle is a SEQUENCE, not a set*. It is meant
to apply to every trade, not just PAINTER.

### The migration, part by part

1. **PART 1** — two genuinely new catalog positions (`added_in_version = 13`); everything else in
   the three lists was already covered by V96/V99/V109.
2. **PART 2** — the 21 old default bundles go. **The catalog is untouched**: «ми чіпаємо тільки
   шаблони, з позицій нічого не викидаємо».
3. **PART 3** — the three sequences, `sort_order` = the running order. A repeated stage (the cycle
   dusts and primes four times) appears **once** — the quantity is the master's to enter.
4. **PART 4** — a `DO $$` block that fails the migration if any bundle line has no catalog match on
   `lower(trim(name))` + type + unit. Template items carry no price, so an unmatched name does not
   fail at apply time — it silently bills the line at **0 ₴**. It has to fail here instead.
5. **PART 5** — pushes the new positions to registered painters, `added_in_version IN (12, 13)`.

### Three judgement calls, stated for the record

- **Фасадні роботи does NOT become a fourth bundle.** The three source price lists contain nothing
  facade-related and the master read the old bundle the same way («фасадні роботи тут думаю не
  мають місця взагалі»). Its positions all stay in the catalog; what goes is a bundle nobody
  assembled on purpose. If facade work earns a bundle later it should be built as its own sequence,
  not restored.
- **The two start-putty positions had to be disambiguated** — «Шпаклювання стін (старт, за
  потреби)» (м², 150) and «Шпаклювання стін (старт, за потреби) до 60 см» (м.п., 260). Two catalog
  rows sharing a name collide on the price-join/dedup key and one half is silently dropped, so the
  second needed a real SCOPE qualifier, not a «(м.п.)» suffix (V99 PART 2 settled that).
- **V109 shipped no propagation push at all**, so every painter registered before it is missing its
  14 positions. PART 5 therefore carries version 12 along — leaving that gap while stamping
  `last_synced_catalog_version = 13` would make the stamp a lie. The backfill is `NOT EXISTS`-
  guarded like every other one, so it can only add what is genuinely absent.

---

## D2. Templates are fully editable — and a default forks on write (V113)

> «нам треба добавити можливість видаляти шаблони, редагувати і також перетягувати позиції у
> шаблонах, редагувати це використати те саме що и всюди маємо - вибір позиції з каталогу чи
> вручну»

The problem: the ready-made bundles are **shared rows** (`is_default = true`, `owner_id IS NULL`)
that every master in the product reads. A write cannot land on them.

### Copy-on-write

`EstimateTemplateService.resolveWritable(id, userId)` is the one door every write path goes
through. My own template comes back as is; a system default is copied into an owned row (name,
trade, every position with its `sort_order`) and a `template_default_override` row records the
fork, taking the shared original out of **my** list; a default I already forked resolves to the same
copy, so a second edit does not mint a second bundle.

Two consequences every caller respects:

1. **Every write endpoint answers with the template it actually wrote**, whose `id` may differ from
   the one in the URL. The PWA's `adoptDetail` re-keys the query cache onto the id the server named
   and drops the requested one — key it under the id we sent and the next refetch hands back the
   pristine default, so the edits look like they vanished.
2. **Addressing the default afterwards keeps resolving to the same copy**, which is what makes a
   sequential batch of adds safe without waiting for the re-render, and what makes an offline replay
   safe: the queued ops all name the default, the first forks, the rest land in the fork.

**Delete on a default is a hide** — the override row with `forked_template_id = NULL`, undone by
`POST /api/estimate-templates/restore-defaults`. The FK is `ON DELETE SET NULL`, so deleting the
fork later leaves the default hidden rather than silently restored: a master who threw the bundle
away twice should not find it back.

### Endpoints

| method | path | note |
| --- | --- | --- |
| `PATCH` | `/api/estimate-templates/{id}` | rename (already existed; now forks) |
| `DELETE` | `/api/estimate-templates/{id}` | own → deleted, default → hidden |
| `POST` | `/api/estimate-templates/restore-defaults` | escape hatch, returns the fresh list |
| `POST` | `…/{id}/items` | add (already existed; `X-Entity-Uuid` idempotent) |
| `PATCH` | `…/{id}/items/{itemId}` | **new** — edit name / type / unit in place |
| `DELETE` | `…/{id}/items/{itemId}` | remove (already existed) |
| `PUT` | `…/{id}/items/order` | **new** — `TemplateItemsOrderRequest { itemIds }` |

Reorder is **declarative, not a move** — the whole final order, mirroring
`EstimateService.reorderItems`; unmentioned items keep their relative order after the named ones.
Stating the whole arrangement is what makes an offline replay idempotent.

### PWA

- `TemplatesPage` → `EditModal` holds an `activeId` that follows the fork, shows a hint banner
  while the template is still the shared default, and toasts once when the copy is made.
- `Composition` is a dnd-kit sortable list; a drag saves the moment it ends, because the order **is**
  the content. Positions are numbered in the editor and in the read-only preview.
- `PositionSheet` is the per-position editor and offers the same two ways a position is chosen
  everywhere else in the app: pick it from the catalog, or type it by hand. Its own name is left out
  of the blocked-names set (re-picking the same position to fix its unit is legitimate), everything
  else in the bundle stays blocked — two positions under one name merge on apply.
- The delete confirm branches on `isDefault`: «Прибрати шаблон зі списку?» with an explicit «зникне
  лише у вас», not «Видалити шаблон?».
- `restoreDefaults` is offered **unconditionally** under the defaults section — which defaults are
  hidden is not something the device can know, the list simply omits them. Online-only for the same
  reason: there is no honest optimistic state to show.
- Offline: `templateItem` gained an `update` branch; reorder is its **own** outbox entity,
  `templateItemOrder`, with `coalesce` — the outbox coalesces on entity+entityId+type, so filing a
  reorder as an `estimateTemplate` update would let a drag swallow a queued rename.
- `DragGrip` moved out of `EstimateItemsBoard` into `src/components/` — the estimate board and the
  template editor now grab identically, and the mobile `touch-action: none` fix can't drift between
  them.

---

## Tests

- **Backend** — `TemplateForkOnWriteIntegrationTest` (Testcontainers): a write on a default forks
  into an owned row with every position, the shared row untouched and out of `listForUser`; a
  reorder on the fork stores the new sequence and renumbers `sort_order` 0..n; delete on a default
  hides it and `restoreDefaults` brings it back; **deleting the fork leaves the default hidden** via
  `ON DELETE SET NULL`; a second edit lands in the same copy. Plus service/controller unit tests for
  `updateItem` / `reorderItems` / `restoreDefaults`, and
  `PainterCatalogRebuildOnLiveDataIntegrationTest` updated for V112's three bundles.
- **PWA** — `TemplatesPage.test`: deleting a default asks to HIDE it (wording asserted, since the
  wording is the point); the first edit of a default follows the fork the server answers with;
  tapping a position opens its editor prefilled and saves name/type/unit in place.
  `useEstimateTemplates.test`: the offline `templateItem` update op, and a reorder dragged twice
  offline collapsing into ONE `templateItemOrder` op carrying the final order.
- Full runs: `./gradlew build` green; PWA gate in CI order (lint → `tsc -b` → `typecheck:tests` →
  vitest 675 → vite build) green.

## Gotchas

- **The returned id may not be the requested id.** Anything new that writes a template must follow
  the response, or the edits land on a template that is no longer in the list.
- **`resolveWritable` is the only door.** A new write path that goes straight to the repository
  would write the shared row — visible to every master in the product.
- **The reorder request is the WHOLE order.** Sending a delta would break the offline replay it was
  designed for.
- **A template position carries no price**, whether typed or picked from the catalog — the catalog
  is only where name/type/unit came from. The price resolves at apply time, which is why an edit
  must never mint a name that another position in the same bundle already has.
- `restoreDefaults` clears override rows only; the master's own copies are untouched, so restoring
  can leave both the default and its fork in the list. That is intended — they are different
  bundles once edited.

---

## D3. Explicit save (round 2, PWA 1.23.4)

The master, after using D2:

> «в редагуванні Шаблонів треба зберігати не автоматично а тільки по натисканні кнопки зберегти,
> яка є активною на будь яку зміну в шаблоні, а якщо користувач закриває діалог то перепитуватись
> чи зберігати чи втратити»

and, on the fork:

> «кожен раз коли ми поміняємо шаблон і збережемо, то давай не роби новий шаблон, воно просто має
> поедітати існуючий»

D2 wrote every action the moment it happened, mirroring the estimate board. That is right for an
estimate — the master is recording work already agreed — and wrong for a template, which is composed
in one sitting where half the composing is trying positions on. So the editor now holds a **draft**.

### What changed

- `EditModal` keeps `draft` + `baseline` (`{name, items}`), seeded from the composition once it
  loads. Add / edit / remove / drag / rename all mutate the draft and write nothing.
- «Зберегти» lights up on **any** difference, order included (`sameItems` compares ids *and*
  position — a bundle is a sequence, so a drag alone is a real change). It is disabled on an empty
  name.
- The button sits **at the top, next to the name**. It was first built as a sticky bar at the
  bottom of the sheet, on the act editor's reasoning that a Save at the far end of a ~30-position
  bundle is off-screen when it is reached for — and the master rejected it on sight: «вона дуже
  плутається з тою Додати позиції і це погано». The add panel lives at the bottom, so the two ended
  up a thumb apart, one adding a single line and the other writing the whole bundle. Proximity beat
  reach: an ambiguous button is worse than a scroll.
- «Зберегти» does **not** close the sheet. It is a checkpoint, not the exit: a bundle is composed
  in one long sitting, and the master saves mid-way, sees it land, and carries on. (It also has to
  stay open on a ready-made bundle — the editor follows the fork the server answers with, and
  closing on save would drop that before the next write could use it.)
- Closing a dirty draft opens a **three-answer** dialog (зберегти / не зберігати / скасувати), built
  on `Modal` rather than `ConfirmDialog`, which only has two. Saving is the whole point of an
  explicit save; offering only discard-or-cancel would make the ✕ a trap on a bundle just reworked.

### How the save writes

There is no bulk endpoint, so the diff is replayed as the sequence the API offers:

```
rename → removals → edits → adds → order
```

Order **last**: an add always appends, so a drag only takes effect once every row exists.

An add's server id is recovered as *the row the previous answer did not have* — offline the
optimistic detail already carries our own uuid, so the same lookup resolves either way.

**Partial failure is the case worth thinking about.** The ops before the failure stayed landed, so
the baseline is re-seeded from `latest` — the answer to the last op that SUCCEEDED, which describes
the server exactly — and «Зберегти» retries only what is left. An add that landed also takes the
server's id and drops `isNew`, or the retry would add it a second time.

### It edits, it does not copy

Answering the master's second question directly: **a save edits the template in place.** The only
copy ever made is the one-time fork of a *ready-made* bundle, which is unavoidable — those rows are
shared by every master, so the first edit has to become the master's own. It happens **once**, not
per save: `EstimateTemplateService.loadWritable` looks up `template_default_override` before forking
and returns the existing copy, and the editor follows the id it gets back, so the second save
addresses the copy directly. Nothing about the draft changed that; it is the same fork rule D2
shipped, now reached once per save instead of once per keystroke.

### Two things this shook out

- **Typing before the composition loaded was thrown away.** The name input is live from the first
  frame (the summary always carries the name, even offline with no cached detail), but the seed then
  overwrote the whole draft once the detail arrived. It now keeps a name the master already touched,
  plus any draft-only position added meanwhile — pre-seed only an `isNew` row can exist, everything
  else IS what just loaded.
- **The composition's trash said «Видалити», the same label as the list's «delete this template».**
  Two very different destructive buttons under one name, a row apart; the position one is now
  «Прибрати позицію» (`templates.removeItem`).

### Tests

`TemplatesPage.test.tsx`: the catalog pick and the manual add now assert **no** API call until
«Зберегти»; the position sheet's own Save closes onto the draft and writes nothing; and two new
cases — the three-answer close dialog with «Не зберігати» writing nothing, and a second save on an
already-forked bundle addressing `fork1` with exactly two `rename` calls in total (the "never a new
template" invariant, pinned where it would actually break).

### Not verified

Mobile layout was not opened in a browser this round.

## D4. Green for «ще не збережено», and a scroll to it (PWA 1.23.5)

Master's ask, straight after the save button moved: «коли ми редагуємо шаблон і для прикладу
додаємо позиції чи позицію вручну, то також було б класно підсвічувати зеленим останні додані в
сесії і при можливості проскролювати до доданої\доданих якщо на екран всі не влазяться».

Both halves are consequences of D3. Once the writes stopped being immediate, an added position
looked exactly like a saved one — and it is appended at the BOTTOM of a bundle that runs to ~30
lines, so on a phone it usually lands off-screen.

### The highlight is derived, not tracked

`unsaved` is a `useMemo` over draft-vs-baseline: a row is green when it has no counterpart in the
baseline (added) or differs from it (edited). No state, no timers, no burst coalescing when a
catalog pick adds six positions at once, and no id remapping when a draft uuid becomes a server id —
`save()` re-seeds the baseline, and the green goes out on its own because the two sides now match.
It also covers an **edit** for free, which a set of "recently added ids" would not have.

This is deliberately NOT the estimate board's `touched`/`lastTouched` machinery. There every action
writes immediately, so the highlight can only mean «щойно змінив» and has to be remembered and
expired. Here it means «ще не збережено», which the draft already knows.

### The scroll had to learn about containers

`scrollRowIntoView` was written for the estimate board, i.e. for the page. Inside a `Modal` the page
is frozen (`position: fixed`) and the list has its own `overflow-y` — so the helper asked
`window.scrollY` whether anything had moved, got "no" every time, and fired its instant fallback on
top of a perfectly good animation. It now walks up to the nearest actually-scrolling ancestor and
asks **that** both questions ("did it move", "is the row fully visible"), falling back to the window
only when there is no such box. The page branch still reads `window.scrollY` on purpose:
`documentElement.scrollTop` is pinned to 0 under the modal's lock.

`TemplatesPage` sets `scrollTo.current` on every add/edit and consumes it in an effect keyed on
`items`, clearing it only once the row exists, so a lagging render can't drop the scroll. A
multi-pick fires it once per position and the last one wins — which puts the list at the END of the
batch, where you look to check the whole lot arrived. `editingItem` is a dependency because an edit
submits from a sheet on top of this one; scrolling a row hidden behind it is pointless.

### Gotcha: jsdom has no `scrollIntoView` at all

Not a no-op — the property is missing, so calling it is a `TypeError`. Four template tests died on
it the moment the editor started scrolling. `src/test-setup.ts` now stubs it globally, so any screen
that brings a row into view doesn't blow up in a test for a reason unrelated to what is under test.

### Tests

`scrollRowIntoView.test.ts` gained the container case (instant fallback when the box didn't move;
hands off while the BOX is animating and the window never budges; visibility measured against the
box's rect, not the viewport). `TemplatesPage.test.tsx` gained one end-to-end case: a manually added
position is green and scrolled to, its untouched neighbour is not, and after «Зберегти» the green is
gone. The lookup there is scoped to `[data-template-item-id] button` — the name also appears in the
«зберегти в каталог» prompt the manual form leaves open behind it.

### Not verified

Mobile layout was not opened in a browser. The green is `bg-success-soft` + `border-success/50`,
already used elsewhere in the app, and the scroll needs a real phone to judge.
