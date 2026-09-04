# Iteration: dictation — free text → positions matched against the master's own catalog

**Status:** cut 0 shipped 2026-09-02 (PWA 1.37.0); **cut 1 code complete** (2026-09-04), backend
build green (`./gradlew build`), PWA gate green in CI order, NOT pushed (awaiting the master's
approval).
**Source:** the "Voice input of a position" open question (catalog-picker iteration, 2026-09-01),
promoted by the master with «так, берись» (2026-09-01); cut 1 promoted 2026-09-04 with «давай, але
враховуй всі моменти для айосу».
**Migrations:** none in cut 0; **V124 `catalog_item_synonym`** in cut 1.
**PWA:** 1.36.0 → 1.37.0 (cut 0) → **1.38.0** (cut 1 — new capabilities: in-app microphone,
save-to-catalog per row, learn-a-synonym per row).

---

## 1. What this cut is, and what it deliberately is not

The situation the open question named is real: a master on an object, gloves on, phone in a dusty
hand, typing a Ukrainian position name into a small field. The question it also raised was whether
that wants **audio from us** — and the answer for cut 0 is no.

**We add no audio, no recording and no transcription.** The field is plain text; the microphone is
the one already on the master's **phone** keyboard, where 95 % of the product is used. The OS
transcribes Ukrainian on the device, for free, and more reliably than a Web Speech API path.

> **Correction (2026-09-04, cut 1).** This paragraph originally said the Web Speech API "does not
> exist on iOS Safari at all". **That was wrong.** `webkitSpeechRecognition` has shipped in iOS
> Safari since **14.5** (2021). The real limitation is narrower and worse for us: it does not work
> inside a PWA installed to the home screen (a WKWebView container) — detection succeeds, no
> microphone permission is requested, and nothing happens. Our manifest is `display: 'standalone'`,
> so that is our case. The conclusion for cut 0 is unchanged (the OS keyboard is the right answer on
> iOS); the reason is different, and cut 1's in-app microphone is built around the real one. See
> §7.

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

## 7. Cut 1 (2026-09-04) — in-app microphone, save-to-catalog, synonyms

Cut 0's premise held: the master dictated on Android and reported «з клавіатури з андроїда
надиктовка працює супер». So the want WAS «dictate», the picker had not already answered it. Cut 1
adds the three parts he asked for after using it: an in-app microphone («натиснув надиктувати і воно
відкрило мікрофон»), an offer to save an unmatched position to his catalog, and synonyms.

### 7.1 The iOS fact this cut is built around

The claim shipped in cut 0 (and in the correction paragraph in §1) that iOS Safari has no
`SpeechRecognition` was WRONG: it has had `webkitSpeechRecognition` since Safari 14.5 (2021). The
real limitation is narrower and worse for us: **it does not work in a PWA installed to the home
screen.** An installed PWA runs in a WKWebView-based container where Apple has not enabled the
speech service; feature detection SUCCEEDS, no microphone permission is ever requested, and nothing
happens. Our manifest is `display: 'standalone'`, so that is our case. Two more iOS-specific traps:
`continuous = true` hangs the microphone (it never stops and no result arrives), and the failure
surfaces as `service-not-allowed`.

**Consequence:** the microphone is a **ladder that degrades and never fails.** `speechAvailability`
in `lib/speech.ts` refuses `installedIos` BEFORE looking for the constructor (the load-bearing
order — a feature check would pass there and the mic would silently never open). What it degrades
to is what already ships from cut 0 — the plain text field plus the OS keyboard's own microphone.

The FAB gate widened to `hasOnScreenKeyboard() || speechAvailability() === 'ready'`, which is why
the entry point now shows on desktop Chrome too — Windows voice typing has no Ukrainian, so the
in-app one is the only way to dictate there at all.

### 7.2 What ships in cut 1

#### Backend

| File | What |
| --- | --- |
| `db/migration/V124__dictation_synonyms.sql` | new table `catalog_item_synonym` (per-master, `UNIQUE(owner, spoken_normalized)`, FKs CASCADE to `users` and `catalog_items`) |
| `entity/CatalogItemSynonym.java` | JPA entity mirroring the shape |
| `repository/CatalogItemSynonymRepository.java` | `findByOwnerId` + `deleteByOwnerIdAndSpokenNormalized` (delete + insert is how the app enforces uniqueness, not the DB throwing) |
| `service/importer/CatalogMatcher.java` | second `match(spoken, catalog, synonyms)` overload; **a synonym wins outright**, before the Dice pass; `normalize` now `public` so the write path stores the same key the read path looks up |
| `service/importer/DictationService.java` | loads per-master synonyms once per parse; `teachSynonym(ownerId, req)` writes them |
| `controller/DictationSynonymController.java` | `POST /api/dictation/synonyms` (204), NOT under `/api/estimates/{id}/dictation` — a synonym is per-master, not per-estimate |
| `dto/DictationSynonymRequest.java` | `{catalogItemId, spokenText}` |

