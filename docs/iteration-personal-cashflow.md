# Iteration — «Мої гроші»: the master's own cash flow (2026-09-17)

**Status:** shipped · **Migration:** V135 · **PWA:** 1.45.1 → 1.46.0

## The ask

> «Один із майстрів хоче додати можливість занотовувати доходи видатки десь у застосунку, це типу не
> суто по обʼєктах, а його власна економіка… дата має бути присутня і робити свого роду такі виписки
> чи фільтри для прикладу за тиждень чи за місяць чи за рік, де він буде бачити рух своїх коштів.»

## What was actually missing

Not the data. The app already records most of a master's cash **with dates**:

| | table | date |
|---|---|---|
| money in | `payment_receipt` (V100) | `received_at` |
| money out | `object_expenses` | `spent_at` |

What was missing is a **cross-object read**: every query over those two says `WHERE object_id = ?`,
and there was not one owner-wide money query in the codebase. So he could see each job's economy and
never his own month.

And one real part of his money had no place at all: fuel, tools, rent, taxes, and income for work
that closed without an act — «не все переводиться через акти, багато хто так не працює».

## The decision the whole shape rests on

**A lens, not a second book.** `cash_entry` holds ONLY what the objects do not know; money that
belongs to an object keeps being written to that object's own journal, and the screen unions the
three sources on the read path.

A standalone personal ledger was considered and rejected. He already logs object money; a book that
ignored it would either be wrong or make him type everything twice — and two books that disagree is
how a master stops trusting both.

### …which makes the object picker a ROUTER, not a label

The master's own argument settled this one, and it reversed the first proposal («особистий запис
завжди поза обʼєктом»): not all work closes with acts, and he does not always enter things into the
object's economy — so today that money is recorded **nowhere**.

So picking an object is a fork with two destinations and no third:

* **object chosen** → the row is written to that object's journal (`payment_receipt` for income,
  `object_expenses` for spending), and this screen picks it up when it reads;
* **no object** → a `cash_entry` row.

A row that merely NAMED an object would leave that object's economy saying «Отримано 0» while this
book said otherwise. The upside is the nice one: the quick-entry screen becomes **the easiest way to
fill an object's economy**, from the screen he actually uses.

Income always lands as an UNPLANNED receipt («Своє»). Targeting a stage would drag the overpayment
question onto a quick-entry screen, and «which stage does this close» is not what it is for.

## Three numbers, because two of them answer different questions

`Прийшло` · `Витрачено` · `Заробив`, where `earned = income − refunds − expense`.

The master ruled the till receipt out — «чек не враховуємо, лише реальний дохід» — and the expense
side honours that for free: it reads `object_expenses`, where V129's ruling already lives (a receipt
the client pays back is a receivable, not a cost). **`project_receipt` is never read here.**

But the mirror of that ruling had to be handled too, and it is what the «повернення за матеріал»
tick is for: when the client pays that material back, the money arrives as an ordinary payment, and
counting it as earnings inflates the month by exactly the material. Marked, it stays in the movement
and leaves «Заробив». `refunds` is returned alongside so the gap between the first number and the
third is explained rather than mysterious.

