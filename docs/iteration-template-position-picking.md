# Iteration — picking POSITIONS out of a bundle, not just bundles

**Status:** done (not committed — the master tests locally first)
**Migrations:** none. This is a request-shape change, nothing is stored.
**Repos:** `majstr-backend` (`ApplyTemplatesRequest`, `EstimateTemplateService.applyToProject`),
`majstr-pwa` (`TemplatePickerSheet`, `useApplyTemplate`, both callers).

## 0. What he actually said

> «треба коли ми створюємо кошторис із шаблону, то мати можливість зразу **вибрати які позиції
> беремо в кошторис**, бо деколи із великого шаблону треба 5-6 позицій і це довго потім викидати,
> пропоную отут … дозволяти едітати то діло і **оця кнопка прибрати з вибору тут ні до чого**,
> треба щось типу **запамятати вибране** чи щось таке»

Two complaints in one, and the second is the interesting one. A DRYWALL bundle is ~30 lines (V112's
own rule: prefer FEW LARGE sequences), and the master routinely wants five or six of them. Today he
applies the whole thing and then deletes twenty-five lines from the estimate board, one at a time,
each one a round trip. And the screen where he can already SEE the composition — the picker's
drill-in preview — is read-only, its only control a «Прибрати з вибору» toggle that says nothing
about what he is looking at.

So the preview stops being a preview. It is the checklist.

## 1. The contract: the whole answer travels in the body

`POST /api/projects/{id}/estimates/from-templates` used to take the picked bundles as a **query
param** (`?ids=a,b,c`) beside an `EstimateCreateRequest` body. A per-bundle subset cannot ride a
query string without inventing an encoding for it, and splitting «which bundles» from «which of
their positions» across the query and the body would be two halves of one answer that can disagree.

One new DTO, `ApplyTemplatesRequest`:

```json
{ "templates": [ { "templateId": "…", "itemIds": ["…", "…"] },
                 { "templateId": "…" } ],
  "estimate": { "name": "Ванна" } }
```

Three rules, each of which had a wrong alternative:

- **`itemIds` absent (or empty) = the WHOLE bundle.** Only a list that actually names positions
  narrows anything. The alternative — «empty means nothing» — would let a bundle silently
  contribute zero lines, which is never something a master can mean, and it would break the
  single-template endpoint and any client that never heard of positions. `wholeBundles(...)` is the
  shorthand the single-template overload calls.
- **`templateIds()` keeps the picked ORDER** (`distinct`, not a Set) — that order decides whose
  wording survives a duplicate position, so it is content.
- **The subset is applied BEFORE the name de-dup.** Unticking a shared position in one bundle is an
  untick of THAT copy, not of the work: the next bundle's copy gets through instead of the line
  vanishing from the estimate entirely. Pinned by
  `applyToProject_whenTheFirstBundlesCopyIsUnticked_theSecondOnesGetsThrough` — the failure mode is
  silent, and it drops a line out of a bundle the master never touched.

What made the `templateId → itemIds` map safe to key on ids: **`loadAccessible` does not fork**
(unlike `loadWritable`), so `template.getId()` always equals the id that was sent. A read that
forked would hand back a different id and the map would miss.

## 2. The picker: the preview IS the checklist

`TemplatePickerSheet` now hands back `TemplatePick[]` — `{ template, itemIds }` where **`itemIds:
null` is the whole bundle**, the same convention as the wire.

- Every position in the preview is a 44 px tappable row with a checkbox, **all ticked to start**:
  narrowing is opt-in, never a chore the master has to do before he can apply anything.
- A header row reads «Обрано N з M» with a select-all / clear-all toggle beside it.
- The footer is **«Готово»**, not «Обрати цей шаблон» / «Прибрати з вибору». It both stores the
  subset and ticks the bundle — a preview the master narrowed is a bundle he wants.
- **Untick every position and the bundle drops out of the selection.** Same answer as untapping it;
  a bundle contributing nothing is not a thing he can mean.
- The subset is remembered in the sheet's `subsets` state (`templateId → string[]`), so closing and
  reopening the preview shows the same ticks back — the «запамятати вибране» he asked for. The row
  itself then reads «Обрано 2 з 8» instead of the position count.
- **Everything ticked is NOT stored as a subset** (`applySubset` deletes the entry). A subset frozen
  at «all of it» would silently drop a position ADDED to the bundle tomorrow.
- The old select/deselect toggle **stays as the fallback** for the one state where there is nothing
  to tick: no cached composition (offline, or a fetch that failed). Showing an empty checklist there
  would read as «this bundle has no positions», which is a lie about the master's own data.

Ordering note: the ticks are stored in tap order, but both the server and the offline path iterate
the TEMPLATE's own items and skip what is not ticked, so the bundle's sequence — which is its
content (V112) — always wins.

## 3. Offline mirrors it character for character

`applyTemplateOffline` filters the same way, in the same place (before the de-dup), so an estimate
composed on the device and one composed on the server come out with the same lines in the same
order. Mirrored-formulas rule — change one, change the other.

## 4. Tests

Backend:
- `EstimateTemplateServiceTest.applyToProject_takesOnlyTheTickedPositions_andAnEmptyPickStillMeansTheWholeBundle`
- `EstimateTemplateServiceTest.applyToProject_whenTheFirstBundlesCopyIsUnticked_theSecondOnesGetsThrough`
- `EstimateTemplateControllerTest.createFromTemplates_passesEveryPickedTemplateInOrder_withThePositionsTickedInEachOne`
- `EstimateTemplateControllerTest.createFromTemplates_withoutASingleTemplate_is400` (`@NotEmpty`, and
  the service never called)

PWA:
- `TemplatePickerSheet.test.tsx` — «takes only the positions ticked in the preview, and remembers
  them» (including reopening the preview and the row's own «Обрано 2 з 3») and «drops the bundle
  from the selection when every position is unticked».
- `useApplyTemplate.test.tsx` — «takes only the ticked positions, and an empty pick still means the
  whole bundle», asserting the subset-before-dedup order offline too.

## 5. Not changed / confirmed

- **No migration, nothing persisted.** A subset is a choice made while composing one estimate; it
  lives as long as the sheet is open. Remembering it per master across sessions was not asked for
  and would be a stored preference about data that changes under it.
- **The single-template endpoint is untouched** and now delegates through `wholeBundles(...)`.
- Template EDITING still lives on the «Шаблони» page. The picker narrows what goes into THIS
  estimate; it does not change the bundle.
