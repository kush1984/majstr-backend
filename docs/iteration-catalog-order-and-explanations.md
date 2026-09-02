# Iteration: the catalog gets an order, foreign folders stop leaking, and a position explains itself

**Status:** code complete, not committed, not pushed.
**Migrations:** `V118__catalog_order_and_shared_categories.sql`, `V119__estimate_item_description.sql`
**PWA:** 1.32.0 → **1.33.0**
**Trigger:** two screenshots of the master's own catalog (`/catalog`, ГІПСОКАРТОН chip) taken right
after the DRYWALL rebuild landed — see [iteration-drywall-catalog.md](iteration-drywall-catalog.md).

## 0. What he actually said

> «на першому скріні в нас є Оздоблення під фарбування і слідуюче за ним іде Шпаклювання та
> шліфування, але перша категорія має в собі Шліфування стиків, я б напевно все переніс під
> Оздоблення, і оце що ми додали Q3, Q4 — якщо таке попаде в кошторис, звідки клієнт має знати що це
> таке? та і сам майстер може не знати, бо не всі вкурсі таких рівнів, треба тут і з поясненням, а в
> порталі клієнта і на пдф — розшифрування тих позначень. Армування склополотном, Заповнення стиків
> ГКЛ з паперовою стрічкою високої щільності, Нанесення верхнього клею і т.д., всі оці позиції з Q4 —
> чому ти їх не додав з того пдф документа що я тобі скинув? Тепер по другому скріні — що тут робить
> категорія Шпалери? Підготовка, Підготовка та захист, Оздоблення — це впринципі одна і та сама
> категорія — Підготовка та захист, обєднай будь ласка, Звукоізоляція і Кладка — треба прибрати тут
> взагалі, давай це все пофіксаємо будь ласка»

and, separately:

> «ага, і ще позиції нам треба посортувати — перше беремо Підготовку, а потім вже роботи»

Seven items. **Only two of them turned out to be data problems.** Four were the same read-path
defect wearing four different folder names, and one was a question, not a bug.

## 1. The diagnosis that changed the plan

«Шпалери», «Шпаклювання та шліфування», «Оздоблення», «Звукоізоляція» and «Кладка» on the DRYWALL
chip are **not** drywall rows filed wrong. `catalog_items` has one row per
`(owner, name, type, unit)` — there is no room for a second — so a position that two of a master's
trades both ship under identical wording is stored **once**, tagged whichever trade claimed it
first, in **that** trade's category. V116 PART 7 deliberately copied ten PAINTER/DEMOLITION
positions verbatim into DRYWALL phases (re-wording one would hand a two-trade master two rows —
the exact defect V116 existed to undo), so his copy lives under PAINTER and the DRYWALL chip showed
him PAINTER's folder.

Re-filing the row would have moved the same foreign folder onto the PAINTER chip. So the fix is in
the **read path**, not in the data: a row now says which category **each** sharing trade keeps it
under, and the client re-files it for display while that trade's chip is the one selected.

`sharedTrades` went from `List<Trade>` to `List<SharedTrade>` (`record SharedTrade(Trade trade,
String category)`) on `CatalogItemResponse`; the PWA's `asSelectedTradeSees` does the re-filing, and
does it **only when exactly one system trade is selected** — with no filter, or several, there is no
single answer to «whose category?» and the stored one is the honest default. It returns the same
object identity when nothing moves, so memoized rows stay cheap.

«Кладка» was the one genuine orphan and is handled in data (V118 PART 2, below).

## 2. V118 — the data half

