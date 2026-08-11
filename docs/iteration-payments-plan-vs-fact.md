# Iteration — Payments: split PLAN and FACT into one shared timeline

PWA `1.14.0 → 1.15.0`. Backend: **V100**.

The old `project_payment` mixed plan (`amount`) and fact (`paidAmount`/`paidAt`) on one row — exactly
one payment per planned stage. A master collecting an advance in two installments had to create two
"Аванс" rows, which read as duplicates. This iteration splits fact into its own table so a plan stage
can be closed by several partial payments, merges plan + fact into one vertical timeline, and asks the
master (never decides silently) what to do when a payment overshoots what was planned.

---

## 1. The model: `payment_receipt` (V100)

`ProjectPayment` stays PLAN-only: `amount`, `dueDate`, `nextStage`, `purpose`, `sortOrder`. Its old
`paidAmount`/`paidAt` columns are **deprecated, not dropped** — `@Deprecated` on the entity fields,
`COMMENT ON COLUMN` in SQL, unread by any new code. A follow-up migration can drop them once the V100
data migration has been eyeballed in production (open-questions).

**`payment_receipt`** is the new FACT table:

```sql
CREATE TABLE payment_receipt (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    plan_payment_id UUID REFERENCES project_payment(id) ON DELETE SET NULL,
    amount NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    received_at DATE NOT NULL,
    label VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- `plan_payment_id` NULL = **unplanned** ("Своє") — `label` carries its own name then, validated
  distinct (case-insensitive) from every plan stage's `purpose` so it can never be mistaken for one
  of the object's real stages.
- **`ON DELETE SET NULL`, not CASCADE** — deleting a plan stage must never destroy money that was
  actually received. A receipt that loses its stage becomes an unplanned one instead of vanishing.
- `ProjectPayment.status(LocalDate today, BigDecimal received)` is now a pure function over an
  **injected** received amount (Σ of the stage's receipts) instead of reading its own field — the
  same "derived, never stored" rule as before, just computed from a different table now.

**Data migration** (Σ=Σ, tested against a DB migrated to V99 then upgraded, mirroring the
`TilingCatalogRebuildOnLiveDataIntegrationTest` "second database" pattern): every existing
`project_payment.paidAmount > 0` row becomes exactly one `payment_receipt` linked back to its own
plan row — covers both an old "Вже отримано" one-step row (`amount == paidAmount`) and a partially
paid planned row identically. See `PaymentReceiptMigrationOnLiveDataIntegrationTest`.

---

## 2. `PaymentService.addReceipt` — the one path money enters through

"Позначити отриманим" and the old "Вже отримано" one-step create both route through the same method
now: `POST /api/projects/{id}/payments/receipts` (`PaymentReceiptRequest{planPaymentId?, label?,
amount, receivedAt, resolution?}`).

- **`planPaymentId` set, `amount ≤ remaining(stage)`** — one receipt, done. Covers exact-close and
  partial-close identically; a stage's `status` (PLANNED/PARTIAL/RECEIVED/OVERDUE) is derived from
  the resulting Σ.
- **`planPaymentId` null** — unplanned, `label` required, validated distinct from every stage's
  `purpose`.
- **Overflow (`amount > remaining(stage)`)** — the backend does **not** decide. If `resolution` is
  missing it throws `PaymentValidationException("error.payment.overflow-resolution-required")`; the
  PWA always computes the overflow itself first (it already holds the summary) and shows the choice
  before ever calling this, so reaching that error means stale client state, not the normal flow.
  Three resolutions, matching the design exactly:
  - **`RESERVE`** — one receipt for the full amount against the same stage; the plan is untouched,
    the stage now reads "7 000 з 5 000".
  - **`INCREASE`** — the stage's `amount` is raised to the new received total; one receipt, the
    stage closes exactly.
  - **`TRANSFER`** — two receipts: one closes the current stage exactly (`remaining`), the other
    posts the surplus to the **next open stage** (`findNextOpenStage` — the next by schedule order
    with `received < amount`). No cascade if the surplus itself overflows the next stage too —
    simplicity, per the prompt; the master resolves that on a second pass.

Editing a receipt (`PATCH .../receipts/{id}`) only ever touches amount/date/label — which stage it
closes is fixed at creation, re-linking isn't supported (it would re-open the whole overflow
question). Deleting is idempotent, same convention as everything else in this codebase.

`list`/`summary` build the whole payments picture from **two queries** regardless of how many
stages/receipts exist: all plan rows, then all receipts for the object in one shot, grouped in memory
by `planPayment` id (or left unplanned) — no N+1 per stage.

---

## 3. Portal isolation

`PublicPortalView.PaymentRow.paidAmount` → **`received`** (same meaning, now computed from receipts
instead of a raw column). `PublicEstimateIsolationTest.publicPortalPaymentsCardCarriesNoPrivateAggregates`
pins the exact field set of `PaymentsCard`/`PaymentRow` — updated to the new name. Individual receipts
are **not** exposed to the portal — the client sees "X з Y" per stage, not a receipt-by-receipt
history; that stays owner-only. `static/portal/index.html`'s `renderPaymentRow` reads `row.received`.

---

## 4. PWA — one dialog, one timeline

- **"Новий платіж"** still fans into two cards. **«Запланований»** is now pure plan — no fact fields
  at all, `ProjectPaymentRequest` dropped `paidAmount`/`paidAt` entirely. **«Вже отримано»** now opens
  the same **`ReceivePaymentSheet`** used everywhere else money is registered (see below), not a
  separate one-shot form.
- **`ReceivePaymentSheet`** ("Отриманий платіж") — a pill picker of every open (not fully received)
  stage plus "Своє"; amount defaults to the picked stage's `remaining`; date defaults to today. On
  submit, the PWA computes the overflow itself (`amount − remaining`) and, if positive, shows
  `OverflowConfirmSheet` with the two choices the prompt specifies (TRANSFER + INCREASE when a next
  stage exists; INCREASE + RESERVE when it's the last one) before ever calling the API.
- **Quick shortcut** — a stage's timeline row has its own "+ Отримати платіж" action, and the plan
  edit sheet (`PaymentSheet`) shows the same action when a stage isn't fully received — both open
  `ReceivePaymentSheet` **preselected** on that stage, per the prompt's "не новий шлях, а ярлик у
  єдиний" requirement.
- **One shared vertical timeline** (`PaymentTimeline`) — merges plan-stage nodes (status dot,
  purpose, condition text, nested receipt history underneath) with unplanned-receipt nodes, sorted by
  date (`dueDate` for a stage, else its earliest receipt's date, else last; `receivedAt` for an
  unplanned receipt). Mobile-first: no legends, no charts, one connecting border, ≤375px verified via
  the existing pill/row patterns already used elsewhere in this tab.
- **Soft date validation — PWA-only.** The backend deliberately does not validate `receivedAt`/
  `dueDate` at all (the prompt made this explicit: "Бекенд МОЖЕ лишити м'яку перевірку... але НЕ
  відкидає"). The PWA shows a non-blocking inline warning: a received date in the future or before
  the object's `createdAt` (threaded down from `ProjectDetailPage` as an optional prop — the check is
  simply skipped if a caller doesn't have it handy), and a planned due date already in the past **on
  create only** (editing an overdue plan is normal — that's what OVERDUE means).

### Offline

The simple add/edit/delete-receipt paths (including RESERVE and INCREASE, which are still one
receipt) go through the existing `offlineMutate` outbox pattern, one client-generated id each,
identical to how plan rows already work. **TRANSFER is online-only** — it creates two receipts from
one submission, which doesn't fit the outbox's one-entity-per-op model, so it's deliberately scoped
like split preview/commit (no offline queueing). The optimistic cache patch for the offline fallback
path is a simplification (it doesn't model a plan-amount bump for INCREASE) — acceptable since it
only ever shows while offline; the next sync replaces it with the server's real numbers, same
reasoning already established for the expense journal's profit figure.

---

## 5. Fixes found in first live use (same day)

Three real gaps surfaced within minutes of the master actually using this on a live object —
recorded here rather than folded silently into the sections above, since they weren't caught by the
gate and are worth remembering as a class of bug.

- **Overflow-detection staleness.** `ReceivePaymentSheet` computes overflow against the `summary`
  prop — a snapshot. `useAddReceipt`'s success handler invalidated the query but never awaited the
  refetch before the mutation resolved, so entering several receipts against the same stage
  back-to-back could reopen the dialog against pre-mutation numbers and under-detect a real
  overflow. Fixed by having `useAddReceipt`/`useAddReceiptTransfer` `await
  qc.refetchQueries({..., type: 'active'})` before resolving — the mutation's promise now only
  settles once the cache is genuinely fresh, online or off (a failed background refetch offline
  still resolves, it just doesn't throw).
- **The "create a new stage while another is over-received" hint was simply missing.** The
  original prompt's spec (§ПЕРЕПЛАТА, case B) and its own "як перевірити" checklist both describe
  it, but it didn't get built in the first pass. Added now: `PaymentService.transferSurplus`
  (`POST .../receipts/transfer-surplus`) reduces the over-received stage's receipts from
  most-recently-created backwards until the surplus is covered (deleting one outright if fully
  consumed — a real cascade, unlike TRANSFER's single-hop one), then posts one new receipt of that
  surplus onto the target stage. `PaymentSheet`'s create-mode `submit()` checks
  `summary.payments` for `received > amount` right after a successful plan creation and, if found,
  offers "На «X» отримано більше на Y — перенести сюди як частково оплачену?" before closing.
- **The portal didn't show unplanned receipts as their own line.** `received`/`Отримано` already
  summed them in, but `PublicPortalView.PaymentsCard` only ever rendered `PaymentRow`s built from
  plan stages — a client saw a total the itemized rows didn't add up to (11 700 total, rows summing
  to 9 000, no explanation for the gap). This was flagged as an OPEN design question in the first
  pass ("should an unplanned receipt show as its own line?") and got answered the same day by it
  visibly breaking: `PaymentsCard` gained `unplannedReceipts: List<UnplannedReceiptRow>`
  (label/amount/receivedAt), rendered as their own rows (`renderUnplannedRow`, green dot, no due
  condition) on `static/portal/index.html`. The RESERVE-overpayment half of that same open question
  needed no code change — `amountText` already prints `received з amount` unconditionally, so
  "7 000 з 5 000" was already correct without special-casing.

---

## Not changed / confirmed

- `PaymentService.requireEconomy`'s `Feature.OBJECT_ECONOMY` gate on every mutation is untouched —
  receipts inherit the exact same FREE/PRO boundary plan payments already had.
- The split ("Розбити на частки") flow is untouched — it still creates pure plan rows with no
  receipts.
- **CLAUDE.md's architecture index was stale** before this iteration — it claimed `PaymentService`
  had no `Feature` gate, which stopped being true in the economy-polish iteration. Corrected as part
  of this session's recon, unrelated to the payments-model work itself.

## Gotchas

- `ProjectPaymentResponse`/`PaymentsSummaryResponse` are breaking DTO shape changes (`paidAmount`/
  `paidAt` → `received`/`remaining`/`receipts`; summary gained `unplannedReceipts`) — every call site
  across both repos was grepped for the old field names before considering this done (record-
  constructor fan-out discipline).
- `PaymentReceipt`'s `sumByPlanPaymentId` returns a plain `BigDecimal` (not wrapped) — every test that
  exercises `addReceipt` must stub it explicitly; Mockito's default-empty-values answer only covers
  collection return types, not `BigDecimal`, and an unstubbed call returns `null` → NPE on `.subtract`.
