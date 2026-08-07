# Iteration — provenance for frozen «%» lines in a consolidated estimate

PWA `1.9.0 → 1.9.1`. Backend: **V92**.

Presentation-only fix — the math was already correct and stays untouched.

## The bug

`EstimateService.copyForConsolidation` freezes a PERCENT line's amount when copying it into a
consolidated rollup — the right call, since re-measuring «% від кошторису» against the merged
(bigger) subtotal would silently hand the client a discount he never signed. But the frozen line
then read **«10 % від 3 450 ₴»** with no hint of what «3 450» is (a reconstructed number, not a
real position) or which estimate it came from. Editing it was worse: the base-picker still opened
POSITION-mode by default, offering an empty/broken dropdown for a base that no longer exists.

## The fix

`estimate_items.base_origin_label` (V92) is a **snapshot**, not a FK — same pattern as
`source_unit_price`/`source_item_id` (V85): the source estimate or position can be edited/deleted
afterwards, and this has to keep reading what was true at consolidation time regardless.

`EstimateService.buildBaseOriginLabel` builds it from the line's **ORIGINAL** kind/base — read
before `copyForConsolidation` overwrites the copy's own kind to MANUAL — as `{signed
percent}% від {робіт|матеріалів|«base position»|суми} · кошторис «{source name}»`. Non-PERCENT
lines get `null`.

PWA:
- `percentLabel` shows `baseOriginLabel` verbatim instead of computing the generic MANUAL wording
  (which read as a percentage of a number nobody could place).
- `ItemForm`: a frozen line (`baseOriginLabel` set) skips the live POSITION/TOTAL picker entirely
  — there's nothing left to point at, and re-picking a base would silently do nothing — and shows
  the snapshot as static text instead. The percent and the base sum stay directly editable (both
  are genuinely stored fields on a MANUAL line); the price field, previously hidden for every
  PERCENT line, is shown (relabeled) specifically for a frozen one.
- `TypeBreakdown` (the editor's black summary panel) gained a second adjustment line, «Перенесені
  знижки/надбавки», alongside the existing «Від кошторису» one — a frozen line's contribution
  otherwise folded invisibly into the base subtotal.

## Not changed

- The frozen amount itself (`lineTotal`) — `Σ` of a consolidated rollup's frozen lines still
  equals the sum of what they contributed in their sources.
- A live (non-consolidated) PERCENT line's behavior.
- SIGNED immutability, portal/PDF isolation, `V90` consolidation lineage.

## Deferred (open-questions)

The real "should be" shape is a consolidated estimate rendered **in sections, one per source
estimate**, each with its own subtotal and a still-LIVE percentage within that section (POSITION/
TOTAL bases stay intact because nothing merged). That is a bigger feature — sectioned estimates +
sectioned math — and lines up with the panel-per-object shape masters already want in economy/
portal. Logged, not built here.

## Tests

Backend: `EstimateServiceTest` — provenance label content for TOTAL/POSITION/MANUAL kinds, a
non-PERCENT line gets no label, frozen sums unchanged (existing regression test untouched).
PWA: `percentMath.test.ts` (frozen line shows the snapshot verbatim), `TradeSelect` unaffected.

## Follow-up (1.9.2, V92 — same migration, no schema change)

A live proof of the first pass surfaced three things:

1. **The source-estimate name was ALWAYS the bare word «Кошторис».** `Estimate.name` is nullable
   (unnamed is the common case), and `buildBaseOriginLabel`'s fallback didn't know the PWA shows a
   dated default for that case (`estimateName()`: name ?? "Кошторис від {день} {місяць}"). Every
   unnamed source therefore read identically — two frozen lines from "6 липня" and "2 липня" were
   indistinguishable. Fixed by `EstimateService.sourceEstimateName` replicating the PWA's default
   1-for-1: `DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("uk"))` over
   `estimate.getCreatedAt().atZone(LocalizationConfig.ZONE)` — same Kyiv-zone conversion the PDF
   date already uses, same "MMMM" genitive form ("6 липня", not the stand-alone "липень"). A
   `base_origin_label` is a snapshot, so an already-consolidated DRAFT keeps its old bare
   «Кошторис» until re-consolidated — no backfill (low value; a DRAFT rollup is trivially
   re-created).
2. **The black summary panel's frozen row was net-signed and singular.** «Перенесені
   знижки/надбавки −4 047 ₴» didn't say which it was, and a type carrying both a carried-over
   discount AND a carried-over markup (from two different source estimates) would net them
   together and hide one against the other. `TypeBreakdown` now splits frozen lines by sign into
   up to two separate rows — «Перенесені знижки» (only if a negative sum exists) and «Перенесені
   надбавки» (only if a positive sum exists) — mirroring the existing markup/discount wording used
   for live «% від кошторису» lines.
3. **The «До сплати» recap («Надбавка · Знижка») a plain estimate already shows was simply absent
   for a consolidated one.** `adjustTotals` (feeding `AdjustNote`, unchanged and reused as-is) only
   ever looked at `percentBaseKind === 'TOTAL'` lines — a frozen line is always `MANUAL`, so it
   never qualified. Widened the same function to also fold in any `baseOriginLabel`-carrying line;
   a no-op for an ordinary estimate (never has one).

Tests: `EstimateServiceTest.consolidate_unnamedSourceEstimate_labelUsesTheSameDatedDefaultThePwaShows`;
PWA `estimateSummary.test.tsx` (new — `TypeBreakdown`/`adjustTotals` exported from
`EstimateEditorPage.tsx` for direct testing, matching how `percentLine.ts`/`estimateName.ts` are
already pulled out as testable pure modules).