**The flag moves no object figure.** `payment_receipt.material_refund` is read by this screen and by
nothing else — «Отримано», the payments summary and every stage's status count the receipt exactly
as before. That is the standing constraint on this area (existing clients' numbers must not shift),
and it is why V135 asserts that it flagged nothing.

It is the small first half of the open «purpose on a payment» question: a payment entered from the
OBJECT screen still carries no purpose, so only what the master tags himself is distinguishable.

## Date, and the time he did not really want

`happened_on` (a DATE) is the authoritative day; `happened_at` only orders rows inside it.

Deriving the day from a timestamp would mean deriving it in SOME timezone, and the object rows beside
it carry a bare date — this way one day means one day everywhere on the screen. The time is
**stamped automatically** and only surfaced if he goes looking: its whole job is ordering within a
day, and a time picker on every entry is friction for nothing else.

Consequence worth knowing: object rows have no time at all, so within a day they sort after the
personal ones. Adding a time column to the old tables was rejected — for rows already entered it
would be invented.

## Periods

Computed on the master's PHONE, which already sits in his timezone: a week starts Monday
(`getDay()` calls Sunday 0, which would put a Sunday's earnings in the week about to begin), a month
ends on its real last day. The server only needs a default for a request with no bounds, and that one
resolves in `LocalizationConfig.ZONE` — on the 1st at 01:00 a UTC boundary would open «цей місяць»
on the previous one. That closes the cash-flow half of the long-standing UTC-boundary item;
`DashboardService` and the admin metrics are deliberately untouched.

**The year view lists MONTHS, not rows.** Two thousand lines is not a screen anyone reads on a phone;
tapping a month re-queries it. Week and month return a flat list capped at 500, and the response says
`truncated` when it cut one — the TOTALS always cover the whole period, and a screen quietly showing
part of a month is worse than one that admits it.

## Where it lives, and what it is NOT

The master asked the right question — «чи не буде забагато карток на екрані коли дивишся з
мобільного». The dashboard already carries a greeting, trade chips, three metric tiles, the shopping
card, recent objects and quick actions.

So: **one line, not a card** (~44 px against ~120), shown only when something moved this week —
the same rule the shopping card follows when there is nothing left to buy. The full screen is
`/finance`, with a second door in Профіль.

**No sixth item in the bottom nav.** Five icons already share a 375 px phone; a sixth is ~62 px
each. If it turns out he opens this daily, that conversation can happen then — one option is folding
«Шаблони» under «Каталог» (both are reference data), which is a separate change.

## Categories

The set deliberately CONTAINS the object journal's three, or the union would group dishonestly:
`MATERIALS`→MATERIALS, `CREW`→LABOR, `OTHER`→OTHER. Plus `FUEL`, `TOOLS`, `TAXES` — what an object
never knows about. Income: `ADVANCE`, `WORK`, `OTHER`.

Rent, advertising and phone bills are deliberately absent: rarer, and a note carries them. Six
buttons is what fits a phone; a seventh gets added when a master asks for it by name. **Always
optional** — a master at the wheel will not pick one, and an object payment has no category at all.

## Offline

One outbox entity, `cashEntry`, create/update/delete under `X-Entity-Uuid`. **One entity covers both
destinations**: the server decides from `projectId`, so a queued entry replays the same way either
way. The object routes were already queueable (`expense`, `payment-receipt` handlers predate this),
so choosing an object offline needed no special case — which was the one open question in the plan.

The optimistic row is provisional by nature: a routed entry comes back as the OBJECT's row with an id
that is not the one we sent, so nothing is keyed on the local id past the next invalidate. The
current month and the summary are prefetched.

## Tests

* `CashFlowServiceTest` — the router in both directions, the category mapping, the refund refused on
  spending, `earned` vs `income`, the day-first sort, the year view.
* `CashFlowIntegrationTest` — the three-source union on real Postgres, **another master's objects are
  invisible** (the join through `projects.owner_id` exists nowhere else), the router really writing
  into the object's own tables, a reimbursable till receipt absent until flipped to «моя витрата»,
  the CHECK refusing a refund on spending, the cascade on user delete.
* PWA: `useCash.test.ts` (Monday, month ends, leap year, year → months), `CashFlowPage.test.tsx`,
  `CashHomeStrip.test.tsx`.

## Not in this iteration

* **Виписки** (PDF/CSV export). The PDF renderer is already here, so it is cheap — but it is its own
  step.
* **A real `purpose` on `payment_receipt`**, read by the object economy too. Unapproved; the
  constraint that existing figures must not move still holds.
* Plan gating: ships FREE alongside the rest of the economy. No new gate was invented for one
  master's request.

---

## Round 2 — the master's first look (same day)

Three corrections, and the third one was worth the whole review.

**1. The week, not the month.** «По замовчуванні треба брати тиждень.» The screen opens on WEEK —
and so does the home strip, which used to sum a MONTH. That mismatch was a real defect: tapping
«+42 000» and landing on 8 000 is two surfaces describing the same money and disagreeing.
`CashSummaryResponse` now carries `from`/`to` so neither side re-derives the window.

**2. «Отримав», not «Витратив».** The sheet opens on INCOME: what he reaches for this screen to
write down is most often money he has just been handed. The labels are his own words now —
«Отримав» / «Витратив» rather than the accounting nouns «Дохід» / «Витрата».

**3. The object picker looked like a required step, and the screen never said it pulls everything.**

> «Як думав що воно зразу буде тягнути кошти по всіх обʼєктах, а ото зі списку щось вибирати — то
> воно якось так не дуже виглядає.»

Two problems were hiding in one sentence, and only one of them was the control:

* **The screen did not SAY what it does.** It already unions every object's money — but nothing on
  it said so, and the one thing that mentioned objects was a labelled dropdown in the add form. So
  the natural reading was «pick one or nothing happens». Fixed by three words under the title:
  **«Усі обʼєкти разом»**, plus an empty state that now says money is pulled in by itself and that
  this is where he adds what the objects do not know about.
* **A `<select>` is the wrong control here.** On a phone it is a full-screen wheel for what is
  usually a choice between two live objects. Replaced with **chips**: «Без обʼєкта» first and
  selected by default, then the LIVE objects (a finished job is not where today's money goes), up to
  four; the rest hide behind «Ще N…», the only case that still opens anything. The hint about
  routing appears only AFTER he picks one — before that it explains a thing he did not do.

The router itself is unchanged: picking an object still writes the row into that object's journal.
What changed is that it now looks like what it is — optional.

**Not changed, deliberately:** the capability to attribute an entry to an object. It is what he
argued for himself («не все переводиться через акти»), and removing it would put that money back
nowhere. If the chips still read as noise, the next step is hiding them behind a single
«привʼязати до обʼєкта» line — say the word.

---

## Round 3 — the object picker is gone

> «Я хочу, щоб гроші/доходи зразу брались з обʼєктів, а не щось там вибиралось, з можливістю
> видаляти рядки чи едітати.»

Round 2 answered half of this — the screen now SAYS it pulls every object. The other half was the
one I had been reading as a UI complaint and was not: **there should be nothing to pick at all, and
the rows themselves should be editable.**

**Adding asks nothing about an object.** `CashEntryRequest.projectId` and the whole router are
removed, not hidden — nothing had shipped, so there was no shim to keep. What he types here is what
no object knows about; money that belongs to an object is already in that object's journal and
arrives on the read path.

**Every row of the feed is editable and deletable in place** — an object's payment and expense
included. Tapping one used to bounce him to the object; now it opens the sheet. The earlier
reasoning («a second edit door would need every rule the object journal has») was wrong about what
was being duplicated: this is a second DOOR to one record, not a second copy. The write goes through
`PaymentService.editReceipt` / `ObjectExpenseService.update|delete`, so every rule those have still
runs — including the refusal to touch an expense a V129 till receipt owns, which surfaces here with
its own message rather than being swallowed.

Three shapes follow from «the sheet shows only what that row's table has»:

* an object PAYMENT has no category and no direction to change — it is income by being a payment,
  and moving it would mean moving it between tables;
* a PLANNED receipt is named by its stage, and `editReceipt` deliberately leaves that label alone —
  so the feed marks the row `noteLocked` and the field is read-only instead of silently discarding
  what he types;
* the object's name is STATED at the top of the sheet, never asked.

`materialRefund` joined `PaymentReceiptEditRequest` so the flag has one door in and out; ticking an
object payment as a refund is now the only way to set it on one. `kind` rides the PATCH body and the
DELETE query string (a DELETE carries no body), and the outbox op carries it too.

---

## Round 4 — «←» goes back to the door he came in by (PWA 1.46.1)

> «Коли тисну назад з Мої гроші я хочу повертатись в Профіль, а не на головну.»

The back arrow was hardcoded to the dashboard, which is wrong for one of the two doors that reach
this screen. Now it reads `location.state.from` — the same pattern `ShoppingListPage` uses for
exactly the same reason (three screens open it, so «←» goes back to the one that did).

The home strip passes `routes.home`, the Профіль row passes `routes.profile`. **The fallback is
Профіль**, not the dashboard: a reload carries no state, and «Мої гроші» is his own section, so that
is where he goes looking for it.

Pinned by three tests — from Профіль, from the dashboard, and opened cold.

---

## Round 5 — the strip sums the MONTH again (PWA 1.46.2)

> «На головній в тій полосці давай будемо показувати кошти за місяць, не за тиждень… якщо сьогодні
> 18.09.2026, то показуємо кошти за вересень, а коли клікаємо на ров, то переходимо в Мої кошти і за
> місяць показуємо, коли ідемо з профілю, то так як зараз нехай буде.»

Round 2 had made the strip a WEEK precisely so it could not disagree with a screen that opens on the
week. The master wants the month back on the home screen — a week there is too small a window to be
worth a glance — and the disagreement is settled the other way instead of being accepted:

* `summary()` sums the calendar month in Kyiv again (and `startOfWeek()` went with it);
* the strip says **«Цей місяць»**, so the period is named rather than guessed from the number;
* **the tap carries `period: 'MONTH'`** in the navigation state, so it lands on exactly the window
  it showed — tapping «+42 000» and arriving at 8 000 is the one thing a money screen may not do;
* **every other door keeps the week**: the Профіль row and a cold reload open on it, which is what
  the screen is for («коли ідемо з профілю, то так як зараз»).

So the navigation state now carries two things — `from` (round 4) and `period` — and each answers a
question the screen cannot answer for itself: which door he came in by, and which window he tapped.

---

## Round 6 — «отримано» had not worked since V135, and the cause was one Jackson default

> «каже тепер мені що не коректний формат запиту при збережені завдатку, що там тепер не так?»

Recording money received on an object answered **400 «Некоректний формат запиту»** — the
`error.malformed-json` message, meaning the body never reached a validator at all. The body was
fine. Jackson 3 turned **`FAIL_ON_NULL_FOR_PRIMITIVES` on by default** (Jackson 2 shipped it off),
and a record's canonical constructor is handed `null` for a property the client simply leaves out.
V135 added `boolean materialRefund` to `PaymentReceiptRequest` and `PaymentReceiptEditRequest`; the
object economy's sheets know nothing about a refund flag and send no such field, so from that commit
on **every** «отримано» and every receipt edit died in the message converter.

It hid behind two things. Planning a payment stage still worked (`ProjectPaymentRequest` carries no
primitive), so it read as one sheet misbehaving; and the toast that carried the refusal was painted
*under* the bottom sheet until the `z-[70]` fix, so what the master saw first was «не зберігає» with
no message at all. Fixing the toast is what turned the symptom into the sentence that named the bug.

The fix is at the mapper, not the DTO — nine request records carry a primitive today and each is the
same 400 waiting for the first caller that omits it:

```yaml
spring.jackson.deserialization.fail-on-null-for-primitives: false
```

An omitted primitive takes its Java default; a field that genuinely may not be missing says so with
`@NotNull` on a **wrapper** and earns a field-level validation error instead of a whole-body
rejection. `RequestDtoPrimitiveDeserializationIntegrationTest` sweeps every record in `dto` for a
primitive component and feeds each one `{}` — an integration test on purpose, because a standalone
MockMvc test builds its own converter and would answer about Jackson's defaults rather than ours.

**And the money bug underneath it.** With the parse fixed, the economy's edit sheet would have
started *landing* `materialRefund = false` on every amount correction — silently clearing a flag set
from «Мої гроші» and moving «Заробив» on a screen the master was not even looking at. So
`PaymentReceiptEditRequest.materialRefund` is now **three-valued** (`Boolean`, null = leave it
alone), the same shape V129 gave `reimbursable`, and `PaymentService.editReceipt` guards on null.
One record still serves both doors; only the door that owns the switch may move it.

## Round 7 — closing the hole that let Round 6 happen

Round 6 fixed «отримано». It did not explain why nothing caught it, and the answer turned out to be
structural rather than a missing assertion.

The backend's `PaymentReceiptRequest` grew `boolean materialRefund` in V135. The PWA's
`src/api/types.ts` is written BY HAND: its interface never grew the field, so the app kept sending
the old body. TypeScript cannot object — it is checking the app against its own hand-written
declaration, not against the backend. And no test on either side crosses the gap:

- the backend's service tests build the record in Java (`new PaymentReceiptRequest(…)`), so Jackson
  never runs and the JSON→record step — the one step that was broken — is not executed;
- there was no controller test for payments **at all**, and a standalone MockMvc one would not have
  helped: it builds its own message converter, so it answers about Jackson's defaults rather than
  about this application's configuration;
- the PWA's tests `vi.mock('@/api/payments.ts')`, so the body is never serialized;
- adding the field forced every Java call site to be updated, and the compiler made each one pass
  `false` — every test was corrected into sending the field the PWA was omitting.

Three layers now, because no single one is enough.

**1. The contract is a file.** `RequestContractSnapshotTest` reflects every `*Request` record into
`src/test/resources/contract/request-dtos.json`: field name, coarse type, and whether the CLIENT
must send it. Any DTO change reddens it in the same commit and names the PWA file to move with it.
Regenerate with `-Dcontract.update=true`; `build.gradle.kts` hands the flag to the forked test JVM,
which Gradle does not do on its own — the first version of the instruction silently did nothing.

*The trap inside the trap:* `@NotNull` does not list `RECORD_COMPONENT` among its targets, so javac
puts it on the backing field and the constructor parameter and leaves the component bare. The first
draft asked the component, got nothing, and recorded all 80 DTOs as fully optional — a contract that
would have gone green forever. `required()` now reads the component, its annotated type, the field
and the canonical constructor's parameter.

**2. The PWA checks itself against that file.** `src/api/contract.test.ts` parses `types.ts` and
asserts, per interface: we declare nothing the backend lacks, we declare every field it REQUIRES and
not as optional, and the types agree. A field the backend merely accepts may be omitted — demanding
those would flag every endpoint with options this app has no screen for, and a guard that cries wolf
gets deleted. The copy is checked byte-for-byte against the live backend snapshot whenever
`../majstr-backend` exists, so it cannot go stale on the machine pushes are made from.

**3. The endpoint is exercised over a real socket.** `PaymentReceiptHttpIntegrationTest` posts the
body copied out of `majstr-pwa/src/api/payments.ts` as JSON TEXT — never as a serialized DTO, which
would reintroduce the blind spot, since the compiler would fill in whatever the record declares
today. Proven by reverting `fail-on-null-for-primitives` and watching 4 of 6 tests fail.

Still open: the payload is copied by a human, not captured from the running PWA. A true end-to-end
contract test would need the two repos to share a build, which they do not.
