# Objects past the FREE cap are view-only

- **Status:** 🛠 BUILT 2026-11-28, waiting for the owner's local test — not pushed.
- **Migrations:** none (computed from the rows: `projects.created_at`, `sample`, `paid_at`, `transferred_at`,
  `estimates_created`).
- **PWA:** 1.96.0.
- **Goal (owner, 2026-11-28):** a master buys PRO, makes 20 objects, lets PRO lapse — and keeps all 20 on
  FREE, renamed for each new client, three fresh estimates each, sent, printed, acted. The FREE cap guarded
  only the CREATE, so the paywall never came back. The owner sees several masters with «вагон обʼєктів»,
  one of them on a paid plan with 10+ objects named nearly the same.
- **Owner's decisions (2026-11-28):** past the first FREE objects an object is view-only; viewing, sharing,
  PDF stay; changing anything needs PRO; blocked at once on release, no grace (masters see the banner);
  money stays recordable («думаю там такого не буде»); transferred objects are view-only too («покищо так
  нехай буде»); the FREE estimate cap counts estimates EVER made on the object; the catalog and templates stay
  editable; and «розблокувати цей обʼєкт за 149 ₴» — a FREE master may buy one view-only object and keep it.

## The rule (`ObjectWriteGuard`)

An object is **writable** when any of these holds, else it is **view-only**:

1. the owner's plan has no object cap (PRO, TEAM);
2. it is the training sample «Приклад: …» (V197, outside the cap);
3. it is a one-off paid object (V214, `paid_at`) — he paid for that one;
4. it is one of the first `MAX_PROJECTS` (2) objects he ever made — `ProjectRepository.findCappedIdsOldestFirst`:
   oldest `created_at` first (id breaks a tie), the sample, paid and transferred objects left out, **archived
   and cancelled ones kept in** (or archiving one would promote the next).

A transferred object (V201, outside the cap) is therefore never writable on FREE. Nothing is stored: a plan
change flips every object at once, both ways.

## What is refused (403 `OBJECT_READ_ONLY`, «Цей обʼєкт лише для перегляду…»)

- **Object:** name / address / description / client (`ProjectService.update`).
- **Estimates:** a new one in any way — empty, template, import, duplicate, consolidation, the bot's
  «Додаткові роботи» (`LimitService.requireCanAddEstimate`, the one door); every line and header write
  (`EstimateService.requireNotSigned`, which every write path already passed); delete, reopen,
  «рахувати в економіці».
- **Acts:** create (`WorkActCreator`), header, lines, delete, status, act receipts — every path that took
  `WorkActService.loadOwnedForUpdate`, plus `WorkActReceiptCreator.prepare`.
- **Photos:** a progress photo upload (`LimitService.requireCanAddPhoto`, MANUAL only), move to a folder,
  create / delete a folder, delete a photo.
- **Measurements:** every room / element write (`MeasurementService.requireMeasurementsWritable`), incl. a
  sketch or project import's commit.
- **Notes and tasks:** add, edit, delete (their services, `ProjectService.loadWritable`).
- **The bot:** files through the same services, so a line / note / task is refused and stays in «Вхідні»;
  a «Додаткові роботи» it would create answers `Notice.OBJECT_READ_ONLY` («Обʼєкт лише для перегляду…»).

## What stays open on a view-only object

- Opening and reading everything; the PDF of an estimate, an act, the shopping list.
- **Sharing**: share links and the project portal, «Надіслано» (mark-sent), email to the client, showing a
  photo to the client, the message link, an act's share/publish.
- **Money**: payments, expenses, «Мої гроші» entries, «Чеки обʼєкта», receipt photos (RECEIPT source),
  «Я оплатив» → «Кошти прийшли».
- **Everything the client does**: signing an estimate or an act in the portal, «Я оплатив», messages; and
  the master recording a paper signature («Підписано на папері», `signOffline`) — the PWA does not offer it
  there, the server allows it.
- **Status** (complete / cancel / archive) and **deleting the object**: neither gives back a slot.
- The **shopping list** and the calculator's answers (params, tile layouts): a buying helper, not a
  document; the shop tick must work from the offline queue hours later (the B-28 argument).
- «Зберегти як шаблон» from an estimate (it writes his catalog, not the object).

## The FREE estimate cap counts estimates EVER made

