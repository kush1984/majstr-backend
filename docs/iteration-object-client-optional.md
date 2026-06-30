# Object/estimate split + optional client (PWA redesign)

- **Status:** ✅ PWA done. tsc clean, vitest **43** + vite build green. Backend: **no
  functional change** (already supported a clientless object) — one test added.
  Separate PWA commit; backend test joins the backend commit.
- **Trigger (user):** the global create flow always forced a new object *and* a
  client up front. Make the client optional at creation (it's only needed to
  *send* the estimate), add an object-only flow, and clarify the button names.

## Key finding — backend already supports this

`projects.client_id` is nullable (`ON DELETE SET NULL`), `ProjectRequest.clientId`
is optional, `ProjectService.create/update` handle a null client, the public
share-link needs no client, the email-share is already gated by
`ClientEmailMissingException`, and the PDF renders with `client == null`. So this
is a **frontend** redesign. Backend test added: `ShareLinkServiceTest
.sendByEmail_noClientAtAll_throwsAndSendsNothing` (previously only the null-*email*
branch was covered).

## Naming — one coherent set of three actions

| Action | Label | Where |
|---|---|---|
| object + first estimate | **Об'єкт і кошторис** (`common.addEstimate`) | Головна CTA, Об'єкти primary |
| object only | **Новий об'єкт** (`common.newObject`) | Об'єкти secondary |
| estimate in an existing object | **Новий кошторис** (`common.newEstimate`) | inside an object |

Page title (`estimate.newTitle`) → "Об'єкт і кошторис"; object-only page title
(`estimate.newObjectTitle`) → "Новий об'єкт"; the submit button is now just
**"Створити"** (`estimate.createEstimate`) since it creates several resources.
(Supersedes the interim "Новий об'єкт" CTA name from
[iteration-new-object-cta.md](iteration-new-object-cta.md).)

## Behaviour

- **Optional client** — new reusable `ClientPicker` (`features/clients/ClientPicker.tsx`):
  segmented **Без клієнта / Наявний / Новий**, with `clientDraftError()` (validation)
  + `resolveClientId()` (creates the client if new, returns `undefined` for none).
  Used by `/new` (combined), `/projects/new` (object-only), and the share prompt.
  `/new` defaults to **Без клієнта**.
- **Object-only flow** — new full-screen page `NewObjectPage` at `/projects/new`
  (route ranks above `/projects/:id`): object name/address + optional client →
  creates the project → opens it. Reached from the new Об'єкти secondary button.
- **Share with no client** — `ShareEstimateSheet` now detects `project.clientId == null`
  and shows "клієнта ще не додано" + **Додати клієнта** → `ClientPicker` (existing/new,
  `allowNone={false}`) → `resolveClientId` → **`useUpdateProject`** attaches the client
  to the object → email becomes available. **Copy-link stays available without a
  client** (per decision — a link can go via Viber/Telegram). The sheet folds the
  just-attached client id locally so email lights up without waiting for the parent
  to refetch. The project-level share CTA already guards `list.length === 0`
  ("create an estimate first"), so object-only objects with no estimates are fine.

## Files

PWA: `features/clients/ClientPicker.tsx` (new), `features/projects/NewObjectPage.tsx`
(new), `features/estimate/NewEstimatePage.tsx` (uses ClientPicker, optional client),
`features/projects/ProjectsPage.tsx` (two CTAs), `features/estimate/ShareEstimateSheet.tsx`
(no-client prompt + attach), `features/projects/useProjects.ts` (`useUpdateProject`),
`routes/routes.tsx` + `lib/config.ts` (`/projects/new`), `locales/{uk,en}.json`.
Backend: `ShareLinkServiceTest` (+1 test).

## Tests

- Backend: `sendByEmail_noClientAtAll_throwsAndSendsNothing`.
- PWA (new): `NewObjectPage.test` (object-only, no client + new-client inline),
  `NewEstimatePage.test` (object+estimate with **no** client), `ShareEstimateSheet.test`
  (no-client → add existing → `useUpdateProject` attaches → email appears).

## Verify (live)

1. Головна / Об'єкти CTA reads "Об'єкт і кошторис"; Об'єкти has a second "Новий об'єкт".
2. `/new`: client defaults to "Без клієнта"; create works with no client; submit says "Створити".
3. "Новий об'єкт" → `/projects/new`: object-only, optional client → opens the object.
4. Object with no client → share → "Додати клієнта" → pick/create → client attached →
   email option appears; copy-link worked throughout.