**Why the FK is CASCADE, not RESTRICT** (decision, see open-questions.md → "A learned synonym
outlives the catalog position it points at"): a synonym for a position that no longer exists is not
a fact about anything, and keeping it would resurrect a deleted position's name in a match a week
later, silently. A **rename** deliberately keeps the synonym pointing at the same row — the row is
the same job under a new wording.

**Why the synonym check is BEFORE the Dice pass.** A wording the master TAUGHT the system must not
be overridden by a longer name that happens to share more stems. Learning has to be authoritative or
it is a lie; the whole reason we teach is that the Dice ladder cannot resolve it.

#### PWA

| File | What |
| --- | --- |
| `lib/speech.ts` | `SpeechAvailability = 'ready' \| 'installedIos' \| 'unsupported'`; the ladder that refuses installed-iOS BEFORE feature detection |
| `hooks/useSpeechDictation.ts` | one utterance per tap (`continuous = false` — never true, iOS-hang rule); every runtime error takes the button off the screen for this session (`denied`/`service`/`audio`/`network`); `no-speech`/`aborted` are not failures |
| `features/estimate/DictationSheet.tsx` | mic button when `available`; per-row «Зберегти в мій каталог» (offered on a miss, DEAD until priced — a 0 ₴ catalog row is exactly what the flagging pass exists to prevent, and saving one here would let the NEXT dictation match it and price the line at 0 silently); per-row «Наступного разу розпізнавати X як цю позицію» (offered on a matched-but-different row); both writes run AFTER the estimate lines land, a failure never rolls back the commit |
| `features/estimate/EstimateEditorPage.tsx` | FAB gate widened to `hasOnScreenKeyboard() \|\| speechAvailability() === 'ready'` (desktop Chrome is now an entry point too) |
| `api/dictation.ts` | `saveSynonym(catalogItemId, spokenText)` |
| `locales/{uk,en}.json` | `hintMic`, `micStart`/`micStop`/`listening`, four `mic*` block reasons, `saveToCatalog`/`saveNeedsPrice`, `saveSynonym`, `addedAndSaved` / `learnedSynonyms` (with plurals), `catalogSaveFailed`/`synonymSaveFailed`; `reviewHint` reworded (it used to say "prices are not added to the catalog") |

**Why no trade picker on save-to-catalog.** A dictation review is the wrong place for two more
pickers; the position lands in «Інше» and the master re-files it if he cares. This makes the «Інше»
pile grow — logged in open-questions.md.

**Why the synonym tick has no "unmatched row" equivalent.** A synonym is a pointer at a row that
already exists; on an unmatched row there is nothing to point at. The correct move on a miss is
«save to catalog» — next time the exact-name rung matches without needing a synonym.

### 7.3 Tests

**Backend** — `CatalogMatcherTest` gains 3: synonym wins over Dice, a synonym pointing at a deleted
row is silently ignored, the read-path key equals the write-path key.
`DictationServiceTest` gains 3: a taught synonym pins a spoken wording to the catalog row;
`teachSynonym` deletes any existing row for the same wording first (the app enforces uniqueness);
teaching against somebody else's catalog item is 404; blank-after-normalization is rejected.

**PWA** — `lib/speech.test.ts` covers the 5 branches of `speechAvailability` (installed iOS refused,
iPad-as-Mac refused, iOS Safari in the browser ready, desktop Chrome ready, no constructor
unsupported). `hooks/useSpeechDictation.test.tsx` covers start/stop lifecycle, `not-allowed` blocks,
`no-speech` does not, and final vs interim results. `DictationSheet.test.tsx` gains 3: save-to-catalog
DEAD until priced, synonym tick offered only on matched-but-different, a failing synonym save never
rolls back the commit.

**Gate:** backend `./gradlew build` green; PWA green in CI order — `npm run lint` · `npx tsc -b` ·
`npm run typecheck:tests` · `npx vitest run` · `npx vite build`.

### 7.4 Not verified

- **iOS is still untried** — the master reported the OS keyboard microphone works on Android; the
  in-app path in installed iOS PWA is what `speechAvailability` refuses without needing the phone.
- Mobile layout of the new tick + microphone button not opened in a live browser this round.