`LimitService.requireEstimateRoom` reads `max(projects.estimates_created, live count)` instead of the live
count: deleting a draft no longer frees a slot (open-questions «FREE estimate cap: delete→create loophole»).
ADDENDUM rollups are not created through it and are not counted as made. `ProjectResponse.estimatesCreated`
carries the counter so the PWA disables «Новий кошторис» before the refusal.

«Перенести в Majstr» creates the object and its estimate in one commit: an object made by THAT commit gets
its estimate through `EstimateService.createFromImportIntoNewObject` (the cap only, no write guard); an
estimate transferred onto an existing object takes the ordinary door.

## Buying ONE view-only object back — 149 ₴

The one-off object of V214 (`users.object_credits`, `projects.paid_at`) reused, no migration:

- **The banner's button is «Розблокувати →»** on FREE (an older server without the one-off price: «Дізнатися
  про PRO»). With a credit already bought it spends it at once — `POST /api/projects/{id}/unlock`. Without one
  it opens the purchase sheet as the choice (PRO · підписка / Один обʼєкт · 149 ₴), the object tab paying for
  THIS object.
- **Checkout:** `POST /api/billing/checkout-object?unlock=<id>` — the controller first checks the object is his
  and view-only (`ProjectService.requireUnlockable`, 409 `OBJECT_NOT_LOCKED`, so 149 ₴ is never paid for
  nothing); the return URL carries `&unlock=<id>`. The webhook adds a credit as for any object purchase.
- **Return page:** once the credit lands, it calls the unlock and offers «Відкрити обʼєкт»; a refused unlock
  falls back to «Створити обʼєкт» — the credit is still his, and the banner spends it later.
- **Unlock** (`ProjectService.unlock`): the user row locked (the lock `reserveProjectSlot` spends under), the
  object must be view-only (else 409 `OBJECT_NOT_LOCKED`), a credit must exist (else 409 `NO_OBJECT_CREDIT`);
  one credit off, `paid_at` stamped (the column lost `updatable = false`). It is then a paid object like one
  created on a credit: writable on FREE for good, PRO's per-object caps, the logo. A transferred object can be
  bought back the same way. It promotes nothing — the next view-only object stays view-only.

## What the master sees (PWA 1.96.0)

- **Objects list:** «🔒 Лише перегляд» under the name (`ProjectCard`).
- **Object page:** the banner «Обʼєкт лише для перегляду: на безкоштовному плані змінювати можна тільки
  перші 2 обʼєкти. Ділитися, PDF і гроші — як завжди.» + «Дізнатися про PRO →» (`ReadOnlyObjectBanner`,
  upgrade trigger `READ_ONLY_OBJECT`); the name and address are plain text, no client «Змінити», no
  «＋» on the estimates / acts / photos / measurements tabs, «Новий кошторис» and «Звести» off, notes and
  tasks read-only.
- **Estimate editor:** locked the way a signed one is (no add / edit / delete / select / markup / dictation
  / receipt / duplicate / reopen, no «Створити акт»), the same banner; share and PDF stay.
- **Act editor:** locked like a signed act, the banner; share and PDF stay, delete and «Підписати» gone.
- **Offline queue:** a queued change refused with `OBJECT_READ_ONLY` is blocked as `limit` (the «PRO or drop
  it» decision in «Синхронізація»), not retried eight times into «stuck».
- **Before PRO ends** (`PlanEndingBanner`): «Далі буде FREE: ваші дані лишаться на місці, але змінювати
  можна буде тільки перші 2 обʼєкти — решта стануть лише для перегляду.»
- `limits.estimatesHint`: «FREE — 3 кошториси на обʼєкт за весь час (видалені теж рахуються).»

## Not changed / confirmed

- The object cap itself (2 ever, V107) is untouched; a credit is spent by a CREATE at the cap or by an unlock.
- The catalog and templates are never behind the guard: «Перенести в Majstr» still files its positions into the
  catalog, and every catalog row and template stays editable on FREE.
- AI feature gates are untouched (they read the plan, not the object).
- An admin-granted dateless PRO is PRO: everything writable.

## Gotchas

- `EstimateService.requireNotSigned` was `private static`; it is an instance method now (it needs the guard).
- `@InjectMocks` passes `null` for a constructor parameter with no `@Mock`: every unit test of a service that
  gained `ObjectWriteGuard` needs the mock, or it NPEs at the first write.
- `ProjectResponse` gained two fields: `ProjectControllerTest` builds it by hand (four).

## Not built

- No undo: an unlocked object stays paid; deleting it returns nothing (the V214 rule).
