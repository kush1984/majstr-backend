# Iteration — two prices for one job, bulk deletion, and a trial long enough to matter

PWA `1.0.0 → 1.1.x`. Backend: **V85**, **V86**.

Three things arrived together because two of them came from masters on the phone while the third
was already half-built.

---

## 1. Duplicating an estimate with a markup

### The workflow it serves

A бригадир quotes two prices for the same job: one to the crew who will do it, one to the client.
The difference is what he keeps. Until now that meant typing the estimate twice, and an object
economy that could not tell the two apart — counting both said he had earned the crew's wages on
top of his own margin.

The shape: **the PARENT holds master prices** (what he pays out), **the DUPLICATE holds client
prices**, and his earnings are the difference.

### Why the source price lives on the LINE, not as one percent on the estimate

The obvious design is a single `markup_percent` on the estimate with everything derived from it. It
breaks on all four things that actually happen to an estimate afterwards:

- the markup is applied to **selected lines only** — works yes, materials usually not — so one
  percent does not describe the sheet;
- the parent can be **deleted** (DRAFT/SENT delete freely) and the difference would go with it;
- the master **edits a price** in the duplicate and the percent stops being true;
- he **adds a line** to the duplicate that the crew is not paid for at all.

`estimate_items.source_unit_price` survives all four, because the earning is always the same
subtraction: `(current price − source price) × quantity`. A line added later has **no** source, and
NULL there correctly means "none of this is passed on to the crew" — the whole line is margin.

`estimates.markup_percent` is still stored, but only as a **label**: it is what the list says
(«в економіку йде тільки націнка +15%») and what names the copy. Nothing computes from it.

### The economy query

```sql
SELECT COALESCE(SUM(ROUND(i.quantity *
         CASE WHEN e.duplicated_from_id IS NULL THEN i.unit_price
              ELSE i.unit_price - COALESCE(i.source_unit_price, 0) END, 2)), 0)
```

One `CASE`, in the query that already existed. An ordinary estimate is unaffected — `duplicated_from_id
IS NULL` is every estimate written before this shipped.

### Defaults, and who decided them

- **Works are ticked by default, materials are not.** A foreman marks up labour; a material bought
  on a receipt is passed through at cost unless he says otherwise. He can tick materials himself.
- **Rounding is to whole hryvnias** (`setScale(0, HALF_UP)`) — a price list with kopecks in it looks
  computed rather than quoted.
- **The parent leaves the economy automatically** (`countInEconomy = false` on duplicate). Both
  halves of the pair then carry a note in the list, because each looks like a mistake on its own:
  the copy is ticked but only its markup is income, and the parent's empty box would otherwise read
  as something lost rather than something decided.

The note is a **separate line under the checkbox, not the checkbox's label**. Inside the button it
dimmed together with an un-ticked box and read as "this applies only while the tick is on"; it
describes the standing arrangement of the pair, which holds either way.

### The client needs the raw link, not a flag

`EstimateSummary` carries `duplicatedFromId`, not a `hasDuplicates` boolean. The object screen
already holds the whole estimate list, so it can see both halves of the pair from the raw fact —
and it is the **parent** that needs the note, which a flag on the copy could not deliver.

---

## 2. Deleting many positions at once

From a master, by phone: a template applies 100 positions and he needs 37 of them gone. One at a
time is not a workflow.

**Selection mode is a third mode, not a modifier.** When it is on, a tap on a row means "pick";
editing and dragging are both out of the way. A tap that might mean three things is worse than
three modes that each mean one.

**Category tick-boxes are the point of the feature.** A master keeping one trade out of a
167-position catalog is not picking scattered lines, he is dropping whole categories. «Басейни»
turns thirty taps into one.

**Deletion cascades parent → duplicate, never the reverse.** The parent is the master's own price
list; a line he removes from it is a line the crew is not doing, so it cannot survive in the client
copy. A line removed from the client copy says nothing about what the crew was hired for. Signed
copies are skipped — a signature certifies exact items.

---

## 3. The PRO trial: 5 days → 15

Five days is barely one object. A master signs up, takes a job, and the trial lapses before he has
run a single estimate through to a client's signature — which is the moment PRO is supposed to prove
itself. Ending a trial before the product has had a chance to work is not a conversion strategy.

- The length lives in **config** (`app.billing.trial-days`), so V86 only fixes up masters who are
  mid-trial right now: `GREATEST(plan_expires_at, trial_started_at + INTERVAL '15 days')`.
- **Payers are untouched** — `NOT EXISTS (SUCCESS payment)`. A master who bought PRO is on a
  subscription, not a trial.
- **Lapsed trials are deliberately not revived.** Extending a trial that already ended would hand
  PRO back to someone who has been on FREE for a week, silently, with no event to explain it.

### The reminder is its own job

`TrialReminderService` — a separate `@Scheduled` bean, **deliberately not** folded into
`AutoRenewService`, which charges cards. A bug in a reminder must never be able to reach a payment
path.

It runs daily over the last three days of a trial, push + email, and stamps `trial_reminder_sent_at`
— compared against **today**, because this fires once a day for three days rather than once per
cycle (which is what `renew_reminder_sent_at` guards). The last day says «Завтра» rather than
«0 днів».

---

## Tests

- `EstimateServiceTest` — duplicate defaults (works ticked, materials not), rounding, cascade into
  duplicates, signed copies skipped, `deleteItems` idempotence.
- `EstimateRepository` economy sum — an ordinary estimate, a marked-up copy, and a line added to the
  copy with no source price.
- `startTrial` reads `props.trialDays()` rather than a literal, so the length can never drift out of
  step with config again.
- PWA: `economyNote` (three branches incl. a copy-of-a-copy), selection mode, markup sheet.

## What this left open

- **Nothing highlights WHAT changed** between a parent and its copy. A master comparing them reads
  both. Tracked in open-questions.
- **No "what changed" on re-signing** either — the same gap from the reopen flow.
