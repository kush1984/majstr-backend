# The master's own comment on an estimate line

- **Status:** ✅ Backend + PWA, not pushed yet (owner tests first).
- **Migrations:** V206 (`estimate_items.note`, `estimate_items.note_visible`), V207 (`catalog_items.note`,
  `estimate_items.note_own`).
- **PWA:** 1.80.0.
- **Goal (webinar, 2026-11-11):** «Можно ли в позициях вставлять комментарии как в excel?» — «у 2 шари»,
  «матеріал замовника», «без демонтажу».

## Owner's rulings (2026-11-14)

- ~~The comment is written on the estimate line only.~~ Revised the same day: the **catalog position**
  has a comment too, and it reaches the estimates — see «The catalog's comment (V207)».
- **The client sees it** — on his page and in the PDF — and the master can **switch that off per line**.

## What changed

- **Two columns beside `description`, not in it.** `description` is the library's explanation snapshotted
  from the catalog (V119) and deliberately stays off the client's surfaces («приберемо для клієнта»);
  `note` is the master's own words, `note_visible` (default TRUE) says whether the client reads them.
- **`EstimateItemRequest.note` / `noteVisible` are three-valued**: absent leaves the stored value alone,
  blank clears the note. An inline price edit, or an older build replaying its outbox, sends neither and
  can never wipe a comment. The 33 Java call sites keep compiling through an 11-argument constructor.
- `EstimateLineNote` is the one rule for what the client may read (`forClient`: the trimmed note when
  visible, else null) — used by the portal line view, the PDF and the signed snapshot.
- **Copies:** `duplicate` and `copyForConsolidation` carry the note and its visibility. Templates, imports
  and acts do not (a template line has no comment; acts freeze their own lines — open question below).
- **Signed snapshot:** `SignedSnapshot.EstimateLine` gained `note` (only the client-visible one is frozen;
  a hidden note never enters the hashed text), `SCHEMA` 1 → 2. A document signed before keeps its stored
  text verbatim and renders with no comments.
- Client page: a muted line under the position name (`.item-note`). PDF: a smaller line in the name cell.

## PWA

- `ItemForm`: «Коментар» (textarea, 500 chars) and, once there is text, «Показувати клієнту» (ticked).
  The form sends both — it holds the whole line.
- The board row (both the inline-edit and the plain variant) shows the comment under the name via
  `LineNote`; a hidden one carries a 🔒 «Клієнт цей коментар не бачить».
- The optimistic add/update patch mirrors the server's three-valued rule.

## The catalog's comment (V207, owner's ruling 2026-11-14, later the same day)

«Робимо можливим добавляти коментар в каталозі, і тоді він зразу підтягується до всіх кошторисів, які ще
не підписані клієнтом… а в кошторисі також залишимо». And: «якщо в кошторисі щось змінили і потім в
каталозі, то вже кошторисного не переписуємо від каталогу… щоб не було так, що ми тихо щось перетерли».

- `catalog_items.note`; `CatalogItemRequest.note` is three-valued like the line's (a 7-argument
  constructor keeps every caller that does not touch it). The inline row (name / price) never sends it.
- **Copied into a line** wherever the catalog is the source: `addItemFromCatalog`, the batch, `applyTemplates`
  (riding the same name match as the price) and the sample object; plus both PWA offline twins
  (`patchEstimateWithCatalogLines`, `applyTemplateOffline` — which sends it as a request field).
- **A change reaches the lines** (`CatalogService.applyNote`, on `update` and on the upsert in `create`):
  this master's estimates whose status is not SIGNED, the same type + unit + folded name (the old name and
  the new one on a rename), and only a line whose comment **still equals the catalog's previous one**. The
  estimate is touched (B-86), so a client holding the page gets 409 on sign and reloads.
- **Never overwritten:** a line whose comment the master changed in the estimate (`updateItem` sets
  `note_own` once the text differs) — even if he later types the catalog's text back — and a line whose
  comment is simply different (typed when it was added, or imported). Visibility is per line and is never
  touched by the catalog.
- PWA: «Коментар» in the catalog form, with one hint line «Підставляється в кошториси, які клієнт ще не
  підписав». The catalog row keeps ONE quiet line under the name: the master's comment when there is one,
  else the library's explanation (which keeps its (i)). A saved comment refetches the cached estimates.

## One comment, behind the (i) (owner's ruling 2026-11-14, third round)

«Якщо є коментар — показувати його в (i), так як зараз підказки показуються, і ті наші підказки давай
також трактувати як коментарі, приєднані позиції можемо все змінювати чи видаляти.»

- **The library hint (`description`) and the master's comment (`note`) are one comment to him.** Shown as
  `note ?? description` (`commentOf`), behind an (i) beside the estimate row and the catalog row — no line
  under the name on an estimate (the catalog keeps its one clamped line). A hidden note says «🔒 Клієнт цей
  коментар не бачить» inside the (i).
- **Editable and clearable alike.** `ItemForm` and `CatalogItemForm` prefill «Коментар» with that comment
  and send `note` ONLY when it changed — so an untouched hint stays a hint the client never reads (no
  visibility box, a one-line «Підказка з каталогу…» under the field). Any sent `note` (line or catalog)
  also sets `description = NULL`: his comment replaces the hint, and clearing it does not bring the hint
  back. The read-only «Що це означає» panel in the line form is gone.
- The price list shows `note ?? description` under a work.
- Client page: the comment sits in its own quiet panel inside the name cell (smaller, warm paper, a thin
  amber edge).
- **Session highlight restored** (it had been a dot since «calm cells», 2026-10-27): the line just changed
  is painted `bg-success-soft`, the ones changed earlier this session `bg-brand-soft/30`; no dots.

## Not changed / open

- Work acts do not carry the comment (same gap as the line `description`, already an open question).
- Dictation and imports do not copy the catalog's comment (the same rule as `description`).
- The estimate's catalog picker does not show the comment (kept quiet on purpose).

## Tests

- `EstimateItemNoteIntegrationTest`: three-valued write (absent keeps, blank clears), the client view
  with and without visibility, duplicate carries both fields, the signed snapshot holds a visible
  comment and never a hidden one (and the signed page renders it from the snapshot).
- PWA `ItemForm.test` (sends note + visibility, emptied field clears), `InlineItemRow.test` (shown under
  the name, the lock on a hidden one, shown on a signed estimate).
- Request contract: `EstimateItemRequest` gained `note` and `noteVisible` in both snapshots;
  `CatalogItemRequest` gained `note`.
- V207: `EstimateItemNoteIntegrationTest` — a line added from the catalog copies it; a change reaches an
  unsigned estimate (and moves its version), never a signed one nor a line he changed; an inline price
  edit moves nothing; clearing clears; changed-and-changed-back stays his; a different comment is left
  alone. PWA: `CatalogItemForm.test` (shown, saved trimmed, emptied = blank), `CatalogInlineRow.test`
  (one line, the comment over the description), `useEstimate.test` (the offline line carries it).