| Part | What it does |
| --- | --- |
| 1 | DRYWALL `Підготовка` → **`Підготовка та захист`** (his wording; the name now collides with PAINTER's category of the same name, which is the point — `catalog_items` groups by category NAME, so a two-trade master sees ONE prep folder). |
| 2 | Re-homes LIBRARY rows **orphaned by a rebuild that retired their name** — his «Кладка» pair: V116 dropped those names from DRYWALL, BUILDER still ships them, he has BUILDER. Deliberately narrow: LIBRARY only, only when the stored trade no longer ships the name, and only when **exactly one** trade he actually has still does. Two candidates means guessing, and a guess silently moves a priced position out of his sight. |
| 3 | **`catalog_templates.sort_order`** — the library finally states the order the work is done in. Rank is GLOBAL (trade → coarse category rank → name) so a six-trade master gets his categories clustered by trade instead of interleaved. Four category groups: demolition, preparation, the work, overheads/«Інше» last. DRYWALL carries an explicit phase sequence because V116 rebuilt it as one and alphabetical order prints Звукоізоляція before Каркас. |
| 4 | Renumbers **every master's** `catalog_items.sort_order` from it. A row he typed himself has no template, so it takes the rank of the first library row in the same `(trade, category)` — landing inside its folder rather than at the end. |
| 5 | Self-check: the DRYWALL category set, no template left at rank 0, no shared rank, no `(owner, sort_order)` collision. |

**Why PART 3/4 exist at all** (item 7): V87 backfilled `sort_order` **once**, alphabetically, and
nothing has maintained it since. Every row a master received afterwards — from a migration or from
«Додати нові з каталогу» — landed on the DEFAULT 0, because `CatalogTemplateService.missingItems`
never set one. **145 of this master's 935 rows sat at 0**, so PostgreSQL returned them in whatever
order it liked and their categories floated to the top of the page. `missingItems` now sets it too;
without that fix the next migration would re-create the mess V118 just cleaned.

Overwriting `sort_order` wholesale is safe: the catalog board has never had drag grips (*«a catalog
is a reference list a master searches and prices, not one he arranges»*), so
`PUT /api/catalog/items/order` has no caller in the app.

## 3. V119 — a line can explain itself (items 2 and 3)

V116 already put the sentence on `catalog_templates`/`catalog_items`, so the master can read it in
his own library. **It stopped at the library.** «Підготовка ГКЛ під фарбування · Q4 (еліт)» reached
the client as a bare name in the portal and in the PDF, and «Q4» is a plasterer's word.

`estimate_items.description VARCHAR(500)`, and the line carries its **own copy** rather than
joining the catalog on read — the same rule as every other field on that table (*estimate lines are
snapshots*): **the client signed THIS wording.** Re-pricing a position, re-wording it or deleting it
from the catalog must never change what a signed estimate says. Pinned by
`aSignedEstimateKeepsTheWordingItWasSignedWithEvenAfterTheCatalogIsReworded`.

Where the copy is taken:

- `addItemFromCatalog` / `addItemsFromCatalogBatch` — straight from the source position.
- `EstimateTemplateService.applyTemplates` — from **the catalog position the line priced from**. A
  bundle carries no explanation of its own; both the price and the sentence resolve through the one
  `nameKey` match, so a line that got a price also got its words. This is the path that would
  regress silently: a missed match fails by producing `null`, not by throwing.
- `duplicate` (with markup) and `copyForConsolidation` — a copy keeps what it was copied from.

Deliberately **not** taken at `createFromImport`, `addItem` or `appendItems` — those have no catalog
source, and inventing a sentence would put words in the master's mouth on a document a client signs.

### Two calls worth re-raising if he disagrees

- **`description` is not on `EstimateItemRequest`**, so it cannot be edited per line, and
  `updateItem` does **not** clear it on a rename. Renames here are usually scope tweaks («…стін» →
  «…стін і стелі»), and silently dropping the client-facing explanation on a typo fix would be worse
  than an occasionally stale one.
- **Work acts are out of scope.** `work_act_item` freezes name/category/unit/price the same way, and
  its PDF and portal show the same trade names — so «Q4» is still undecoded there. That needs its
  own column and two more render surfaces; logged in [open-questions.md](open-questions.md) rather
  than half-done here.

### The four surfaces the sentence now reaches

| Surface | Treatment |
| --- | --- |
| Catalog board + catalog picker | One **clamped** line under the name, plus an `InfoPopover` **beside** the row (a button inside a button is invalid markup) holding the whole text. |
| Estimate board | One clamped line under the name. The row IS a button, so there is nowhere to put an (i) — the full text opens with the line in the editor. |
| `ItemForm` (the editor) | A read-only «Що це означає» panel under the name, with the hint **«Клієнт побачить це пояснення в порталі та в PDF»** — which is the answer to his own question, said out loud on the screen where he can still act on it. |
| Client portal + PDF | `.line-note` under the name in `static/portal/index.html`; in the PDF a two-paragraph `nameCell` (10pt name, 8pt grey explanation). The name still ellipsizes in the portal table while the note wraps — the `white-space: normal` trick the acts table already uses. |

## 4. Item 4 — the «missing» Q4 positions

> «Армування склополотном, Заповнення стиків ГКЛ з паперовою стрічкою високої щільності, Нанесення
> верхнього клею і т.д. — чому ти їх не додав?»

They were already there. V116 ships «Заповнення стиків ГКЛ паперовою стрічкою високої щільності»
(150 ₴/м.п.), «Проклеювання склополотном примикань і кутів» (60 ₴/м.п.) and shares «Поклейка
склополотна» from PAINTER. What he could not see was the **third one**, sitting under a PAINTER
folder on his DRYWALL screen — the same read-path defect as item 5. Fixing §1 makes all three
visible under ГІПСОКАРТОН.

His own calls on the rest, taken as given:

- нижній / верхній клей — **«лишаємо»** (they are stages inside a level position, not separate sales).
- «Поклейка склополотна» — **«лишаємо»**, do not rename.

## 5. Item 1 — merging the two finishing folders

«Шпаклювання та шліфування» **is** the PAINTER folder leaking through, so §1 removes it from the
drywall chip on its own; «Шліфування стиків ГКЛ» (V117) already sits in «Оздоблення під фарбування».
No data change was needed, and the rename he asked for in item 5 (**«Підготовка та захист»**) is
V118 PART 1.

## 6. Tests

**Backend**
- `CatalogOrderOnLiveDataIntegrationTest` — 7 tests against a restored copy of live data: the rename,
  the orphan re-home (and that it refuses to guess between two candidates), no row left at rank 0,
  preparation ordering before the work, and `missingItems` handing out a real rank.
- `EstimateLineExplanationIntegrationTest` — 5 tests, a real database because the field is a COLUMN
  that has to survive a round trip: picking from the catalog, applying a bundle (the silent-regression
  path), the client portal view, the re-wording immunity of a signed estimate, and duplication.
- Unit: `EstimateServiceTest` (catalog add, single + batch), `EstimatePdfServiceTest` (the shorthand
  is decoded under the line it names, exactly once).

**PWA**
- `sharedCategory.test.ts` — 6 tests around `asSelectedTradeSees`, including the two cases it must
  NOT act on (no filter / several trades) and the identity-preserving no-op.
- `CatalogPicker.test.tsx`, `CatalogPage.test.tsx` — the clamped line renders, the (i) sits beside
  the row and is offered **only** where there is something to read.
- `EstimateItemsBoard.test.tsx` — the shorthand is decoded under its own line, and a neighbouring
  line does not inherit it.
- `ItemForm.test.tsx` (new) — the full panel, its client-facing hint, its absence on a line with no
  explanation, and that it is never an input.

## 7. Not changed / confirmed

- **No price data was touched here — it was settled in the next round.** Three figures disagreed
  with his own numbers and were waiting on his word, not on code: soundproofing 80 vs 850 ₴,
  «Облаштування дверного пройому» 800 vs 1350 ₴, and the joints — which this section originally
  mis-named «Армування кладки». The third figure is PAINTER **«Армування стиків ГКЛ»** 75 vs
  DRYWALL's 100 ₴/м.п.; «Армування кладки» is a separate, still-unadjudicated BUILDER pair (90 ₴/м.п.
  vs 50 ₴/м², his own copy at 100) and stays in [open-questions.md](open-questions.md). He answered
  «беремо 850, 1350 і 100» — shipped in **V120**, see
  [iteration-drywall-catalog.md](iteration-drywall-catalog.md) §14.
