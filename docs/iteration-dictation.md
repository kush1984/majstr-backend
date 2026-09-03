# Iteration: dictation, cut 0 — free text → positions matched against the master's own catalog

**Status:** code complete, backend build green (`./gradlew build`), PWA gate green in CI order,
NOT pushed (awaiting the user's approval).
**Source:** the "Voice input of a position" open question (catalog-picker iteration, 2026-09-01),
promoted by the master with «так, берись» (2026-09-01).
**Migrations:** none — nothing new is stored.
**PWA:** 1.36.0 → **1.37.0** (minor — a new capability on the estimate editor).

---

## 1. What this cut is, and what it deliberately is not

The situation the open question named is real: a master on an object, gloves on, phone in a dusty
hand, typing a Ukrainian position name into a small field. The question it also raised was whether
that wants **audio from us** — and the answer for cut 0 is no.

**We add no audio, no recording and no transcription.** The field is plain text; the microphone is
the one already on the master's **phone** keyboard, where 95 % of the product is used. The OS
transcribes Ukrainian on the device, for free, better than a Web Speech API path that does not
exist on iOS Safari at all.

**Windows voice typing (`Win+H`) has no Ukrainian** — verified on the master's own machine
(2026-09-03): it refuses with «Голосовий ввід недоступний для поточної мови: українська» before anything
reaches us. This cost us nothing but the desktop TESTING story (type or paste the text instead —
dictation is only an input method, and everything we built starts once the text is in the field),
and it leaves the premise itself **unproven until it is tried on a phone**: that the OS already
dictates Ukrainian well is the whole reason this cut records no audio. What the OS *cannot* do is the part we add:
splitting «поклеїти шпалери двадцять квадратів по 250» into a position, a number and a unit, and
pinning it to **his** price list.

**So the entry point is hidden on a desktop entirely** (master's call, 2026-09-03 — «наразі не
будемо пропонувати надиктовку на компʼютерах»): `lib/deviceInput.ts` → `hasOnScreenKeyboard()`
asks `matchMedia('(pointer: coarse)')`, and the 🎤 FAB item renders only when it is true. There is
no API for «can this OS dictate Ukrainian into a field», so this is a proxy and a deliberate one:
a coarse PRIMARY pointer means a finger, which is exactly the class of device whose keyboard is
drawn on screen with a 🎤 on it. A touchscreen laptop reports `fine` (its primary pointer is the
trackpad) — the answer we want there. No `matchMedia` → `false`: rather not offer it than offer it
where the OS cannot honour it. The backend endpoints are **not** gated on this — the check is about
not advertising an input method, not about who may call `parse`.

Explicitly out of this cut, all still open: recording audio, server-side transcription, offline
support, a PRO gate, and learning his synonyms («шпалери» → «Поклейка шпалер») across sessions.

**Ungated on purpose.** The point of cut 0 is to find out whether dictation is worth having at all;
a PRO wall would answer a different question. What bounds it is a per-account hourly counter, the
same shape as the receipt-scan pass.

## 2. The rule the whole flow hangs on

> A position we could not pin to the master's catalog comes back **flagged**, never quietly priced
> at 0 ₴.

An estimate line the client signs is a number the master committed to. A silent 0 ₴ line is the one
failure mode that turns a convenience into a loss, so the response says plainly what it could not
establish, and the review screen is where the master sees it — the whole reason the lines are not
appended straight away.

`DictationParseResponse.DictationItem.issues` is that channel, reusing `ReceiptLines`' token
convention and adding one: `"catalog"` (nothing in his price list matched), `"unit"`, `"quantity"`,
`"price"`.

**Spoken values win, the catalog fills the blanks.** A master who says «по 250 гривень» means it and
must not be overwritten by his own default; a master who says nothing about price gets the catalog's.

## 3. Backend

| File | What |
| --- | --- |
| `service/ai/AiFlow.java` | new `DICTATION` constant — the only flow here that reads no picture at all |
| `application.yml` | `app.ai.flows.dictation` **pinned to `claude-sonnet-5`** + `app.rate-limit.dictation.max-per-hour` (default **60**) |
| `config/RateLimitProperties.java` | nested `Dictation(maxPerHour)` record |
| `service/DictationRateLimiter.java` | Bucket4j, per account, its own bucket |
| `service/importer/DictationExtractor.java` | text → `Spoken(name, unit, quantity, unitPrice, type)` |
| `service/importer/CatalogMatcher.java` | spoken name → his `catalog_items` row, or nothing |
| `service/importer/DictationService.java` | the two operations, non-`@Transactional` |
| `controller/DictationController.java` | `POST /api/estimates/{id}/dictation/parse` + `/commit` |
| `dto/Dictation{ParseRequest,ParseResponse,CommitRequest}.java` | own DTOs, not the receipt ones |
| `messages*.properties` | `error.rate.dictation` (uk + en) |

### Its own rate-limit bucket, not a share of the receipt ones

Same decision as `QrScanRateLimiter`, for the same reason: the passes answer different questions,
and a day spent photographing receipts must never be why dictation stops working. 60/hour is far
above a master dictating a whole flat's estimate, and low enough to bound an accident.

### The catalog is NOT sent into the prompt

Considered and rejected for cut 0. A master's catalog runs to ~900 names; putting it in the prompt
costs tokens on every call and, worse, invites the model to match *everything* to something — which
is precisely the silent-0 ₴ failure the flagging rule exists to prevent. The model only splits the
sentence; the matching is deterministic and inspectable in `CatalogMatcher`.

### `CatalogMatcher` — exact first, then a stemmed Dice, and a tie is refused

1. **Exact on the normalized name** (lowercased, apostrophes dropped, other punctuation collapsed to
   single spaces). Two exact hits (the same name under two units) → refuse, do not guess.
2. **Dice coefficient over word stems**, `MIN_SCORE = 0.6` **and** `MIN_MARGIN = 0.1` over the
   runner-up. The margin is what makes a Q3/Q4-style pair answer "I don't know" instead of picking
   the alphabetically luckier row.

**`STEM_LENGTH = 4` is load-bearing** and was found by a failing test, not chosen: Ukrainian
inflects on the tail, and at five letters «стіни» (spoken) and «стін» (catalog) stay two different
tokens — which made «штукатурити стіни по маяках» score *exactly* as high against the CEILING row as
against the wall one, and a tie is refused. A digit-bearing token is never truncated.

### Not `@Transactional`, on purpose

A model call must never pin a pooled Hikari connection. Ownership and the not-signed check run in
their own short transaction up front (so a signed or foreign estimate never spends a call), the
catalog is loaded only after the call returns, and an empty extraction never touches the catalog at
all. Same shape as `ReceiptImportService.parse` and `WorkActReceiptService.recognize`.

`commit` goes through `EstimateService.appendItems`, so a SIGNED estimate is refused by the one
guard all eight write paths share — nothing new is invented for it.

### An untyped line defaults to WORK

The opposite of a receipt's default, and deliberately: a contractor dictating an estimate is listing
his own work; a receipt is goods.

## 4. PWA

- **`src/api/dictation.ts`** — `parse` / `commit`. `/dictation/parse` already qualifies for the
  long-timeout rule in `api/client.ts` (it ends in `/parse`), so it does not inherit the 12 s
  fail-fast write default that once made `…/receipts/recognize` abort mid-call.
- **`src/features/estimate/DictationSheet.tsx`** — three steps: a large text field → parsing → the
  same editable review shape as `ReceiptImportSheet` (name, quantity, unit, price, WORK/MATERIAL
  toggle, 🗑/↩ per row, one «Додати N»).
  - a row with **no catalog match** is amber and says «Немає у вашому каталозі — впишіть ціну»;
  - a matched row whose wording differs shows «Сказано: …», so a **wrong** match is visible rather
    than silently accepted;
  - **missing price warns, missing unit blocks.** A unit is required by the estimate line, so
    «Додати» is disabled without one; an unpriced line is legal (he may price it in the editor) but
    the count is named *before* he taps «Додати» — never something he finds out about later;
  - a failed read returns to the input step **with his text still in the field**.
- **`EstimateEditorPage.tsx`** — a 🎤 «Надиктувати позиції» item in the 🧾 FAB, above the receipt
  one, `!signed`, behind `guard(...)` (online-only: the parse is a model call) **and behind
  `hasOnScreenKeyboard()`** — phones and tablets only, see §2.
- **i18n** — a `dictation.*` block in uk and en.

## 5. Tests

**Backend** — `CatalogMatcherTest` (8: exact wording, spoken inflection, apostrophes, one shared
word is not a match, a Q3/Q4 tie is refused, digits are never stemmed away, the same name under two
units is refused, empty inputs), `DictationExtractorTest` (6: sentinels → nulls, a fully spoken line
keeps its numbers, a nameless row is skipped, empty JSON → empty list, unusable JSON throws, the
text rides `AiFlow.DICTATION`), `DictationServiceTest` (8, including **the** case — an unmatched
position comes back `["catalog","price"]` with `unitPrice()` **null**, not `BigDecimal.ZERO`),
`DictationRateLimiterTest` (3: the cap, per-account independence, and exhausting dictation leaves
both receipt buckets untouched), `DictationControllerTest` (5: the proposal, 429 +
`Retry-After` without reaching the model, blank text is 400 **before** the limiter is charged,
commit is not rate-limited, empty commit is 400).

**PWA** — `DictationSheet.test.tsx` (6), covering the unmatched-row flag and its empty price field,
the spoken-name line, the unit block, the kept text on failure, and the way back from an empty read.

**Gate:** backend `./gradlew build` green; PWA green in CI order — `npm run lint` · `npx tsc -b` ·
`npm run typecheck:tests` · `npx vitest run` (**842** tests, 116 files) · `npx vite build`.

## 6. Not verified

- **The mobile layout was not opened in a live browser this round.** The sheet is a `Modal size="lg"`
  whose review rows are the markup of `ReceiptImportSheet` (already phone-verified) — full-width
  inputs in a `grid-cols-2`, a `max-h-[55dvh]` scroll area, full-width buttons — plus a `w-full`
  textarea at `text-base` (16 px, so iOS does not zoom on focus). No horizontal overflow is possible
  from the new markup, but this is reasoning, not a screenshot.
- **No real dictated text has gone through the model yet.** Whether the prompt splits a real
  Ukrainian sentence well enough is exactly what this cut exists to find out.
