# Iteration: album takeoff — adopted from the archive, then split into two products

> **Retrospective doc.** Written during the 2026-07-27 catch-up by reading the diffs
> (`1ffeb41`), not by the session that built this. The *what* is from the code; where intent
> was not written down, this says so rather than inventing it.

- **Status:** ✅ code complete and tested — ⚠️ **not exposed** (see *What is missing* below)
- **Migration:** none (nothing persisted yet)
- **PWA:** none

## What it is

Recognition of a whole designer **album** — the multi-sheet PDF set — rather than one page at a
time. Adopted from the second agent's archived feature (evaluated 2026-07-24) and reshaped.

`service/album/`:

| Piece | Job |
|---|---|
| `ClaudeAlbumExtractor` | multi-pass Opus over the album; Files API, prompt caching, structured outputs. Owns its **own** HTTP client with long timeouts |
| `AlbumExtraction`, `AlbumSchemas` | the extraction model + JSON schemas |
| `RoomSurfaceCalc` | deterministic surfaces: floor/ceiling, perimeter, gross/net walls, plinth, reveals, sills, house totals |
| `ElectroTakeoffCalc` | deterministic electrical BOM: cable, chase, back-boxes |
| `SurfaceTakeoffService` | the «площі» product flow — surface passes only |
| `ElectroTakeoffService` | the «електрика» product flow — electrical passes only |

## The one design decision worth keeping

**Two independent product flows over one extractor, not one "analyse the album" button.**

A painter needs areas and will never care how many back-boxes the flat needs; an electrician is
the reverse. Running both sets of passes for everyone means every master pays for half a result
they did not ask for — and these are Opus passes over dense A3 line-work, so that is real money.
Each service therefore runs **only its own** passes plus its own deterministic calculator.

Prompt caching makes the split cheap rather than wasteful: if both flows run on the same album
inside the cache TTL, the second reads the document from cache (~10% of input price). So
splitting costs almost nothing and saves a lot in the common single-trade case.

## Honesty is enforced by the calculators, not hoped for

`RoomSurfaceCalc` mirrors `MeasurementCalc` exactly — walls = Σ planes − Σ openings (w×h);
reveals use the LINEAR default sides (left + right + top, no bottom) i.e. `2·H + W`. A door
shared by two rooms is deducted from **both**, because each side of the wall loses the hole.

And nothing is guessed: a missing ceiling height or an underivable perimeter leaves the
dependent values `null` **with a note**; an opening missing either dimension is skipped from the
deduction and *reported*. The master learns WHAT is missing instead of receiving a confidently
wrong number — the same principle as the import review's «Джерела / Відсутнє» block.

## Testing

`AlbumFixtureHarnessTest` replays **three real project albums** (`src/test/resources/album-fixtures/`:
an OTDL apartment, a Clearline house, a Clearline one-room) through the calculators. So a formula
change is caught against known-good output **without spending a single LLM call** — which is what
makes it safe to keep editing these formulas at all. `ClaudeAlbumExtractorTest`,
`RoomSurfaceCalcTest`, `ElectroTakeoffCalcTest` and `TakeoffServicesTest` cover the rest.

## What is missing — and it is not small

**No controller calls either service, and there is no job runner to host them.** The feature is
unreachable from the product today. The services' own docs are explicit that a run is *minutes*
of wall clock and must never sit on a request thread — which is correct, and there is currently
nowhere else to put it (`@Async` exists only for email and push).

Raised as its own open question, including the real decision (a DB-backed job row polled by
`@Scheduled`, versus fire-and-forget, versus a queue) and the constraint that matters: a paid
multi-pass Opus run must never be **silently** lost.

## Gotchas
- `ClaudeAlbumExtractor` is deliberately **outside** the `AiFlow` / `AiExtractors` registry — it
  needs much longer timeouts than the seam currently carries. It joins when the seam learns to.
- Don't "simplify" the two services into one. The split is the product decision, not duplication.