- **No row was re-filed to fix a foreign folder** — §1 explains why that would only move the problem.
- `CatalogItemRequest` still has no `description`: a PATCH omitting it would null the text.
- The catalog board still has no drag grips; `sort_order` is the library's opinion, not the master's.

---

## 8. Round 2 — the folders were STILL out of order, and the reason was not ranking

> «в нас знову для категорій гіпсокартону спочатку іде каркас і обшивка, а потім підготовка, ми ж
> казали це сортувати по порядку виконання робіт, **запамятай то якось**»

§2's V118 ranked `catalog_items.sort_order` off the library and renumbered every master's rows, and
that half was right. It was not the half that decides where a FOLDER opens on the board.

**The board opens a section where its first ROW appears** (`toSections` groups in `sortOrder` order).
So the folder order is a consequence of the row order — and V118's rank is per-row, computed from
(trade, category, name) at the moment it ran. Any row written since then, and any row whose category
moved under it (V116 re-filed ten positions by phase), lands wherever its own number puts it, and one
early «Каркас і обшивка» row is enough to open that folder above «Підготовка». Renumbering again
would fix today's data and last exactly until the next write.

So the ORDER OF FOLDERS became a read-path fact, like §1's foreign category:

- `catalog_templates` already knows the sequence (V118's global `sort_order`), so the library can
  answer «where does this category sit in this trade's sequence» directly —
  `CatalogTemplateRepository.findCategoryRanks()`, `TRADE|category → rank`.
- It rides the read as **`CatalogItemResponse.categoryOrder`**, and — because §1's `SharedTrade`
  already re-files a row's category for display — on **`SharedTrade.categoryOrder`** too, so a row
  shown under another trade's folder sorts with THAT trade's sequence, not its own.
- `toSections` takes an optional `sectionRank`. The catalog passes `categoryOrder`; **the estimate
  board must never pass one** — there the order IS the master's drag. Inside a section `sortOrder`
  still rules, so a position the master typed himself stays where he put it.
- A category the library ships nothing for (a folder he invented) has no rank and sorts **last**,
  which is the honest answer: we have no opinion about where his own folder belongs.

**Why this is the durable fix and the renumber was not:** nothing has to be re-run after a
migration adds, retires or re-files a position. The rank is derived on every read from the same
column V118 already maintains.

Saved as a memory (`catalog-category-execution-order.md`) because this is the **third** time the
complaint has come back, each time about a different half of it.

## 9. Round 2 — the client stops reading any of it

> «по порталі і пдф — давай ми це все, я про ті дескрипшини, стандарт робіт і (і) **приберемо для
> клієнта взагалі покищо, йому це не треба**»

§3 (V119) shipped the line's own explanation onto four surfaces, two of them client-facing, and V121
added a «Стандарт робіт» card for the finish level. Both are now **master-side only**:

- `PublicEstimateItemView` loses `description`; `PublicEstimateView` / `PublicPortalView` lose
  `qualityNote`; `static/portal/index.html` loses `.line-note` and the row `(i)`.
- `EstimatePdfService` prints neither the per-position paragraph nor the quality block.

**The COLUMNS stay** — `estimate_items.description` and `estimates.quality_note` are still snapshots,
still copied at apply time, still shown in the app. Nothing is dropped and no migration is needed;
only the two client renderers stopped reading them, so bringing them back is a render change. That is
what «покищо» buys: the data has been accumulating correctly the whole time.

The portal's own layout fix from the same round stays: the name column **wraps** instead of
ellipsizing to one line, which on a phone cut «Підготовка ГКЛ під фарбування…» off exactly where the
meaning is.

## 10. Round 3 — the picker becomes a TREE, and the trade chips go (PWA only, v1.35.1)

> «якщо вибрати декілька трейдів, то не зрозуміло яка категорія до чого відноситься і це рівно то що
> писав майстер, що списки зовсім не зрозумілі, тому пропоную прибрати чіпси і зробити дерево,
> трейд->категорії->позиції і сортування категорій має бути таке як в каталозі»

**No backend change, no migration.** Everything the tree needs already rides the read: V118's
`categoryOrder` (per trade — see §8) and `sharedTrades`.

### The defect

`CatalogPicker` grouped into categories and left trade as a chip row, on an argument written into its
own doc comment: a master works one trade, so a trade level would be a tap that answers nothing. That
was true of him then. With two trades ticked the list is a run of folders — «Каркас і обшивка»,
«Підготовка», «Шпалери» — that are already **contiguous per trade** (V118 ranks trade first), and
nothing on screen says where one trade ends. So the folders answered "what kind of work is this" and
re-created the original complaint one level up: «списки зовсім не зрозумілі».

**The ordering was not the defect and nothing was re-sorted.** Reported as invited by «якщо бачиш
щось не то в сортуванні, то пиши або поправляй»: the flat list's sequence was already right, the
trade LABEL was missing. The tree is a pure regrouping — same rows, same order, one level added.

### What shipped

- **`features/catalog/catalogTree.ts`** — `toTradeTree(items): TradeBranch[]`, branch = trade +
  `Section[]` (`toSections(list, catalogSectionRank)`, the same grouping the catalog board uses) +
  its own count. Folders inside a branch keep the library's execution order; branches come out in the
  order their FIRST folder already had, so a tree over a flat list shows the same sequence.
- **`CatalogPicker`** loses `TradeFilterChips`, `tradeFilter` and `asSelectedTradeSees`; the type
  chips and the search stay. One branch renders **no trade level at all** — the rule the chips
  already had (they hid themselves under two chips), so a one-trade master's screen is unchanged.
- Open state is keyed **per level and per branch** (`t:<tradeKey>` / `c:<tradeKey>|<category>`):
  «Підготовка» is a phase in several trades, and a shared key would open both folders together.
- Mobile: three levels are separated by COLOUR, not indentation — trade `bg-brand-soft` + emoji,
  category `bg-surface-sunken`, row a white card — with a 2px rail (`border-l-2` + `pl-2`) under an
  open trade. At 375 px every level of padding is width the position name loses.

### The one genuinely new behaviour

**A position shared by two of the master's trades now appears under BOTH**, each in that trade's own
category. `asSelectedTradeSees` could only ever re-file for a single selected chip (§8); a tree has
no such ambiguity, because each branch IS one trade. Two bounds keep it honest:

- **Only trades the catalog actually uses get a branch.** `sharedTrades` names every trade the
  LIBRARY ships the name under, not the master's — a drywaller owns «Установка люка-ревізії» because
  V120 copied it into a drywall phase, and a whole «Сантехніка» branch holding that one row would be
  a trade he does not do.
- **A custom trade never lends rows to a system branch.** Its `trade` column reads OTHER for storage
  reasons only (V91) and `sharedTrades` is computed off the NAME, so the system trade shipping the
  same wording would otherwise swallow a position he filed himself.

The two copies are the same row with the same id, so ticking either ticks the position once; the
basket adds from the flat deduped list, never from the tree.

### Deliberately NOT done

**`CatalogPage` keeps its chips.** There the filter is load-bearing beyond browsing: it prefills
`defaultTradeKey` for a new position and it defines what «Видалити все» deletes. Turning that screen
into a tree is a bigger question (what does a destructive bulk action mean on a branch?) and belongs
to its own round — logged in `open-questions.md`.

### Tests

`catalogTree.test.ts` (7) — branch per trade, library order inside a branch, an unranked folder last,
a shared position under both trades, no phantom branch for a trade he lacks, a custom trade as its own
branch that lends nothing, an untagged row landing in OTHER. `CatalogPicker.test.tsx` (+4) — the trade
level is named and counted, a big multi-trade catalog opens on the trades and one tap opens one, a
one-trade catalog draws no trade level, a shared position shows twice and is added once. Full gate
green: lint · `tsc -b` · `typecheck:tests` · vitest (811) · `vite build`.

## 11. Round 4 — the TEMPLATE picker gets the same tree, and a missing translation gets a guard (PWA only, v1.35.2)

> «давай отут так само приберемо чіпси і зробимо дерево і з можливістю вибирати шаблони з різних
> трейдів для одного кошторису»
>
> «немає перекладу, глянеш потім до цього»

**No backend change, no migration.** Two unrelated things in one round, both PWA-side.

### 11a. The defect was VISIBILITY, not capability

Picking bundles from several trades into one estimate **already worked** — `ApplyTemplatesRequest`
takes a list, `picked` is keyed by template id, and the chip only ever filtered `defaultsByTrade`
(own templates were never filtered at all). What the chip did was hide the other trades *and the
ticks already made in them*: with «🧱 Будівельник» active, a bundle ticked under «Плитка» was still
in the basket but nowhere on screen. So cross-trade picking read as impossible, and the answer is
not new data — it is showing the trades at once and letting the selection accumulate in sight.

- **`features/estimate/templateTree.ts`** — `toTemplateTree(templates): TemplateBranch[]`, branch =
  trade key + the master's own name for a `custom:` trade + its bundles. Unlike the catalog there is
  **nothing to resolve**: a template belongs to exactly one trade, so the flat list and the tree hold
  the same rows. Bundles keep the order the server sent (a template has no `sort_order`, and a trade
  holds few); branches come out in **`TRADE_VALUES` order** — the library's own trade sequence, the
  one V118 ranks the catalog by — so the two pickers name the trades in the same order. Custom trades
  sort after every system one, by name.
- **`templateTradeKey`** — the custom id wins over the `trade` column (which reads OTHER for storage
  reasons, V91), and a template with no trade is **`GENERAL`**, not OTHER. That difference is exactly
  why `TemplatesPage` never shared `TradeFilterChips`.
- **`TemplatePickerSheet`** loses `TradeChip` and the `trade` state; the search stays. Both sections
  («МОЇ ШАБЛОНИ» and «ГОТОВІ ШАБЛОНИ») render through one branch renderer, and the same rule as the
  catalog applies to each independently: **one branch draws no trade level**, so a one-trade master's
  screen is unchanged. Open state is keyed `` `${scope}:${branch.key}` `` — one trade can hold both
  own bundles and defaults, and a shared key would open both sections' branches together.
- **A shut branch still says how many of its bundles are picked.** That badge is the point of the
  whole round: without it a selection spanning two trades looks lost the moment the master browses on.
- `AUTO_EXPAND_MAX_TEMPLATES = 6`, lower than the catalog's 10 — a template row is two lines tall
  (name + «N позицій»), so six of them already fill a 375 px screen. A search opens every branch it
  draws, same as the catalog.
- `templates.filterAll` is deleted from both bundles — the only string the chips owned.

`picked` (tap order, which decides whose wording survives the name de-dup), the per-bundle `subsets`,
the drill-in preview checklist and the sticky «Створити кошторис (N)» footer are untouched.

### 11b. «немає перекладу» — two missing keys, and why nothing caught them

The act editor's catalog modal was titled with the raw key `acts.addAdditionalFromCatalog`. Two keys
had never been added (that one and `acts.additionalQtyHint`), and the title now has its own key
(`acts.additionalFromCatalogTitle`) rather than reusing the button's «+ З каталогу».

**This class of bug is invisible to the whole gate.** A missing key is a plain string: it type-checks,
it lints, i18next renders it verbatim at runtime, and a component test asserting on `t('…')` output
would have to name the very key that is missing. It took a screenshot from the master.

So `src/lib/i18nKeys.test.ts` reads the SOURCE: every static `t('some.key')` — a call whose whole
argument is one quoted literal — must exist in **both** uk and en. Three assertions, because two of
them can pass vacuously: first that the scan found > 300 keys at all (a regex matching nothing would
pass everything), then uk, then en; each failure lists `key (file)`. A counted key is stored under
its plural forms and never bare, so the lookup accepts any `_one`/`_few`/`_many`/`_other` variant —
without that, `notifications.newQuestions` and `economy.paymentsReceivedCount` are false positives.
A key composed at runtime (`t('trades.' + code)`) is deliberately skipped rather than guessed at:
**this test is a floor, not a proof.** It was verified against the real bug — renaming the key in
`uk.json` fails the test naming `ActEditorPage.tsx`.

### Tests

`TemplatePickerSheet.test.tsx` (+4, 15 total) — the trades are named in library order and the sheet
opens on them (TILING before BUILDER, one tap opens one); **a bundle ticked in one trade stays counted
on its shut branch while another trade is browsed, and both arrive in the apply call**; a one-trade
list draws no trade level; a search opens every branch it still draws. `i18nKeys.test.ts` (3).
Full gate green: lint · `tsc -b` · `typecheck:tests` · vitest (**818**) · `vite build`.

**Not verified:** the 375 px layout could not be checked visually this round — the browser tooling
failed repeatedly (`Script injection timed out` / `Page still loading`). The markup is the catalog
tree's, shape for shape (`min-h-11` rows, `break-words`, a `border-l-2` rail instead of indentation).
